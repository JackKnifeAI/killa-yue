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
public final class AiJobs {
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

    /** Every file of a feature is here, at its size (its hash was checked when it came); a zip is unpacked. */
    static boolean installed(Context c, String feature) {
        if (!StudioModels.available(feature)) return false;
        for (StudioModels.File f : StudioModels.forFeature(feature)) {
            if (f.name.endsWith(".zip")) {
                if (!new File(dir(c), feature + "/.complete").isFile()) return false;
            } else if (file(c, f).length() != f.size) return false;
        }
        return true;
    }

    /** Where an unpacked feature lives. */
    static File unpacked(Context c, String feature) {
        return new File(dir(c), feature + "/" + feature);
    }

    /** Unpacks a downloaded zip into dir/feature/, only inside it, then removes the zip. */
    static void unpack(Context c, String feature, File zip) throws IOException {
        File into = new File(dir(c), feature);
        String root = into.getCanonicalPath() + File.separator;
        java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zip), 1 << 16));
        try {
            byte[] buf = new byte[1 << 16];
            for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null;) {
                File out = new File(into, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("The download is damaged.");
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                java.io.OutputStream o = new java.io.FileOutputStream(out);
                try {
                    for (int n; (n = in.read(buf)) > 0;) o.write(buf, 0, n);
                } finally {
                    o.close();
                }
                if (e.getName().endsWith(".so") || !e.getName().contains(".")) out.setExecutable(true, true);
                // code is loaded read-only (Android refuses writable libraries in future versions)
                if (e.getName().endsWith(".so") || !e.getName().contains(".")) out.setWritable(false, false);
            }
        } finally {
            in.close();
        }
        if (!new File(into, ".complete").createNewFile() && !new File(into, ".complete").isFile()) throw new IOException("Could not finish unpacking.");
        zip.delete();
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
            if (f.name.endsWith(".zip") && new File(dir(c), feature + "/.complete").isFile()) {
                before += f.size;
                continue;
            }
            if (out.length() == f.size) {
                if (f.name.endsWith(".zip")) unpack(c, feature, out);
                before += f.size;
                continue;
            }
            File part = new File(out.getPath() + ".part");
            String why = "no source answered";
            boolean got = false;
            for (String url : f.urls) {  // JackKnifeAI first; the next source if it fails
                if (cancel.get()) throw new IOException("Cancelled.");
                final Store.Dl d = new Store.Dl();
                d.total = f.size;  // fetch stops once the file reaches this size; left at 0 it fetched nothing
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
            if (f.name.endsWith(".zip")) unpack(c, feature, out);
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

    /** A job that speaks text with a Kokoro voice into the project's audio folder. */
    static AiJob speak(Context c, File project, String text, int voice, String lang, float speed) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        AiJob j = AiJob.create(new File(project, "jobs/voice-" + stamp), "voice", text.length() > 40 ? text.substring(0, 40) + "…" : text);
        j.put("text", text);
        j.put("voice", Integer.valueOf(voice));
        j.put("lang", lang);
        j.put("speed", Double.valueOf(speed));
        j.put("out", new File(project, "jobs/voice-" + stamp + "/voice.wav").getAbsolutePath());
        j.save();
        return j;
    }

    /** A job that makes a beat from a prompt (and, to restyle, an input recording with a strength). */
    static AiJob beat(Context c, File project, String prompt, double seconds, long seed, int steps, File input, double strength)
        throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        AiJob j = AiJob.create(new File(project, "jobs/beats-" + stamp), "beats", prompt.length() > 40 ? prompt.substring(0, 40) + "…" : prompt);
        j.put("prompt", prompt);
        j.put("seconds", Double.valueOf(seconds));
        j.put("seed", Long.valueOf(seed));
        j.put("steps", Integer.valueOf(steps));
        if (input != null) {
            j.put("input", input.getAbsolutePath());
            j.put("strength", Double.valueOf(strength));
        }
        j.put("out", new File(project, "jobs/beats-" + stamp + "/beat.wav").getAbsolutePath());
        j.save();
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
        if (StudioSession.recordingBusy()) return "Finish the recording first.";
        if (MainActivity.jobBusy) return "A song is being made: this can start when it is done.";
        if (!installed(c, job.kind())) return "The model is not downloaded yet.";
        job.uncancel();
        final Context app = c.getApplicationContext();
        Intent i = new Intent(app, AiHost.class);
        i.putExtra(AiHost.JOB, job.dir().getAbsolutePath());
        boolean fast = fast(app);
        i.putExtra(AiHost.FAST, fast);
        StudioModels.File[] files = StudioModels.forFeature(job.kind());
        for (StudioModels.File f : files) {
            if (f.name.endsWith(".zip")) i.putExtra(AiHost.MODEL, unpacked(app, job.kind()).getAbsolutePath());
            else if (f.name.endsWith(".so")) i.putExtra(AiHost.RUNTIME, file(app, f).getAbsolutePath());
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
        if (!app.bindService(i, running, Context.BIND_AUTO_CREATE | (fast ? Context.BIND_IMPORTANT : 0))) {
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

    /** Full speed: the job runs at normal priority on the big cores instead of in the background. */
    public static boolean fast(Context c) {
        return c.getSharedPreferences("studio", Context.MODE_PRIVATE).getBoolean("aiFast", false);
    }

    public static void fast(Context c, boolean on) {
        c.getSharedPreferences("studio", Context.MODE_PRIVATE).edit().putBoolean("aiFast", on).apply();
    }
}
