package ai.jackknife.killayue.studio;

/** History.drag: a whole fader drag is one undo step, and Edit.compound: several changes as one step. */
public class HistoryDragTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        Project p = new Project("Remix", 48000);
        History h = new History(p);
        h.run(Edit.addTrack("Vocals", "audio/vocals.wav", 480000));
        p.track("t1").gainDb = -3f;
        Object drag = new Object();
        for (int i = 0; i < 200; i++) h.drag(Edit.gain("t1", -3f - i * 0.05f), drag);
        same("200 moves of one drag are one step", 3, h.steps().size());
        same("the level is where the drag ended", -3f - 199 * 0.05f, p.track("t1").gainDb);
        same("the step is labelled for what it changed", "Level", h.undoLabel());
        h.undo();
        same("one undo goes back to before the drag", -3f, p.track("t1").gainDb);
        h.redo();
        same("redo brings back where it ended", -3f - 199 * 0.05f, p.track("t1").gainDb);

        // a merged step saves and loads as the step it became
        String fp = "x";
        History back = History.fromJson(Json.parse(Json.write(h.toJson(fp))), p.copy());
        same("the merged step survives saving", 3, back.steps().size());
        back.undo();
        same("and undoes to before the drag after loading", -3f, back.project().track("t1").gainDb);

        // a new gesture is a new step
        h.drag(Edit.gain("t1", -12f), new Object());
        same("a second drag is a second step", 4, h.steps().size());
        // the same gesture on another track or another control is a new step too
        Object both = new Object();
        h.drag(Edit.pan("t1", 0.5f), both);
        h.drag(Edit.gain("t1", -1f), both);
        same("pan then level in one gesture: two steps", 6, h.steps().size());

        // never merge into a step that has branches: undo, then drag, makes a branch
        int before = h.steps().size();
        Object again = new Object();
        h.drag(Edit.gain("t1", -2f), again);
        h.undo();
        h.drag(Edit.gain("t1", -4f), again);
        same("after an undo the same gesture starts a branch, not a merge", before + 2, h.steps().size());
        h.undo();
        same("there are two branches to redo", 2, h.redoChoices().size());

        // any other change ends the gesture
        Object g = new Object();
        h.redo();
        h.drag(Edit.gain("t1", -5f), g);
        h.run(Edit.mute("t1", true));
        h.drag(Edit.gain("t1", -6f), g);
        same("a run() between ends the drag", "Level", h.undoLabel());
        h.undo();
        same("so undo goes to the mute, not before it", true, p.track("t1").mute);

        // compound: several edits, one step
        Project q = new Project("Split", 48000);
        History k = new History(q);
        k.run(Edit.addTrack("Song", "audio/song.wav", 1000));
        k.run(Edit.compound("Split into stems", Edit.mute("t1", true), Edit.addTrack("Vocals", "audio/v.wav", 1000),
                Edit.addTrack("Drums", "audio/d.wav", 1000)));
        same("a compound edit is one step", 3, k.steps().size());
        same("with its own label", "Split into stems", k.undoLabel());
        same("all its parts applied", "true 3", q.track("t1").mute + " " + q.tracks.size());
        k.undo();
        same("one undo takes all of it back", "false 1", q.track("t1").mute + " " + q.tracks.size());
        k.redo();
        same("redo puts it all back with the same IDs", "t3 t5", q.tracks.get(1).id + " " + q.tracks.get(2).id);
        History k2 = History.fromJson(Json.parse(Json.write(k.toJson(fp))), q.copy());
        k2.undo();
        same("a compound edit survives saving", 1, k2.project().tracks.size());

        System.out.println("HistoryDragTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
