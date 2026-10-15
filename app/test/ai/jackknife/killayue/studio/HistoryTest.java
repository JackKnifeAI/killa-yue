package ai.jackknife.killayue.studio;

public class HistoryTest {
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
        h.run(Edit.addTrack("Drums", "audio/drums.wav", 480000));
        String two = p.toText();
        h.run(Edit.gain("t1", -6f));
        h.run(Edit.solo("t3", true));
        same("edits apply", "-6.0 true", p.track("t1").gainDb + " " + p.track("t3").solo);
        String edited = p.toText();
        h.run(Edit.removeTrack("t1"));
        same("removed", null, p.track("t1"));
        h.undo();
        same("undoing a removal restores the track exactly, in its place", edited, p.toText());
        h.redo();
        h.undo();
        same("redo then undo again lands on the same text", edited, p.toText());
        h.undo();
        h.undo();
        same("undo walks back to before the edits", two, p.toText());
        h.undo();
        h.undo();
        same("all the way back: no tracks", 0, p.tracks.size());
        same("nothing left to undo", false, h.canUndo());
        h.redo();
        h.redo();
        same("redo re-adds the tracks with the same IDs", "t1 t3", p.tracks.get(0).id + " " + p.tracks.get(1).id);
        h.run(Edit.pan("t3", 0.5f));
        same("a new edit clears redo", false, h.canRedo());
        same("every step is counted for autosave (6 edits, 7 undos, 2 redos)", 15, h.changes());
        same("the undo label says what it undoes", "Pan", h.undoLabel());
        Project q = new Project("IDs", 48000);
        History hq = new History(q);
        hq.run(Edit.addTrack("A", "audio/a.wav", 10));
        hq.undo();
        hq.run(Edit.addTrack("B", "audio/b.wav", 10));
        same("an ID is never handed out twice, even after undo", "t3", q.tracks.get(0).id);
        boolean refused = false;
        try {
            Edit.gain("t3", Float.NEGATIVE_INFINITY);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        same("an impossible level is refused when asked for, not when saving later", true, refused);
        refused = false;
        try {
            Edit.rename("t3", null);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        same("a missing name is refused", true, refused);

        System.out.println("HistoryTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
