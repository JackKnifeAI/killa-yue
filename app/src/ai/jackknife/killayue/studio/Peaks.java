package ai.jackknife.killayue.studio;

import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * A peak file (.kpk, written beside each audio file by the native importer): the min and max of every block at
 * five zooms, so a waveform draws from a few thousand numbers. Values are 16-bit (-32767 to 32767 is -1 to 1).
 */
public final class Peaks {
    public final long frames;
    public final int channels;
    private final int[] blocks;
    private final short[][] data;  // per level: (min, max) per channel per block

    private Peaks(long frames, int channels, int[] blocks, short[][] data) {
        this.frames = frames;
        this.channels = channels;
        this.blocks = blocks;
        this.data = data;
    }

    public static Peaks read(File f) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16));
        try {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!new String(magic, "US-ASCII").equals("KPK1") || le32(in) != 1) throw new IOException(f.getName() + " is not a peak file.");
            long frames = le64(in);
            int ch = le32(in), levels = le32(in);
            if (frames < 0 || ch < 1 || ch > 8 || levels < 1 || levels > 16) throw new IOException(f.getName() + " is damaged.");
            int[] blocks = new int[levels];
            short[][] data = new short[levels][];
            for (int l = 0; l < levels; l++) {
                blocks[l] = le32(in);
                long count = le64(in);
                if (blocks[l] <= 0 || count != (frames + blocks[l] - 1) / blocks[l] || count * ch * 2 > Integer.MAX_VALUE)
                    throw new IOException(f.getName() + " is damaged.");
                short[] d = new short[(int) (count * ch * 2)];
                byte[] raw = new byte[d.length * 2];
                in.readFully(raw);
                for (int i = 0; i < d.length; i++) d[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
                data[l] = d;
            }
            return new Peaks(frames, ch, blocks, data);
        } finally {
            in.close();
        }
    }

    public int levels() {
        return blocks.length;
    }

    public int block(int level) {
        return blocks[level];
    }

    public long count(int level) {
        return data[level].length / (2L * channels);
    }

    public short min(int level, long block, int channel) {
        return data[level][(int) ((block * channels + channel) * 2)];
    }

    public short max(int level, long block, int channel) {
        return data[level][(int) ((block * channels + channel) * 2 + 1)];
    }

    /** The coarsest zoom whose blocks are no bigger than a pixel's worth of frames (the finest if all are). */
    public int levelFor(double framesPerPixel) {
        int best = 0;
        for (int l = 0; l < blocks.length; l++)
            if (blocks[l] <= framesPerPixel) best = l;
        return best;
    }

    private static int le32(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static long le64(DataInputStream in) throws IOException {
        return Long.reverseBytes(in.readLong());
    }
}
