package ai.jackknife.killayue.studio;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Original modal sounds, rendered once to sample files and subsequently streamed from storage.
 * No third-party recording, soundfont, model or sample is used. Apache-2.0, like the application.
 */
public final class InstrumentSamples {
    
    private InstrumentSamples() {}
    public static String path(int instrument, int rate, int key) {
        return "audio/instruments-v1/" + rate + "/" + instrument + "-" + key + ".wav";
    }
    public interface Progress {
        void at(int done, int total);
    }
    public static void bank(File dir, int rate, int instrument, final Progress progress, AtomicBoolean cancel)
        throws IOException {
        Sounds.bank(dir, rate, instrument, new Sounds.Progress() {
            public void at(int done, int total) { progress.at(done, total); }
        }, cancel);
    }
    public static File ensure(File dir, int rate, int instrument, int key) throws IOException {
        return ensure(dir, rate, instrument, key, 0);
    }
    public static synchronized File ensure(File dir, int rate, int instrument, int key, int layer) throws IOException {
        Sounds.Sound s = Sounds.get(instrument);
        if (s == null || s.acoustic() || rate < 8000 || rate > 384000 || key < MidiNotes.LOW || key > MidiNotes.HIGH
            || layer < 0 || layer >= s.layers)
            throw new IOException("Invalid sampled instrument.");
        File out = new File(dir, Sounds.path(instrument, rate, key, layer));
        if (out.isFile())
            return out;
        if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs())
            throw new IOException("Could not create the instrument sample folder.");
        if (instrument <= 1)
            return legacy(out, rate, instrument, key);
        Synth synth = Synth.of(instrument, rate, 440 * Math.pow(2, (key - 69) / 12.0), layer, s.layers);
        return write(out, rate, synth.stereo ? 2 : 1, (int) (s.seconds * rate), synth);
    }
    /** One sample frame at a time; mono writes left only. */
    interface Generator {
        void next(float[] frame);
    }
    static File write(File out, int rate, int channels, int frames, Generator g) throws IOException {
        File tmp = File.createTempFile("sample-", ".tmp", out.getParentFile());
        boolean saved = false;
        try {
            FileOutputStream stream = new FileOutputStream(tmp);
            try {
                int bytes = frames * 4 * channels;
                ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                header.put("RIFF".getBytes("US-ASCII")).putInt(36 + bytes).put("WAVEfmt ".getBytes("US-ASCII"))
                    .putInt(16).putShort((short) 3).putShort((short) channels).putInt(rate)
                    .putInt(rate * 4 * channels).putShort((short) (4 * channels)).putShort((short) 32)
                    .put("data".getBytes("US-ASCII")).putInt(bytes);
                stream.write(header.array());
                ByteBuffer chunk = ByteBuffer.allocate(4096 * 4 * channels).order(ByteOrder.LITTLE_ENDIAN);
                float[] frame = new float[2];
                int fadeIn = Math.max(1, rate / 1000), fadeOut = Math.max(1, rate * 8 / 100);
                for (int at = 0; at < frames;) {
                    chunk.clear();
                    int n = Math.min(4096, frames - at);
                    for (int i = 0; i < n; i++) {
                        frame[0] = frame[1] = 0;
                        g.next(frame);
                        float env = Math.min(1f, (at + i) / (float) fadeIn) * Math.min(1f, (frames - at - i) / (float) fadeOut);
                        for (int ch = 0; ch < channels; ch++) {
                            float v = frame[ch] * env;
                            chunk.putFloat(Float.isNaN(v) || Float.isInfinite(v) ? 0 : Math.max(-1, Math.min(1, v)));
                        }
                    }
                    stream.write(chunk.array(), 0, n * 4 * channels);
                    at += n;
                }
                stream.getFD().sync();
            } finally {
                stream.close();
            }
            if (!tmp.renameTo(out))
                throw new IOException("Could not save the instrument sample.");
            saved = true;
            return out;
        } finally {
            if (!saved)
                tmp.delete();
        }
    }
    private static File legacy(File out, int rate, int instrument, int key) throws IOException {
        File tmp = File.createTempFile("sample-", ".tmp", out.getParentFile());
        boolean saved = false;
        try {
            FileOutputStream stream = new FileOutputStream(tmp);
            try {
                int frames = rate * 4, bytes = frames * 4;
                ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                header.put("RIFF".getBytes("US-ASCII"))
                    .putInt(36 + bytes)
                    .put("WAVEfmt ".getBytes("US-ASCII"))
                    .putInt(16)
                    .putShort((short) 3)
                    .putShort((short) 1)
                    .putInt(rate)
                    .putInt(rate * 4)
                    .putShort((short) 4)
                    .putShort((short) 32)
                    .put("data".getBytes("US-ASCII"))
                    .putInt(bytes);
                stream.write(header.array());
                double hz = 440 * Math.pow(2, (key - 69) / 12.0);
                double[] ratio = instrument == 0 ? new double[] {1, 2, 3, 4, 5, 6, 7, 8}
                                                 : new double[] {1, 2.756, 5.404, 8.933, 13.344};
                double[] re = new double[ratio.length], im = new double[ratio.length], a = new double[ratio.length],
                         b = new double[ratio.length];
                for (int k = 0; k < ratio.length; k++) {
                    double freq = hz * ratio[k],
                           decay = Math.exp(-(instrument == 0 ? 1.7 + ratio[k] * .5 : 1.1 + ratio[k] * .4) / rate),
                           phase = 2 * Math.PI * freq / rate;
                    a[k] = decay * Math.cos(phase);
                    b[k] = decay * Math.sin(phase);
                    re[k] = freq < rate * .45 ? .36 / (instrument == 0 ? ratio[k] * ratio[k] : 1 + k * 2) : 0;
                }
                ByteBuffer chunk = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int at = 0; at < frames;) {
                    chunk.clear();
                    int n = Math.min(4096, frames - at);
                    for (int i = 0; i < n; i++) {
                        double value = 0;
                        for (int k = 0; k < ratio.length; k++) {
                            double r = re[k] * a[k] - im[k] * b[k];
                            im[k] = im[k] * a[k] + re[k] * b[k];
                            re[k] = r;
                            value += im[k];
                        }
                        double envelope =
                            Math.min(1, (at + i) / (rate * .002)) * Math.min(1, (frames - at - i) / (rate * .08));
                        chunk.putFloat((float) (value * envelope));
                    }
                    stream.write(chunk.array(), 0, n * 4);
                    at += n;
                }
                stream.getFD().sync();
            } finally {
                stream.close();
            }
            if (!tmp.renameTo(out))
                throw new IOException("Could not save the instrument sample.");
            saved = true;
            return out;
        } finally {
            if (!saved)
                tmp.delete();
        }
    }
}
