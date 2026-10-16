package ai.jackknife.killayue;

import java.util.ArrayList;

/**
 * Reads the ABC score the engine writes with each song into notes, rests, bars, chord symbols
 * and section names, one timeline per voice. MidiFile and SheetMusic both work from this.
 *
 * It covers the ABC the engine writes (voices, keys, lengths, ties, chord symbols, multi-bar
 * rests, "% section" lines) and the common rest of the standard (chords in brackets, tuplets,
 * broken rhythm, accidentals that last to the bar line). Decorations, grace notes and slurs
 * are read past, not kept.
 */
public class Abc {
    static final int PPQ = 960; // ticks per quarter note

    static class Event {
        int tick, dur;
        boolean rest;
        int bars;            // a multi-bar rest: how many bars (0 otherwise)
        int[] pitch;         // MIDI note numbers, one per note of a chord
        int[] step;          // staff position: 0 is the bottom line of a treble staff, each step a line or a space
        int[] shown;         // the accidental written in the score (-2 to 2), or NONE when there was none
        boolean tie;         // tied to the next note
    }

    static final int NONE = 99;

    static class Mark {
        int tick;
        String text;

        Mark(int tick, String text) {
            this.tick = tick;
            this.text = text;
        }
    }

    static class Voice {
        String id, name;
        final ArrayList<Event> events = new ArrayList<Event>();
        final ArrayList<Integer> bars = new ArrayList<Integer>(); // the tick each bar starts at
        final ArrayList<Mark> chords = new ArrayList<Mark>();
        int end;

        boolean silent() {
            for (int i = 0; i < events.size(); i++) if (!events.get(i).rest) return false;
            return true;
        }
    }

    public static class Score {
        public int meterNum = 4, meterDen = 4, bpm = 120;
        String key = "C";
        int fifths;          // sharps (above 0) or flats (below 0) in the key signature
        boolean minor;
        final ArrayList<Voice> voices = new ArrayList<Voice>();
        final ArrayList<Mark> sections = new ArrayList<Mark>();

        int barTicks() {
            return PPQ * 4 * meterNum / meterDen;
        }

        int end() {
            int e = 0;
            for (int i = 0; i < voices.size(); i++) e = Math.max(e, voices.get(i).end);
            return e;
        }

        int bars() {
            int n = 0;
            for (int i = 0; i < voices.size(); i++) n = Math.max(n, voices.get(i).bars.size());
            return n;
        }
    }

    // semitones and diatonic index of C D E F G A B
    private static final int[] SEMI = {0, 2, 4, 5, 7, 9, 11};
    private static final String LETTERS = "CDEFGAB";
    // key signature in fifths for each tonic letter, before its own sharp or flat
    private static final int[] TONIC_FIFTHS = {0, 2, 4, -1, 1, 3, 5};
    // the order sharps are added in; flats go the other way
    static final String SHARPS = "FCGDAEB";

    /** Reads a key like "Eb", "F#m", "Dmix" into sharps (positive) or flats (negative), and whether it is minor. */
    static int[] key(String k) {
        k = k.trim();
        if (k.length() == 0 || !Character.isLetter(k.charAt(0)) || k.toLowerCase().startsWith("none")) return new int[] {0, 0};
        int letter = LETTERS.indexOf(Character.toUpperCase(k.charAt(0)));
        if (letter < 0) return new int[] {0, 0};
        int i = 1, f = TONIC_FIFTHS[letter];
        if (i < k.length() && k.charAt(i) == '#') {
            f += 7;
            i++;
        } else if (i < k.length() && k.charAt(i) == 'b') {
            f -= 7;
            i++;
        }
        String mode = k.substring(i).trim().toLowerCase();
        int minor = 0;
        if (mode.startsWith("min") || mode.startsWith("aeo") || (mode.startsWith("m") && !mode.startsWith("mix") && !mode.startsWith("maj"))) {
            f -= 3;
            minor = 1;
        } else if (mode.startsWith("dor")) f -= 2;
        else if (mode.startsWith("phr")) f -= 4;
        else if (mode.startsWith("lyd")) f += 1;
        else if (mode.startsWith("mix")) f -= 1;
        else if (mode.startsWith("loc")) f -= 5;
        return new int[] {Math.max(-7, Math.min(7, f)), minor};
    }

    /** The alteration the key signature gives a letter (index into CDEFGAB). */
    static int keyAlter(int fifths, int letter) {
        char c = LETTERS.charAt(letter);
        if (fifths > 0) return SHARPS.indexOf(c) < fifths ? 1 : 0;
        if (fifths < 0) return 6 - SHARPS.indexOf(c) < -fifths ? -1 : 0;
        return 0;
    }

