package ai.jackknife.killayue.studio;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Standard MIDI files in and out. Reading takes formats 0 and 1 (format 0 is split by channel), running status,
 * zero-velocity note-offs and overlapping notes of one pitch; positions are kept in beats, so a file lands on the
 * project's grid whatever its tempo. Writing makes a format 1 file at 480 ticks a beat: a tempo track with the
 * project's tempo changes and markers, then one track per instrument track with its name and a General MIDI program.
 */
public final class Smf {
    private Smf() {}

    public static final class Part {
        public String name = "";
        public int channel, program = -1;
        public final ArrayList<MidiNotes.Note> notes = new ArrayList<MidiNotes.Note>();
        /** Notes left out: outside the 88 keys, or past the 4096 a track holds. */
        public int dropped;
        public boolean drums() { return channel == 9; }
    }
    public static final class Song {
        public final ArrayList<Part> parts = new ArrayList<Part>();
        public double tempo = 120;
        /** Tempo changes after the start: beat → bpm. */
        public final TreeMap<Double, Double> tempos = new TreeMap<Double, Double>();
        public String title = "";
    }

    public static Song read(byte[] b) throws IOException {
        if (b.length < 14 || !"MThd".equals(new String(b, 0, 4, "US-ASCII")))
            throw new IOException("Not a MIDI file.");
        int headLen = be(b, 4, 4), format = be(b, 8, 2), count = be(b, 10, 2), division = be(b, 12, 2);
        if (format > 2)
            throw new IOException("Unsupported MIDI file format " + format + ".");
        if ((division & 0x8000) != 0 || division == 0)
            throw new IOException("This MIDI file uses SMPTE time, which the Studio does not read.");
        Song song = new Song();
        int at = 8 + headLen;
        // key: track * 16 + channel
        TreeMap<Integer, Part> parts = new TreeMap<Integer, Part>();
        for (int t = 0; t < count && at + 8 <= b.length; t++) {
            if (!"MTrk".equals(new String(b, at, 4, "US-ASCII"))) {
                at += 8 + be(b, at + 4, 4);
                t--;
                if (at >= b.length)
                    break;
                continue;
            }
            int len = be(b, at + 4, 4), p = at + 8, end = Math.min(b.length, p + len);
            at = end;
            long tick = 0;
            int status = 0;
            String trackName = "";
            HashMap<Integer, LinkedList<MidiNotes.Note>> open = new HashMap<Integer, LinkedList<MidiNotes.Note>>();
            HashMap<Integer, Integer> programs = new HashMap<Integer, Integer>();
            while (p < end) {
                long delta = 0;
                for (int i = 0; i < 4 && p < end; i++) {
                    int v = b[p++] & 255;
                    delta = (delta << 7) | (v & 127);
                    if ((v & 128) == 0)
                        break;
                }
                tick += delta;
                if (p >= end)
                    break;
                int s = b[p] & 255;
                if (s >= 0x80) {
                    p++;
                    if (s < 0xF0)
                        status = s;
                } else
                    s = status;
                double beat = tick / (double) division;
                if (s == 0xFF) {
                    if (p >= end)
                        break;
                    int type = b[p++] & 255;
                    long[] l = vlq(b, p, end);
                    p = (int) l[1];
                    int n = (int) Math.min(l[0], end - p);
                    if (type == 0x51 && n == 3) {
                        double bpm = 60000000.0 / Math.max(1, be(b, p, 3));
                        if (beat == 0)
                            song.tempo = bpm;
                        else
                            song.tempos.put(beat, bpm);
                    } else if (type == 0x03 && n > 0)
                        trackName = new String(b, p, n, "ISO-8859-1").trim();
                    p += n;
                    status = 0;
                    continue;
                }
                if (s == 0xF0 || s == 0xF7) {
                    long[] l = vlq(b, p, end);
                    p = (int) Math.min(end, l[1] + l[0]);
                    status = 0;
                    continue;
                }
                if (s < 0x80) {
                    p++;  // data without a status: skip it
                    continue;
                }
                int kind = s & 0xF0, ch = s & 15, need = kind == 0xC0 || kind == 0xD0 ? 1 : 2;
                if (p + need > end)
                    break;
                int d1 = b[p] & 127, d2 = need == 2 ? b[p + 1] & 127 : 0;
                p += need;
                Part part = parts.get(t * 16 + ch);
                if (kind == 0xC0) {
                    programs.put(ch, d1);
                    if (part != null && part.program < 0)
                        part.program = d1;
                    continue;
                }
                if (kind != 0x90 && kind != 0x80)
                    continue;
                if (part == null) {
                    part = new Part();
                    part.channel = ch;
                    Integer prog = programs.get(ch);
                    part.program = prog == null ? -1 : prog;
                    parts.put(t * 16 + ch, part);
                }
                part.name = trackName;
                int k = ch * 128 + d1;
                if (kind == 0x90 && d2 > 0) {
                    MidiNotes.Note n = new MidiNotes.Note();
                    n.key = d1;
                    n.beat = beat;
                    n.velocity = Math.max(.01f, d2 / 127f);
                    n.length = -1;
                    LinkedList<MidiNotes.Note> q = open.get(k);
                    if (q == null)
                        open.put(k, q = new LinkedList<MidiNotes.Note>());
                    q.add(n);
                    part.notes.add(n);
                } else {
                    LinkedList<MidiNotes.Note> q = open.get(k);
                    if (q != null && !q.isEmpty()) {
                        MidiNotes.Note n = q.removeFirst();
                        n.length = beat - n.beat;
                    }
                }
            }
            for (LinkedList<MidiNotes.Note> q : open.values())
                for (MidiNotes.Note n : q) n.length = Math.max(.25, tick / (double) division - n.beat);
        }
        for (Part part : parts.values()) {
            ArrayList<MidiNotes.Note> kept = new ArrayList<MidiNotes.Note>();
            for (MidiNotes.Note n : part.notes) {
                n.length = Math.max(1 / 64.0, Math.min(256, n.length));
                if (n.key < MidiNotes.LOW || n.key > MidiNotes.HIGH || n.beat > 100000 || kept.size() >= MidiNotes.MAX)
                    part.dropped++;
                else
                    kept.add(n);
            }
            part.notes.clear();
            part.notes.addAll(kept);
            Collections.sort(part.notes, new Comparator<MidiNotes.Note>() {
                public int compare(MidiNotes.Note x, MidiNotes.Note y) { return Double.compare(x.beat, y.beat); }
            });
            if (part.name.isEmpty())
                part.name = part.drums() ? "Drums" : part.program >= 0 ? gmName(part.program) : "MIDI " + (part.channel + 1);
            if (!part.notes.isEmpty())
                song.parts.add(part);
        }
        return song;
    }

