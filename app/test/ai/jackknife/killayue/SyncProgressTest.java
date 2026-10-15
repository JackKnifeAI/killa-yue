package ai.jackknife.killayue;

/** Tests for SyncProgress: the recogniser's progress, read from what whisper prints, and an honest time left. */
public class SyncProgressTest {
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
        same("whisper's progress line", 75, SyncProgress.percent("whisper_print_progress_callback: progress =  75%"));
        same("one hundred", 100, SyncProgress.percent("whisper_print_progress_callback: progress = 100%"));
        same("anything else is not progress", -1, SyncProgress.percent("output_json: saving output to 'sync.json'"));
        same("a sung word that says progress is not progress", -1, SyncProgress.percent("[00:01:02.000 --> 00:01:03.000]   progress = 50%"));
        same("too early to say", "", SyncProgress.timeLeft(2, 30000));
        same("a quarter done after 2 minutes: about 6 minutes left", "about 6 minutes left", SyncProgress.timeLeft(25, 120000));
        same("nearly done", "under a minute left", SyncProgress.timeLeft(95, 600000));
        same("one minute", "about a minute left", SyncProgress.timeLeft(50, 70000));
        same("finished", "", SyncProgress.timeLeft(100, 600000));
        System.out.println("SyncProgressTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