    private static class Reader {
        final Score s = new Score();
        double unit = PPQ / 2.0; // L:1/8 until told otherwise
        Voice cur;
        double t;                // the current voice's time in ticks
        // accidentals written earlier in the bar, by letter and octave
        final java.util.HashMap<Integer, Integer> barAcc = new java.util.HashMap<Integer, Integer>();
        int tupletLeft;
        double tupletScale = 1;
        double nextScale = 1;    // set by broken rhythm for the note after > or <
        Event last;
        final java.util.HashMap<String, Double> voiceTime = new java.util.HashMap<String, Double>();
        boolean unitSet;

        Voice voice(String id) {
            for (int i = 0; i < s.voices.size(); i++) if (s.voices.get(i).id.equals(id)) return s.voices.get(i);
            Voice v = new Voice();
            v.id = id;
            v.name = id;
            v.bars.add(Integer.valueOf(0));
            s.voices.add(v);
            return v;
        }

        void switchTo(String id) {
            if (cur != null) {
                voiceTime.put(cur.id, Double.valueOf(t));
                cur.end = Math.max(cur.end, (int) Math.round(t));
            }
            cur = voice(id);
            Double at = voiceTime.get(id);
            t = at == null ? 0 : at.doubleValue();
            barAcc.clear();
            last = null;
        }

