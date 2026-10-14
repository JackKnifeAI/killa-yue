package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A song's line written out for an instrument: every note moved by the instrument's interval, spelled
 * the way a player expects. Keys move with the notes (and are respelled when the plain spelling would
 * need more than six sharps or flats: D sharp minor for alto sax is C minor, not B sharp minor), chord
 * names move with them, accidentals follow the ABC and printed-music rule (an accidental holds to the
 * end of its bar), and a line outside the instrument's range moves by whole octaves to fit.
 * Plain Java, tested in app/test; tools/sheetcheck proves every part note by note.
 */
class Transpose {
    static class Part {
        String abc;       // the part as an ABC score with one voice, ready for ScorePrep
        String key;       // the written key, e.g. C#m
        int octaves;      // whole octaves the line was moved to fit the instrument (0 if none)
        int outOfRange;   // notes still outside the instrument's range after that
        int notes;
    }

    private static final String LETTERS = "CDEFGAB";
    private static final int[] NATURAL = {0, 2, 4, 5, 7, 9, 11};  // pitch class of each letter
    private static final int[] FIFTHS = {0, 2, 4, -1, 1, 3, 5};   // major key on each natural letter
    private static final Pattern KEY = Pattern.compile("^\\s*([A-Ga-g])([#b]?)\\s*([A-Za-z]*)");

    /** Sharps (positive) or flats (negative) of an ABC key, e.g. Em is 1, D#m is 6, Bb is -2. */
    static int fifths(String key) {
        Matcher m = KEY.matcher(key);
        if (!m.find()) return 0;
        int f = FIFTHS[LETTERS.indexOf(Character.toUpperCase(m.group(1).charAt(0)))];
        if (m.group(2).equals("#")) f += 7;
        if (m.group(2).equals("b")) f -= 7;
        return f + modeShift(m.group(3));
    }

    private static int modeShift(String mode) {
        String m = mode.toLowerCase();
        if (m.equals("m") || m.startsWith("min") || m.startsWith("aeo")) return -3;
        if (m.startsWith("dor")) return -2;
        if (m.startsWith("phr")) return -4;
        if (m.startsWith("lyd")) return 1;
        if (m.startsWith("mix")) return -1;
        if (m.startsWith("loc")) return -5;
        return 0; // major, ionian or nothing
    }

