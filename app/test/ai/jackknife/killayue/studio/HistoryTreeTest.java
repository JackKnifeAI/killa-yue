package ai.jackknife.killayue.studio;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Tests for the undo tree: go back, try something else, and still return to what you had. */
public class HistoryTreeTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) throws Exception {
        Project p = new Project("Remix", 48000);
        History h = new History(p);
        h.run(Edit.addTrack("Vocals", "audio/vocals.wav", 480000));   // t1
        String vocals = p.toText();
        h.run(Edit.gain("t1", -3f));
        h.run(Edit.pan("t1", -0.5f));
        String quietLeft = p.toText();
        h.undo();
        h.undo();
        h.run(Edit.gain("t1", 2f));                                     // a new idea: a branch
        String loud = p.toText();
        same("a new edit after undo starts a branch", "2.0", p.track("t1").gainDb);
        h.undo();
        same("undo returns to the branch point", vocals, p.toText());
        same("both ideas are still there to redo", "[Level, Level]", h.redoChoices());
        h.redo();
        same("redo follows the branch last visited", loud, p.toText());
        h.undo();
        h.redo(0);
        h.redo();
        same("or take the other branch, and its later steps come back too", quietLeft, p.toText());

        List<History.Step> steps = h.steps();
        int loudId = -1;
        for (History.Step s : steps) if (s.label.equals("Level") && s.parent == steps.get(1).id && s.id != steps.get(2).id) loudId = s.id;
        h.jumpTo(loudId);
        same("jump straight to any step, across branches", loud, p.toText());
        h.jumpTo(steps.get(0).id);
        same("jump to the very start", new Project("Remix", 48000).toText().replace("\"nextId\":1", "\"nextId\":" + p.nextId), p.toText());
        same("every step is listed: start, add, two levels, pan", 5, h.steps().size());

        // a long session: thousands of steps, all the way back and forward again, exactly
        Project big = new Project("Long", 48000);
        History hb = new History(big);
        hb.run(Edit.addTrack("Drums", "audio/drums.wav", 1000));
        for (int i = 0; i < 5000; i++) hb.run(i % 2 == 0 ? Edit.gain("t1", -(i % 60)) : Edit.pan("t1", (i % 200 - 100) / 100f));
        String end = big.toText();
        while (hb.undo()) { }
        while (hb.redo()) { }
        same("five thousand steps back and forward land exactly where they were", end, big.toText());

        // the tree is kept with the project and comes back after the app is closed
        File dir = Files.createTempDirectory("studio").toFile();
        h.jumpTo(loudId);
        ProjectStore.save(p, dir);
        ProjectStore.saveHistory(h, dir);
        Project back = ProjectStore.load(dir).project;
        ProjectStore.LoadedHistory lh = ProjectStore.loadHistory(dir, back);
        same("the history loads with the project", true, lh.problem == null);
        History hh = lh.history;
        same("all its steps", 5, hh.steps().size());
        hh.undo();
        same("undo after reopening works", vocals, back.toText());
        hh.redo(0);
        hh.redo();
        same("and so does the other branch", quietLeft, back.toText());
        hh.undo();
        hh.undo();
        hh.undo();
        same("back to before the track was added", 0, back.tracks.size());
        hh.redo();
        same("and the track comes back with the same ID", "t1", back.tracks.get(0).id);

        // a history that does not belong to this project state is not trusted
        p.track("t1").name = "Edited outside the history";
        ProjectStore.save(p, dir);
        ProjectStore.LoadedHistory stale = ProjectStore.loadHistory(dir, ProjectStore.load(dir).project);
        same("a history from another save starts fresh, and says why", true, stale.problem != null && stale.history.steps().size() == 1);

        System.out.println("HistoryTreeTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
