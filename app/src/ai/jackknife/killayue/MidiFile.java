package ai.jackknife.killayue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Writes a song's score as a standard MIDI file: one track per voice of the score (the vocal
 * melody and the instrument melody), a track that plays the chord symbols, and section names
 * as markers, so a DAW shows "verse" and "chorus" on its timeline.
 */
public class MidiFile {
    private static final int VOCAL_PROGRAM = 53, INSTRUMENT_PROGRAM = 0, CHORD_PROGRAM = 48; // voice oohs, piano, strings

    private static class Msg {
        final int tick, order;
        final byte[] data;

        Msg(int tick, int order, byte[] data) {
            this.tick = tick;
            this.order = order;
            this.data = data;
        }
    }

    static void write(Abc.Score s, String title, File out) throws Exception {
        ArrayList<byte[]> tracks = new ArrayList<byte[]>();

        ArrayList<Msg> meta = new ArrayList<Msg>();
        meta.add(new Msg(0, 0, metaEvent(0x03, title)));
        int den = Integer.numberOfTrailingZeros(Math.max(1, s.meterDen));
        meta.add(new Msg(0, 0, new byte[] {(byte) 0xFF, 0x58, 4, (byte) s.meterNum, (byte) den, 24, 8}));
        meta.add(new Msg(0, 0, new byte[] {(byte) 0xFF, 0x59, 2, (byte) s.fifths, (byte) (s.minor ? 1 : 0)}));
        int us = 60000000 / Math.max(20, s.bpm);
        meta.add(new Msg(0, 0, new byte[] {(byte) 0xFF, 0x51, 3, (byte) (us >> 16), (byte) (us >> 8), (byte) us}));
        for (int i = 0; i < s.sections.size(); i++) meta.add(new Msg(s.sections.get(i).tick, 0, metaEvent(0x06, s.sections.get(i).text)));
        tracks.add(track(meta));

        int channel = 0;
        ArrayList<Abc.Mark> chords = new ArrayList<Abc.Mark>();
        for (int v = 0; v < s.voices.size(); v++) {
            Abc.Voice voice = s.voices.get(v);
            chords.addAll(voice.chords);
            if (voice.silent()) continue;
            boolean vocal = voice.id.toLowerCase().startsWith("voc") || voice.name.toLowerCase().startsWith("voc");
            ArrayList<Msg> m = new ArrayList<Msg>();
            m.add(new Msg(0, 0, metaEvent(0x03, voice.name)));
            m.add(new Msg(0, 0, new byte[] {(byte) (0xC0 | channel), (byte) (vocal ? VOCAL_PROGRAM : INSTRUMENT_PROGRAM)}));
            ArrayList<Abc.Event> ev = voice.events;
            for (int i = 0; i < ev.size(); i++) {
                Abc.Event e = ev.get(i);
                if (e.rest) continue;
                for (int k = 0; k < e.pitch.length; k++) {
                    if (startedByTie(ev, i, e.pitch[k])) continue; // sounding already, from the note tied to this one
                    int end = e.tick + e.dur;
                    // a tie carries the note on through the next ones of the same pitch
                    for (int j = i; j < ev.size() && ev.get(j).tie && j + 1 < ev.size() && has(ev.get(j + 1), e.pitch[k]); j++) {
                        end = ev.get(j + 1).tick + ev.get(j + 1).dur;
                    }
                    note(m, channel, e.pitch[k], e.tick, end, 96);
                }
            }
            tracks.add(track(m));
            channel++;
        }

        if (chords.size() > 0) {
            Collections.sort(chords, new Comparator<Abc.Mark>() {
                public int compare(Abc.Mark a, Abc.Mark b) {
                    return a.tick - b.tick;
                }
            });
            ArrayList<Msg> m = new ArrayList<Msg>();
            m.add(new Msg(0, 0, metaEvent(0x03, "Chords")));
            m.add(new Msg(0, 0, new byte[] {(byte) (0xC0 | channel), (byte) CHORD_PROGRAM}));
            int songEnd = s.end();
            for (int i = 0; i < chords.size(); i++) {
                int from = chords.get(i).tick, to = i + 1 < chords.size() ? chords.get(i + 1).tick : songEnd;
                int[] notes = chordNotes(chords.get(i).text);
                if (notes == null || to <= from) continue;
                for (int k = 0; k < notes.length; k++) note(m, channel, notes[k], from, to, 64);
            }
            tracks.add(track(m));
        }

        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write("MThd".getBytes("US-ASCII"));
        int32(f, 6);
        int16(f, 1);
        int16(f, tracks.size());
        int16(f, Abc.PPQ);
        for (int i = 0; i < tracks.size(); i++) {
            f.write("MTrk".getBytes("US-ASCII"));
            int32(f, tracks.get(i).length);
            f.write(tracks.get(i));
        }
        FileOutputStream o = new FileOutputStream(out);
        try {
            o.write(f.toByteArray());
        } finally {
            o.close();
        }
    }

    private static boolean has(Abc.Event e, int pitch) {
        if (e.rest) return false;
        for (int k = 0; k < e.pitch.length; k++) if (e.pitch[k] == pitch) return true;
        return false;
    }

    private static boolean startedByTie(ArrayList<Abc.Event> ev, int i, int pitch) {
        return i > 0 && ev.get(i - 1).tie && has(ev.get(i - 1), pitch);
    }

