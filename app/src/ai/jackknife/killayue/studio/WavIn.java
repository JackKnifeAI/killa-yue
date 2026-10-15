package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Reads WAV files in plain Java (exports, checks, tests): 32-bit float or 16/24-bit PCM, mono or stereo, RIFF
 * or RF64. Frames come out as floats, interleaved.
 */
public final class WavIn implements AutoCloseable {
    public final int rate, channels, bits;
    public final boolean floats;
    public final long frames;
    private final RandomAccessFile f;
    private final long dataStart;

    private WavIn(RandomAccessFile f, int rate, int channels, int bits, boolean floats, long frames, long dataStart) {
        this.f = f;
        this.rate = rate;
        this.channels = channels;
        this.bits = bits;
        this.floats = floats;
        this.frames = frames;
        this.dataStart = dataStart;
    }

    public static WavIn open(File file) throws IOException {
        RandomAccessFile f = new RandomAccessFile(file, "r");
        try {
            byte[] h = new byte[12];
            f.readFully(h);
            String riff = new String(h, 0, 4, "US-ASCII");
            if (!(riff.equals("RIFF") || riff.equals("RF64")) || !new String(h, 8, 4, "US-ASCII").equals("WAVE"))
                throw new IOException(file.getName() + " is not a WAV file.");
            int rate = 0, channels = 0, bits = 0, format = 0;
            long big = -1;  // the data size from ds64, for RF64
            while (true) {
                byte[] c = new byte[8];
                f.readFully(c);
                String id = new String(c, 0, 4, "US-ASCII");
                long size = le(c, 4, 4);
                long next = f.getFilePointer() + size + (size & 1);
                if (id.equals("ds64")) {
                    byte[] d = new byte[16];
                    f.readFully(d);
                    big = le(d, 8, 8);
                } else if (id.equals("fmt ")) {
                    byte[] d = new byte[16];
                    f.readFully(d);
                    format = (int) le(d, 0, 2);
                    channels = (int) le(d, 2, 2);
                    rate = (int) le(d, 4, 4);
                    bits = (int) le(d, 14, 2);
                } else if (id.equals("data")) {
                    if (rate == 0) throw new IOException(file.getName() + ": no format before the audio.");
                    if (format == 0xFFFE) format = bits == 32 ? 3 : 1;  // WAVE_FORMAT_EXTENSIBLE: go by the bits
                    boolean fl = format == 3;
                    if (!(fl && bits == 32) && !(format == 1 && (bits == 16 || bits == 24)))
                        throw new IOException(file.getName() + ": " + bits + "-bit format " + format + " is not read.");
                    if (channels < 1 || channels > 2) throw new IOException(file.getName() + ": " + channels + " channels.");
                    long bytes = size == 0xFFFFFFFFL && big >= 0 ? big : size;
                    long frames = Math.min(bytes, f.length() - f.getFilePointer()) / ((long) channels * bits / 8);
                    return new WavIn(f, rate, channels, bits, fl, frames, f.getFilePointer());
                }
                f.seek(next);
            }
        } catch (IOException e) {
            f.close();
            throw e;
        }
    }

    /** Reads frames from frame at into out (interleaved floats); returns how many there were. */
    public int read(long at, float[] out, int count) throws IOException {
        int n = (int) Math.max(0, Math.min(count, frames - at));
        int bps = bits / 8, stride = bps * channels;
        byte[] b = new byte[n * stride];
        f.seek(dataStart + at * stride);
        f.readFully(b);
        for (int i = 0; i < n * channels; i++) {
            int o = i * bps;
            if (floats) out[i] = Float.intBitsToFloat((int) le(b, o, 4));
            else if (bits == 16) out[i] = (short) le(b, o, 2) / 32768f;
            else out[i] = (((int) le(b, o, 3)) << 8 >> 8) / 8388608f;
        }
        return n;
    }

    private static long le(byte[] b, int o, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }

    @Override
    public void close() throws IOException {
        f.close();
    }
}