    /** The notes as a track's notes, numbered from 1. */
    public static MidiNotes notes(Part part, int sound) {
        MidiNotes m = new MidiNotes();
        m.instrument = sound;
        for (MidiNotes.Note n : part.notes) {
            MidiNotes.Note c = m.add(n.beat, n.key);
            c.length = n.length;
            c.velocity = n.velocity;
        }
        m.validate();
        return m;
    }

    /** The sound a part should get: General MIDI program to the nearest Studio sound. */
    public static int sound(Part part) {
        if (part.drums())
            return 7;
        int g = part.program < 0 ? 0 : part.program;
        if (g <= 3) return g == 1 ? 18 : 16;
        if (g <= 5) return 2;
        if (g <= 7) return 0;
        if (g == 9) return 29;
        if (g == 12) return 28;
        if (g == 13) return 30;
        if (g <= 15) return 1;
        if (g <= 23) return 19;
        if (g <= 31) return 0;
        if (g <= 39) return g >= 38 ? 3 : 5;
        if (g == 45) return 21;
        if (g == 46) return 22;
        if (g <= 51) return 20;
        if (g <= 55) return 4;
        if (g == 57 || g == 58) return 27;
        if (g == 60) return 26;
        if (g <= 63) return 25;
        if (g <= 71) return 24;
        if (g <= 79) return 23;
        if (g <= 87) return 6;
        if (g <= 103) return 4;
        if (g == 118) return 3;
        return 0;
    }
    /** The General MIDI program a sound is written with. */
    public static int program(int sound) {
        switch (sound) {
            case 0: return 25;
            case 1: return 10;
            case 2: return 4;
            case 3: return 38;
            case 4: return 89;
            case 5: return 38;
            case 6: return 81;
            case 17: return 0;
            case 18: return 1;
            case 19: return 19;
            case 20: return 48;
            case 21: return 45;
            case 22: return 46;
            case 23: return 73;
            case 24: return 71;
            case 25: return 56;
            case 26: return 60;
            case 27: return 57;
            case 28: return 12;
            case 29: return 9;
            case 30: return 13;
            default: return 0;
        }
    }
    static String gmName(int g) {
        String[] families = {"Piano", "Chromatic percussion", "Organ", "Guitar", "Bass", "Strings", "Ensemble",
                             "Brass", "Reed", "Pipe", "Synth lead", "Synth pad", "Synth effects", "Ethnic",
                             "Percussive", "Sound effects"};
        return families[Math.max(0, Math.min(15, g / 8))];
    }

