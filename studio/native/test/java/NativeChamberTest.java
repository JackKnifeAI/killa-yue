package ai.jackknife.killayue.studio;
import java.io.File;
public final class NativeChamberTest {
    public static int passed;
    static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
        passed++;
    }
    static float[] read(File file) throws Exception {
        WavIn w = WavIn.open(file);
        try {
            float[] x = new float[(int) w.frames * w.channels];
            check(w.read(0, x, (int) w.frames) == w.frames, "read complete Chamber render");
            return x;
        } finally {
            w.close();
        }
    }
    static double energy(float[] x, int from, int to) {
        double v = 0;
        for (int i = from; i < Math.min(to, x.length); i++) v += x[i] * x[i];
        return v;
    }
    public static void main(String[] args) throws Exception {
        NativeEngine.load();
        File dir = new File(System.getProperty("java.io.tmpdir"), "chamber-jni-" + System.nanoTime());
        new File(dir, "audio").mkdirs();
        try {
            File source = new File(dir, "audio/pad.wav");
            NativeImport writer = NativeImport.open(48000, 1, 48000, source);
            try {
                float[] tone = new float[2400];
                for (int i = 0; i < tone.length; i++) tone[i] = (float) (.2 * Math.sin(2 * Math.PI * 440 * i / 48000));
                writer.push(tone, tone.length);
                writer.finish();
            } finally {
                writer.close();
            }
            Project p = new Project("Song", 48000);
            Performance f = p.performance;
            for (int pad = 0; pad < 2; pad++) {
                f.pads[pad].file = "audio/pad.wav";
                f.pads[pad].length = 2400;
                f.pads[pad].attack = 0;
                f.pads[pad].decay = 0;
            }
            f.pattern = true;
            f.steps[0][0] = 1;
            f.chamber.patterns[1][1][0] = .5f;
            f.chamber.song = true;
            f.chamber.chain.clear();
            f.chamber.chain.add(0);
            f.chamber.chain.add(1);
            p.validate();
            File out = new File(dir, "song.wav");
            NativeEngine e = NativeEngine.offline(48000);
            try {
                e.load(p, dir);
                e.export(out, p.renderFrames(), true);
                float[] song = read(out);
                check(energy(song, 0, 5000) > 1 && energy(song, 190000, 200000) > 1, "A then B play in song order");
                check(energy(song, 8000, 180000) < 1e-12, "song does not repeat A inside its bar");
                e.export(out, p.renderFrames(), true);
                check(java.util.Arrays.equals(song, read(out)),
                    "repeated pattern exports start with identical voice state");
                f.chamber.mute[0] = true;
                e.load(p, dir);
                e.export(out, p.renderFrames(), true);
                float[] muted = read(out);
                check(energy(muted, 0, 10000) < 1e-12 && energy(muted, 190000, 200000) > 1,
                    "per-pad mute affects export");
                f.chamber.mute[0] = false;
                f.chamber.solo[0] = true;
                e.load(p, dir);
                e.export(out, p.renderFrames(), true);
                float[] solo = read(out);
                check(energy(solo, 0, 5000) > 1 && energy(solo, 190000, 200000) < 1e-12, "per-pad solo affects export");
            } finally {
                e.close();
            }
            f.pattern = false;
            f.chamber.solo[0] = false;
            f.chamber.song = false;
            f.chamber.active = 2;
            f.chamber.repeat = 3;
            f.tempos.put(12000L, 60.0);
            e = NativeEngine.offline(48000);
            try {
                e.load(p, dir);
                e.play();
                e.pad(0, .7f, true);
                float[] block = new float[1024];
                for (int at = 0; at < 40000; at += 512) e.render(block, 512);
                double[] hits = new double[1025];
                int count = e.padHits(hits);
                check(count == 5 && hits[0] == 0, "repeat captures every live hit");
                long[] expected = {0, 6000, 12000, 24000, 36000};
                for (int i = 0; i < count; i++)
                    check((long) hits[1 + 4 * i] == expected[i] && hits[4 + 4 * i] == 2,
                        "repeat follows tempo map and captures active bank");
                e.pad(0, 0, false);
                for (int i = 0; i < 40; i++) e.render(block, 512);
                check(e.padHits(hits) == 0, "releasing the pad stops repeat");
                f.chamber.repeat = 0;
                f.chamber.levels = true;
                f.chamber.levelPad = 0;
                e.performance(p, dir);
                e.pad(0, .9f, true);
                e.pad(15, .1f, true);
                e.render(block, 512);
                count = e.padHits(hits);
                check(count == 2 && hits[2] == 0 && hits[6] == 0 && Math.abs(hits[3] - .0625) < 1e-6 && hits[7] == 1,
                    "16 levels maps pads to one sound with sixteen velocities");
                e.padsOff();
                for (int i = 0; i < 80; i++) e.render(block, 512);
                check(energy(block, 0, 1024) < 1e-12, "all pads off clears held/repeated sound");
            } finally {
                e.close();
            }
            f.tempos.clear();
            f.chamber.levels = false;
            f.chamber.active = 0;
            f.chamber.song = true;
            f.chamber.chain.clear();
            f.chamber.chain.add(0);
            java.util.Arrays.fill(f.steps[0], 0);
            f.steps[0][15] = 1;
            f.pattern = true;
            f.pads[0].mode = 2;
            f.pads[0].release = .01f;
            e = NativeEngine.offline(48000);
            try {
                e.load(p, dir);
                e.export(out, p.renderFrames() + 10000, true);
                float[] gate = read(out);
                check(energy(gate, 180000, 190000) > 1 && energy(gate, gate.length - 8000, gate.length) < 1e-12,
                    "looped pad gates at the next step and releases after song end");
            } finally {
                e.close();
            }
        } finally {
            Projects.delete(dir);
        }
        System.out.println("NativeChamberTest: " + passed + " passed");
    }
}
