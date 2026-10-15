package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A song's score, or one instrument's part, as MusicXML 4.0 for MuseScore, Sibelius, Dorico and Finale.
 * Every voice becomes a part; every note keeps its exact pitch and length (a length no single note has is
 * written as notes tied together); chord names become chord symbols, section comments rehearsal marks.
 * A part for a transposing instrument says how it transposes; under a clef with an 8 below, notes are
 * stored where they sound, as MusicXML expects. Anything it does not know stops it with the reason.
 */
class MusicXml {
    private static final String LETTERS = "CDEFGAB";
    private static final int[] NATURAL = {0, 2, 4, 5, 7, 9, 11};

    // chord qualities (Chords' own spellings) as MusicXML kinds, with any degrees added or altered:
    // {kind, degree value, alter, type, ...}
    private static final HashMap<String, String[]> KINDS = new HashMap<String, String[]>();

    private static void kind(String quality, String... k) {
        KINDS.put(quality, k);
    }

    static {
        kind("", "major");
        kind("m", "minor");
        kind("5", "power");
        kind("6", "major-sixth");
        kind("m6", "minor-sixth");
        kind("7", "dominant");
        kind("maj7", "major-seventh");
        kind("m7", "minor-seventh");
        kind("mMaj7", "major-minor");
        kind("dim", "diminished");
        kind("dim7", "diminished-seventh");
        kind("m7b5", "half-diminished");
        kind("aug", "augmented");
        kind("aug7", "augmented-seventh");
        kind("sus2", "suspended-second");
        kind("sus4", "suspended-fourth");
        kind("7sus4", "suspended-fourth", "7", "-1", "add");
        kind("7sus2", "suspended-second", "7", "-1", "add");
        kind("add9", "major", "9", "0", "add");
        kind("madd9", "minor", "9", "0", "add");
        kind("9", "dominant-ninth");
        kind("maj9", "major-ninth");
        kind("m9", "minor-ninth");
        kind("6/9", "major-sixth", "9", "0", "add");
        kind("7b9", "dominant", "9", "-1", "add");
        kind("7#9", "dominant", "9", "1", "add");
        kind("7b5", "dominant", "5", "-1", "alter");
        kind("11", "dominant-11th");
        kind("m11", "minor-11th");
        kind("13", "dominant-13th");
        kind("maj13", "major-13th");
        kind("m13", "minor-13th");
        kind("maj7#11", "major-seventh", "11", "1", "add");
    }

    private static class Voice {
        String id, name = "", shortName = "", clef = "treble";
        final ArrayList<String> lines = new ArrayList<String>();
        final ArrayList<Integer> lineStarts = new ArrayList<Integer>(); // index into lines where a labelled section begins
        final ArrayList<String> labels = new ArrayList<String>();
    }

