package ai.jackknife.killayue.studio;

import java.io.File;

/** AiJob: what a job in the ":ai" process is asked to do and how it is going, in files both processes read. */
public class AiJobTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "aijob-" + System.nanoTime());
        AiJob j = AiJob.create(dir, "stems", "t3");  // throws IOException
        j.put("song", "/x/audio/song.wav");
        j.put("rate", Integer.valueOf(48000));
        j.save();
        AiJob back = AiJob.open(dir);
        same("a job reads back", "stems t3 /x/audio/song.wav 48000", back.kind() + " " + back.subject() + " " + back.get("song") + " " + back.getInt("rate"));
        same("a new job is waiting", "waiting", back.state());
        back.status("running", 0.25, null);
        same("the other process sees its progress", "running 0.25", AiJob.open(dir).state() + " " + AiJob.open(dir).progress());
        back.status("failed", 0.5, "the model would not load");
        same("and what went wrong", "failed the model would not load", AiJob.open(dir).state() + " " + AiJob.open(dir).error());
        same("not cancelled", false, back.cancelled());
        AiJob.open(dir).cancel();
        same("a cancel from the app is seen by the job", true, back.cancelled());
        same("no half-written status file is ever left", false, new File(dir, "status.json.tmp").exists());
        same("a folder without a job is no job", null, AiJob.open(new File(dir, "nothing")));
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
        System.out.println("AiJobTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
