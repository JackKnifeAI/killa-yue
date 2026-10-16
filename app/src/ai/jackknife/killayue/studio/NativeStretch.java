package ai.jackknife.killayue.studio;
import java.io.*;
import java.nio.charset.Charset;
public final class NativeStretch {
    public static void run(File in, File out, long offset, long frames, double ratio, double semitones) throws IOException {
        nRun(in.getPath().getBytes(Charset.forName("UTF-8")), out.getPath().getBytes(Charset.forName("UTF-8")), offset, frames, ratio,
             semitones);
    }
    public static void peaks(File file) throws IOException { nPeaks(file.getPath().getBytes(Charset.forName("UTF-8"))); }
    private static native void nPeaks(byte[] file) throws IOException;
    private static native void nRun(byte[] in, byte[] out, long offset, long frames, double ratio, double semitones) throws IOException;
}
