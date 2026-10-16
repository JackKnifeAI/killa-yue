package ai.jackknife.killayue.studio;

import java.util.ArrayList;

/** SessionDiff: after any change (edit, undo, redo, jump) the engine gets exactly what changed. */
public class SessionDiffTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static final ArrayList<String> calls = new ArrayList<String>();

    static String take() {
        String s = calls.toString();
        calls.clear();
        return s;
    }

    public static void main(String[] a) {
        SessionDiff.Target engine = new SessionDiff.Target() {
            public void reload(Project p) {
                calls.add("reload " + p.tracks.size());
            }

            public void gain(String id, float db) {
                calls.add("gain " + id + " " + db);
            }

            public void pan(String id, float pan) {
                calls.add("pan " + id + " " + pan);
            }

            public void mute(String id, boolean on) {
                calls.add("mute " + id + " " + on);
            }

            public void solo(String id, boolean on) {
                calls.add("solo " + id + " " + on);
            }

            public void master(float db) {
                calls.add("master " + db);
            }

            public void loop(long start, long end, boolean on) {
                calls.add("loop " + start + " " + end + " " + on);
            }
        };
        Project p = new Project("Diff", 48000);
        History h = new History(p);
        SessionDiff d = new SessionDiff();
        d.sync(p, engine);
        same("the first sync loads the project", "[reload 0]", take());
        h.run(Edit.addTrack("Song", "audio/song.wav", 1000));
        d.sync(p, engine);
        same("a new track is a reload", "[reload 1]", take());
        d.sync(p, engine);
        same("nothing changed: nothing sent", "[]", take());
        h.run(Edit.gain("t1", -6f));
        d.sync(p, engine);
        same("a level is a message, not a reload", "[gain t1 -6.0]", take());
        h.run(Edit.compound("Set up", Edit.pan("t1", 0.5f), Edit.mute("t1", true), Edit.solo("t1", true)));
        d.sync(p, engine);
        same("several settings at once: one message each", "[pan t1 0.5, mute t1 true, solo t1 true]", take());
        h.undo();
        d.sync(p, engine);
        same("undo sends what it changed back", "[pan t1 0.0, mute t1 false, solo t1 false]", take());
        h.run(Edit.masterGain(-3f));
        d.sync(p, engine);
        same("the master level", "[master -3.0]", take());
        h.run(Edit.setLoop(100, 900, true));
        d.sync(p, engine);
        same("the loop", "[loop 100 900 true]", take());
        h.run(Edit.rename("t1", "Lead"));
        h.run(Edit.setColor("t1", 0xff112233));
        d.sync(p, engine);
        same("a name or a colour is nothing to the engine", "[]", take());
        h.run(Edit.removeTrack("t1"));
        d.sync(p, engine);
        same("removing a track is a reload", "[reload 0]", take());
        h.undo();
        d.sync(p, engine);
        same("and undoing it too", "[reload 1]", take());
        p.track("t1").clips.get(0).gainDb = -2f;
        d.sync(p, engine);
        same("a clip's own level changes the session", "[reload 1]", take());
        h.jumpTo(0);
        d.sync(p, engine);
        same("a jump to the start reloads the empty project", "[reload 0]", take());
        d.forget();
        d.sync(p, engine);
        same("forget (the engine was rebuilt): everything is sent again", "[reload 0]", take());
        System.out.println("SessionDiffTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