    /** A format 1 file of the project's instrument tracks; asHeard: after each track's MIDI effects. */
    public static byte[] write(Project p, boolean asHeard) {
        final int ppq = 480;
        ArrayList<byte[]> tracks = new ArrayList<byte[]>();
        TempoMap tempo = new TempoMap(p.rate, p.performance);
        Track0 t0 = new Track0();
        t0.text(0, 0x03, p.name);
        t0.raw(0, new byte[] {(byte) 0xFF, 0x58, 4, 4, 2, 24, 8});
        t0.tempo(0, p.performance.tempo);
        for (Map.Entry<Long, Double> e : p.performance.tempos.entrySet())
            t0.tempo(Math.round(tempo.beatAt(e.getKey()) * ppq), e.getValue());
        tracks.add(t0.bytes());
        int channel = 0;
        for (Track t : p.tracks) {
            if (t.notes.instrument < 0 || t.notes.notes.isEmpty())
                continue;
            boolean drums = t.notes.instrument == 7;
            int ch = drums ? 9 : channel;
            if (!drums && ++channel == 9)
                channel++;
            channel %= 16;
            Track0 w = new Track0();
            w.text(0, 0x03, t.name);
            if (!drums)
                w.raw(0, new byte[] {(byte) (0xC0 | ch), (byte) program(t.notes.instrument)});
            List<MidiNotes.Note> ns = asHeard ? t.notes.played(p) : t.notes.notes;
            for (MidiNotes.Note n : ns) {
                long on = Math.round(n.beat * ppq), off = Math.max(on + 1, Math.round((n.beat + n.length) * ppq));
                int v = Math.max(1, Math.min(127, Math.round(n.velocity * 127)));
                w.raw(on, new byte[] {(byte) (0x90 | ch), (byte) n.key, (byte) v});
                w.raw(off, new byte[] {(byte) (0x80 | ch), (byte) n.key, 64});
            }
            tracks.add(w.bytes());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('M');
        out.write('T');
        out.write('h');
        out.write('d');
        put(out, 6, 4);
        put(out, 1, 2);
        put(out, tracks.size(), 2);
        put(out, ppq, 2);
        for (byte[] t : tracks) {
            out.write('M');
            out.write('T');
            out.write('r');
            out.write('k');
            put(out, t.length, 4);
            out.write(t, 0, t.length);
        }
        return out.toByteArray();
    }

    /** Events at ticks; written sorted, note-offs before note-ons at the same tick. */
    static final class Track0 {
        final ArrayList<Object[]> events = new ArrayList<Object[]>();
        int order;
        void raw(long tick, byte[] data) { events.add(new Object[] {tick, order++, data}); }
        void text(long tick, int type, String s) {
            byte[] t;
            try {
                t = (s == null ? "" : s).getBytes("UTF-8");
            } catch (Exception e) {
                t = new byte[0];
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(0xFF);
            b.write(type);
            vlq(b, t.length);
            b.write(t, 0, t.length);
            raw(tick, b.toByteArray());
        }
        void tempo(long tick, double bpm) {
            int us = (int) Math.round(60000000 / Math.max(10, bpm));
            raw(tick, new byte[] {(byte) 0xFF, 0x51, 3, (byte) (us >> 16), (byte) (us >> 8), (byte) us});
        }
        byte[] bytes() {
            Collections.sort(events, new Comparator<Object[]>() {
                public int compare(Object[] a, Object[] b) {
                    long ta = (Long) a[0], tb = (Long) b[0];
                    if (ta != tb)
                        return ta < tb ? -1 : 1;
                    boolean offA = ((((byte[]) a[2])[0] & 0xF0) == 0x80), offB = ((((byte[]) b[2])[0] & 0xF0) == 0x80);
                    if (offA != offB)
                        return offA ? -1 : 1;
                    return (Integer) a[1] - (Integer) b[1];
                }
            });
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            long last = 0;
            for (Object[] e : events) {
                long tick = (Long) e[0];
                vlq(b, tick - last);
                last = tick;
                byte[] d = (byte[]) e[2];
                b.write(d, 0, d.length);
            }
            b.write(0);
            b.write(0xFF);
            b.write(0x2F);
            b.write(0);
            return b.toByteArray();
        }
    }
    static void vlq(ByteArrayOutputStream b, long v) {
        int[] parts = new int[5];
        int n = 0;
        do {
            parts[n++] = (int) (v & 127);
            v >>= 7;
        } while (v > 0 && n < 5);
        for (int i = n - 1; i >= 0; i--) b.write(parts[i] | (i > 0 ? 128 : 0));
    }
    static long[] vlq(byte[] b, int p, int end) {
        long v = 0;
        for (int i = 0; i < 4 && p < end; i++) {
            int x = b[p++] & 255;
            v = (v << 7) | (x & 127);
            if ((x & 128) == 0)
                break;
        }
        return new long[] {v, p};
    }
    static int be(byte[] b, int at, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (at + i < b.length ? b[at + i] & 255 : 0);
        return v;
    }
    static void put(ByteArrayOutputStream out, int v, int n) {
        for (int i = n - 1; i >= 0; i--) out.write((v >> (8 * i)) & 255);
    }
}
