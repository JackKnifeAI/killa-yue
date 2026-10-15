package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * The Studio's audio engine (libstudio.so): plays a project's tracks through the phone's audio device, or
 * renders them by hand when there is no device (export, tests). Tracks are named by their project IDs; edits
 * reach the audio within one block and never restart the sound. Not thread-safe: use it from one thread
 * (the UI's), except measureLatency, which blocks and belongs on a worker thread.
 */
public final class NativeEngine implements AutoCloseable {
    // the engine's message kinds (studio::Msg, in order)
    static final int GAIN = 0, PAN = 1, MUTE = 2, SOLO = 3, MASTER = 4, PLAY = 5, STOP = 6, SEEK = 7, RESET = 10;

    /** What the engine and the device report. Peaks are the loudest moment since the last status(). */
    public static final class Status {
        public long position, underruns, refused;
        public boolean playing;
        public float peakL, peakR;
        // the device (all 0 off-line)
        public int deviceRate, burst, bufferFrames, inputChannels, reopened;
        public boolean running, exclusive;
        public long xruns, callbacks, slowCallbacks;
        public double callbackMaxMs, callbackAvgMs, outputLatencyMs;
        public int callbackPolicy = -1, callbackPriority = -1;  // 1 = SCHED_FIFO: the callback runs in real time
        public String error;  // the device's last problem, in words, or null
    }

    private long h;
    private final boolean device;
    private int measuring;  // measureLatency calls in flight (guarded by this): close() waits for them
    private final ArrayList<String> trackIds = new ArrayList<String>();  // engine track index -> project track ID
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Loads libstudio.so; throws UnsatisfiedLinkError if this build has no Studio engine. */
    public static void load() {
        System.loadLibrary("studio");
    }

    /** An engine on the phone's audio device, at the device's own rate, with the microphone when mic. */
    public static NativeEngine onDevice(boolean mic) throws IOException {
        return new NativeEngine(nOpen(true, mic, 0), true);
    }

    /** An engine with no device, at rate: call render() yourself. */
    public static NativeEngine offline(int rate) {
        try {
            return new NativeEngine(nOpen(false, false, rate), false);
        } catch (IOException x) {
            throw new IllegalStateException(x);  // nothing to open off-line
        }
    }

    private NativeEngine(long handle, boolean device) {
        h = handle;
        this.device = device;
    }

    private synchronized long handle() {
        if (h == 0) throw new IllegalStateException("The Studio engine is closed.");
        return h;
    }

    public int rate() {
        return nRate(handle());
    }

    public void start() throws IOException {
        nStart(handle());
    }

    /**
     * Plays the project's tracks from now on, at the song's position; clip files are relative to folder. On an
     * IOException (a file missing, damaged or at another rate) the tracks that were playing play on.
     */
    public void load(Project p, File folder) throws IOException {
        if (p.rate != rate()) throw new IOException("The project is at " + p.rate + " Hz; the Studio runs at " + rate() + " Hz.");
        int n = p.tracks.size(), clips = 0;
        for (Track t : p.tracks) clips += t.clips.size();
        float[] gain = new float[n], pan = new float[n], clipGain = new float[clips];
        boolean[] mute = new boolean[n], solo = new boolean[n];
        int[] clipTrack = new int[clips];
        byte[][] files = new byte[clips][];  // real UTF-8: JNI's own strings mangle emoji, and songs have them
        long[] start = new long[clips], offset = new long[clips], length = new long[clips];
        ArrayList<String> ids = new ArrayList<String>();
        int k = 0;
        for (int i = 0; i < n; i++) {
            Track t = p.tracks.get(i);
            ids.add(t.id);
            gain[i] = t.gainDb;
            pan[i] = t.pan;
            mute[i] = t.mute;
            solo[i] = t.solo;
            for (Clip c : t.clips) {
                clipTrack[k] = i;
                files[k] = new File(folder, c.file).getPath().getBytes(UTF8);
                start[k] = c.start;
                offset[k] = c.offset;
                length[k] = c.length;
                clipGain[k] = c.gainDb;
                k++;
            }
        }
        nLoad(handle(), gain, pan, mute, solo, clipTrack, files, start, offset, length, clipGain, p.masterDb);
        trackIds.clear();
        trackIds.addAll(ids);
    }

    private int index(String trackId) {
        int i = trackIds.indexOf(trackId);
        if (i < 0) throw new IllegalArgumentException("The Studio is not playing a track " + trackId + ".");
        return i;
    }

    private boolean post(int kind, int track, float value, long frame) {
        return nPost(handle(), kind, track, value, frame);
    }

    // Each returns false only when the engine's queue is full (hundreds of edits waiting): try again next frame.
    public boolean gain(String trackId, float db) {
        return post(GAIN, index(trackId), db, 0);
    }

    public boolean pan(String trackId, float pan) {
        return post(PAN, index(trackId), pan, 0);
    }

    public boolean mute(String trackId, boolean on) {
        return post(MUTE, index(trackId), on ? 1f : 0f, 0);
    }

    public boolean solo(String trackId, boolean on) {
        return post(SOLO, index(trackId), on ? 1f : 0f, 0);
    }

    public boolean master(float db) {
        return post(MASTER, -1, db, 0);
    }

    public boolean play() {
        return post(PLAY, -1, 0, 0);
    }

    public boolean stop() {
        return post(STOP, -1, 0, 0);
    }

    public boolean seek(long frame) {
        return post(SEEK, -1, 0, Math.max(0, frame));
    }

    public Status status() {
        double[] v = new double[22];
        String err = nStatus(handle(), v);
        Status s = new Status();
        s.position = (long) v[0];
        s.underruns = (long) v[1];
        s.refused = (long) v[2];
        s.playing = v[3] != 0;
        s.peakL = (float) v[4];
        s.peakR = (float) v[5];
        s.deviceRate = (int) v[6];
        s.burst = (int) v[7];
        s.bufferFrames = (int) v[8];
        s.inputChannels = (int) v[9];
        s.reopened = (int) v[10];
        s.running = v[11] != 0;
        s.exclusive = v[12] != 0;
        s.xruns = (long) v[13];
        s.callbacks = (long) v[14];
        s.slowCallbacks = (long) v[15];
        s.callbackMaxMs = v[16];
        s.callbackAvgMs = v[17];
        s.outputLatencyMs = v[18];
        s.callbackPolicy = (int) v[19];
        s.callbackPriority = (int) v[20];
        s.error = err;
        return s;
    }

    /** Off-line only: renders frames of stereo into out (interleaved; out holds at least 2 * frames). */
    public void render(float[] out, int frames) {
        if (device) throw new IllegalStateException("The device renders this engine; render() is for off-line engines.");
        if (frames < 0 || out.length < 2 * frames) throw new IllegalArgumentException("render: out is too small.");
        nRender(handle(), out, frames);
    }

    /**
     * Plays probes and finds them in the microphone: the round trip in frames. Blocks for about probes * 0.4 + 1.3 s,
     * so call it on a worker thread; close() from another thread cancels it.
     */
    public long measureLatency(int probes) throws IOException {
        long handle;
        synchronized (this) {
            handle = handle();
            measuring++;
        }
        try {
            return nLatency(handle, probes);
        } finally {
            synchronized (this) {
                measuring--;
                notifyAll();
            }
        }
    }

    /** Closes the engine; a measurement running on another thread is cancelled and waited for first. */
    @Override
    public synchronized void close() {
        if (h == 0) return;
        if (measuring > 0) nCancelLatency(h);
        while (measuring > 0) {
            try {
                wait();
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
            }
        }
        nClose(h);
        h = 0;
    }

    private static native long nOpen(boolean device, boolean mic, int rate) throws IOException;

    private static native void nClose(long h);

    private static native int nRate(long h);

    private static native void nStart(long h) throws IOException;

    private static native void nLoad(long h, float[] gain, float[] pan, boolean[] mute, boolean[] solo, int[] clipTrack, byte[][] files,
            long[] start, long[] offset, long[] length, float[] clipGain, float masterDb) throws IOException;

    private static native boolean nPost(long h, int kind, int track, float value, long frame);

    private static native String nStatus(long h, double[] out);

    private static native void nRender(long h, float[] out, int frames);

    private static native long nLatency(long h, int probes) throws IOException;

    private static native void nCancelLatency(long h);
}
