package ai.jackknife.killayue.studio;

import java.util.HashSet;
import java.util.List;
import java.util.Random;

/** TreeLayout: where each step of the undo tree is drawn, with no two in one place. */
public class TreeLayoutTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        Project p = new Project("Tree", 48000);
        History h = new History(p);
        h.run(Edit.addTrack("A", "audio/a.wav", 100));
        h.run(Edit.gain("t1", -1f));
        h.run(Edit.gain("t1", -2f));
        h.undo();
        h.undo();
        h.run(Edit.pan("t1", 0.3f));  // a branch at step 1
        h.run(Edit.pan("t1", 0.4f));  // and a step after it, on the branch
        TreeLayout t = TreeLayout.of(h);
        same("a branch's next step carries on in the branch's lane", "3 1", t.x[5] + " " + t.y[5]);
        same("the start is at the left, on the first lane", "0 0", t.x[0] + " " + t.y[0]);
        same("the first path stays on lane 0", "1 0 2 0 3 0", t.x[1] + " " + t.y[1] + " " + t.x[2] + " " + t.y[2] + " " + t.x[3] + " " + t.y[3]);
        same("a branch takes a lane of its own, one step right of where it split", "2 1", t.x[4] + " " + t.y[4]);
        same("two lanes, four columns", "2 4", t.lanes + " " + t.columns);
        same("the current step", 5, t.current);

        // a big random tree: no two steps in one place, every step one column right of its parent
        Random r = new Random(5);
        Project q = new Project("Big", 48000);
        History k = new History(q);
        k.run(Edit.addTrack("A", "audio/a.wav", 100));
        for (int i = 0; i < 1000; i++) {
            if (r.nextInt(4) == 0) {  // back a few steps (never past the track being added), then a new branch
                for (int u = r.nextInt(5); u >= 0 && k.currentStep() > 1; u--) k.undo();
            }
            k.run(Edit.gain("t1", -r.nextFloat() * 20));
        }
        TreeLayout big = TreeLayout.of(k);
        List<History.Step> steps = k.steps();
        HashSet<Long> places = new HashSet<Long>();
        boolean ok = true;
        for (History.Step s : steps) {
            if (!places.add(((long) big.x[s.id] << 32) | big.y[s.id])) ok = false;
            if (s.parent >= 0 && big.x[s.id] != big.x[s.parent] + 1) ok = false;
            if (s.parent >= 0 && big.y[s.id] < big.y[s.parent]) ok = false;  // branches go down, never up
        }
        same("1000 steps with branches: every step in a place of its own, one right of its parent", true, ok);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) TreeLayout.of(k);
        double ms = (System.nanoTime() - t0) / 20 / 1e6;
        same("laid out in under 5 ms (" + String.format("%.2f", ms) + ")", true, ms < 5);
        System.out.println("TreeLayoutTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
