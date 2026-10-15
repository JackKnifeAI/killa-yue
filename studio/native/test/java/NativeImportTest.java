package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.IOException;

/**
 * Import end to end on a computer: a 44.1 kHz file through libstudio into a 48 kHz project, read back with the
 * Java WAV reader, and its peak file read by Peaks and held against the samples. Also WavIn on its own.
 */
public class NativeImportTest {
    private static int failed, passed;

    private static void check(String name, boolean ok) {
        if (ok) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    // the native writer's quantising: clamp, multiply in float, round half away from zero (lround)
    static long q(float v) {
        v = Math.max(-1f, Math.min(1f, v));
        float x = v * 32767f;
        return (long) (Math.signum(x) * Math.floor(Math.abs((double) x) + 0.5));
    }

    public static void main(String[] a) throws Exception {
        NativeEngine.load();
        File dir = new File(System.getProperty("java.io.tmpdir"), "native-import-test-" + System.nanoTime() + " 🔥 Café");
        dir.mkdirs();
        File src = new File(dir, "source.wav");
        int n = 44100 * 3;
        float[] tone = new float[2 * n];
        for (int i = 0; i < n; i++) {
            tone[2 * i] = (float) (0.5 * Math.sin(2 * Math.PI * 440 * i / 44100.0)) * (i < n / 2 ? 1f : 0.25f);
            tone[2 * i + 1] = -0.3f;
        }
        NativeEngineTest.wav(src, 44100, 2, 0f, n);  // a header of the right size; the samples are written below
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(src, "rw");
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(tone.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : tone) bb.putFloat(v);
        raf.seek(44);
        raf.write(bb.array());
        raf.close();

        WavIn in = WavIn.open(src);
        check("WavIn reads a float WAV's format", in.rate == 44100 && in.channels == 2 && in.floats && in.frames == n);
        float[] back = new float[2 * 10];
        in.read(1000, back, 10);
        check("and its samples", back[0] == tone[2000] && back[1] == -0.3f);

        File out = new File(dir, "audio/song.wav");
        out.getParentFile().mkdirs();
        NativeImport im = NativeImport.open(44100, 2, 48000, out);
        float[] chunk = new float[2 * 4096];
        for (long at = 0; at < n; ) {
            int k = (int) Math.min(4096, n - at);
            in.read(at, chunk, k);
            im.push(chunk, k);
            at += k;
        }
        long frames = im.finish();
        im.close();
        in.close();
        check("the import is exactly as long as the source at 48 kHz", frames == (long) Math.ceil(n * 48000.0 / 44100.0));
        WavIn w = WavIn.open(out);
        check("it is a 48 kHz stereo float WAV, in a folder named with emoji", w.rate == 48000 && w.channels == 2 && w.floats && w.frames == frames);
        float[] s = new float[(int) (2 * w.frames)];
        w.read(0, s, (int) w.frames);
        w.close();
        check("the tone is in place at 1 s", Math.abs(s[2 * 48000] - 0.5 * Math.sin(2 * Math.PI * 440)) < 1e-4);

        Peaks p = Peaks.read(new File(out.getPath() + ".kpk"));
        check("its peak file is as long as it", p.frames == w.frames && p.channels == 2 && p.levels() == 5);
        boolean exact = true;
        for (int l = 0; l < p.levels(); l++)
            for (long b = 0; b < p.count(l); b++) {
                float mn = 2, mx = -2;
                for (long i = b * p.block(l); i < Math.min(w.frames, (b + 1) * p.block(l)); i++) {
                    mn = Math.min(mn, s[(int) (2 * i)]);
                    mx = Math.max(mx, s[(int) (2 * i)]);
                }
                if (p.min(l, b, 0) != q(mn) || p.max(l, b, 0) != q(mx)) exact = false;
            }
        check("Peaks reads what the native writer wrote: every block matches the samples", exact);
        check("the zoom for 1000 frames a pixel is blocks of 256", p.block(p.levelFor(1000)) == 256);
        check("for 100000 frames a pixel, blocks of 65536", p.block(p.levelFor(100000)) == 65536);

        // a cancelled import leaves nothing
        File gone = new File(dir, "audio/gone.wav");
        NativeImport c = NativeImport.open(44100, 1, 48000, gone);
        c.push(new float[1000], 1000);
        c.close();
        check("closing an unfinished import removes it", !gone.exists() && !new File(gone.getPath() + ".part").exists());
        boolean threw = false;
        try {
            NativeImport.open(44100, 5, 48000, new File(dir, "audio/x.wav"));
        } catch (IOException e) {
            threw = true;
        }
        check("five channels are refused", threw);

        for (File f : new File(dir, "audio").listFiles()) f.delete();
        new File(dir, "audio").delete();
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
        System.out.println("NativeImportTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
