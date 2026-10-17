package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.studio.*;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
/** Test builds only. Makes one named fixture without removing or modifying any existing project. */
public final class InstrumentsProof {
    private static void log(Activity a, String text) {
        Log.i("StudioTest", text);
        try {
            FileWriter w = new FileWriter(new File(a.getFilesDir(), "studio-test.txt"), true);
            w.write(text + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }
    private static void check(boolean value, String text) {
        if (!value)
            throw new AssertionError(text);
    }
    public static void start(final Activity activity) {
        final StudioSession session = StudioSession.get(activity);
        final int rate = session.rate();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final File dir = Projects.create(StudioSession.root(activity), "Instruments device proof", rate, "",
                        "", System.currentTimeMillis());
                    Project p = ProjectStore.load(dir).project;
                    for (int bank = 0; bank < 2; bank++) {
                        Track t = p.addTrack(InstrumentSamples.NAMES[bank]);
                        t.notes.instrument = bank;
                        for (int step = 0; step < 8; step++) {
                            MidiNotes.Note n = t.notes.add(step, 48 + bank * 12 + new int[] {0, 4, 7, 12}[step % 4]);
                            n.length = .75;
                            n.velocity = .5f;
                            InstrumentSamples.ensure(dir, rate, bank, n.key);
                        }
                    }
                    p.masterDb = -24;
                    p.loopOn = true;
                    p.loopEnd = rate * 4L;
                    ProjectStore.save(p, dir);
                    activity.runOnUiThread(new Runnable() {
                        public void run() {
                            try {
                                session.open(dir);
                                final Project p = session.project();
                                final String id = p.tracks.get(0).id;
                                MidiNotes edited = p.track(id).notes.copy();
                                edited.notes.get(0).velocity = .9f;
                                int changes = session.history().steps().size();
                                session.run(new NotesEdit(p, id, edited, "Velocity proof"));
                                check(session.history().steps().size() == changes + 1, "one note edit, one undo step");
                                check(session.undo() && p.track(id).notes.notes.get(0).velocity == .5f, "note undo");
                                check(session.redo(-1) && p.track(id).notes.notes.get(0).velocity == .9f, "note redo");
                                session.flush();
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    public void run() {
                                        try {
                                            ProjectStore.Loaded saved = ProjectStore.load(dir);
                                            check(saved.project.track(id).notes.notes.get(0).velocity == .9f,
                                                "saved notes");
                                            ProjectStore.LoadedHistory history =
                                                ProjectStore.loadHistory(dir, saved.project, saved.originalText);
                                            check(history.history.undo()
                                                    && saved.project.track(id).notes.notes.get(0).velocity == .5f,
                                                "saved note undo");
                                            log(activity,
                                                "RESULT PASS instrument controller: undo/redo, autosave and reopened "
                                                + "note history. Fixture: "
                                                    + dir);
                                            StudioActivity.open(activity);
                                        } catch (Throwable ex) {
                                            log(activity, "RESULT FAIL instrument controller: " + ex);
                                        }
                                    }
                                }, 2500);
                            } catch (Throwable ex) {
                                log(activity, "RESULT FAIL instrument controller: " + ex);
                            }
                        }
                    });
                } catch (Throwable ex) {
                    log(activity, "RESULT FAIL instrument fixture: " + ex);
                }
            }
        }, "studio-instruments-proof").start();
    }
}