    /** Where an interval sits on the circle of fifths: a major second is 2, a major sixth 3, an octave 0. */
    private static int intervalFifths(int steps, int semis) {
        for (int f = 0; f <= 42; f++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int g = f * sign;
                if (Math.floorMod(4 * g, 7) == Math.floorMod(steps, 7) && Math.floorMod(7 * g, 12) == Math.floorMod(semis, 12)) return g;
            }
        }
        throw new IllegalArgumentException("not an interval: " + steps + " steps, " + semis + " semitones");
    }

    /**
     * The key moved by an interval {steps, semitones}: {new key, steps, semitones}. The steps may change
     * by one (same semitones) when the plain spelling would need more than six sharps or flats.
     */
    static String[] key(String key, int[] interval) {
        int steps = interval[0], semis = interval[1];
        int old = fifths(key), f = intervalFifths(steps, semis);
        if (Math.abs(old + f) > 6) {
            int down = f - 12, up = f + 12; // the same semitones, spelled one letter further or nearer
            if (Math.abs(old + down) < Math.abs(old + f) && Math.abs(old + down) <= Math.abs(old + up)) {
                steps += 1;
            } else if (Math.abs(old + up) < Math.abs(old + f)) {
                steps -= 1;
            }
        }
        Matcher m = KEY.matcher(key);
        if (!m.find()) return new String[] {key, String.valueOf(steps), String.valueOf(semis)};
        String tonic = spell(Character.toUpperCase(m.group(1).charAt(0)), m.group(2), steps, semis);
        return new String[] {tonic + m.group(3), String.valueOf(steps), String.valueOf(semis)};
    }

    /** A note name (letter and # or b) moved by an interval. */
    private static String spell(char letter, String acc, int steps, int semis) {
        int li = LETTERS.indexOf(letter), pc = NATURAL[li] + (acc.equals("#") ? 1 : acc.equals("b") ? -1 : 0);
        int nl = Math.floorMod(li + steps, 7);
        int alt = Math.floorMod(pc + semis - NATURAL[nl] + 6, 12) - 6;
        StringBuilder s = new StringBuilder().append(LETTERS.charAt(nl));
        for (int i = 0; i < alt; i++) s.append('#');
        for (int i = 0; i < -alt; i++) s.append('b');
        return s.toString();
    }

    /** A chord name moved by an interval; annotations ("^Chorus") and anything not a chord stay as they are. */
    static String chord(String name, int steps, int semis) {
        Matcher m = Pattern.compile("^([A-G])([#b]?)([^/]*)(?:/([A-G])([#b]?))?$").matcher(name);
        if (!m.matches()) return name;
        String out = spell(m.group(1).charAt(0), m.group(2), steps, semis) + m.group(3);
        if (m.group(4) != null) out += "/" + spell(m.group(4).charAt(0), m.group(5), steps, semis);
        return out;
    }

    /** Writes a voice's music lines in a new key; keeps the accidental state of the bar from line to line. */
    static class Writer {
        final int steps, semis;
        final int[] inKey = new int[7], outKey = new int[7]; // alteration of each letter in the old and new key
        final HashMap<Integer, Integer> inBar = new HashMap<Integer, Integer>(), outBar = new HashMap<Integer, Integer>();
        final ArrayList<Integer> written = new ArrayList<Integer>(); // every note's written pitch, in order

        Writer(String key, int steps, int semis) {
            this.steps = steps;
            this.semis = semis;
            keyAlterations(fifths(key), inKey);
            keyAlterations(fifths(key) + intervalFifths(steps, semis), outKey);
        }

        String line(String music) {
            ArrayList<ScorePrep.Tok> ts = ScorePrep.tokens(music);
            if (ts == null) throw new IllegalArgumentException("This score has notation the part writer does not know: " + music);
            StringBuilder out = new StringBuilder();
            for (ScorePrep.Tok t : ts) {
                if (t.kind == ScorePrep.NOTE) {
                    out.append(note(t.text));
                } else if (t.kind == ScorePrep.NAME) {
                    String inner = t.text.substring(1, t.text.length() - 1);
                    out.append('"').append(chord(inner, steps, semis)).append('"');
                } else {
                    if (t.kind == ScorePrep.BAR) {
                        inBar.clear();
                        outBar.clear();
                    }
                    out.append(t.text);
                }
            }
            return out.toString();
        }

        private String note(String text) {
            Matcher m = Pattern.compile("^([\\^_=]*)([A-Ga-g])([,']*)(.*)$").matcher(text);
            if (!m.matches()) throw new IllegalArgumentException("not a note: " + text);
            String acc = m.group(1), marks = m.group(3), length = m.group(4);
            char letter = m.group(2).charAt(0);
            int li = LETTERS.indexOf(Character.toUpperCase(letter));
            int octave = (Character.isLowerCase(letter) ? 5 : 4);
            for (char c : marks.toCharArray()) octave += c == '\'' ? 1 : -1;
            int place = octave * 7 + li, alt;
            if (acc.length() > 0) {
                alt = acc.startsWith("=") ? 0 : acc.charAt(0) == '^' ? acc.length() : -acc.length();
                inBar.put(Integer.valueOf(place), Integer.valueOf(alt));
            } else {
                Integer held = inBar.get(Integer.valueOf(place));
                alt = held != null ? held.intValue() : inKey[li];
            }
            int pitch = 12 * (octave + 1) + NATURAL[li] + alt + semis;
            int nplace = place + steps, nli = Math.floorMod(nplace, 7), noct = Math.floorDiv(nplace, 7);
            int nalt = pitch - (12 * (noct + 1) + NATURAL[nli]);
            if (nalt < -2 || nalt > 2) throw new IllegalArgumentException("cannot spell " + text + " moved " + semis + " semitones");
            written.add(Integer.valueOf(pitch));
            Integer held = outBar.get(Integer.valueOf(nplace));
            int implied = held != null ? held.intValue() : outKey[nli];
            StringBuilder s = new StringBuilder();
            if (nalt != implied) {
                s.append(nalt == 2 ? "^^" : nalt == 1 ? "^" : nalt == 0 ? "=" : nalt == -1 ? "_" : "__");
                outBar.put(Integer.valueOf(nplace), Integer.valueOf(nalt));
            }
            char nl = LETTERS.charAt(nli);
            if (noct >= 5) {
                s.append(Character.toLowerCase(nl));
                for (int i = 5; i < noct; i++) s.append('\'');
            } else {
                s.append(nl);
                for (int i = noct; i < 4; i++) s.append(',');
            }
            return s.append(length).toString();
        }
    }

    /** Which letters a key alters, and how: sharps in the order F C G D A E B, flats in reverse. */
    private static void keyAlterations(int fifths, int[] out) {
        Arrays.fill(out, 0);
        String sharps = "FCGDAEB";
        for (int i = 0; i < Math.min(7, Math.abs(fifths)); i++) {
            char c = fifths > 0 ? sharps.charAt(i) : sharps.charAt(6 - i);
            out[LETTERS.indexOf(c)] = fifths > 0 ? 1 : -1;
        }
    }

    /** One line of music in a new key (the bar's accidentals start fresh). */
    static String music(String music, String key, int steps, int semis) {
        String[] k = key(key, new int[] {steps, semis});
        return new Writer(key, Integer.parseInt(k[1]), Integer.parseInt(k[2])).line(music);
    }

    /** Whole octaves to move written pitches so the fewest fall outside [low, high]; the smallest move wins a tie. */
    static int octaveFit(int[] written, int low, int high) {
        int best = 0, bestOut = Integer.MAX_VALUE;
        for (int k : new int[] {0, -1, 1, -2, 2, -3, 3}) {
            int out = 0;
            for (int p : written) if (p + 12 * k < low || p + 12 * k > high) out++;
            if (out < bestOut) {
                best = k;
                bestOut = out;
            }
        }
        return best;
    }

    /** One voice of a song's score written out for an instrument, as an ABC score with that one voice. */
    static Part part(String abc, String voice, Instruments.Instrument ins) {
        ArrayList<String> head = new ArrayList<String>(), body = new ArrayList<String>();
        ArrayList<String> lines = new ArrayList<String>();
        String key = "C", cur = null, pending = null;
        boolean inBody = false;
        Pattern voiceLine = Pattern.compile("^V:\\s*(\\S+)(.*)$");
        for (String row : abc.replace("\r", "").split("\n")) {
            String r = row.trim();
            if (!inBody) {
                if (r.startsWith("K:")) {
                    key = r.substring(2).trim();
                    inBody = true;
                } else if (!r.startsWith("V:") && r.length() > 0) {
                    head.add(row);
                }
                continue;
            }
            if (r.length() == 0) continue;
            Matcher v = voiceLine.matcher(r);
            if (r.startsWith("%")) {
                pending = r;
            } else if (v.matches()) {
                cur = v.group(1);
            } else if (r.matches("^[A-Za-z]:.*") || r.contains("[K:") || r.contains("[M:") || r.contains("[L:")) {
                throw new IllegalArgumentException("This score changes key, metre or length inside the music, which parts do not handle yet.");
            } else if (voice.equals(cur)) {
                body.add(pending);
                lines.add(r);
                pending = null;
            }
        }
        if (lines.size() == 0) throw new IllegalArgumentException("The score has no voice called " + voice + ".");

        // first at the instrument's own interval, to see where the line sits; then moved by whole octaves to fit
        String[] k = key(key, new int[] {ins.steps, ins.semitones});
        Writer probe = new Writer(key, Integer.parseInt(k[1]), Integer.parseInt(k[2]));
        for (String l : lines) probe.line(l);
        int[] w = new int[probe.written.size()];
        for (int i = 0; i < w.length; i++) w[i] = probe.written.get(i).intValue();
        Part p = new Part();
        p.octaves = octaveFit(w, ins.low, ins.high);
        k = key(key, new int[] {ins.steps + 7 * p.octaves, ins.semitones + 12 * p.octaves});
        Writer out = new Writer(key, Integer.parseInt(k[1]), Integer.parseInt(k[2]));
        p.key = k[0];
        StringBuilder music = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (body.get(i) != null) music.append(body.get(i)).append('\n');
            music.append("V: P\n").append(out.line(lines.get(i))).append('\n');
        }
        p.notes = out.written.size();
        int sum = 0;
        int[] sorted = new int[out.written.size()];
        for (int i = 0; i < sorted.length; i++) {
            int x = out.written.get(i).intValue();
            sorted[i] = x;
            if (x < ins.low || x > ins.high) p.outOfRange++;
        }
        Arrays.sort(sorted);
        String clef = ins.clef;
        if (clef.equals("auto")) clef = sorted.length == 0 || sorted[sorted.length / 2] >= 60 ? "treble" : "bass";
        StringBuilder s = new StringBuilder();
        for (String h : head) s.append(h).append('\n');
        s.append("V: P clef=").append(clef).append(" name=\"").append(ins.name).append("\" snm=\"").append(ins.shortName).append("\"\n");
        s.append("K:").append(p.key).append('\n');
        s.append(music);
        p.abc = s.toString();
        return p;
    }
}
