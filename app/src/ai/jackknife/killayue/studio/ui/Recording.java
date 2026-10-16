package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.studio.*;
import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
/** One take at a time. The native recorder writes; this controller owns placement, receipts and the UI lifetime. */
final class Recording {
    private final StudioSession session;
    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TakeJournal journal;
    private File dir;
    private boolean stopping;
    boolean calibrating;
    private CaptureSource capture;
    String armed;
    int inputDevice = 0;
    boolean external;
    long latency;
    final float[] meter = new float[3];
    Recording(StudioSession s, Context c) {
        session = s;
        app = c;
    }
    boolean active() { return journal != null; }
    boolean busy() { return active() || stopping || calibrating; }
    void arm(String id, int input, boolean other) throws IOException {
        if (busy())
            throw new IOException("Stop the take before changing inputs.");
        session.stop();
        session.input(!other, input);
        armed = id;
        inputDevice = input;
        external = other;
        latency = app.getSharedPreferences("studio-latency", 0).getLong(route(), 0);
    }
    String route() {
        AudioManager am = (AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
        StringBuilder b = new StringBuilder("input:" + inputDevice);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            b.append(':').append(d.getType()).append(':').append(d.getProductName());
        return b.toString();
    }
    void setLatency(long n) {
        latency = Math.max(0, n);
        app.getSharedPreferences("studio-latency", 0).edit().putLong(route(), latency).apply();
    }
    void clearInput() {
        if (capture != null) { capture.close(); capture = null; }
        armed = null;
        external = false;
        app.stopService(new Intent(app, StudioService.class));
    }
    void setCapture(CaptureSource c) {
        if (capture != null)
            capture.close();
        capture = c;
    }
    void start() throws IOException {
        if (busy())
            throw new IOException("A take is already running.");
        if (AiJobs.busy() || ai.jackknife.killayue.MainActivity.jobBusy)
            throw new IOException("Finish the AI job before recording.");
        Project p = session.project();
        if (p == null || p.track(armed) == null)
            throw new IOException("Arm a track first.");
        if (p.loopOn && p.tracks.size() >= 64)
            throw new IOException("Loop recording needs one free track for additional takes.");
        if (external && capture == null)
            throw new IOException("Choose another app and allow its audio capture first.");
        session.stop();
        dir = session.dir();
        TakeJournal j = new TakeJournal();
        j.file = "takes/take-" + System.currentTimeMillis() + ".wav";
        j.track = armed;
        j.start = session.status().position;
        session.seek(j.start);
        j.latency = external ? 0 : latency + session.engine().processingLatency();
        j.loop = p.loopOn;
        j.loopStart = p.loopStart;
        j.loopEnd = p.loopEnd;
        j.punch = p.performance.punch;
        j.punchStart = p.performance.punchStart;
        j.punchEnd = p.performance.punchEnd;
        j.save(dir);
        long count = Math.round(p.performance.countIn * p.performance.beats * session.rate() * 60.0 / p.performance.tempo * 4 /
                                p.performance.beatUnit);
        try {
            session.engine().record(new File(dir, j.file), count, external);
            session.engine().countIn(count);
            journal = j;
            app.startForegroundService(new Intent(app, StudioService.class));
            session.play();
            if (capture != null)
                capture.start(session.engine());
            ui.post(tick);
        } catch (Exception e) {
            long[] ignored = new long[3];
            session.engine().stopRecord(ignored);
            journal = null;
            session.stop();
            if (capture != null) {
                capture.close();
                capture = null;
            }
            app.stopService(new Intent(app, StudioService.class));
            throw new IOException(e);
        }
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (journal == null)
                return;
            long[] v = new long[3];
            session.engine().recordInfo(v);
            journal.first = v[1];
            journal.anchor = external
                                 ? Math.round(session.project().performance.countIn * session.project().performance.beats * session.rate() *
                                              60.0 / session.project().performance.tempo * 4 / session.project().performance.beatUnit)
                                 : v[2];
            final TakeJournal snapshot = journal.copy();
            final File where = dir;
            session.afterSave(new Runnable() {
                public void run() {
                    try {
                        snapshot.save(where);
                    } catch (final IOException e) {
                        ui.post(new Runnable() {
                            public void run() {
                                session.report(e.getMessage());
                                stop();
                            }
                        });
                    }
                }
            });
            session.engine().recordStatus(meter);
            if (meter[2] != 0) {
                stop();
                return;
            }
            ui.postDelayed(this, 500);
        }
    };
    void stop() {
        if (journal == null || stopping)
            return;
        stopping = true;
        final TakeJournal j = journal;
        final File where = dir;
        final NativeEngine e = session.engine();
        journal = null;
        ui.removeCallbacks(tick);
        if (capture != null) {
            capture.close();
            capture = null;
        }
        session.afterSave(new Runnable() {
            public void run() {
                long[] v = new long[3];
                final String err = e.stopRecord(v);
                j.first = v[1];
                if (!external)
                    j.anchor = v[2];
                final long frames = v[0];
                try {
                    j.save(where);
                    if (frames > 0)
                        NativeStretch.peaks(new File(where, j.file));
                } catch (IOException ex) {
                    session.report(ex.getMessage());
                }
                ui.post(new Runnable() {
                    public void run() {
                        stopping = false;
                        if (session.dir() != null && session.dir().equals(where)) {
                            final Edit edit = j.add(session.project(), frames);
                            if (edit != null)
                                session.run(edit);
                            session.flush();
                            session.afterSave(new Runnable() {
                                public void run() { retire(j, where, frames == 0 || edit == null); }
                            });
                        }
                        if (err != null)
                            session.report(err);
                        app.stopService(new Intent(app, StudioService.class));
                    }
                });
            }
        });
    }
    void retire(TakeJournal j, File where, boolean empty) {
        try {
            boolean saved = empty;
            Project p = ProjectStore.load(where).project;
            for (Track t : p.tracks)
                for (Clip c : t.clips)
                    if (c.file.equals(j.file))
                        saved = true;
            if (saved)
                j.receipt(where).delete();
        } catch (Exception e) {
            session.report("The take is kept for recovery: " + e.getMessage());
        }
    }
    void recover() {
        File where = session.dir();
        File[] fs = new File(where, "takes").listFiles();
        if (fs == null)
            return;
        for (File f : fs)
            if (f.getName().endsWith(".take"))
                try {
                    TakeJournal j = TakeJournal.read(f);
                    File wav = new File(where, j.file);
                    if (new File(wav.getPath() + ".part").isFile())
                        NativeEngine.recover(wav);
                    if (!wav.isFile())
                        continue;
                    WavIn in = WavIn.open(wav);
                    long frames = in.frames;
                    in.close();
                    if (!new File(wav.getPath() + ".kpk").isFile())
                        NativeStretch.peaks(wav);
                    final Edit edit = j.add(session.project(), frames);
                    if (edit != null)
                        session.run(edit);
                    session.flush();
                    final TakeJournal recovered = j;
                    final File home = where;
                    session.afterSave(new Runnable() {
                        public void run() { retire(recovered, home, edit == null); }
                    });
                    session.report("Recovered an interrupted take" + (j.anchor < 0 ? "; its timing needs checking." : "."));
                } catch (Exception ex) {
                    session.report("Take recovery: " + ex.getMessage());
                }
    }
}
