package ai.jackknife.killayue.studio.ui;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.jackknife.killayue.studio.NativeImport;
import ai.jackknife.killayue.studio.WavIn;

/**
 * Brings an audio file into a project at the Studio's rate: WAV files are read directly (keeping 24-bit
 * detail), everything else Android plays (MP3, AAC, FLAC, Ogg...) is decoded with MediaCodec a buffer at a
 * time, never held whole in memory. Mono stays mono; more than two channels keep the first two. Off the UI
 * thread; cancel stops it and removes what it wrote.
 */
public final class ImportJob {
    public interface Progress {
        void at(float fraction);  // the import's thread
    }

    private ImportJob() {}

    public static long run(Context c, File source, File out, int rate, Progress p, AtomicBoolean cancel) throws IOException {
        if (source.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) {
            try {
                return wav(source, out, rate, p, cancel);
            } catch (IOException e) {
                // a WAV the plain reader does not take (an unusual format): let Android decode it
            }
        }
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(source.getPath());
        return decode(ex, out, rate, p, cancel);
    }

    /** A file the user picked (a content URI). */
    public static long run(Context c, Uri source, File out, int rate, Progress p, AtomicBoolean cancel) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(c, source, null);
        return decode(ex, out, rate, p, cancel);
    }

    /** Free space for a file of this many seconds at the Studio's rate (float, stereo), with room to spare. */
    static void checkSpace(File out, double seconds, int rate) throws IOException {
        long need = (long) (seconds * rate * 2 * 4) + (500L << 20);
        long have = new StatFs(out.getParentFile().getPath()).getAvailableBytes();
        if (have < need)
            throw new IOException("Not enough space: this needs " + (need >> 20) + " MB free and there are " + (have >> 20) + " MB.");
    }

    private static long wav(File source, File out, int rate, Progress p, AtomicBoolean cancel) throws IOException {
        WavIn in = WavIn.open(source);
        try {
            checkSpace(out, in.frames / (double) in.rate, rate);
            NativeImport im = NativeImport.open(in.rate, in.channels, rate, out);
            try {
                float[] buf = new float[8192 * in.channels];
                for (long at = 0; at < in.frames; ) {
                    if (cancel.get()) throw new IOException("Cancelled.");
                    int n = in.read(at, buf, 8192);
                    im.push(buf, n);
                    at += n;
                    p.at((float) at / in.frames);
                }
                return im.finish();
            } finally {
                im.close();
            }
        } finally {
            in.close();
        }
    }

    private static long decode(MediaExtractor ex, File out, int rate, Progress p, AtomicBoolean cancel) throws IOException {
        MediaCodec codec = null;
        NativeImport im = null;
        try {
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("There is no audio in that file.");
            ex.selectTrack(track);
            long duration = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            if (duration > 0) checkSpace(out, duration / 1e6, rate);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), inCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean inputDone = false, outputDone = false;
            float[] buf = new float[0];
            while (!outputDone) {
                if (cancel.get()) throw new IOException("Cancelled.");
                if (!inputDone) {
                    int ib = codec.dequeueInputBuffer(10000);
                    if (ib >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ib);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int ob = codec.dequeueOutputBuffer(info, 10000);
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (ob >= 0) {
                    if (info.size > 0) {
                        if (im == null) im = NativeImport.open(inRate, Math.min(inCh, 2), rate, out);
                        ByteBuffer b = codec.getOutputBuffer(ob);
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        java.nio.ShortBuffer s = b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        int frames = s.remaining() / inCh, keep = Math.min(inCh, 2);
                        if (buf.length < frames * keep) buf = new float[frames * keep];
                        for (int i = 0; i < frames; i++)
                            for (int ch = 0; ch < keep; ch++) buf[i * keep + ch] = s.get(i * inCh + ch) / 32768f;
                        im.push(buf, frames);
                        if (duration > 0) p.at(Math.min(1f, info.presentationTimeUs / (float) duration));
                    }
                    codec.releaseOutputBuffer(ob, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            if (im == null) throw new IOException("The file decoded to no audio.");
            long frames = im.finish();
            p.at(1f);
            return frames;
        } finally {
            if (im != null) im.close();  // an unfinished import removes itself
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException e) {
                    // already stopped
                }
                codec.release();
            }
            ex.release();
        }
    }
}