    /**
     * The score as MusicXML. ins is the instrument of a one-voice part from Transpose (its transposition is
     * written in), or null for a score at concert pitch.
     */
    static String write(String abc, String title, Instruments.Instrument ins) {
        int meterNum = 4, meterDen = 4, unitNum = 1, unitDen = 8, tempo = 0;
        String key = "C";
        LinkedHashMap<String, Voice> voices = new LinkedHashMap<String, Voice>();
        Voice cur = null;
        String pending = null;
        boolean body = false;
        Pattern voiceLine = Pattern.compile("^V:\\s*(\\S+)(.*)$");
        for (String row : abc.replace("\r", "").split("\n")) {
            String r = row.trim();
            if (r.length() == 0) continue;
            Matcher v = voiceLine.matcher(r);
            if (!body) {
                if (r.startsWith("M:")) {
                    String[] p = r.substring(2).trim().split("/");
                    meterNum = Integer.parseInt(p[0].trim());
                    meterDen = Integer.parseInt(p[1].trim());
                } else if (r.startsWith("L:")) {
                    String[] p = r.substring(2).trim().split("/");
                    unitNum = Integer.parseInt(p[0].trim());
                    unitDen = Integer.parseInt(p[1].trim());
                } else if (r.startsWith("Q:")) {
                    Matcher q = Pattern.compile("1/4\\s*=\\s*(\\d+)").matcher(r);
                    if (q.find()) tempo = Integer.parseInt(q.group(1));
                } else if (r.startsWith("K:")) {
                    key = r.substring(2).trim();
                    body = true;
                } else if (v.matches()) {
                    voice(voices, v.group(1), v.group(2));
                }
                continue;
            }
            if (r.startsWith("%")) {
                String s = r.substring(1).trim();
                if (s.length() > 0) pending = Character.toUpperCase(s.charAt(0)) + s.substring(1);
            } else if (v.matches()) {
                cur = voice(voices, v.group(1), v.group(2));
                if (pending != null && cur == voices.values().iterator().next()) {
                    cur.lineStarts.add(Integer.valueOf(cur.lines.size()));
                    cur.labels.add(pending);
                    pending = null;
                }
            } else if (r.matches("^[A-Za-z]:.*") || r.contains("[K:") || r.contains("[M:") || r.contains("[L:")) {
                throw new IllegalArgumentException("This score changes key, metre or length inside the music, which MusicXML export does not handle yet.");
            } else {
                if (cur == null) throw new IllegalArgumentException("The score has music before any voice.");
                cur.lines.add(r);
            }
        }
        if (voices.isEmpty()) throw new IllegalArgumentException("The score has no voices.");
        if (ins != null && voices.size() != 1) throw new IllegalArgumentException("A part has one voice.");

        // divisions per quarter: one per unit of L where it can be, and enough that every length and the bar
        // are whole numbers of them
        long divisions = unitDen % (4 * unitNum) == 0 ? unitDen / (4 * unitNum) : 1;
        divisions = lcm(divisions, meterDen / gcd(4L * meterNum, meterDen));
        for (Voice vc : voices.values()) for (String l : vc.lines) {
            ArrayList<ScorePrep.Tok> ts = ScorePrep.tokens(l);
            if (ts == null) throw new IllegalArgumentException("This score has notation MusicXML export does not know: " + l);
            for (ScorePrep.Tok t : ts) if (t.kind == ScorePrep.NOTE || t.kind == ScorePrep.REST) {
                long n = 4L * unitNum * t.num, d = (long) unitDen * t.den;
                divisions = lcm(divisions, d / gcd(n, d));
            }
        }
        long bar = 4 * divisions * meterNum / meterDen;
        int fifths = Transpose.fifths(key);
        boolean minor = key.matches("^[A-G][#b]?\\s*m(?!aj).*");

        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n");
        x.append("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" \"http://www.musicxml.org/dtds/partwise.dtd\">\n");
        x.append("<score-partwise version=\"4.0\">\n");
        x.append("  <work><work-title>").append(esc(title)).append("</work-title></work>\n");
        x.append("  <identification><encoding><software>Killa YuE</software></encoding></identification>\n");
        x.append("  <part-list>\n");
        int pn = 0;
        for (Voice vc : voices.values()) {
            pn++;
            x.append("    <score-part id=\"P").append(pn).append("\"><part-name>").append(esc(vc.name)).append("</part-name>");
            if (vc.shortName.length() > 0) x.append("<part-abbreviation>").append(esc(vc.shortName)).append("</part-abbreviation>");
            x.append("</score-part>\n");
        }
        x.append("  </part-list>\n");
        pn = 0;
        for (Voice vc : voices.values()) {
            pn++;
            String clef = vc.clef;
            boolean octaveClef = clef.endsWith("-8");
            StringBuilder attrs = new StringBuilder();
            attrs.append("<attributes><divisions>").append(divisions).append("</divisions>");
            attrs.append("<key><fifths>").append(fifths).append("</fifths><mode>").append(minor ? "minor" : "major").append("</mode></key>");
            attrs.append("<time><beats>").append(meterNum).append("</beats><beat-type>").append(meterDen).append("</beat-type></time>");
            attrs.append(clef(clef));
            if (ins != null) attrs.append(transpose(ins, octaveClef));
            attrs.append("</attributes>");
            x.append("  <part id=\"P").append(pn).append("\">\n");
            Writer w = new Writer(fifths, divisions, unitNum, unitDen, bar, octaveClef ? -12 : 0);
            w.open(attrs.toString() + (pn == 1 && tempo > 0 ? "<direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>"
                    + tempo + "</per-minute></metronome></direction-type><sound tempo=\"" + tempo + "\"/></direction>" : ""));
            int labelAt = 0;
            for (int li = 0; li < vc.lines.size(); li++) {
                while (labelAt < vc.lineStarts.size() && vc.lineStarts.get(labelAt).intValue() == li) {
                    w.direction("<direction placement=\"above\"><direction-type><rehearsal>" + esc(vc.labels.get(labelAt)) + "</rehearsal></direction-type></direction>");
                    labelAt++;
                }
                w.line(vc.lines.get(li));
            }
            w.finish();
            x.append(w.out);
            x.append("  </part>\n");
        }
        x.append("</score-partwise>\n");
        return x.toString();
    }

