package ai.jackknife.killayue.studio;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;

/** Crosses Java -> JNI -> disk readers -> mixer -> WAV; also used as the device's offline fixture. */
public final class NativeMixerTest {
    static int passed;
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); passed++; }
    static void near(float actual, float expected, String why) { check(Math.abs(actual-expected) < .00001f, why + ": " + actual); }
    public static void main(String[] args) throws Exception {
        NativeEngine.load();
        File dir = new File(System.getProperty("java.io.tmpdir"), "mixer-bridge-" + System.nanoTime());
        new File(dir, "audio").mkdirs();
        NativeEngineTest.wav(new File(dir, "audio/pulse.wav"), 48000, 2, .25f, 32);
        Project p = new Project("Wet mixer", 48000);
        Track track = p.addTrack("Pulse");
        p.addClip(track, "audio/pulse.wav", 0, 0, 32);
        String group = p.mixer.addGroup("Band");
        p.mixer.edit(track.id).route = group;
        p.mixer.edit(track.id).delay = .5f;
        p.mixer.addSlot(track.id, 1).value = -6.0206f;
        p.mixer.edit(group).gainDb = -6.0206f;
        p.mixer.edit(group).delay = .25f;
        p.mixer.addSlot(Mixer.MASTER, 3);
        NativeEngine e = NativeEngine.offline(48000);
        try {
            e.load(p, dir);
            File first = new File(dir, "first.wav"), second = new File(dir, "second.wav");
            e.export(first, 192032, false);
            e.export(second, 192032, false);
            float[] a = new float[384064], b = new float[a.length];
            WavIn w = WavIn.open(first); check(w.frames == 192032, "export retains requested tail"); w.read(0, a, 192032); w.close();
            w = WavIn.open(second); w.read(0, b, 192032); w.close();
            check(Arrays.equals(a, b), "repeated wet exports are sample-identical");
            near(a[0], -.0625f, "track insert then group fader then master insert");
            near(a[24000], -.078125f, "track and group sends sum at the shared delay");
            near(a[48000], -.0234375f, "delay feedback reaches the exported tail");
            p.mixer.edit(Mixer.MASTER).chain.get(0).bypass = true;
            e.mixer(p);
            e.export(second, 192032, false);
            w = WavIn.open(second); w.read(0, b, 192032); w.close();
            near(b[0], .0625f, "bypass keeps the slot but removes master polarity");
            near(b[24000], .078125f, "bypass also applies to wet returns");
            HashMap<String, float[]> meters = new HashMap<String, float[]>();
            e.meters(meters);
            check(meters.containsKey(group) && meters.containsKey(Mixer.DELAY), "bus meters keep project group IDs");
            near(meters.get(group)[0], .0625f, "group peak");
            near(meters.get(Mixer.DELAY)[0], .078125f, "delay return peak");
        } finally { e.close(); }

        NativeEngineTest.wav(new File(dir, "audio/steady.wav"), 48000, 2, .1f, 48000);
        Project live = new Project("Live updates", 48000);
        Track t = live.addTrack("Steady"); live.addClip(t, "audio/steady.wav", 0, 0, 48000);
        e = NativeEngine.offline(48000);
        try {
            e.load(live, dir); e.play();
            float[] before = NativeEngineTest.play(e, 2048);
            near(before[1800], .1f, "dry default");
            // If mixer() tried to reopen clips, this deliberately missing path would fail.
            t.clips.get(0).file = "audio/not-reopened.wav";
            String g = live.mixer.addGroup("Quiet");
            live.mixer.edit(t.id).route = g; live.mixer.edit(g).gainDb = -6.0206f;
            e.mixer(live);
            float[] after = NativeEngineTest.play(e, 2048);
            near(after[1800], .05f, "live routing update retains active readers and playhead");
            check(e.status().position == 4096, "mixer updates do not reset transport");
            live.mixer.edit(t.id).delay = Float.NaN;
            try { e.mixer(live); throw new AssertionError("NaN accepted"); }
            catch (IllegalArgumentException expected) { passed++; }
            after = NativeEngineTest.play(e, 2048);
            near(after[1800], .05f, "invalid update leaves previous mix playing");
        } finally { e.close(); Projects.delete(dir); }
        System.out.println("NativeMixerTest: " + passed + " passed");
    }
}
