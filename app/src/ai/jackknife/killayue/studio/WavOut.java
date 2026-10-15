package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Writes a 16-bit PCM WAV (export): to name.part, renamed into place only when finished. */
public final class WavOut {
    private final File file, part;
    private final RandomAccessFile f;
    private final int channels;
    private long bytes;

    private WavOut(File file, int rate, int channels) throws IOException {
        this.file = file;
        this.part = new File(file.getPath() + ".part");
        this.channels = channels;
        f = new RandomAccessFile(part, "rw");
        f.setLength(0);
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        le(h, 16, 16, 4);
        le(h, 20, 1, 2);
        le(h, 22, channels, 2);
        le(h, 24, rate, 4);
        le(h, 28, rate * channels * 2, 4);
        le(h, 32, channels * 2, 2);
        le(h, 34, 16, 2);
        put(h, 36, "data");
        f.write(h);
    }

    public static WavOut create(File file, int rate, int channels) throws IOException {
        return new WavOut(file, rate, channels);
    }

    public void write(short[] s, int frames) throws IOException {
        byte[] b = new byte[frames * channels * 2];
        for (int i = 0; i < frames * channels; i++) {
            b[2 * i] = (byte) s[i];
            b[2 * i + 1] = (byte) (s[i] >> 8);
        }
        f.write(b);
        bytes += b.length;
    }

    /** Sizes written, forced to disk, renamed into place. */
    public void finish() throws IOException {
        byte[] n = new byte[4];
        le(n, 0, (int) (36 + bytes), 4);
        f.seek(4);
        f.write(n);
        le(n, 0, (int) bytes, 4);
        f.seek(40);
        f.write(n);
        f.getFD().sync();
        f.close();
        if (!part.renameTo(file)) throw new IOException("Could not name the file " + file.getName() + ".");
    }

    /** Stops and removes what was written. */
    public void abandon() {
        try {
            f.close();
        } catch (IOException e) {
            // closing anyway
        }
        part.delete();
    }

    private static void put(byte[] b, int o, String s) {
        for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void le(byte[] b, int o, int v, int n) {
        for (int i = 0; i < n; i++) b[o + i] = (byte) (v >> (8 * i));
    }
}
