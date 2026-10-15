package ai.jackknife.killayue;

/** Tests for Tabs: the tablature abcjs draws under a fretted instrument's part. */
public class TabsTest {
    private static int failed, passed;

    private static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        same("middle C", "C", Tabs.abcNote(60));
        same("an octave below middle C", "C,", Tabs.abcNote(48));
        same("the guitar's low E", "E,,", Tabs.abcNote(40));
        same("the octave above middle C is lower case", "e", Tabs.abcNote(76));
        same("two octaves above middle C", "c'", Tabs.abcNote(84));
        same("a black key is written as a sharp", "^F,", Tabs.abcNote(54));

        // abcjs reads a treble-8 part an octave down, so the guitar's strings are given at the pitch they sound
        same("guitar: six strings at sounding pitch, low E at the bottom",
                "[{\"instrument\":\"guitar\",\"label\":\"\",\"tuning\":[\"E,,\",\"A,,\",\"D,\",\"G,\",\"B,\",\"E\"],\"highestNote\":\"e'\"}]",
                Tabs.option(Instruments.find("guitar")));
        // a bass guitar part is in plain bass clef, written an octave above where it sounds
        same("bass guitar: four strings at written pitch",
                "[{\"instrument\":\"violin\",\"label\":\"\",\"tuning\":[\"E,,\",\"A,,\",\"D,\",\"G,\"],\"highestNote\":\"g\"}]",
                Tabs.option(Instruments.find("bass-guitar")));
        same("ukulele: the high G string stays high (re-entrant), so nothing is ever fretted on it by mistake",
                "[{\"instrument\":\"mandolin\",\"label\":\"\",\"tuning\":[\"G\",\"C\",\"E\",\"A\"],\"highestNote\":\"a'\"}]",
                Tabs.option(Instruments.find("ukulele")));
        same("mandolin: tuned like a violin",
                "[{\"instrument\":\"mandolin\",\"label\":\"\",\"tuning\":[\"G,\",\"D\",\"A\",\"e\"],\"highestNote\":\"e''\"}]",
                Tabs.option(Instruments.find("mandolin")));
        same("banjo: the short fifth string at the bottom, open G tuning",
                "[{\"instrument\":\"fiveString\",\"label\":\"\",\"tuning\":[\"G\",\"D,\",\"G,\",\"B,\",\"D\"],\"highestNote\":\"d'\"}]",
                Tabs.option(Instruments.find("banjo")));
        same("violin has no frets, so no tab", null, Tabs.option(Instruments.find("violin")));
        same("alto sax has no tab", null, Tabs.option(Instruments.find("alto-sax")));

        same("tuning names, low string first", "E A D G B E", Tabs.tuningName(Instruments.find("guitar")));
        same("banjo tuning names: the short high string in lower case", "g D G B D", Tabs.tuningName(Instruments.find("banjo")));
        same("ukulele tuning names: re-entrant high G", "g C E A", Tabs.tuningName(Instruments.find("ukulele")));

        same("the ukulele's lowest string is its C, not the high G", 60, Instruments.find("ukulele").lowestString());
        same("the banjo's lowest string is its D", 50, Instruments.find("banjo").lowestString());
        same("the guitar's lowest string is its low E", 40, Instruments.find("guitar").lowestString());

        String song = "X:1\nT:\nM:4/4\nL:1/8\nV: Vocal clef=treble\nK:C\nV: Vocal\nC,2c'2c'2c'2|\n";
        Transpose.Part uke = Transpose.part(song, "Vocal", Instruments.find("ukulele"));
        same("a part counts the notes below the lowest string, which the tab shows as ?", 1, uke.unplayable);
        same("fitting that wide line on a guitar moves it down an octave, below the low E", 1,
                Transpose.part(song, "Vocal", Instruments.find("guitar")).unplayable);
        String tune = "X:1\nT:\nM:4/4\nL:1/8\nV: Vocal clef=treble\nK:C\nV: Vocal\nC2E2G2c2|\n";
        same("a guitar can play every note of a line in its range", 0, Transpose.part(tune, "Vocal", Instruments.find("guitar")).unplayable);
        same("a part for an instrument without strings has none", 0, Transpose.part(song, "Vocal", Instruments.find("flute")).unplayable);

        System.out.println("TabsTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
