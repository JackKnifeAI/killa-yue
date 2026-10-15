package ai.jackknife.killayue;

/** Tests for Transpose: a song's line written for an instrument, every note exactly the instrument's interval away. */
public class TransposeTest {
    private static int failed, passed;

    private static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + String.valueOf(want).replace("\n", "\\n") + "\n  got:  " + String.valueOf(got).replace("\n", "\\n"));
        }
    }

    // intervals as {letter steps, semitones}
    private static final int[] UNISON = {0, 0}, MAJOR_SECOND = {1, 2}, MAJOR_SIXTH = {5, 9}, OCTAVE = {7, 12}, MAJOR_NINTH = {8, 14};

    public static void main(String[] a) {
        same("E minor has one sharp", 1, Transpose.fifths("Em"));
        same("B flat major has two flats", -2, Transpose.fifths("Bb"));
        same("D sharp minor has six sharps", 6, Transpose.fifths("D#m"));
        same("F minor has four flats", -4, Transpose.fifths("Fm"));
        same("C major", 0, Transpose.fifths("C"));
        same("a key with a mode word", 1, Transpose.fifths("E minor"));

        same("E minor for alto sax is C sharp minor", "C#m", Transpose.key("Em", MAJOR_SIXTH)[0]);
        same("B flat for B flat trumpet is C", "C", Transpose.key("Bb", MAJOR_SECOND)[0]);
        same("D sharp minor for alto sax is spelled C minor, not B sharp minor", "Cm", Transpose.key("D#m", MAJOR_SIXTH)[0]);
        same("the respelled interval is a diminished seventh", "6,9", Transpose.key("D#m", MAJOR_SIXTH)[1] + "," + Transpose.key("D#m", MAJOR_SIXTH)[2]);
        same("an octave keeps the key", "Em", Transpose.key("Em", OCTAVE)[0]);

        same("a note in the new key needs no accidental (F sharp up a major sixth is D sharp, in C sharp minor)",
                "d'2", Transpose.music("f2", "Em", 5, 9));
        same("an accidental carries through the bar and stops at the bar line",
                "^d2d2|d2", Transpose.music("^c2c2|c2", "C", 1, 2));
        same("a natural against the new key is written, once per bar",
                "=g2g2|g2", Transpose.music("=f2f2|f2", "G", 1, 2));
        same("rests, ties, chord names and bar lines are kept", "z4\"C#m\"c'4-c'8|Z2|", Transpose.music("z4\"Em\"e4-e8|Z2|", "Em", 5, 9));
        same("octave marks follow the new octave", "c'2C2", Transpose.music("c2C,2", "C", 7, 12));
        same("a double sharp when the interval needs one (B sharp 4 up a whole step is C double sharp 5)", "^^c2", Transpose.music("^b,2", "C", 1, 2));

        same("chord name root", "C#m7", Transpose.chord("Em7", 5, 9));
        same("chord name with a flat root", "Cmaj7", Transpose.chord("Bbmaj7", 1, 2));
        same("chord name with a bass note", "E/G#", Transpose.chord("D/F#", 1, 2));
        same("text that is not a chord is left alone", "^Chorus", Transpose.chord("^Chorus", 1, 2));

        same("octave fit: a low line stays", 0, Transpose.octaveFit(new int[] {60, 64, 67}, 55, 90));
        same("octave fit: a line too high for the range moves down", -1, Transpose.octaveFit(new int[] {84, 88, 91}, 40, 80));
        same("octave fit: a line too low moves up two octaves when it must", 2, Transpose.octaveFit(new int[] {36, 40, 43}, 60, 84));

        String song = "X:1\nT:\nM:4/4\nL:1/32\nQ:1/4=92\n"
                + "V: Vocal clef=treble name=\"Vocal Melody\" snm=\"Vocal\"\n"
                + "V: Ins clef=treble name=\"Ins Melody\" snm=\"Inst.\"\n"
                + "K:Em\n"
                + "% intro\nV: Vocal\nz32|\nV: Ins\ne32|\n"
                + "% chorus\nV: Vocal\n\"Em\"B8A8G8E8|\nV: Ins\nE32|\n";
        Transpose.Part p = Transpose.part(song, "Vocal", Instruments.find("alto-sax"));
        same("a part: one voice, the instrument's key, clef and name, sections kept, every note moved a major sixth up",
                "X:1\nT:\nM:4/4\nL:1/32\nQ:1/4=92\nV: P clef=treble name=\"Alto Sax\" snm=\"A. Sax\"\nK:C#m\n"
                        + "% intro\nV: P\nz32|\n% chorus\nV: P\n\"C#m\"g8f8e8c8|\n",
                p.abc);
        same("the part says it moved no octave", 0, p.octaves);

        Transpose.Part tuba = Transpose.part(song, "Vocal", Instruments.find("tuba"));
        same("tuba: the line moves down an octave to fit", -1, tuba.octaves);
        same("tuba: bass clef, concert key", true, tuba.abc.contains("V: P clef=bass") && tuba.abc.contains("K:Em\n"));

        System.out.println("TransposeTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
