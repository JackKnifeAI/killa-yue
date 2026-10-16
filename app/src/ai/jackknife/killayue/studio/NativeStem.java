package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Demucs stems in the app's ":ai" process (libkillastem.so, on the ExecuTorch runtime downloaded with the
 * model). split() blocks; progress() and cancel() work from another thread.
 */
public final class NativeStem implements AutoCloseable {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private long h;

    /** Loads the ExecuTorch runtime (downloaded, checked) and then the stems library. */
    public static void load(File runtime) {
        System.load(runtime.getAbsolutePath());
        System.loadLibrary("killastem");
    }

    public static boolean vulkan() {
        return nVulkan();
    }

    public NativeStem(File model) throws IOException {
        h = nOpen(model.getPath().getBytes(UTF8));
    }

    /** The song into drums, bass, other and vocals (.wav with peak files) in out, at rate; work keeps its checkpoint. */
    public void split(File song, File out, File work, int rate) throws IOException {
        nSplit(h, song.getPath().getBytes(UTF8), out.getPath().getBytes(UTF8), work.getPath().getBytes(UTF8), rate);
    }

    public double progress() {
        return h == 0 ? 0 : nProgress(h);
    }

    public void cancel() {
        if (h != 0) nCancel(h);
    }

    @Override
    public void close() {
        if (h != 0) {
            nClose(h);
            h = 0;
        }
    }

    private static native boolean nVulkan();

    private static native long nOpen(byte[] model) throws IOException;

    private static native void nSplit(long h, byte[] song, byte[] out, byte[] work, int rate) throws IOException;

    private static native double nProgress(long h);

    private static native void nCancel(long h);

    private static native void nClose(long h);
}
