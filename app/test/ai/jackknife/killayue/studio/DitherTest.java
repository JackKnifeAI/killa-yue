package ai.jackknife.killayue.studio;

import java.io.File;

/** Dither and WavOut: float audio to 16-bit for export, with TPDF dither, never wrapping at full scale. */
public class DitherTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) throws Exception {
        Dither d = new Dither(7);
        // the error is triangular noise: mean ~0, spread of about two steps, never more
        double sum = 0, sq = 0;
        int worst = 0, n = 200000;
        for (int i = 0; i < n; i++) {
            float x = (float) (0.3 * Math.sin(i * 0.001));
            short q = d.quantise(x);
            double err = q - x * 32767.0;
            sum += err;
            sq += err * err;
            worst = Math.max(worst, (int) Math.ceil(Math.abs(err)));
        }
        double mean = sum / n, rms = Math.sqrt(sq / n - mean * mean);
        same("the dither's error averages to nothing", true, Math.abs(mean) < 0.01);
        same("its spread is TPDF's (rms about 0.41 of a step after rounding, under 0.6)", true, rms > 0.3 && rms < 0.6);
        same("never more than a step and a half off", true, worst <= 2);
        // silence stays silence-like (no fixed offset), and full scale never wraps around
        same("full scale up stays at the top", 32767, (int) d.quantise(1.0f));
        same("full scale down stays at the bottom", true, d.quantise(-1.0f) <= -32766);
        same("over full scale is held at the top, not wrapped", 32767, (int) d.quantise(1.7f));
        same("NaN is silence", true, Math.abs(d.quantise(Float.NaN)) <= 1);
        Dither d2 = new Dither(7);
        same("the same seed, the same noise (exports are repeatable)", d.getClass().getName() + " " + new Dither(7).quantise(0.25f),
                d2.getClass().getName() + " " + d2.quantise(0.25f));

        // a 16-bit WAV written and read back
        File f = new File(System.getProperty("java.io.tmpdir"), "wavout-" + System.nanoTime() + ".wav");
        WavOut w = WavOut.create(f, 44100, 2);
        short[] s = new short[2 * 1000];
        for (int i = 0; i < s.length; i++) s[i] = (short) (i * 30 - 30000);
        w.write(s, 1000);
        w.finish();
        WavIn in = WavIn.open(f);
        same("a 16-bit stereo WAV at 44.1 kHz", "44100 2 16 1000", in.rate + " " + in.channels + " " + in.bits + " " + in.frames);
        float[] back = new float[2 * 1000];
        in.read(0, back, 1000);
        in.close();
        same("its samples come back", true, Math.abs(back[123] * 32768f - s[123]) < 0.01f);
        same("named only when finished (no .part left)", false, new File(f.getPath() + ".part").exists());
        f.delete();
        System.out.println("DitherTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
