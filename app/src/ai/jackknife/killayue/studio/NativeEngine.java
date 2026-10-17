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
        public int callbackPolicy = -1, callbackPriority = -1; // 1 = SCHED_FIFO: the callback runs in real time
        public String error; // the device's last problem, in words, or null
    }

    private long h;
    private final boolean device;
    private int measuring; // measureLatency calls in flight (guarded by this): close() waits for them
    private final ArrayList<String> groupIds = new ArrayList<String>();
    private final float[] busMeters = new float[20];
    private final ArrayList<String> trackIds = new ArrayList<String>(); // engine track index -> project track ID
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Loads libstudio.so; throws UnsatisfiedLinkError if this build has no Studio engine. */
    public static void load() {
        System.loadLibrary("studio");
    }

    /** An engine on the phone's audio device, at the device's own rate, with the microphone when mic. */
    public static NativeEngine onDevice(boolean mic) throws IOException {
        return new NativeEngine(nOpen(true, mic, 0, 0), true);
    }

    /** An engine with no device, at rate: call render() yourself. */
    public static NativeEngine onDevice(boolean mic, int input) throws IOException {
        return new NativeEngine(nOpen(true, mic, 0, input), true);
    }

    public static NativeEngine offline(int rate) {
        try {
            return new NativeEngine(nOpen(false, false, rate, 0), false);
        } catch (IOException x) {
            throw new IllegalStateException(x); // nothing to open off-line
        }
    }

    private NativeEngine(long handle, boolean device) {
        h = handle;
        this.device = device;
    }

    private synchronized long handle() {
        if (h == 0)
            throw new IllegalStateException("The Studio engine is closed.");
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
    public synchronized void load(Project p, File folder) throws IOException {
        float[] mixerData = p.mixer.nativeData(p);
        if (p.rate != rate())
            throw new IOException("The project is at " + p.rate + " Hz; the Studio runs at " + rate() + " Hz.");
        int n = p.tracks.size(), clips = 0;
        ArrayList<ArrayList<Clip>> material = new ArrayList<ArrayList<Clip>>();
        for (Track track : p.tracks) {
            ArrayList<Clip> list = new ArrayList<Clip>(track.clips);
            for (MidiNotes.Note note : track.notes.notes) InstrumentSamples.ensure(folder,p.rate,track.notes.instrument,note.key);
            list.addAll(track.notes.clips(p)); material.add(list);
        }
        for (ArrayList<Clip> list : material) clips += list.size();
        float[] gain = new float[n], pan = new float[n], clipGain = new float[clips];
        boolean[] mute = new boolean[n], solo = new boolean[n];
        int[] clipTrack = new int[clips];
        byte[][] files = new byte[clips][]; // real UTF-8: JNI's own strings mangle emoji, and songs have them
        long[] start = new long[clips], offset = new long[clips], length = new long[clips], fadeIn = new long[clips],
               fadeOut = new long[clips];
        ArrayList<String> ids = new ArrayList<String>();
        int k = 0;
        for (int i = 0; i < n; i++) {
            Track t = p.tracks.get(i);
            ids.add(t.id);
            gain[i] = t.gainDb;
            pan[i] = t.pan;
            mute[i] = t.mute;
            solo[i] = t.solo;
            for (Clip c : material.get(i)) {
                clipTrack[k] = i;
                files[k] = new File(folder, c.file).getPath().getBytes(UTF8);
                start[k] = c.start;
                offset[k] = c.offset;
                length[k] = c.length;
                clipGain[k] = c.gainDb;
                fadeIn[k] = c.fadeIn;
                fadeOut[k] = c.fadeOut;
                k++;
            }
        }
        nLoad(handle(), gain, pan, mute, solo, clipTrack, files, start, offset, length, clipGain, fadeIn, fadeOut, p.masterDb, mixerData);
        trackIds.clear();
        trackIds.addAll(ids);
        groupIds.clear();
        groupIds.addAll(p.mixer.groups);
        performance(p, folder);
    }

    private int index(String trackId) {
        int i = trackIds.indexOf(trackId);
        if (i < 0)
            throw new IllegalArgumentException("The Studio is not playing a track " + trackId + ".");
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

    /** Plays [start, end) round and round once the playhead reaches it; off plays straight on. */
    public boolean loop(long start, long end, boolean on) {
        return nLoop(handle(), start, end, on);
    }

    private float[] meterBuf = new float[0];

    /** Mixer edits are queued independently of disk readers; turning a send never reloads clips. */
    public synchronized void mixer(Project p) throws IOException {
        if (p.tracks.size() != trackIds.size()) throw new IOException("Load the project's tracks before its mixer.");
        for (int i = 0; i < trackIds.size(); i++)
            if (!trackIds.get(i).equals(p.tracks.get(i).id)) throw new IOException("The mixer belongs to different tracks.");
        nMixer(handle(), p.mixer.nativeData(p));
        groupIds.clear();
        groupIds.addAll(p.mixer.groups);
    }

    /** Held peaks by track/group ID and reverb/delay return name; reading clears them. */
    public void meters(java.util.Map<String, float[]> out) {
        if (meterBuf.length < 2 * trackIds.size())
            meterBuf = new float[2 * Math.max(16, trackIds.size())];
        int n = nMeters(handle(), meterBuf);
        out.clear();
        for (int i = 0; i < n && i < trackIds.size(); i++) out.put(trackIds.get(i), new float[] {meterBuf[2 * i], meterBuf[2 * i + 1]});
        nMixerMeters(handle(), busMeters);
        for (int i = 0; i < groupIds.size(); i++) out.put(groupIds.get(i), new float[] {busMeters[2*i], busMeters[2*i+1]});
        out.put(Mixer.REVERB, new float[] {busMeters[16], busMeters[17]});
        out.put(Mixer.DELAY, new float[] {busMeters[18], busMeters[19]});
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

    /**
     * Off-line engines only, on a worker thread: renders the loaded project from the start into a float WAV at the
     * engine's rate (named only when finished). limiter false bypasses the master limiter (stems).
     */
    public void export(File out, long frames, boolean limiter) throws IOException {
        if (device)
            throw new IllegalStateException("Exports run on an off-line engine, never on the one playing.");
        nExport(handle(), out.getPath().getBytes(UTF8), frames, limiter);
    }

    /** Frames the export has written so far (any thread). */
    public long exportProgress() {
        return nExportProgress(handle());
    }

    /** Stops the export (any thread): it throws, and leaves no file. */
    public void cancelExport() {
        nExportCancel(handle());
    }

    /** Off-line only: renders frames of stereo into out (interleaved; out holds at least 2 * frames). */
    public void render(float[] out, int frames) {
        if (device)
            throw new IllegalStateException("The device renders this engine; render() is for off-line engines.");
        if (frames < 0 || out.length < 2 * frames)
            throw new IllegalArgumentException("render: out is too small.");
        nRender(handle(), out, frames);
    }

    /**
     * Plays probes and finds them in the microphone: the round trip in frames. Blocks for about probes * 0.4 + 1.6 s,
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

    /** What the last measureLatency heard: the microphone's level and each probe's match. Same thread, after it. */
    public String latencyReport() {
        return nLatencyReport(handle());
    }

    /** Closes the engine; a measurement running on another thread is cancelled and waited for first. */
    @Override
    public synchronized void close() {
        if (h == 0)
            return;
        if (measuring > 0)
            nCancelLatency(h);
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

    public void cancelLatency() {
        nCancelLatency(handle());
    }

    public int processingLatency() {
        return nProcessingLatency(handle());
    }
    private static native int nProcessingLatency(long h);
    public void monitor(boolean on) {
        nMonitor(handle(), on);
    }
    private static native void nMonitor(long h, boolean on);
    private String sampleKey;
    public void performance(Project p, File dir) throws IOException {
        StringBuilder key = new StringBuilder();
        for (Performance.Pad d : p.performance.pads)
            key.append(d.file).append('|').append(d.offset).append('|').append(d.length).append(';');
        String k = dir.getPath() + key;
        if (!k.equals(sampleKey)) {
            nKit(handle());
            for (int i = 0; i < 16; i++) {
                Performance.Pad d = p.performance.pads[i];
                if (!d.file.isEmpty())
                    nSample(handle(), i, new File(dir, d.file).getPath().getBytes(UTF8), d.offset, d.length);
            }
            sampleKey = k;
        }
        Performance f = p.performance;
        float[] data = new float[7 + 16 * 42];
        data[0] = (float) f.tempo;
        data[1] = f.beats;
        data[2] = f.beatUnit;
        data[3] = f.click ? 1 : 0;
        data[4] = f.pattern ? 1 : 0;
        data[5] = f.patternLength;
        data[6] = (float) f.swing;
        for (int i = 0; i < 16; i++) {
            Performance.Pad d = f.pads[i];
            int at = 7 + i * 42;
            float[] v = {d.tune, d.attack, d.decay, d.sustain, d.release, d.cutoff, d.mode, d.choke, d.reverse ? 1 : 0, 0};
            System.arraycopy(v, 0, data, at, 10);
            System.arraycopy(f.steps[i], 0, data, at + 10, 32);
        }
        nPerformance(handle(), data);
        long[] tf = new long[1 + f.tempos.size()];
        double[] tb = new double[tf.length];
        tb[0] = f.tempo;
        int ti = 1;
        for (java.util.Map.Entry<Long, Double> e : f.tempos.entrySet()) {
            tf[ti] = e.getKey();
            tb[ti++] = e.getValue();
        }
        nTempos(handle(), tf, tb);
    }
    /** Call after InstrumentSamples.bank has prepared the files on a worker. Live notes feed Master. */
    public void keyboard(File dir,int instrument) throws IOException {
        byte[][] paths = new byte[MidiNotes.HIGH-MidiNotes.LOW+1][];
        for(int key=MidiNotes.LOW;key<=MidiNotes.HIGH;key++) paths[key-MidiNotes.LOW]=new File(dir,InstrumentSamples.path(instrument,rate(),key)).getPath().getBytes(UTF8);
        nKeyboard(handle(),paths);
    }
    public boolean note(int key,float velocity,boolean on){return nNote(handle(),key,velocity,on);}
    public void notesOff(){nNotesOff(handle());}
    private static native void nKeyboard(long h,byte[][] paths) throws IOException;
    private static native boolean nNote(long h,int key,float velocity,boolean on);
    private static native void nNotesOff(long h);
    public boolean pad(int pad, float velocity, boolean on) {
        return nPad(handle(), pad, velocity, on);
    }
    public void countIn(long frames) throws IOException {
        nCountIn(handle(), frames);
    }
    public void record(File file, long countIn, boolean external) throws IOException {
        nRecord(handle(), file.getPath().getBytes(UTF8), countIn, external);
    }
    public String stopRecord(long[] result) {
        return nStopRecord(handle(), result);
    }
    public void recordInfo(long[] result) {
        nRecordInfo(handle(), result);
    }
    private static native void nRecordInfo(long h, long[] result);
    public void recordStatus(float[] result) {
        nRecordStatus(handle(), result);
    }
    public void external(float[] data, int n, long at) {
        nExternal(handle(), data, n, at);
    }
    public static void recover(File file) throws IOException {
        nRecover(file.getPath().getBytes(UTF8));
    }
    private static native void nTempos(long h, long[] frames, double[] bpms) throws IOException;
    private static native void nKit(long h) throws IOException;
    private static native void nSample(long h, int pad, byte[] file, long offset, long length) throws IOException;
    private static native boolean nPad(long h, int pad, float velocity, boolean on);
    private static native void nPerformance(long h, float[] data) throws IOException;
    private static native void nCountIn(long h, long frames) throws IOException;
    private static native void nRecord(long h, byte[] file, long countIn, boolean external) throws IOException;
    private static native String nStopRecord(long h, long[] result);
    private static native void nRecordStatus(long h, float[] result);
    private static native void nExternal(long h, float[] data, int n, long at);
    private static native void nRecover(byte[] file) throws IOException;

    private static native long nOpen(boolean device, boolean mic, int rate, int inputDevice) throws IOException;

    private static native void nClose(long h);

    private static native int nRate(long h);

    private static native void nStart(long h) throws IOException;

    private static native void nLoad(long h, float[] gain, float[] pan, boolean[] mute, boolean[] solo, int[] clipTrack, byte[][] files,
        long[] start, long[] offset, long[] length, float[] clipGain, long[] fadeIn, long[] fadeOut, float masterDb, float[] mixerData) throws IOException;

    private static native boolean nPost(long h, int kind, int track, float value, long frame);

    private static native String nStatus(long h, double[] out);

    private static native void nRender(long h, float[] out, int frames);

    private static native long nLatency(long h, int probes) throws IOException;

    private static native void nCancelLatency(long h);

    private static native boolean nLoop(long h, long start, long end, boolean on);

    private static native void nMixer(long h, float[] config) throws IOException;
    private static native void nMixerMeters(long h, float[] out);

    private static native int nMeters(long h, float[] out);

    private static native String nLatencyReport(long h);

    private static native void nExport(long h, byte[] path, long frames, boolean limiter) throws IOException;

    private static native long nExportProgress(long h);

    private static native void nExportCancel(long h);
}