    private static Voice voice(LinkedHashMap<String, Voice> voices, String id, String settings) {
        Voice v = voices.get(id);
        if (v == null) {
            v = new Voice();
            v.id = id;
            v.name = id;
            voices.put(id, v);
        }
        Matcher n = Pattern.compile("(?:^|\\s)name=\"([^\"]*)\"").matcher(settings);
        if (n.find()) {
            v.name = n.group(1).replaceAll("\\s+Melody$", "");
            if (v.name.equals("Ins")) v.name = "Instrument";
        }
        Matcher s = Pattern.compile("snm=\"([^\"]*)\"").matcher(settings);
        if (s.find()) v.shortName = s.group(1);
        Matcher c = Pattern.compile("clef=(\\S+)").matcher(settings);
        if (c.find()) v.clef = c.group(1);
        return v;
    }

    private static String clef(String clef) {
        if (clef.equals("treble")) return "<clef><sign>G</sign><line>2</line></clef>";
        if (clef.equals("treble-8")) return "<clef><sign>G</sign><line>2</line><clef-octave-change>-1</clef-octave-change></clef>";
        if (clef.equals("bass")) return "<clef><sign>F</sign><line>4</line></clef>";
        if (clef.equals("alto")) return "<clef><sign>C</sign><line>3</line></clef>";
        if (clef.equals("tenor")) return "<clef><sign>C</sign><line>4</line></clef>";
        throw new IllegalArgumentException("MusicXML export does not know the clef " + clef);
    }

    /**
     * How a transposing part's written notes become the sounding ones: sounding = written + chromatic +
     * 12 x octave-change. Under an octave clef the notes are stored an octave down already.
     */
    private static String transpose(Instruments.Instrument ins, boolean octaveClef) {
        int chromatic = -ins.semitones + (octaveClef ? 12 : 0), diatonic = -ins.steps + (octaveClef ? 7 : 0);
        if (chromatic == 0 && diatonic == 0) return "";
        int octave = chromatic / 12;
        chromatic -= 12 * octave;
        diatonic -= 7 * octave;
        return "<transpose><diatonic>" + diatonic + "</diatonic><chromatic>" + chromatic + "</chromatic>"
                + (octave != 0 ? "<octave-change>" + octave + "</octave-change>" : "") + "</transpose>";
    }

    /** A chord name as a MusicXML chord symbol. Throws for a name Chords does not know. */
    static String harmony(String name) {
        Chords.Chord c = Chords.parse(name);
        String[] k = KINDS.get(c.quality);
        if (k == null) throw new IllegalArgumentException("MusicXML export has no chord kind for " + name);
        StringBuilder h = new StringBuilder("<harmony>");
        String n = name.trim();
        int rootLen = 1;
        while (rootLen < n.length() && "#b\u266f\u266d".indexOf(n.charAt(rootLen)) >= 0) rootLen++;
        h.append("<root>").append(step(n, 0, "root")).append("</root>");
        h.append("<kind text=\"").append(esc(c.suffix)).append("\">").append(k[0]).append("</kind>");
        int bassAt = rootLen + c.suffix.length();
        if (bassAt < n.length() && n.charAt(bassAt) == '/') h.append("<bass>").append(step(n, bassAt + 1, "bass")).append("</bass>");
        for (int d = 1; d + 2 < k.length; d += 3) {
            h.append("<degree><degree-value>").append(k[d]).append("</degree-value><degree-alter>").append(k[d + 1])
                    .append("</degree-alter><degree-type>").append(k[d + 2]).append("</degree-type></degree>");
        }
        return h.append("</harmony>").toString();
    }

