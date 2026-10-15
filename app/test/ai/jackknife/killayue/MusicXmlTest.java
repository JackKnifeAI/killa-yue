package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tests for MusicXml: a song's score, or a part, written as MusicXML that notation programs open. */
public class MusicXmlTest {
    private static int failed, passed;

    private static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    private static boolean has(String xml, String part) {
        return xml.replaceAll(">\\s+<", "><").contains(part);
    }

    /** Each note or rest as "step alter octave duration type dots tie", in order. */
    private static ArrayList<String> notes(String xml) {
        ArrayList<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("<note>(.*?)</note>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String n = m.group(1);
            StringBuilder b = new StringBuilder();
            if (n.contains("<rest")) b.append(n.contains("measure=\"yes\"") ? "bar-rest" : "rest");
            else b.append(get(n, "step")).append(get(n, "alter").length() > 0 ? get(n, "alter") : "0").append(get(n, "octave"));
            b.append(' ').append(get(n, "duration"));
            if (get(n, "type").length() > 0) b.append(' ').append(get(n, "type"));
            int dots = n.split("<dot/>", -1).length - 1;
            for (int k = 0; k < dots; k++) b.append('.');
            if (n.contains("<tie type=\"stop\"/>")) b.append(" <");
            if (n.contains("<tie type=\"start\"/>")) b.append(" >");
            out.add(b.toString());
        }
        return out;
    }

    private static String get(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private static String song(String key, String vocal) {
        return "X:1\nT:\nM:4/4\nL:1/16\nQ:1/4=92\nV: Vocal clef=treble name=\"Vocal Melody\" snm=\"Vocal\"\nK:" + key + "\n% intro\nV: Vocal\n" + vocal + "\n";
    }

    public static void main(String[] a) {
        String x = MusicXml.write(song("Fm", "\"Fm\"F4c4A6G2|"), "Gold", null);
        same("a MusicXML 4.0 score", true, x.startsWith("<?xml") && has(x, "<score-partwise version=\"4.0\">"));
        same("the title", true, has(x, "<work><work-title>Gold</work-title></work>"));
        same("the part is named as on the sheet", true, has(x, "<part-name>Vocal</part-name>"));
        same("a quarter is four divisions when the score's unit is a sixteenth", true, has(x, "<divisions>4</divisions>"));
        same("F minor has four flats", true, has(x, "<key><fifths>-4</fifths><mode>minor</mode></key>"));
        same("four four time", true, has(x, "<time><beats>4</beats><beat-type>4</beat-type></time>"));
        same("treble clef", true, has(x, "<clef><sign>G</sign><line>2</line></clef>"));
        same("the tempo, shown and played", true, has(x, "<per-minute>92</per-minute>") && has(x, "<sound tempo=\"92\"/>"));
        same("the section's label", true, has(x, "<rehearsal>Intro</rehearsal>"));
        same("notes in the key: A is flat in F minor; a sixteenth-note unit makes C4 a quarter, A6 a dotted quarter",
                "[F04 4 quarter, C05 4 quarter, A-14 6 quarter., G04 2 eighth]", notes(x));
        same("the chord name", true, has(x, "<harmony><root><root-step>F</root-step></root><kind text=\"m\">minor</kind></harmony>"));

        String t = MusicXml.write(song("C", "c5c3-|c4z12|"), "T", null);
        same("a length no single note has is two notes tied; a tie across the bar line is kept",
                "[C05 4 quarter >, C05 1 16th <, C05 3 eighth. >, C05 4 quarter <, rest 12 half.]", notes(t));

        String acc = MusicXml.write(song("C", "^F4F4=F4F4|F16|"), "A", null);
        same("an accidental holds to the bar line; a natural cancels it", "[F14 4 quarter, F14 4 quarter, F04 4 quarter, F04 4 quarter, F04 16 whole]",
                notes(acc));
        same("the sharp and the natural are shown where the score writes them", true,
                has(acc, "<accidental>sharp</accidental>") && has(acc, "<accidental>natural</accidental>"));

        String rest = MusicXml.write(song("C", "Z2|C16|"), "R", null);
        same("a multi-bar rest is a whole-bar rest in each bar", "[bar-rest 16, bar-rest 16, C04 16 whole]", notes(rest));
        same("three bars", 3, rest.split("<measure ", -1).length - 1);

        String chords = MusicXml.write(song("C", "\"Ebmaj7\"C4\"Bb7sus4\"C4\"D/F#\"C4\"C#m7b5\"C4|"), "C", null);
        same("a flat root and a major seventh", true, has(chords, "<root><root-step>E</root-step><root-alter>-1</root-alter></root><kind text=\"maj7\">major-seventh</kind>"));
        same("a seventh with a suspended fourth: the sus4 chord with a minor seventh added", true,
                has(chords, "<kind text=\"7sus4\">suspended-fourth</kind><degree><degree-value>7</degree-value><degree-alter>-1</degree-alter><degree-type>add</degree-type></degree>"));
        same("a slash chord's bass", true, has(chords, "<kind text=\"\">major</kind><bass><bass-step>F</bass-step><bass-alter>1</bass-alter></bass>"));
        same("half-diminished", true, has(chords, "<kind text=\"m7b5\">half-diminished</kind>"));
        boolean refused = false;
        try {
            MusicXml.write(song("C", "\"Cblargh\"C16|"), "X", null);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        same("a chord name it does not know stops the export", true, refused);

        // parts: the guitar's octave clef, and the alto sax's transposition
        String abc = song("Em", "\"Em\"E4G4B4e4|");
        String gtr = MusicXml.write(Transpose.part(abc, "Vocal", Instruments.find("guitar")).abc, "G", Instruments.find("guitar"));
        same("guitar: treble clef with an 8 below", true, has(gtr, "<clef><sign>G</sign><line>2</line><clef-octave-change>-1</clef-octave-change></clef>"));
        same("guitar: written an octave up, so under that clef the notes are stored where they sound",
                "[E04 4 quarter, G04 4 quarter, B04 4 quarter, E05 4 quarter]", notes(gtr));
        same("guitar: no transposition beyond the clef", false, has(gtr, "<transpose>"));
        String sax = MusicXml.write(Transpose.part(abc, "Vocal", Instruments.find("alto-sax")).abc, "S", Instruments.find("alto-sax"));
        same("alto sax: sounds a major sixth below what is written", true,
                has(sax, "<transpose><diatonic>-5</diatonic><chromatic>-9</chromatic></transpose>"));
        same("alto sax: written in C sharp minor", true, has(sax, "<key><fifths>4</fifths><mode>minor</mode></key>"));
        same("alto sax: the part's name", true, has(sax, "<part-name>Alto Sax</part-name>"));
        String tenor = MusicXml.write(Transpose.part(abc, "Vocal", Instruments.find("tenor-sax")).abc, "S", Instruments.find("tenor-sax"));
        same("tenor sax: a major ninth below, as a second and an octave", true,
                has(tenor, "<transpose><diatonic>-1</diatonic><chromatic>-2</chromatic><octave-change>-1</octave-change></transpose>"));

        ArrayList<String> missing = new ArrayList<String>();
        for (String q : Chords.qualities()) {
            try {
                MusicXml.harmony("C" + q);
            } catch (IllegalArgumentException e) {
                missing.add(q);
            }
        }
        same("every chord quality the app reads has a MusicXML chord kind", "[]", missing);

        System.out.println("MusicXmlTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
