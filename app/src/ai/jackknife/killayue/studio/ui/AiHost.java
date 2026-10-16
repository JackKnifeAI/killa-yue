package ai.jackknife.killayue.studio.ui;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

import java.io.File;

import ai.jackknife.killayue.studio.AiJob;
import ai.jackknife.killayue.studio.NativeStem;

/**
 * The ":ai" process: runs one AI job (stems now) from its folder, reporting through the folder's status file,
 * then ends, so a heavy model's memory goes back to the phone at once. Low priority: playback, recording and
 * the song engine come first. Unbinding kills it; the job's checkpoint lets it carry on later.
 */
public class AiHost extends Service {
    static final String JOB = "job", RUNTIME = "runtime", MODEL = "model", FAST = "fast";
    private static volatile boolean started;

    @Override
    public IBinder onBind(Intent in) {
        if (!started) {
            started = true;
            final File dir = new File(in.getStringExtra(JOB));
            final File runtime = new File(in.getStringExtra(RUNTIME)), model = new File(in.getStringExtra(MODEL));
            final boolean fast = in.getBooleanExtra(FAST, false);
            new Thread(new Runnable() {
                public void run() {
                    Process.setThreadPriority(fast ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
                    AiJob job = AiJob.open(dir);
                    try {
                        if (job == null) throw new Exception("The job folder is damaged.");
                        job.status("running", 0, null);
                        NativeStem.load(runtime);
                        final NativeStem stem = new NativeStem(model);
                        final AiJob j = job;
                        Thread watch = new Thread(new Runnable() {
                            public void run() {
                                while (!Thread.interrupted()) {
                                    try {
                                        if (j.cancelled()) stem.cancel();
                                        j.status("running", stem.progress(), null);
                                        Thread.sleep(500);
                                    } catch (Exception e) {
                                        return;
                                    }
                                }
                            }
                        }, "ai-watch");
                        watch.start();
                        try {
                            File work = new File(dir, "work");  // the native side writes into it but never makes it
                            if (!work.isDirectory() && !work.mkdirs()) throw new java.io.IOException("Could not make " + work + ".");
                            stem.split(new File((String) job.get("song")), new File((String) job.get("out")), work,
                                    job.getInt("rate"));
                        } finally {
                            watch.interrupt();
                            watch.join();
                            stem.close();
                        }
                        job.status("done", 1, null);
                    } catch (Throwable t) {
                        try {
                            if (job != null) job.status(job.cancelled() ? "cancelled" : "failed", job.progress(), String.valueOf(t.getMessage()));
                        } catch (Exception e) {
                            // nothing more to say
                        }
                    }
                    System.exit(0);  // one job per process: its memory goes back now
                }
            }, "ai-job").start();
        }
        return new Binder();
    }

    @Override
    public boolean onUnbind(Intent in) {
        Process.killProcess(Process.myPid());
        return false;
    }
}
