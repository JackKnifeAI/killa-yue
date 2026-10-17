package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The JNI bridge, end to end on a computer: a Project's tracks loaded from WAV files into libstudio, edits by
 * track ID, rendering, status and the errors a person would meet. Run by studio/native/test/run.sh, which
 * builds libstudio for this machine first.
 */
public class NativeEngineTest {
    private static int failed, passed;

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void near(String name, double want, double got, double eps) {
        check(name + " (want " + want + ", got " + got + ")", Math.abs(want - got) <= eps);
    }

    // a 32-bit float WAV, every sample the same value
    static void wav(File f, int rate, int channels, float value, int frames) throws IOException {
        int data = frames * channels * 4;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + data).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 3).putShort((short) channels).putInt(rate)
                .putInt(rate * channels * 4).putShort((short) (channels * 4)).putShort((short) 32);
        b.put("data".getBytes("US-ASCII")).putInt(data);
        for (int i = 0; i < frames * channels; i++) b.putFloat(value);
        FileOutputStream o = new FileOutputStream(f);
        o.write(b.array());
        o.close();
    }

    static float[] play(NativeEngine e, int frames) throws InterruptedException {
        float[] block = new float[2 * 256], left = new float[frames];
        for (int done = 0; done < frames; done += 256) {
            int n = Math.min(256, frames - done);
            e.render(block, n);
            for (int i = 0; i < n; i++) left[done + i] = block[2 * i];
            Thread.sleep(0, 500000);  // a moment for the disk thread, as real time would give it
        }
        return left;
    }

    public static void main(String[] a) throws Exception {
        NativeEngine.load();
        // a folder named like a song, emoji and accents included: it must open like any other
        File dir = new File(System.getProperty("java.io.tmpdir"), "native-engine-test-" + System.nanoTime() + " \uD83D\uDD25 Café");
        new File(dir, "audio").mkdirs();
        wav(new File(dir, "audio/a.wav"), 48000, 2, 0.25f, 48000);
        wav(new File(dir, "audio/b.wav"), 48000, 1, 0.5f, 48000);

        Project p = new Project("Bridge", 48000);
        Track ta = p.addTrack("Quarter"), tb = p.addTrack("Half, mono");
        p.addClip(ta, "audio/a.wav", 0, 0, 48000);
        p.addClip(tb, "audio/b.wav", 0, 0, 48000);

        NativeEngine e = NativeEngine.offline(48000);
        check("an off-line engine runs at the rate it was given", e.rate() == 48000);
        e.load(p, dir);
        e.play();
        float[] out = play(e, 4000);
        near("both tracks play: 0.25 stereo plus 0.5 mono at the centre (x 0.7071)", 0.25 + 0.5 * Math.sqrt(0.5), out[3500], 1e-4);

        e.mute(tb.id, true);
        out = play(e, 4000);
        near("a track is muted by its ID", 0.25, out[3500], 1e-4);
        e.gain(ta.id, -6.0206f);
        out = play(e, 4000);
        near("and levelled by its ID", 0.125, out[3500], 1e-4);

        // loop: the playhead goes round [4800, 14400)
        e.loop(4800, 14400, true);
        e.seek(4000);
        play(e, 30000);
        long lpos = e.status().position;
        check("a loop keeps the playhead inside it", lpos >= 4800 && lpos < 14400);
        e.loop(4800, 14400, false);
        // meters: each track after its fader, by track ID
        e.meters(new java.util.HashMap<String, float[]>());  // clear
        play(e, 2000);
        java.util.HashMap<String, float[]> m = new java.util.HashMap<String, float[]>();
        e.meters(m);
        check("a meter for each track by its ID", m.size() == 4 && m.containsKey(ta.id) && m.containsKey(tb.id) && m.containsKey(Mixer.REVERB) && m.containsKey(Mixer.DELAY));
        near("the levelled track's meter is after its fader", 0.125, m.get(ta.id)[0], 1e-4);
        near("the muted track's meter is still", 0.0, m.get(tb.id)[0], 1e-6);

        NativeEngine.Status s = e.status();
        check("status: playing, and the position has moved on", s.playing && s.position > 4800);
        check("status: no device stats off-line", s.deviceRate == 0);

        boolean threw = false;
        try {
            e.gain("t999", 0f);
        } catch (IllegalArgumentException x) {
            threw = true;
        }
        check("an unknown track ID is refused", threw);

        Project bad = p.copy();
        bad.addClip(bad.track(ta.id), "audio/missing.wav", 0, 0, 100);
        String why = "";
        try {
            e.load(bad, dir);
        } catch (IOException x) {
            why = String.valueOf(x.getMessage());
        }
        check("a missing file is refused, by name: " + why, why.contains("missing.wav"));
        out = play(e, 2000);
        near("and the tracks that were playing play on", 0.125, out[1500], 1e-4);

        // a failed load with different track IDs keeps the old IDs working
        Project fresh = new Project("Fresh", 48000);
        Track tf = fresh.addTrack("New");
        fresh.addClip(tf, "audio/missing-too.wav", 0, 0, 100);
        fresh.nextId = 500;
        try {
            e.load(fresh, dir);
        } catch (IOException x) {
            // expected
        }
        e.gain(ta.id, -12.0412f);
        out = play(e, 3000);
        near("after a failed load, the old track IDs still reach their tracks", 0.0625, out[2500], 1e-4);
        e.gain(ta.id, -6.0206f);

        // the master level travels with the project
        Project loud = p.copy();
        loud.masterDb = -6.0206f;
        e.load(loud, dir);
        out = play(e, 4000);
        // the copy carries the project's own levels (0 dB, unmuted), not the edits sent since: both tracks, halved
        near("the project's master level is applied", 0.5 * (0.25 + 0.5 * Math.sqrt(0.5)), out[3500], 1e-4);
        loud.masterDb = 0f;
        e.load(loud, dir);

        // export: the project from the start, through an off-line engine, into a float WAV
        NativeEngine ex = NativeEngine.offline(48000);
        Project ep = p.copy();
        ep.track(tb.id).mute = true;
        ex.load(ep, dir);
        File mix = new File(dir, "audio/mix.wav");
        ex.export(mix, 24000, true);
        check("an export is as long as asked", ex.exportProgress() == 24000);
        WavIn wi = WavIn.open(mix);
        float[] ev = new float[2 * 24000];
        wi.read(0, ev, 24000);
        wi.close();
        near("and holds the mix (the muted track left out)", 0.25, ev[2 * 12000], 1e-4);
        ex.close();

        Project other = new Project("Other rate", 44100);
        why = "";
        try {
            e.load(other, dir);
        } catch (IOException x) {
            why = String.valueOf(x.getMessage());
        }
        check("a project at another rate is refused: " + why, why.contains("44100") && why.contains("48000"));

        why = "";
        try {
            e.measureLatency(5);
        } catch (IOException x) {
            why = String.valueOf(x.getMessage());
        }
        check("latency needs the microphone: " + why, why.contains("microphone"));

        e.close();
        e.close();
        threw = false;
        try {
            e.play();
        } catch (IllegalStateException x) {
            threw = true;
        }
        check("closing twice is harmless, and a closed engine says so", threw);

        for (File f : new File(dir, "audio").listFiles()) f.delete();
        new File(dir, "audio").delete();
        dir.delete();
        System.out.println("NativeEngineTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
