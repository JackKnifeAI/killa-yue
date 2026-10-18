package ai.jackknife.killayue.studio;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;

/** Killa Groove plays like a drummer: backbeats where rock puts them, repeatable seeds, busy and feel work. */
public final class GrooveModelTest {
    static int passed;
    static void check(boolean b, String s) {
        if (!b)
            throw new AssertionError(s);
        passed++;
    }
    static int count(List<MidiNotes.Note> ns, int key) {
        int n = 0;
        for (MidiNotes.Note x : ns) if (x.key == key) n++;
        return n;
    }
    public static void main(String[] args) throws Exception {
        GrooveModel m;
        try (FileInputStream in = new FileInputStream(new File("assets/studio/killa-groove-v2.bin"))) {
            m = GrooveModel.read(in);
        }
        List<MidiNotes.Note> a = m.play(0, 4, 1, 1, 42), b = m.play(0, 4, 1, 1, 42);
        check(a.size() == b.size() && a.get(a.size() - 1).beat == b.get(b.size() - 1).beat, "same seed, same groove");
        // rock: over many grooves, snares mostly on beats 2 and 4, kicks mostly on 1 and 3's side of the bar
        int backbeat = 0, snares = 0, hats = 0, notes = 0, sparse = 0, dense = 0;
        for (int seed = 1; seed <= 40; seed++) {
            List<MidiNotes.Note> g = m.play(0, 4, 1, 0, seed);
            notes += g.size();
            for (MidiNotes.Note n : g) {
                double inBar = n.beat % 4;
                if (n.key == 38) {
                    snares++;
                    if (Math.abs(inBar - 1) < .01 || Math.abs(inBar - 3) < .01)
                        backbeat++;
                }
                if (n.key == 42 || n.key == 51)
                    hats++;
                check(Math.abs(n.beat * 4 - Math.rint(n.beat * 4)) < 1e-9, "feel 0 is on the grid");
                check(n.velocity > 0 && n.velocity <= 1 && n.key >= 36 && n.key <= 51, "valid hits");
            }
            sparse += m.play(0, 4, .6f, 1, seed).size();
            dense += m.play(0, 4, 1.4f, 1, seed).size();
        }
        System.out.println("  rock: " + notes / 40 + " hits per 4 bars, " + backbeat * 100 / Math.max(1, snares) + "% of snares on 2 and 4, "
            + hats / 40 + " hats or rides");
        check(snares > 0 && backbeat * 2 > snares, "rock snares fall mostly on the backbeat");
        check(hats > 40 * 8, "a timekeeper (hats or ride) plays through");
        check(sparse < notes && notes < dense, "busy makes fewer or more hits");
        for (int s = 0; s < GrooveModel.STYLES.length; s++) check(!m.play(s, 2, 1, 1, 7).isEmpty(), GrooveModel.STYLE_NAMES[s] + " plays");
        System.out.println("GrooveModelTest: " + passed + " checks passed");
    }
}