    /** "<root-step>E</root-step><root-alter>-1</root-alter>" for the note name at index from. */
    private static String step(String name, int from, String what) {
        char letter = name.charAt(from);
        int alter = 0;
        for (int i = from + 1; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '#' || ch == '\u266f') alter++;
            else if (ch == 'b' || ch == '\u266d') alter--;
            else break;
        }
        return "<" + what + "-step>" + letter + "</" + what + "-step>" + (alter != 0 ? "<" + what + "-alter>" + alter + "</" + what + "-alter>" : "");
    }

    /** Writes one voice's measures. */
    private static class Writer {
        final StringBuilder out = new StringBuilder();
        final int[] keyAlter = new int[7];
        final HashMap<Integer, Integer> barAlter = new HashMap<Integer, Integer>();
        final long divisions, bar;
        final int unitNum, unitDen, shift;
        StringBuilder measure = new StringBuilder();
        int number = 0;
        long filled = 0;
        boolean tieNext;          // the next note is tied from the one before
        String chord;             // a chord symbol waiting for its note or rest

        Writer(int fifths, long divisions, int unitNum, int unitDen, long bar, int shift) {
            String sharps = "FCGDAEB";
            for (int i = 0; i < Math.abs(fifths) && i < 7; i++) {
                char l = fifths > 0 ? sharps.charAt(i) : sharps.charAt(6 - i);
                keyAlter[LETTERS.indexOf(l)] = fifths > 0 ? 1 : -1;
            }
            this.divisions = divisions;
            this.unitNum = unitNum;
            this.unitDen = unitDen;
            this.bar = bar;
            this.shift = shift;
        }

        void open(String first) {
            number = 1;
            measure.append(first);
        }

        void direction(String d) {
            measure.append(d);
        }

        void line(String music) {
            ArrayList<ScorePrep.Tok> ts = ScorePrep.tokens(music);
            for (int i = 0; i < ts.size(); i++) {
                ScorePrep.Tok t = ts.get(i);
                if (t.kind == ScorePrep.NAME) {
                    String inner = t.text.substring(1, t.text.length() - 1);
                    if (inner.length() > 0 && "^_<>@".indexOf(inner.charAt(0)) >= 0) {
                        measure.append("<direction placement=\"above\"><direction-type><words>").append(esc(inner.substring(1)))
                                .append("</words></direction-type></direction>");
                    } else if (!Chords.noChord(inner)) {
                        measure.append(harmony(inner));
                    }
                } else if (t.kind == ScorePrep.NOTE) {
                    boolean tieOut = i + 1 < ts.size() && ts.get(i + 1).kind == ScorePrep.TIE;
                    note(t, tieOut);
                } else if (t.kind == ScorePrep.REST) {
                    rest(length(t));
                } else if (t.kind == ScorePrep.MULTI) {
                    for (int k = 0; k < t.bars; k++) {
                        measure.append("<note><rest measure=\"yes\"/><duration>").append(bar).append("</duration><voice>1</voice></note>");
                        filled += bar;
                        if (k < t.bars - 1) close();
                    }
                } else if (t.kind == ScorePrep.BAR) {
                    close();
                }
            }
        }

        long length(ScorePrep.Tok t) {
            long n = 4L * divisions * unitNum * t.num, d = (long) unitDen * t.den;
            if (n % d != 0) throw new IllegalArgumentException("a length that is not a whole number of divisions: " + t.text);
            return n / d;
        }

        void note(ScorePrep.Tok t, boolean tieOut) {
            Matcher m = Pattern.compile("^([\\^_=]*)([A-Ga-g])([,']*)").matcher(t.text);
            if (!m.find()) throw new IllegalArgumentException("not a note: " + t.text);
            String acc = m.group(1);
            char letter = m.group(2).charAt(0);
            int li = LETTERS.indexOf(Character.toUpperCase(letter));
            int octave = Character.isLowerCase(letter) ? 5 : 4;
            for (char c : m.group(3).toCharArray()) octave += c == '\'' ? 1 : -1;
            int place = octave * 7 + li, alter;
            if (acc.length() > 0) {
                alter = acc.startsWith("=") ? 0 : acc.charAt(0) == '^' ? acc.length() : -acc.length();
                barAlter.put(Integer.valueOf(place), Integer.valueOf(alter));
            } else {
                Integer held = barAlter.get(Integer.valueOf(place));
                alter = held != null ? held.intValue() : keyAlter[li];
            }
            octave += shift / 12;
            String shown = acc.length() == 0 ? null : alter == 2 ? "double-sharp" : alter == 1 ? "sharp" : alter == 0 ? "natural"
                    : alter == -1 ? "flat" : "flat-flat";
            String pitch = "<pitch><step>" + Character.toUpperCase(letter) + "</step>" + (alter != 0 ? "<alter>" + alter + "</alter>" : "")
                    + "<octave>" + octave + "</octave></pitch>";
            long[] pieces = split(length(t));
            for (int k = 0; k < pieces.length; k++) {
                boolean stop = k > 0 || tieNext, start = k < pieces.length - 1 || tieOut;
                emit(pitch, pieces[k], stop, start, k == 0 ? shown : null);
            }
            tieNext = tieOut;
        }

        void rest(long len) {
            for (long p : split(len)) emit("<rest/>", p, false, false, null);
            tieNext = false;
        }

        void emit(String what, long dur, boolean stop, boolean start, String accidental) {
            StringBuilder n = new StringBuilder("<note>").append(what).append("<duration>").append(dur).append("</duration>");
            if (stop) n.append("<tie type=\"stop\"/>");
            if (start) n.append("<tie type=\"start\"/>");
            n.append("<voice>1</voice>");
            int[] td = type(dur);
            n.append("<type>").append(TYPES[td[0]]).append("</type>");
            for (int d = 0; d < td[1]; d++) n.append("<dot/>");
            if (accidental != null) n.append("<accidental>").append(accidental).append("</accidental>");
            if (stop || start) {
                n.append("<notations>");
                if (stop) n.append("<tied type=\"stop\"/>");
                if (start) n.append("<tied type=\"start\"/>");
                n.append("</notations>");
            }
            measure.append(n.append("</note>"));
            filled += dur;
        }

        static final String[] TYPES = {"whole", "half", "quarter", "eighth", "16th", "32nd", "64th", "128th"};

        /** {type index, dots} for a length a single note has, or null. */
        int[] type(long dur) {
            for (int t = 0; t < TYPES.length; t++) {
                if ((4 * divisions) % (1L << t) != 0) break; // shorter than one division
                long base = 4 * divisions / (1L << t);
                for (int dots = 0; dots <= 2; dots++) {
                    long v = base * ((1L << (dots + 1)) - 1) / (1L << dots);
                    if (v == dur && base * ((1L << (dots + 1)) - 1) % (1L << dots) == 0) return new int[] {t, dots};
                }
            }
            return null;
        }

        /** A length as notes a score can show, longest first, to be tied together. */
        long[] split(long dur) {
            ArrayList<Long> out = new ArrayList<Long>();
            long left = dur;
            while (left > 0) {
                long best = 0;
                for (long v = left; v > 0; v--) if (type(v) != null) {
                    best = v;
                    break;
                }
                if (best == 0) throw new IllegalArgumentException("a length no notes can show: " + dur);
                out.add(Long.valueOf(best));
                left -= best;
            }
            long[] a = new long[out.size()];
            for (int i = 0; i < a.length; i++) a[i] = out.get(i).longValue();
            return a;
        }

        void close() {
            if (measure.length() == 0 && filled == 0) return;
            out.append("    <measure number=\"").append(number).append("\">").append(measure).append("</measure>\n");
            measure = new StringBuilder();
            number++;
            filled = 0;
            barAlter.clear();
        }

        void finish() {
            if (measure.length() > 0 || filled > 0) close();
            int at = out.lastIndexOf("</measure>");
            if (at >= 0) out.insert(at, "<barline location=\"right\"><bar-style>light-heavy</bar-style></barline>");
        }
    }

    private static long gcd(long a, long b) {
        return b == 0 ? Math.abs(a) : gcd(b, a % b);
    }

    private static long lcm(long a, long b) {
        return a / gcd(a, b) * b;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
