package ai.jackknife.killayue.studio;

/** Autosave: the project is written a second after the last change, never on every edit, and at once on pause. */
public class AutosaveTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static long now;
    static int writes, failNext;
    static String lastProject, lastHistory;

    public static void main(String[] a) {
        Autosave.Clock clock = new Autosave.Clock() {
            public long now() {
                return now;
            }
        };
        Autosave.Saver saver = new Autosave.Saver() {
            public void save(String project, String history) throws Exception {
                if (failNext > 0) {
                    failNext--;
                    throw new java.io.IOException("the card is full");
                }
                writes++;
                lastProject = project;
                lastHistory = history;
            }
        };
        Autosave s = new Autosave(saver, 1000, clock);
        s.tick();
        same("nothing changed, nothing written", 0, writes);
        for (int i = 0; i < 50; i++) {
            now += 30;
            s.changed("p" + i, "h" + i);
            s.tick();
        }
        same("50 quick edits: not written while they keep coming", 0, writes);
        now += 999;
        s.tick();
        same("not yet a second of quiet", 0, writes);
        now += 1;
        s.tick();
        same("written a second after the last edit", 1, writes);
        same("the latest state", "p49 h49", lastProject + " " + lastHistory);
        now += 5000;
        s.tick();
        same("written once, not again while nothing changes", 1, writes);

        s.changed("p50", "h50");
        s.flush();
        same("flush (the screen going away) writes at once", "2 p50", writes + " " + lastProject);
        s.flush();
        same("and only when there is something to write", 2, writes);

        failNext = 1;
        s.changed("p51", "h51");
        now += 1000;
        s.tick();
        same("a failed write is reported", "the card is full", s.lastError());
        now += 100;
        s.tick();
        same("and tried again, not lost", "3 p51", writes + " " + lastProject);
        same("the error clears once a write works", null, s.lastError());
        same("dirty is clear", false, s.dirty());
        System.out.println("AutosaveTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
