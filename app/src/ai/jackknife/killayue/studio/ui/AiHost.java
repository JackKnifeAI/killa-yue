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
            final String runtimePath = in.getStringExtra(RUNTIME);
            final File runtime = runtimePath == null ? null : new File(runtimePath), model = new File(in.getStringExtra(MODEL));
            final boolean fast = in.getBooleanExtra(FAST, false);
            new Thread(new Runnable() {
                public void run() {
                    Process.setThreadPriority(fast ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
                    AiJob job = AiJob.open(dir);
                    try {
                        if (job == null) throw new Exception("The job folder is damaged.");
                        job.status("running", 0, null);
                        if ("voice".equals(job.kind())) {
                            speak(job, model);
                            job.status("done", 1, null);
                            System.exit(0);
                        }
                        if ("beats".equals(job.kind())) {
                            beat(job, model, fast);
                            job.status("done", 1, null);
                            System.exit(0);
                        }
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

    static native int nSpeak(String dir, String lang, String text, int voice, float speed, int threads, String out);

    /** Speak: Kokoro through sherpa-onnx, its libraries loaded by full path from the download. */
    static void speak(AiJob job, File dir) throws Exception {
        System.load(new File(dir, "libonnxruntime.so").getAbsolutePath());
        System.load(new File(dir, "libsherpa-onnx-c-api.so").getAbsolutePath());
        System.load(new File(dir, "libkillavoice.so").getAbsolutePath());
        job.status("running", .1, null);
        Object speed = job.get("speed");
        int n = nSpeak(dir.getAbsolutePath(), (String) job.get("lang"), (String) job.get("text"), job.getInt("voice"),
                speed instanceof Number ? ((Number) speed).floatValue() : 1f, 4, (String) job.get("out"));
        if (n <= 0) throw new Exception("The voice could not speak that text.");
    }

    /**
     * Beats: Stable Audio Open Small on LiteRT, run by Arm's audiogen (Apache-2.0) as its own program. Progress is
     * estimated from the steps it prints; cancelling ends the program.
     */
    static void beat(AiJob job, File dir, boolean fast) throws Exception {
        File exe = new File(dir, "audiogen");
        exe.setExecutable(true, true);
        java.util.ArrayList<String> cmd = new java.util.ArrayList<String>();
        cmd.add(exe.getAbsolutePath());
        cmd.add("-m");
        cmd.add(dir.getAbsolutePath());
        cmd.add("-p");
        cmd.add((String) job.get("prompt"));
        cmd.add("-t");
        cmd.add(fast ? "6" : "4");
        cmd.add("-s");
        cmd.add(String.valueOf(((Number) job.get("seed")).longValue()));
        cmd.add("-n");
        cmd.add(String.valueOf(job.getInt("steps")));
        cmd.add("-l");
        cmd.add(String.valueOf((int) Math.ceil(((Number) job.get("seconds")).doubleValue())));
        cmd.add("-o");
        cmd.add((String) job.get("out"));
        if (job.get("input") != null) {
            cmd.add("-i");
            cmd.add((String) job.get("input"));
            cmd.add("-x");
            cmd.add(String.valueOf(((Number) job.get("strength")).doubleValue()));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", dir.getAbsolutePath());
        final java.lang.Process p = pb.start();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        StringBuilder tail = new StringBuilder();
        int steps = Math.max(1, job.getInt("steps")), seen = 0;
        for (String line; (line = r.readLine()) != null;) {
            if (job.cancelled()) {
                p.destroy();
                throw new Exception("Cancelled.");
            }
            if (line.toLowerCase(java.util.Locale.ROOT).contains("step")) seen++;
            job.status("running", Math.min(.95, .1 + .85 * seen / steps), null);
            tail.append(line).append('\n');
            if (tail.length() > 2000) tail.delete(0, tail.length() - 2000);
        }
        if (p.waitFor() != 0 || !new File((String) job.get("out")).isFile())
            throw new Exception("The beat model stopped: " + tail.toString().trim());
    }

    @Override
    public boolean onUnbind(Intent in) {
        Process.killProcess(Process.myPid());
        return false;
    }
}
