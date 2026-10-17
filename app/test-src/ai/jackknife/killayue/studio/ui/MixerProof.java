package ai.jackknife.killayue.studio.ui;

import ai.jackknife.killayue.studio.*;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;

/** Test build only: a disposable project for real controller/undo/save and mixer touch checks. */
public final class MixerProof {
    private static void log(Activity a, String message) {
        Log.i("StudioTest", message);
        try {
            FileWriter out = new FileWriter(new File(a.getFilesDir(), "studio-test.txt"), true);
            out.write(message + "\n"); out.close();
        } catch (Exception ignored) {}
    }
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    public static void start(final Activity activity) {
        try {
            final StudioSession session = StudioSession.get(activity);
            final File dir = Projects.create(StudioSession.root(activity), "Mixer device proof", session.rate(), "", "", System.currentTimeMillis());
            Project p = ProjectStore.load(dir).project;
            for (int k = 0; k < 2; k++) {
                int frames = p.rate * 4;
                short[] samples = new short[frames * 2];
                for (int i = 0; i < frames; i++)
                    samples[2*i] = samples[2*i+1] = (short)(2500 * Math.sin(2 * Math.PI * (k == 0 ? 110 : 220) * i / p.rate));
                String file = "audio/tone" + k + ".wav";
                WavOut wave = WavOut.create(new File(dir, file), p.rate, 2);
                wave.write(samples, frames); wave.finish();
                Track t = p.addTrack(k == 0 ? "Bass tone" : "Lead tone");
                p.addClip(t, file, 0, 0, frames);
            }
            p.masterDb = -24;
            p.loopOn = true; p.loopEnd = p.rate * 4L;
            ProjectStore.save(p, dir);
            session.open(dir);
            p = session.project();
            final String track = p.tracks.get(0).id;
            Mixer m = p.mixer.copy();
            String group = m.addGroup("Rhythm");
            m.edit(track).route = group;
            m.edit(track).reverb = .2f; m.edit(track).delay = .15f;
            m.edit(group).gainDb = -3;
            m.addSlot(track, 1).value = -3;
            m.addSlot(Mixer.MASTER, 2).value = .8f;
            session.run(new MixerEdit(p, m, "Mixer proof setup", null));
            check(session.undo() && p.mixer.groups.isEmpty(), "setup undo");
            check(session.redo(-1) && p.mixer.groups.size() == 1, "setup redo");
            int steps = session.history().steps().size();
            Object gesture = new Object();
            for (float value : new float[] {.3f, .4f, .5f}) {
                m = p.mixer.copy(); m.edit(track).reverb = value;
                session.drag(new MixerEdit(p, m, "Reverb send", track + ":reverb"), gesture);
            }
            check(session.history().steps().size() == steps + 1, "gesture coalescing");
            check(session.undo() && p.mixer.get(track).reverb == .2f, "send undo");
            check(session.redo(-1) && p.mixer.get(track).reverb == .5f, "send redo");
            session.flush();
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    try {
                        ProjectStore.Loaded loaded = ProjectStore.load(dir);
                        Project saved = loaded.project;
                        check(saved.mixer.get(track).reverb == .5f && saved.mixer.groups.size() == 1, "saved mixer");
                        ProjectStore.LoadedHistory history = ProjectStore.loadHistory(dir, saved, loaded.originalText);
                        check(history.history.undo() && saved.mixer.get(track).reverb == .2f, "persisted undo");
                        log(activity, "RESULT PASS mixer controller: setup undo/redo, one step per send gesture, autosave and persisted undo. Fixture: " + dir);
                        StudioActivity.open(activity);
                    } catch (Throwable ex) { log(activity, "RESULT FAIL mixer controller: " + ex); }
                }
            }, 2500);
        } catch (Throwable ex) { log(activity, "RESULT FAIL mixer controller: " + ex); }
    }
}
