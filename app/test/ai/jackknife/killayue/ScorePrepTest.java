package ai.jackknife.killayue;

/** Tests for ScorePrep: the score is made ready for engraving without changing a single note. */
public class ScorePrepTest {
    private static int failed, passed;

    private static void same(String name, String want, String got) {
        if (want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want.replace("\n", "\\n") + "\n  got:  " + got.replace("\n", "\\n"));
        }
    }

    public static void main(String[] a) {
        // 4/4 with L:1/32 has a beat of 8 units: beams break at every beat
        same("eighths break into beats", "e4e4 e4e4 e4e4 e4e4|", ScorePrep.rebeam("e4e4e4e4e4e4e4e4|", 4, 4, 1, 32));
        same("sixteenths break into beats", "a2g2a2g2 e8|", ScorePrep.rebeam("a2g2a2g2e8|", 4, 4, 1, 32));
        same("a chord name stays with its note", "\"Em\"B8 \"Am\"A8 z16|", ScorePrep.rebeam("\"Em\"B8\"Am\"A8z16|", 4, 4, 1, 32));
        same("a tie stays on its note", "e4b4- b8 z16|", ScorePrep.rebeam("e4b4-b8z16|", 4, 4, 1, 32));
        same("octave marks and accidentals are part of the note", "^c'4d,4 =e8 z16|", ScorePrep.rebeam("^c'4d,4=e8z16|", 4, 4, 1, 32));
        same("fractional lengths count exactly", "A3/2B/2 A2 z4|", ScorePrep.rebeam("A3/2B/2A2z4|", 4, 4, 1, 8));
        same("6/8 beams in dotted quarters", "a4a4a4 a4a4a4|", ScorePrep.rebeam("a4a4a4a4a4a4|", 6, 8, 1, 32));
        same("every bar starts counting again", "e4e4 e4e4 e4e4 e4e4|e4e4 e4e4 e4e4 e4e4|", ScorePrep.rebeam("e4e4e4e4e4e4e4e4|e4e4e4e4e4e4e4e4|", 4, 4, 1, 32));
        same("existing spaces are kept", "e4 e4 e4e4 z16|", ScorePrep.rebeam("e4 e4e4e4z16|", 4, 4, 1, 32));
        same("syntax it does not know leaves the line as written", "(3e4e4e4e8z16|", ScorePrep.rebeam("(3e4e4e4e8z16|", 4, 4, 1, 32));

        String song = "X:1\nT:\nM:4/4\nL:1/32\nQ:1/4=92\n"
                + "V: Vocal clef=treble name=\"Vocal Melody\" snm=\"Vocal\"\n"
                + "V: Ins clef=treble name=\"Ins Melody\" snm=\"Inst.\"\n"
                + "K:Em\n"
                + "% intro\nV: Vocal\nz32|z32|\nV: Ins\nZ2|\n"
                + "% chorus\nV: Vocal\n\"Em\"B8A8G8E8|z32|\nV: Ins\ne32|e32|\n";
        String want = "X:1\nT:Cut the Line\nT:Killa YuE · key of Em\nM:4/4\nL:1/32\nQ:1/4=92\n"
                + "V: Vocal clef=treble name=\"Vocal\" snm=\"Vocal\"\n"
                + "V: Ins clef=treble name=\"Instrument\" snm=\"Inst.\"\n"
                + "K:Em\n"
                + "V: Vocal\n[P:Intro]z32|z32|[P:Chorus]\"Em\"B8 A8 G8 E8|z32|\n"
                + "V: Ins\nz32|z32|e32|e32|\n";
        same("a full score: rests spelled out, sections labelled, bars reflowed", want, ScorePrep.prepare(song, "Cut the Line", "Killa YuE · key of Em"));

        StringBuilder sparse = new StringBuilder(), sparseIns = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sparse.append("z32|");
            sparseIns.append("e32|");
        }
        String eight = "X:1\nT:\nM:4/4\nL:1/32\nV: A\nV: B\nK:C\nV: A\n" + sparse + "\nV: B\n" + sparseIns + "\n";
        same("light bars: four to a line",
                "X:1\nT:t\nM:4/4\nL:1/32\nV: A\nV: B\nK:C\nV: A\nz32|z32|z32|z32|\nV: B\ne32|e32|e32|e32|\nV: A\nz32|z32|z32|z32|\nV: B\ne32|e32|e32|e32|\n",
                ScorePrep.prepare(eight, "t", ""));

        StringBuilder busy = new StringBuilder();
        for (int i = 0; i < 16; i++) busy.append("a2");
        String b = busy + "|";
        String dense = "X:1\nT:\nM:4/4\nL:1/32\nV: A\nV: B\nK:C\nV: A\n" + b + b + b + "\nV: B\nz32|z32|z32|\n";
        String rb = ScorePrep.rebeam(b, 4, 4, 1, 32);
        same("busy bars: two to a line",
                "X:1\nT:t\nM:4/4\nL:1/32\nV: A\nV: B\nK:C\nV: A\n" + rb + rb + "\nV: B\nz32|z32|\nV: A\n" + rb + "\nV: B\nz32|\n",
                ScorePrep.prepare(dense, "t", ""));

        String named = "X:1\nT:\nM:4/4\nL:1/32\nV: A\nV: B\nK:C\nV: A\n\"Em\"Z2|e32|\nV: B\nz32|z32|e32|\n";
        same("a chord name before a multi-bar rest stays on its first bar",
                "X:1\nT:t\nM:4/4\nL:1/32\nV: A\nV: B\nK:C\nV: A\n\"Em\"z32|z32|e32|\nV: B\nz32|z32|e32|\n",
                ScorePrep.prepare(named, "t", ""));

        String single = "X:1\nT:\nM:4/4\nL:1/32\nV: Ins\nK:C\nV: Ins\nZ4|e32|\n";
        same("one voice keeps its multi-bar rest", "X:1\nT:t\nM:4/4\nL:1/32\nV: Ins\nK:C\nV: Ins\nZ4|e32|\n", ScorePrep.prepare(single, "t", ""));

        String one = "X:1\nT:\nM:4/4\nL:1/32\nV: P\nK:C\nV: P\nZ|Z1|Z3|e32|\n";
        same("one voice: a one-bar rest is a whole rest, longer ones stay multi-bar rests",
                "X:1\nT:t\nM:4/4\nL:1/32\nV: P\nK:C\nV: P\nz32|z32|Z3|e32|\n", ScorePrep.prepare(one, "t", ""));

        System.out.println("ScorePrepTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
