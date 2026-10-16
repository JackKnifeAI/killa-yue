package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.studio.*;
import android.app.Activity;
import android.os.*;
import android.util.Log;
import java.io.*;
/** Exercises the real controller, foreground service, saved take and undo history on device. */
public final class PlayAlongProof {
    private static void pass(Activity a, String message) {
        Log.i("StudioTest", message);
        try {
            FileWriter f = new FileWriter(new File(a.getFilesDir(), "studio-test.txt"), true);
            f.write(message + "\n"); f.close();
        } catch (IOException ignored) {}
    }
    private static void fail(Activity a, String message, Throwable t) {
        Log.e("StudioTest", message, t);
        pass(a, message + ": " + t);
    }

    public static void recover(final Activity activity) {
        try {
            final StudioSession s = StudioSession.get(activity);
            final File dir = new File(activity.getSharedPreferences("studio-proof", 0).getString("crash-dir", ""));
            if (!dir.getName().startsWith("play-along-device-proof-"))
                throw new IOException("No crash fixture.");
            s.open(dir);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    try {
                        Project p = ProjectStore.load(dir).project;
                        if (p.tracks.get(0).clips.size() != 1)
                            throw new IOException("Recovered clip missing.");
                        Clip c = p.tracks.get(0).clips.get(0);
                        if (c.length < p.rate || !new File(dir, c.file + ".kpk").isFile())
                            throw new IOException("Recovered audio/peaks missing.");
                        if (!s.undo() || !s.project().tracks.get(0).clips.isEmpty())
                            throw new IOException("Recovery undo.");
                        s.redo(-1);
                        s.flush();
                        pass(activity,
                              "RESULT PASS recovery: force-stopped take recovered into saved project, peaks and undo/redo. " + dir);
                        StudioActivity.open(activity);
                    } catch (Throwable t) {
                        fail(activity, "RESULT FAIL recovery", t);
                    }
                }
            }, 2500);
        } catch (Throwable t) {
            fail(activity, "RESULT FAIL recovery", t);
        }
    }
    public static void start(final Activity activity) {
        final boolean crash = "playalong-crash".equals(activity.getIntent().getStringExtra("test"));
        final Handler ui = new Handler(Looper.getMainLooper());
        try {
            final StudioSession s = StudioSession.get(activity);
            final File dir =
                Projects.create(StudioSession.root(activity), "Play Along device proof", s.rate(), "", "", System.currentTimeMillis());
            Project p = ProjectStore.load(dir).project;
            String track = p.addTrack("Microphone take").id;
            p.performance.countIn = 1;
            p.performance.click = true;
            ProjectStore.save(p, dir);
            s.open(dir);
            s.recording.arm(track, 0, false);
            s.recording.start();
            if (crash) {
                activity.getSharedPreferences("studio-proof", 0).edit().putString("crash-dir", dir.getPath()).commit();
                ui.postDelayed(new Runnable() {
                    public void run() { pass(activity, "READY crash: take recording; force-stop now. " + dir); }
                }, 5000);
                return;
            }
            ui.postDelayed(new Runnable() {
                public void run() {
                    s.stop();
                    ui.postDelayed(new Runnable() {
                        public void run() {
                            try {
                                if (s.recording.busy())
                                    throw new IOException("Take did not finish.");
                                Project saved = ProjectStore.load(dir).project;
                                if (saved.tracks.get(0).clips.size() != 1)
                                    throw new IOException("Take missing from saved project.");
                                Clip c = saved.tracks.get(0).clips.get(0);
                                if (c.length < 2L * saved.rate)
                                    throw new IOException("Take too short after count-in.");
                                if (!new File(dir, c.file + ".kpk").isFile())
                                    throw new IOException("Peaks missing.");
                                for (File f : new File(dir, "takes").listFiles())
                                    if (f.getName().endsWith(".take"))
                                        throw new IOException("Receipt was not retired.");
                                if (!s.undo() || !s.project().tracks.get(0).clips.isEmpty())
                                    throw new IOException("Take undo failed.");
                                if (!s.redo(-1) || s.project().tracks.get(0).clips.size() != 1)
                                    throw new IOException("Take redo failed.");
                                s.flush();
                                final Project padsOnly = new Project("Sampler export proof", s.rate());
                                padsOnly.performance.pattern = true;
                                padsOnly.performance.steps[0][0] = 1;
                                padsOnly.performance.click = true;
                                new Thread(new Runnable() {
                                    public void run() {
                                        try {
                                            java.util.ArrayList<File> files =
                                                ExportJob.run(activity, padsOnly, dir, ExportJob.WAV, new ExportJob.Progress() {
                                                    public void at(String what, float fraction) {}
                                                }, new java.util.concurrent.atomic.AtomicBoolean());
                                            WavIn audio = WavIn.open(files.get(0));
                                            if (audio.frames != 2L * padsOnly.rate)
                                                throw new IOException("Pattern export length.");
                                            float[] buf = new float[8192 * audio.channels];
                                            double energy = 0, clickEnergy = 0;
                                            for (long at = 0; at < audio.frames; at += 8192) {
                                                int n = audio.read(at, buf, 8192);
                                                for (int i = 0; i < n * audio.channels; i++) {
                                                    energy += buf[i] * buf[i];
                                                    if (at + i / audio.channels > padsOnly.rate * 3 / 4)
                                                        clickEnergy += buf[i] * buf[i];
                                                }
                                            }
                                            audio.close();
                                            if (energy < 1 || clickEnergy > .001)
                                                throw new IOException("Sampler missing or practice click leaked.");
                                            pass(activity,
                                                  "RESULT PASS export: sampler-only pattern, exact cycle, no practice click. " +
                                                      files.get(0));
                                        } catch (Throwable t) {
                                            fail(activity, "RESULT FAIL export", t);
                                        }
                                    }
                                }, "sampler-export-proof").start();
                                pass(activity, "RESULT PASS controller: foreground microphone recording, count-in, saved clip + " +
                                                    "peaks, receipt retired, undo/redo. " +
                                                        dir);
                                StudioActivity.open(activity);
                            } catch (Throwable t) {
                                fail(activity, "RESULT FAIL controller", t);
                            }
                        }
                    }, 2500);
                }
            }, 6000);
        } catch (Throwable t) {
            fail(activity, "RESULT FAIL controller", t);
        }
    }
}