        void field(char f, String v) {
            v = v.trim();
            if (f == 'M') {
                if (v.equals("C")) v = "4/4";
                else if (v.equals("C|")) v = "2/2";
                int sl = v.indexOf('/');
                if (sl > 0) {
                    try {
                        s.meterNum = Integer.parseInt(v.substring(0, sl).trim());
                        s.meterDen = Integer.parseInt(v.substring(sl + 1).trim());
                    } catch (Exception e) {
                        // keep 4/4
                    }
                }
                if (!unitSet) unit = 4.0 * s.meterNum / s.meterDen < 0.75 ? PPQ / 4.0 : PPQ / 2.0;
            } else if (f == 'L') {
                int sl = v.indexOf('/');
                try {
                    unit = PPQ * 4.0 * Integer.parseInt(v.substring(0, sl).trim()) / Integer.parseInt(v.substring(sl + 1).trim());
                    unitSet = true;
                } catch (Exception e) {
                    // keep the unit
                }
            } else if (f == 'Q') {
                int eq = v.indexOf('=');
                try {
                    String bpm = (eq >= 0 ? v.substring(eq + 1) : v).trim().replaceAll("[^0-9].*", "");
                    double beat = 0.25;
                    if (eq > 0) {
                        String b = v.substring(0, eq).trim();
                        int sl = b.indexOf('/');
                        if (sl > 0) beat = Integer.parseInt(b.substring(0, sl).trim()) / (double) Integer.parseInt(b.substring(sl + 1).trim());
                    }
                    s.bpm = (int) Math.round(Integer.parseInt(bpm) * beat / 0.25); // in quarter notes a minute
                } catch (Exception e) {
                    // keep the tempo
                }
            } else if (f == 'K') {
                String k = v.replaceAll("clef=.*", "").trim();
                if (k.length() > 0) {
                    int[] kk = key(k);
                    s.fifths = kk[0];
                    s.minor = kk[1] == 1;
                    s.key = k.split("\\s+")[0];
                }
            } else if (f == 'V') {
                String id = v.split("\\s+")[0];
                Voice vc = voice(id);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:snm|name)=\"([^\"]*)\"").matcher(v);
                while (m.find()) vc.name = m.group(1); // the short name wins: it is the one printed beside each staff
                // a voice line with only a name switches to that voice; with settings it only declares it, in the header
                if (v.indexOf('=') < 0 || cur != null) switchTo(id);
            }
        }

        void bar() {
            barAcc.clear();
            int tick = (int) Math.round(t);
            ArrayList<Integer> b = cur.bars;
            if (tick > b.get(b.size() - 1).intValue()) b.add(Integer.valueOf(tick));
        }

        void add(Event e, double len) {
            e.tick = (int) Math.round(t);
            t += len;
            e.dur = (int) Math.round(t) - e.tick;
            cur.events.add(e);
            last = e;
        }

        /** Applies the tuplet and broken-rhythm factors that are waiting, to a length in ticks. */
        double scaled(double len) {
            len *= nextScale;
            nextScale = 1;
            if (tupletLeft > 0) {
                len *= tupletScale;
                tupletLeft--;
            }
            return len;
        }

        /** Reads a note length after a note, rest or chord: "3", "/", "3/2", "//", "/4". */
        double length(String line, int[] at) {
            int i = at[0];
            int num = 0;
            boolean hasNum = false;
            while (i < line.length() && Character.isDigit(line.charAt(i))) {
                num = num * 10 + (line.charAt(i++) - '0');
                hasNum = true;
            }
            double len = hasNum ? num : 1;
            int slashes = 0;
            while (i < line.length() && line.charAt(i) == '/') {
                slashes++;
                i++;
            }
            if (slashes > 0) {
                int den = 0;
                boolean hasDen = false;
                while (i < line.length() && Character.isDigit(line.charAt(i))) {
                    den = den * 10 + (line.charAt(i++) - '0');
                    hasDen = true;
                }
                len /= hasDen ? den : Math.pow(2, slashes);
            }
            at[0] = i;
            return len;
        }

        /** Reads one note (accidental, letter, octave marks), without its length; null when there is none at i. */
        int[] note(String line, int[] at) {
            int i = at[0];
            int acc = NONE;
            while (i < line.length() && "^_=".indexOf(line.charAt(i)) >= 0) {
                char c = line.charAt(i++);
                if (c == '=') acc = 0;
                else acc = (acc == NONE ? 0 : acc) + (c == '^' ? 1 : -1);
            }
            if (i >= line.length()) return null;
            char c = line.charAt(i);
            int letter = LETTERS.indexOf(Character.toUpperCase(c));
            if (letter < 0 || "ABCDEFGabcdefg".indexOf(c) < 0) return null;
            i++;
            int octave = Character.isUpperCase(c) ? 4 : 5;
            while (i < line.length() && (line.charAt(i) == '\'' || line.charAt(i) == ',')) octave += line.charAt(i++) == '\'' ? 1 : -1;
            at[0] = i;
            Integer k = Integer.valueOf(letter + octave * 7);
            int alter;
            if (acc != NONE) {
                alter = acc;
                barAcc.put(k, Integer.valueOf(acc));
            } else if (barAcc.containsKey(k)) {
                alter = barAcc.get(k).intValue();
            } else {
                alter = keyAlter(s.fifths, letter);
            }
            int pitch = 12 * (octave + 1) + SEMI[letter] + alter;
            int step = letter + 7 * octave - (2 + 7 * 4); // E4, the bottom line of a treble staff, is 0
            return new int[] {pitch, step, acc};
        }

        void body(String line) {
            int[] at = {0};
            int n = line.length();
            while (at[0] < n) {
                int i = at[0];
                char c = line.charAt(i);
                if (c == '%') return; // the rest of the line is a comment
                if (c == '"') {
                    int e = line.indexOf('"', i + 1);
                    if (e < 0) return;
                    String text = line.substring(i + 1, e);
                    // "^text", "_text" and the like are annotations, not chords
                    if (text.length() > 0 && "^_<>@".indexOf(text.charAt(0)) < 0) cur.chords.add(new Mark((int) Math.round(t), text));
                    at[0] = e + 1;
                } else if (c == '!' || c == '+') {
                    int e = line.indexOf(c, i + 1);
                    at[0] = e < 0 ? n : e + 1;
                } else if (c == '{') {
                    int e = line.indexOf('}', i + 1);
                    at[0] = e < 0 ? n : e + 1;
                } else if (c == '|' || c == ':') {
                    while (at[0] < n && "|:]".indexOf(line.charAt(at[0])) >= 0) at[0]++;
                    while (at[0] < n && Character.isDigit(line.charAt(at[0]))) at[0]++; // |1 and :|2 endings
                    bar();
                } else if (c == '[' && i + 2 < n && Character.isLetter(line.charAt(i + 1)) && line.charAt(i + 2) == ':') {
                    int e = line.indexOf(']', i);
                    if (e < 0) return;
                    field(line.charAt(i + 1), line.substring(i + 3, e)); // an inline field such as [K:G]
                    at[0] = e + 1;
                } else if (c == '[' && i + 1 < n && (Character.isDigit(line.charAt(i + 1)) || line.charAt(i + 1) == '|')) {
                    at[0] = i + 2; // an ending or a thick bar line
                    if (line.charAt(i + 1) == '|') bar();
                } else if (c == '[') {
                    // notes sounding together
                    at[0] = i + 1;
                    ArrayList<int[]> notes = new ArrayList<int[]>();
                    double first = -1;
                    boolean tie = false;
                    while (at[0] < n && line.charAt(at[0]) != ']') {
                        int[] nt = note(line, at);
                        if (nt == null) {
                            if (line.charAt(at[0]) == '-') tie = true;
                            at[0]++;
                            continue;
                        }
                        double len = length(line, at);
                        if (first < 0) first = len;
                        notes.add(nt);
                    }
                    at[0] = Math.min(n, at[0] + 1);
                    double mult = length(line, at);
                    if (at[0] < n && line.charAt(at[0]) == '-') {
                        tie = true;
                        at[0]++;
                    }
                    if (notes.size() == 0) continue;
                    Event e = new Event();
                    e.pitch = new int[notes.size()];
                    e.step = new int[notes.size()];
                    e.shown = new int[notes.size()];
                    for (int k = 0; k < notes.size(); k++) {
                        e.pitch[k] = notes.get(k)[0];
                        e.step[k] = notes.get(k)[1];
                        e.shown[k] = notes.get(k)[2];
                    }
                    e.tie = tie;
                    add(e, scaled(Math.max(first, 0) * mult * unit));
                } else if (c == '(' && i + 1 < n && Character.isDigit(line.charAt(i + 1))) {
                    int p = line.charAt(i + 1) - '0';
                    int q = p == 2 ? 3 : p == 3 || p == 6 ? 2 : p == 4 || p == 8 ? 3 : 2;
                    at[0] = i + 2;
                    // (p:q:r
                    if (at[0] + 1 < n && line.charAt(at[0]) == ':' && Character.isDigit(line.charAt(at[0] + 1))) {
                        q = line.charAt(at[0] + 1) - '0';
                        at[0] += 2;
                        if (at[0] + 1 < n && line.charAt(at[0]) == ':' && Character.isDigit(line.charAt(at[0] + 1))) at[0] += 2;
                    }
                    tupletLeft = p;
                    tupletScale = q / (double) p;
                } else if (c == '>' || c == '<') {
                    int k = 0;
                    while (at[0] < n && line.charAt(at[0]) == c) {
                        k++;
                        at[0]++;
                    }
                    double cut = Math.pow(0.5, k); // > : the note before is dotted, the one after shortened
                    if (last != null) {
                        double len = last.dur;
                        double now = c == '>' ? len * (2 - cut) : len * cut;
                        t += now - len;
                        last.dur = (int) Math.round(now);
                    }
                    nextScale = c == '>' ? cut : 2 - cut;
                } else if (c == 'z' || c == 'x') {
                    at[0] = i + 1;
                    Event e = new Event();
                    e.rest = true;
                    add(e, scaled(length(line, at) * unit));
                } else if (c == 'Z' || c == 'X') {
                    at[0] = i + 1;
                    int count = 0;
                    while (at[0] < n && Character.isDigit(line.charAt(at[0]))) count = count * 10 + (line.charAt(at[0]++) - '0');
                    if (count == 0) count = 1;
                    Event e = new Event();
                    e.rest = true;
                    e.bars = count;
                    int bt = s.barTicks();
                    for (int k = 0; k < count; k++) {
                        if (k > 0) bar();
                        if (k == 0) add(e, bt);
                        else t += bt;
                    }
                    e.dur = (int) Math.round(t) - e.tick;
                } else if (c == '-') {
                    if (last != null && !last.rest) last.tie = true;
                    at[0] = i + 1;
                } else {
                    int[] nt = note(line, at);
                    if (nt == null) {
                        at[0] = i + 1; // decorations (. ~ H T u v ...), slurs, spaces
                        continue;
                    }
                    Event e = new Event();
                    e.pitch = new int[] {nt[0]};
                    e.step = new int[] {nt[1]};
                    e.shown = new int[] {nt[2]};
                    add(e, scaled(length(line, at) * unit));
                    if (at[0] < n && line.charAt(at[0]) == '-') {
                        e.tie = true;
                        at[0]++;
                    }
                }
            }
        }
    }

    public static Score parse(String abc) {
        Reader r = new Reader();
        String[] rows = abc.replace("\r", "").split("\n");
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.length() == 0) continue;
            if (row.startsWith("%")) {
                String label = row.substring(1).trim();
                if (label.length() > 0 && !label.startsWith("%")) {
                    // a section starts for every voice at once; voices are level at a section line
                    int tick = 0;
                    if (r.cur != null) r.voiceTime.put(r.cur.id, Double.valueOf(r.t));
                    for (Double d : r.voiceTime.values()) tick = Math.max(tick, (int) Math.round(d.doubleValue()));
                    r.s.sections.add(new Mark(tick, label));
                }
                continue;
            }
            if (row.length() > 1 && row.charAt(1) == ':' && Character.isLetter(row.charAt(0)) && "|:".indexOf(row.charAt(0)) < 0) {
                r.field(row.charAt(0), row.substring(2));
                continue;
            }
            if (r.cur == null) r.switchTo("1");
            r.body(row);
        }
        if (r.cur != null) r.switchTo(r.cur.id); // records where the last voice ended
        // a voice declared in the header but never used has nothing to show
        for (int i = r.s.voices.size() - 1; i >= 0; i--) if (r.s.voices.get(i).events.size() == 0) r.s.voices.remove(i);
        for (int i = 0; i < r.s.voices.size(); i++) {
            Voice v = r.s.voices.get(i);
            int last = v.bars.get(v.bars.size() - 1).intValue();
            if (last >= v.end && v.bars.size() > 1) v.bars.remove(v.bars.size() - 1); // a closing bar line starts no bar
        }
        return r.s;
    }
}
