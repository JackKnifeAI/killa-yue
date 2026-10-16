package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Brings decoded audio into a project (libstudio): resampled to the Studio's rate and written as a float WAV
 * with its peak file (.kpk) beside it. The WAV only takes its name when finished. Off the UI thread.
 */
public final class NativeImport implements AutoCloseable {
    private long h;
    private boolean finished;

    private NativeImport(long h) {
        this.h = h;
    }

    public static NativeImport open(int rateIn, int channels, int rateOut, File out) throws IOException {
        return new NativeImport(nOpen(rateIn, channels, rateOut, out.getPath().getBytes(Charset.forName("UTF-8"))));
    }

    /** Interleaved frames at the file's own rate. */
    public void push(float[] interleaved, int frames) throws IOException {
        if (h == 0) throw new IllegalStateException("The import is closed.");
        nPush(h, interleaved, frames);
    }

    /** Ends it: the frames written at the Studio's rate. */
    public long finish() throws IOException {
        if (h == 0) throw new IllegalStateException("The import is closed.");
        long n = nFinish(h);
        finished = true;
        return n;
    }

    /** Stops and removes everything it wrote. */
    public void cancel() {
        if (h != 0) nCancel(h);
    }

    @Override
    public void close() {
        if (h != 0) {
            if (!finished) nCancel(h);
            nClose(h);
            h = 0;
        }
    }

    private static native long nOpen(int rateIn, int channels, int rateOut, byte[] path) throws IOException;

    private static native void nPush(long h, float[] x, int frames) throws IOException;

    private static native long nFinish(long h) throws IOException;

    private static native void nCancel(long h);

    private static native void nClose(long h);
}
