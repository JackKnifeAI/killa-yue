package ai.jackknife.killayue.studio.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.jackknife.killayue.MainActivity;
import ai.jackknife.killayue.Store;
import ai.jackknife.killayue.studio.AiJob;
import ai.jackknife.killayue.studio.StudioModels;

/**
 * The app's side of AI jobs: downloads the models (pinned, from JackKnifeAI first), starts one job at a time in
 * the ":ai" process, lets it be cancelled and carried on, and never starts one while a song is being made (the
 * song engine has the GPU first).
 */
final class AiJobs {
    private static ServiceConnection running;
    private static AiJob current;

    private AiJobs() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "studio-ai");
        d.mkdirs();
        return d;
    }

    static File file(Context c, StudioModels.File f) {
        return new File(dir(c), f.name);
    }

    /** Every file of a feature is here, at its size (its hash was checked when it came). */
    static boolean installed(Context c, String feature) {
        for (StudioModels.File f : StudioModels.forFeature(feature))
            if (file(c, f).length() != f.size) return false;
        return true;
    }

    interface Progress {
        void at(long done, long total);  // the download's thread
    }

    /** Downloads a feature's files: each from its first source that works, resumed, checked against its pin. */
    static void download(Context c, String feature, final Progress p, AtomicBoolean cancel) throws IOException {
        final long total = StudioModels.bytes(feature);
        long before = 0;
        for (StudioModels.File f : StudioModels.forFeature(feature)) {
            File out = file(c, f);
            if (out.length() == f.size) {
                before += f.size;
                continue;
            }
            File part = new File(out.getPath() + ".part");
            String why = "no source answered";
            boolean got = false;
            for (String url : f.urls) {  // JackKnifeAI first; the next source if it fails
                if (cancel.get()) throw new IOException("Cancelled.");
                final Store.Dl d = new Store.Dl();
                final long base = before;
                final AtomicBoolean over = new AtomicBoolean();
                final AtomicBoolean stop = cancel;
                Thread watch = new Thread(new Runnable() {
                    public void run() {
                        while (!over.get()) {
                            if (stop.get()) d.cancel = true;
                            p.at(base + d.done, total);
                            try {
                                Thread.sleep(300);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                }, "ai-download");
                watch.start();
                try {
                    Store.fetch(url, part, d);
                    if (cancel.get()) throw new IOException("Cancelled.");
                    if (part.length() != f.size || !Store.sha256(part).equals(f.sha256)) {
                        part.delete();
                        why = f.name + " did not match its checksum";
                        continue;
                    }
                    if (!part.renameTo(out)) throw new IOException("Could not keep " + f.name + ".");
                    got = true;
                    break;
                } catch (Exception e) {
                    if (cancel.get()) throw new IOException("Cancelled.");
                    why = e.getMessage() == null ? e.toString() : e.getMessage();  // try the next source
                } finally {
                    over.set(true);
                    watch.interrupt();
                }
            }
            if (!got) throw new IOException("Could not download " + f.name + ": " + why);
            before += f.size;
            p.at(before, total);
        }
    }

    /** A new stems job for a project's track (its first clip's file), or null if one is running. */
    static AiJob stems(Context c, File project, String trackId, File song, int rate) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File dir = new File(project, "jobs/stems-" + trackId + "-" + stamp);
        AiJob j = AiJob.create(dir, "stems", trackId);
        j.put("song", song.getAbsolutePath());
        j.put("outRel", "audio/stems-" + trackId + "-" + stamp);
        j.put("out", new File(project, "audio/stems-" + trackId + "-" + stamp).getAbsolutePath());
        j.put("rate", Integer.valueOf(rate));
        j.save();
        new File(project, "audio/stems-" + trackId + "-" + stamp).mkdirs();
        return j;
    }

    /** The jobs in a project, newest first. */
    static ArrayList<AiJob> jobs(File project) {
        ArrayList<AiJob> out = new ArrayList<AiJob>();
        File[] dirs = new File(project, "jobs").listFiles();
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs);
        for (int i = dirs.length - 1; i >= 0; i--) {
            AiJob j = AiJob.open(dirs[i]);
            if (j != null) out.add(j);
        }
        return out;
    }

    static boolean busy() {
        return running != null;
    }

    /** Runs (or carries on) a job in the ":ai" process. Null when it started; otherwise why it did not. */
    static String start(Context c, AiJob job) {
        if (running != null) return "A job is already running.";
        if (MainActivity.jobBusy) return "A song is being made: the stems can start when it is done.";
        if (!installed(c, job.kind())) return "The model is not downloaded yet.";
        job.uncancel();
        final Context app = c.getApplicationContext();
        Intent i = new Intent(app, AiHost.class);
        i.putExtra(AiHost.JOB, job.dir().getAbsolutePath());
        StudioModels.File[] files = StudioModels.forFeature(job.kind());
        for (StudioModels.File f : files) {
            if (f.name.endsWith(".so")) i.putExtra(AiHost.RUNTIME, file(app, f).getAbsolutePath());
            else i.putExtra(AiHost.MODEL, file(app, f).getAbsolutePath());
        }
        running = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {}

            public void onServiceDisconnected(ComponentName n) {
                // the job ended (it exits when done) or the process was killed
                finished(app);
            }
        };
        current = job;
        if (!app.bindService(i, running, Context.BIND_AUTO_CREATE)) {
            running = null;
            current = null;
            return "The AI process would not start.";
        }
        return null;
    }

    /** The job is over (or gone): let go of the process. */
    static void finished(Context c) {
        if (running != null) {
            try {
                c.getApplicationContext().unbindService(running);
            } catch (IllegalArgumentException e) {
                // already unbound
            }
        }
        running = null;
        current = null;
    }

    static AiJob current() {
        return current;
    }
}
