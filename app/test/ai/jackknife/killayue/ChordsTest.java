package ai.jackknife.killayue;

import java.util.Arrays;

/** Tests for Chords: chord names read exactly, and the shapes players know found on the fretboard. */
public class ChordsTest {
    private static int failed, passed;

    private static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    private static String tones(String name) {
        Chords.Chord c = Chords.parse(name);
        return c.root + " " + Arrays.toString(c.tones) + (c.bass != c.root ? " /" + c.bass : "");
    }

    /** A shape as players write it, low string first: x32010. */
    private static String shape(String chord, String instrument) {
        int[] f = Chords.shape(Chords.parse(chord), Instruments.find(instrument));
        StringBuilder b = new StringBuilder();
        for (int x : f) b.append(x < 0 ? "x" : x > 9 ? "(" + x + ")" : String.valueOf(x));
        return b.toString();
    }

    /** One of the shapes chord books give. */
    private static void oneOf(String name, String got, String... books) {
        same(name + " (one of " + java.util.Arrays.toString(books) + ")", true, java.util.Arrays.asList(books).contains(got) ? true : got);
    }

    public static void main(String[] a) {
        same("a major triad", "0 [0, 4, 7]", tones("C"));
        same("minor", "5 [0, 3, 7]", tones("Fm"));
        same("a flat root", "3 [0, 4, 7, 11]", tones("Ebmaj7"));
        same("a sharp root, minor", "3 [0, 3, 7]", tones("D#m"));
        same("minor seventh", "7 [0, 3, 7, 10]", tones("Gm7"));
        same("seventh, suspended fourth", "10 [0, 5, 7, 10]", tones("Bb7sus4"));
        same("suspended fourth", "9 [0, 5, 7]", tones("Asus4"));
        same("dominant seventh", "7 [0, 4, 7, 10]", tones("G7"));
        same("half-diminished", "11 [0, 3, 6, 10]", tones("Bm7b5"));
        same("diminished seventh", "1 [0, 3, 6, 9]", tones("C#dim7"));
        same("ninth", "2 [0, 4, 7, 10, 14]", tones("D9"));
        same("added ninth", "0 [0, 4, 7, 14]", tones("Cadd9"));
        same("power chord", "4 [0, 7]", tones("E5"));
        same("a slash chord keeps its bass", "2 [0, 4, 7] /6", tones("D/F#"));
        boolean refused = false;
        try {
            Chords.parse("Cblargh");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        same("a chord name it does not know is refused, never guessed", true, refused);

        // the open shapes in every guitar book
        same("C", "x32010", shape("C", "guitar"));
        same("G", "320003", shape("G", "guitar"));
        same("D", "xx0232", shape("D", "guitar"));
        same("A", "x02220", shape("A", "guitar"));
        same("E", "022100", shape("E", "guitar"));
        same("Am", "x02210", shape("Am", "guitar"));
        same("Em", "022000", shape("Em", "guitar"));
        same("Dm", "xx0231", shape("Dm", "guitar"));
        same("E7", "020100", shape("E7", "guitar"));
        same("A7", "x02020", shape("A7", "guitar"));
        same("D7", "xx0212", shape("D7", "guitar"));
        same("C7", "x32310", shape("C7", "guitar"));
        same("G7", "320001", shape("G7", "guitar"));
        same("Am7", "x02010", shape("Am7", "guitar"));
        same("Cmaj7", "x32000", shape("Cmaj7", "guitar"));
        same("Fmaj7", "xx3210", shape("Fmaj7", "guitar"));
        oneOf("Asus4", shape("Asus4", "guitar"), "x02230", "x00230");
        oneOf("F", shape("F", "guitar"), "133211", "xx3211");
        oneOf("Bm", shape("Bm", "guitar"), "x24432", "xx4432");
        same("Fm, barred at the first fret", "133111", shape("Fm", "guitar"));

        // and on the ukulele (g C E A)
        same("uke C", "0003", shape("C", "ukulele"));
        same("uke G", "0232", shape("G", "ukulele"));
        same("uke F", "2010", shape("F", "ukulele"));
        same("uke Am", "2000", shape("Am", "ukulele"));
        same("uke Dm", "2210", shape("Dm", "ukulele"));
        same("uke G7", "0212", shape("G7", "ukulele"));

        // mandolin (G D A E) and banjo (open G, the four long strings)
        same("mandolin G", "0023", shape("G", "mandolin"));
        same("mandolin C", "0230", shape("C", "mandolin"));
        same("banjo G, all open", "0000", shape("G", "banjo"));
        same("banjo C", "2012", shape("C", "banjo"));

        System.out.println("ChordsTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