    private static void note(ArrayList<Msg> m, int ch, int pitch, int from, int to, int vel) {
        if (pitch < 0 || pitch > 127 || to <= from) return;
        m.add(new Msg(from, 2, new byte[] {(byte) (0x90 | ch), (byte) pitch, (byte) vel}));
        m.add(new Msg(to, 1, new byte[] {(byte) (0x80 | ch), (byte) pitch, 0})); // note-offs first at the same tick
    }

    // ---- chord symbols to notes ----

    private static final String LETTERS = "CDEFGAB";
    private static final int[] SEMI = {0, 2, 4, 5, 7, 9, 11};

    /** "Ebmaj7", "Bb7sus4", "F#m7b5/C" to MIDI notes around the octave below middle C. Null if it is not a chord. */
    static int[] chordNotes(String sym) {
        sym = sym.trim();
        if (sym.length() == 0) return null;
        int letter = LETTERS.indexOf(Character.toUpperCase(sym.charAt(0)));
        if (letter < 0) return null;
        int i = 1, root = SEMI[letter];
        while (i < sym.length() && (sym.charAt(i) == '#' || sym.charAt(i) == 'b')) root += sym.charAt(i++) == '#' ? 1 : -1;
        String q = sym.substring(i);
        int bass = -1;
        int slash = q.indexOf('/');
        if (slash >= 0) {
            String b = q.substring(slash + 1);
            q = q.substring(0, slash);
            if (b.length() > 0 && LETTERS.indexOf(Character.toUpperCase(b.charAt(0))) >= 0) {
                bass = SEMI[LETTERS.indexOf(Character.toUpperCase(b.charAt(0)))];
                for (int k = 1; k < b.length(); k++) bass += b.charAt(k) == '#' ? 1 : b.charAt(k) == 'b' ? -1 : 0;
            }
        }
        String l = q.toLowerCase();
        int[] iv;
        boolean maj = q.startsWith("maj") || q.startsWith("M") || q.startsWith("\u0394");
        boolean min = !maj && (q.startsWith("m") || q.startsWith("-")) && !l.startsWith("maj");
        if (l.startsWith("dim7") || l.startsWith("o7")) iv = new int[] {0, 3, 6, 9};
        else if (l.startsWith("dim") || l.startsWith("o")) iv = new int[] {0, 3, 6};
        else if (l.contains("m7b5") || q.startsWith("\u00f8")) iv = new int[] {0, 3, 6, 10};
        else if (l.startsWith("aug") || l.startsWith("+")) iv = new int[] {0, 4, 8};
        else if (maj) iv = l.contains("9") ? new int[] {0, 4, 7, 11, 14} : l.contains("7") ? new int[] {0, 4, 7, 11} : new int[] {0, 4, 7};
        else if (min) iv = l.contains("9") ? new int[] {0, 3, 7, 10, 14} : l.contains("7") ? new int[] {0, 3, 7, 10}
                : l.contains("6") ? new int[] {0, 3, 7, 9} : new int[] {0, 3, 7};
        else if (l.contains("sus")) iv = l.contains("7") ? new int[] {0, 5, 7, 10} : new int[] {0, 5, 7}; // sus2 is fixed below
        else if (l.startsWith("13") || l.startsWith("11") || l.startsWith("9")) iv = new int[] {0, 4, 7, 10, 14};
        else if (l.startsWith("7")) iv = new int[] {0, 4, 7, 10};
        else if (l.startsWith("6")) iv = new int[] {0, 4, 7, 9};
        else if (l.startsWith("add9")) iv = new int[] {0, 4, 7, 14};
        else if (l.startsWith("5")) iv = new int[] {0, 7};
        else iv = new int[] {0, 4, 7};
        if (l.contains("sus2") && iv.length >= 3 && !l.contains("sus4")) iv[1] = 2;
        int base = 48 + ((root % 12) + 12) % 12; // C3 to B3
        int[] out = new int[iv.length + (bass >= 0 ? 1 : 0)];
        for (int k = 0; k < iv.length; k++) out[k] = base + iv[k];
        if (bass >= 0) out[iv.length] = 36 + ((bass % 12) + 12) % 12;
        return out;
    }

    // ---- file pieces ----

    private static byte[] metaEvent(int type, String text) throws Exception {
        byte[] t = text.getBytes("UTF-8");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0xFF);
        b.write(type);
        vlq(b, t.length);
        b.write(t);
        return b.toByteArray();
    }

    private static byte[] track(ArrayList<Msg> m) {
        Collections.sort(m, new Comparator<Msg>() {
            public int compare(Msg a, Msg b) {
                return a.tick != b.tick ? a.tick - b.tick : a.order - b.order;
            }
        });
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int at = 0;
        for (int i = 0; i < m.size(); i++) {
            vlq(b, Math.max(0, m.get(i).tick - at));
            at = Math.max(at, m.get(i).tick);
            b.write(m.get(i).data, 0, m.get(i).data.length);
        }
        vlq(b, 0);
        b.write(0xFF);
        b.write(0x2F);
        b.write(0);
        return b.toByteArray();
    }

    private static void vlq(ByteArrayOutputStream b, int v) {
        int buf = v & 0x7F;
        while ((v >>= 7) > 0) {
            buf <<= 8;
            buf |= (v & 0x7F) | 0x80;
        }
        while (true) {
            b.write(buf & 0xFF);
            if ((buf & 0x80) != 0) buf >>= 8;
            else break;
        }
    }

    private static void int32(ByteArrayOutputStream b, int v) {
        b.write(v >> 24);
        b.write(v >> 16);
        b.write(v >> 8);
        b.write(v);
    }

    private static void int16(ByteArrayOutputStream b, int v) {
        b.write(v >> 8);
        b.write(v);
    }
}
