package ai.jackknife.killayue.studio.ui;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.jackknife.killayue.Covers;
import ai.jackknife.killayue.Store;
import ai.jackknife.killayue.studio.Autosave;
import ai.jackknife.killayue.studio.Edit;
import ai.jackknife.killayue.studio.History;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.ProjectStore;
import ai.jackknife.killayue.studio.Projects;
import ai.jackknife.killayue.studio.SessionDiff;
import ai.jackknife.killayue.studio.Track;

/**
 * The open Studio: the audio engine on the phone's device, the project and its undo tree, autosave, and the
 * engine kept in step after every change. One for the app (the screens come and go; rotating never restarts
 * the sound). Everything here runs on the UI thread except the autosave writes and imports, which have their
 * own thread.
 */
public final class StudioSession implements SessionDiff.Target {
    public interface Listener {
        void projectChanged();  // an edit, undo, redo or jump, or another project opened
    }

    private static StudioSession inst;

    public static StudioSession get(Context c) {
        if (inst == null) inst = new StudioSession(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread worker = new HandlerThread("studio-work");
    private final Handler work;
    private final ArrayList<Listener> listeners = new ArrayList<Listener>();
    private final SessionDiff diff = new SessionDiff();
    private NativeEngine engine;
    private String engineError;
    private File dir;
    private Project project;
    private History history;
    private Autosave autosave;
    private boolean ticking;

    private StudioSession(Context app) {
        this.app = app;
        worker.start();
        work = new Handler(worker.getLooper());
    }

    public static File root(Context c) {
        return Store.dir(c, "studio");
    }

    /** The engine on the phone's audio device, opened the first time; null (and error() says why) if it cannot be. */
    public NativeEngine engine() {
        if (engine == null && engineError == null) {
            try {
                NativeEngine.load();
                engine = NativeEngine.onDevice(false);
                engine.start();
            } catch (Throwable t) {
                engineError = t.getMessage() == null ? t.toString() : t.getMessage();
                engine = null;
            }
        }
        return engine;
    }

    public String error() {
        return engineError;
    }

    /** The Studio's sample rate: the device's (48 kHz on most phones). */
    public int rate() {
        NativeEngine e = engine();
        return e == null ? 48000 : e.rate();
    }

    public Project project() {
        return project;
    }

    public History history() {
        return history;
    }

    public File dir() {
        return dir;
    }

    public void listen(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void unlisten(Listener l) {
        listeners.remove(l);
    }

    // ---------- projects ----------

    /** Opens a project folder: the project (or its backup), its undo tree, and plays it from its position. */
    public void open(File d) throws IOException {
        close();
        ProjectStore.Loaded l = ProjectStore.load(d);
        ProjectStore.LoadedHistory h = ProjectStore.loadHistory(d, l.project, l.originalText);
        dir = d;
        project = l.project;
        history = h.history;
        final File where = d;
        autosave = new Autosave(new Autosave.Saver() {
            public void save(String p, String hist) throws Exception {
                ProjectStore.saveSnapshot(where, new String[] {p, hist});
            }
        }, 1000, new Autosave.Clock() {
            public long now() {
                return System.currentTimeMillis();
            }
        });
        diff.forget();
        if (engine() != null) {
            engine.stop();
            engine.seek(0);
        }
        changed(false);
        startTicking();
    }

    /** Saves and lets go of the open project (the engine stays, silent). */
    public void close() {
        if (autosave != null) {
            final Autosave a = autosave;
            work.post(new Runnable() {
                public void run() {
                    a.flush();
                }
            });
        }
        if (engine != null) engine.stop();
        project = null;
        history = null;
        dir = null;
        autosave = null;
        diff.forget();
        for (Listener l : new ArrayList<Listener>(listeners)) l.projectChanged();
    }

    /** Writes now (the screen going away). */
    public void flush() {
        if (autosave == null) return;
        final Autosave a = autosave;
        work.post(new Runnable() {
            public void run() {
                a.flush();
            }
        });
    }

    private void startTicking() {
        if (ticking) return;
        ticking = true;
        work.post(new Runnable() {
            public void run() {
                Autosave a = autosave;
                if (a != null) a.tick();
                work.postDelayed(this, 250);
            }
        });
    }

    public interface Progress {
        void at(String what, float fraction);  // UI thread
    }

    public interface Done {
        void done(File project, String error);  // UI thread; error is null when it worked
    }

    /**
     * A new project from a song in the library: the song comes in as its first track (at the Studio's rate),
     * with its cover. Runs on the work thread; cancel stops it and removes the half-made project.
     */
    public void createFromSong(final File meta, final Progress progress, final AtomicBoolean cancel, final Done done) {
        final int rate = rate();
        work.post(new Runnable() {
            public void run() {
                File d = null;
                String error = null;
                try {
                    JSONObject m = new JSONObject(new String(Store.read(meta), "UTF-8"));
                    String title = m.optString("_title", meta.getName().replaceAll("\\.json$", ""));
                    File audio = new File(Store.library(app), m.optString("_audio"));
                    if (!audio.isFile()) throw new IOException("The song's audio is missing (" + audio.getName() + ").");
                    d = Projects.create(root(app), title + " (remix)", rate, meta.getName(), title, System.currentTimeMillis());
                    File out = new File(d, "audio/song.wav");
                    long frames = ImportJob.run(app, audio, out, rate, new ImportJob.Progress() {
                        public void at(final float f) {
                            ui.post(new Runnable() {
                                public void run() {
                                    progress.at("Bringing in the song", f);
                                }
                            });
                        }
                    }, cancel);
                    Project p = ProjectStore.load(d).project;
                    Track t = p.addTrack(title);
                    t.role = "song";
                    p.addClip(t, "audio/song.wav", 0, 0, frames);
                    ProjectStore.save(p, d);
                    File cover = Covers.coverFor(app, m);
                    if (cover.isFile()) copy(cover, new File(d, "cover.jpg"));
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                    if (d != null) Projects.delete(d);
                    d = null;
                }
                final File made = d;
                final String why = cancel.get() ? "Cancelled." : error;
                ui.post(new Runnable() {
                    public void run() {
                        done.done(made, why);
                    }
                });
            }
        });
    }

    static void copy(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    // ---------- edits: every change goes through here, so the engine and the saved project follow ----------

    public void run(Edit e) {
        if (history == null) return;
        history.run(e);
        changed(true);
    }

    public void drag(Edit e, Object gesture) {
        if (history == null) return;
        history.drag(e, gesture);
        changed(true);
    }

    public boolean undo() {
        if (history == null || !history.undo()) return false;
        changed(true);
        return true;
    }

    public boolean redo(int choice) {
        if (history == null) return false;
        boolean ok = choice < 0 ? history.redo() : history.redo(choice);
        if (ok) changed(true);
        return ok;
    }

    public void jumpTo(int step) {
        if (history != null && history.jumpTo(step)) changed(true);
    }

    private void changed(boolean save) {
        if (project != null && engine() != null) diff.sync(project, this);
        if (save && autosave != null) {
            try {
                String[] s = ProjectStore.snapshot(project, history);
                autosave.changed(s[0], s[1]);
            } catch (RuntimeException e) {
                engineError = "Not saved: " + e.getMessage();
            }
        }
        for (Listener l : new ArrayList<Listener>(listeners)) l.projectChanged();
    }

    // ---------- transport ----------

    public void play() {
        NativeEngine e = engine();
        if (e == null || project == null) return;
        requestFocus();
        e.play();
    }

    public void stop() {
        if (engine != null) engine.stop();
    }

    public void seek(long frame) {
        if (engine != null) engine.seek(frame);
    }

    private AudioManager.OnAudioFocusChangeListener focus;

    private void requestFocus() {
        // the app's own player (and anything else playing) pauses while the Studio plays
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (focus == null)
            focus = new AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int change) {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) stop();
                }
            };
        am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    /** Each track's meter since the last call, by track ID (cleared on read). */
    public void meters(java.util.Map<String, float[]> out) {
        if (engine != null && project != null) engine.meters(out);
        else out.clear();
    }

    public NativeEngine.Status status() {
        return engine == null ? null : engine.status();
    }

    // ---------- SessionDiff.Target: the engine ----------

    public void reload(Project p) {
        try {
            engine.load(p, dir);
            engine.loop(p.loopStart, p.loopEnd, p.loopOn);
        } catch (IOException e) {
            engineError = e.getMessage();
        }
    }

    public void gain(String id, float db) {
        engine.gain(id, db);
    }

    public void pan(String id, float pan) {
        engine.pan(id, pan);
    }

    public void mute(String id, boolean on) {
        engine.mute(id, on);
    }

    public void solo(String id, boolean on) {
        engine.solo(id, on);
    }

    public void master(float db) {
        engine.master(db);
    }

    public void loop(long start, long end, boolean on) {
        engine.loop(start, end, on);
    }

    /** The last thing that went wrong (cleared when read), for the screen to say. */
    public String takeError() {
        String e = engineError;
        if (engine != null) engineError = null;
        return e;
    }
}
