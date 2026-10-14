package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.view.Surface;

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Turns a song into a vertical lyric video (1080x1920, 30 fps, H.264 + AAC) for TikTok, Reels and
 * Shorts: the cover with a slow push-in, the title in the skin's lettering, the line being sung
 * filling with the skin's metal, the lines around it dimmed, the staff with notes drifting along
 * it, and a progress rule. Frames are drawn with the hardware canvas into the encoder's surface;
 * the audio is decoded and re-encoded to AAC. Written to Movies/Killa YuE.
 */
public class LyricVideo {
    static final int W = 1080, H = 1920, FPS = 30;

    interface Progress {
        void at(int percent, String what);

        boolean cancelled();
    }

    static File outFile(JSONObject m) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Killa YuE");
        dir.mkdirs();
        return new File(dir, Albums.safeName(m.optString("_title", "Untitled")) + ".mp4");
    }

    // ---------------- audio: decode the song, encode AAC, keep the samples ----------------

    static class Sample {
        byte[] data;
        long us;
        int flags;
    }

    private static MediaFormat encodeAudio(File song, ArrayList<Sample> out, Progress p) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(song.getAbsolutePath());
        int track = -1;
        MediaFormat inFmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                track = i;
                inFmt = f;
                break;
            }
        }
        if (track < 0) throw new Exception("The song file has no audio track");
        ex.selectTrack(track);
        int rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        long durUs = inFmt.containsKey(MediaFormat.KEY_DURATION) ? inFmt.getLong(MediaFormat.KEY_DURATION) : 0;

        MediaCodec dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME));
        dec.configure(inFmt, null, null, 0);
        dec.start();
        MediaFormat encFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch);
        encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encFmt.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
        encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaFormat outFmt = null;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        boolean inDone = false, decDone = false, encDone = false;
        byte[] pending = null;
        int pendingOff = 0;
        long pendingUs = 0;
        long pcmFrames = 0; // frames handed to the encoder, for its timestamps
        boolean encEosSent = false;
        while (!encDone) {
            if (p != null && p.cancelled()) throw new Exception("Cancelled");
            // feed the decoder
            if (!inDone) {
                int ii = dec.dequeueInputBuffer(5000);
                if (ii >= 0) {
                    ByteBuffer b = dec.getInputBuffer(ii);
                    int n = ex.readSampleData(b, 0);
                    if (n < 0) {
                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inDone = true;
                    } else {
                        dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            // take PCM from the decoder
            if (pending == null && !decDone) {
                int oi = dec.dequeueOutputBuffer(bi, 5000);
                if (oi >= 0) {
                    if (bi.size > 0) {
                        ByteBuffer b = dec.getOutputBuffer(oi);
                        pending = new byte[bi.size];
                        b.position(bi.offset);
                        b.get(pending);
                        pendingOff = 0;
                        pendingUs = bi.presentationTimeUs;
                    }
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decDone = true;
                    dec.releaseOutputBuffer(oi, false);
                    if (p != null && durUs > 0) p.at((int) Math.min(15, 15 * pendingUs / durUs), "Preparing the sound");
                }
            }
            // hand PCM to the encoder
            if (pending != null || (decDone && !encEosSent)) {
                int ii = enc.dequeueInputBuffer(5000);
                if (ii >= 0) {
                    ByteBuffer b = enc.getInputBuffer(ii);
                    if (pending != null) {
                        int n = Math.min(b.remaining(), pending.length - pendingOff);
                        b.put(pending, pendingOff, n);
                        long us = pcmFrames * 1000000L / rate;
                        enc.queueInputBuffer(ii, 0, n, us, 0);
                        pcmFrames += n / (2 * ch);
                        pendingOff += n;
                        if (pendingOff >= pending.length) pending = null;
                    } else {
                        enc.queueInputBuffer(ii, 0, 0, pcmFrames * 1000000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        encEosSent = true;
                    }
                }
            }
            // collect AAC
            int oi = enc.dequeueOutputBuffer(bi, 5000);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFmt = enc.getOutputFormat();
            } else if (oi >= 0) {
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                    ByteBuffer b = enc.getOutputBuffer(oi);
                    Sample s = new Sample();
                    s.data = new byte[bi.size];
                    b.position(bi.offset);
                    b.get(s.data);
                    s.us = bi.presentationTimeUs;
                    s.flags = bi.flags;
                    out.add(s);
                }
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
                enc.releaseOutputBuffer(oi, false);
            }
        }
        dec.stop();
        dec.release();
        enc.stop();
        enc.release();
        ex.release();
        if (outFmt == null) throw new Exception("The sound could not be encoded");
        return outFmt;
    }

    // ---------------- the picture ----------------

    static class Look {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap cover, staff;
        Bitmap[] notes = new Bitmap[6];
        Shader bg;
        String title;
        ArrayList<Karaoke.Line> lines;
        int durMs;
    }

    private static Look look(Context c, JSONObject m, int durMs) {
        Look k = new Look();
        k.title = m.optString("_title", "Untitled");
        k.lines = MainActivity.linesFor(m, durMs);
        k.durMs = durMs;
        File cf = Covers.coverFor(c, m);
        k.cover = cf.isFile() ? Covers.load(cf, 1) : null;
        if (k.cover == null) k.cover = Skin.dial();
        k.staff = Skin.staff();
        for (int i = 0; i < 6; i++) k.notes[i] = Skin.note(i);
        Bitmap bg = Skin.backgroundBitmap();
        if (bg != null) {
            BitmapShader s = new BitmapShader(bg, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            Matrix mx = new Matrix();
            float sc = Skin.tiledBackground() ? 640f / bg.getWidth() : Math.max(W / (float) bg.getWidth(), H / (float) bg.getHeight());
            mx.setScale(sc, sc);
            s.setLocalMatrix(mx);
            k.bg = s;
        }
        return k;
    }

    /** Fits text into width by shrinking from size, down to min. */
    private static float fit(Paint p, String s, float size, float min, float width) {
        p.setTextSize(size);
        float w = p.measureText(s);
        if (w <= width) return size;
        return Math.max(min, size * width / w);
    }

    /** Splits a long line into two near the middle space, so it stays big enough to read. */
    private static String[] split(String s, Paint p, float width) {
        if (p.measureText(s) <= width || s.indexOf(' ') < 0) return new String[] {s};
        int mid = s.length() / 2, best = -1;
        for (int d = 0; d < s.length(); d++) {
            if (mid - d > 0 && s.charAt(mid - d) == ' ') { best = mid - d; break; }
            if (mid + d < s.length() && s.charAt(mid + d) == ' ') { best = mid + d; break; }
        }
        if (best < 0) return new String[] {s};
        return new String[] {s.substring(0, best), s.substring(best + 1)};
    }

    private static void frame(Canvas c, Look k, int ms) {
        Paint p = k.p;
        p.setShader(null);
        p.setColor(Skin.DECK);
        c.drawRect(0, 0, W, H, p);
        if (k.bg != null) {
            p.setShader(k.bg);
            c.drawRect(0, 0, W, H, p);
        }
        p.setShader(new RadialGradient(W / 2f, H * 0.32f, H * 0.85f, new int[] {0x66000000, 0xB0000000, 0xF0000000},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        // cover, pushing in slowly over the song, inside a metal frame
        float t = k.durMs > 0 ? Math.min(1f, ms / (float) k.durMs) : 0f;
        RectF box = new RectF(110, 150, W - 110, 150 + (W - 220));
        float zoom = 1f + 0.08f * t;
        c.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(box, 34, 34, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        float cw = box.width() * zoom, ch = box.height() * zoom;
        c.drawBitmap(k.cover, null, new RectF(box.centerX() - cw / 2, box.centerY() - ch / 2, box.centerX() + cw / 2, box.centerY() + ch / 2), p);
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(10);
        p.setColor(Skin.LO);
        c.drawRoundRect(new RectF(box.left - 9, box.top - 9, box.right + 9, box.bottom + 9), 42, 42, p);
        p.setStrokeWidth(5);
        p.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                new int[] {Skin.LO, Skin.HI, Skin.ACCENT, Skin.LO, Skin.HI}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(box.left - 3, box.top - 3, box.right + 3, box.bottom + 3), 37, 37, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);

        // title
        Paint tx = k.text;
        boolean script = Skin.SCRIPT_TITLES && Skin.script() != null;
        tx.setTypeface(script ? Skin.script() : Skin.text());
        tx.setTextAlign(Paint.Align.CENTER);
        tx.setFakeBoldText(!script);
        float ts = fit(tx, k.title, script ? 92 : 64, 44, W - 140);
        tx.setTextSize(ts);
        tx.setShadowLayer(14, 0, 5, 0xE6000000);
        tx.setShader(new LinearGradient(0, box.bottom + 60, 0, box.bottom + 60 + ts, new int[] {Skin.HI, Skin.ACCENT, Skin.LO}, null, Shader.TileMode.CLAMP));
        c.drawText(k.title, W / 2f, box.bottom + 70 + ts * 0.8f, tx);
        tx.setShader(null);
        tx.setFakeBoldText(false);

        // lyrics: the line being sung in the middle, the one before and after dimmed
        int cur = -1;
        for (int i = 0; i < k.lines.size(); i++) if (k.lines.get(i).start <= ms) cur = i;
        float centre = 1390;
        tx.setTypeface(Skin.text());
        tx.setShadowLayer(10, 0, 3, 0xE6000000);
        if (cur >= 0) {
            Karaoke.Line ln = k.lines.get(cur);
            float size = 62;
            tx.setTextSize(size);
            String[] parts = split(ln.text, tx, W - 120);
            float frac = ln.end > ln.start ? Math.max(0f, Math.min(1f, (ms - ln.start) / (float) (ln.end - ln.start))) : 1f;
            float y0 = centre - (parts.length - 1) * size * 0.62f;
            // fill: characters before the sweep in the skin's metal, the rest in cream
            int total = 0;
            for (int i = 0; i < parts.length; i++) total += parts[i].length();
            int lit = Math.round(frac * total);
            for (int i = 0; i < parts.length; i++) {
                String s = parts[i];
                tx.setTextSize(fit(tx, s, size, 40, W - 120));
                float w = tx.measureText(s), x0 = (W - w) / 2f, y = y0 + i * size * 1.24f;
                int on = Math.max(0, Math.min(s.length(), lit));
                lit -= s.length();
                tx.setTextAlign(Paint.Align.LEFT);
                String a = s.substring(0, on), b = s.substring(on);
                tx.setColor(Skin.HI);
                tx.setShadowLayer(18, 0, 0, Skin.ACCENT);
                c.drawText(a, x0, y, tx);
                tx.setShadowLayer(10, 0, 3, 0xE6000000);
                tx.setColor(Skin.TEXT);
                c.drawText(b, x0 + tx.measureText(a), y, tx);
                tx.setTextAlign(Paint.Align.CENTER);
            }
            tx.setTextSize(40);
            tx.setColor((Skin.DIM & 0x00FFFFFF) | 0xB0000000);
            if (cur > 0) c.drawText(clip(tx, k.lines.get(cur - 1).text, W - 160), W / 2f, y0 - size * 1.15f, tx);
            if (cur + 1 < k.lines.size()) {
                c.drawText(clip(tx, k.lines.get(cur + 1).text, W - 160), W / 2f, y0 + (parts.length - 1) * size * 1.24f + size * 1.25f, tx);
            }
        } else if (k.lines.size() > 0) {
            tx.setTextSize(40);
            tx.setColor(Skin.DIM);
            c.drawText(clip(tx, k.lines.get(0).text, W - 160), W / 2f, centre, tx);
        }

        // staff with notes drifting along it
        RectF st = new RectF(70, 1580, W - 70, 1580 + (W - 140) * k.staff.getHeight() / (float) k.staff.getWidth());
        c.drawBitmap(k.staff, null, st, p);
        for (int i = 0; i < 4; i++) {
            float ph = ((ms / 9000f) + i * 0.25f) % 1f;
            float x = st.left + st.width() * (0.12f + 0.8f * ph);
            int alpha = (int) (255 * Math.min(1f, Math.min(ph / 0.1f, (1f - ph) / 0.1f)));
            Bitmap n = k.notes[(i * 2) % 6];
            float nh = st.height() * 0.78f, nw = nh * n.getWidth() / n.getHeight();
            p.setAlpha(Math.max(0, alpha));
            c.drawBitmap(n, null, new RectF(x - nw / 2, st.top + st.height() * 0.1f, x + nw / 2, st.top + st.height() * 0.1f + nh), p);
        }
        p.setAlpha(255);

        // progress rule and time
        float py = st.bottom + 70;
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(new RectF(110, py, W - 110, py + 8), 4, 4, p);
        p.setShader(new LinearGradient(110, 0, W - 110, 0, Skin.LO, Skin.HI, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(110, py, 110 + (W - 220) * t, py + 8), 4, 4, p);
        p.setShader(null);
        tx.setTextSize(30);
        tx.setColor(Skin.DIM);
        tx.setShadowLayer(6, 0, 2, 0xE6000000);
        tx.setLetterSpacing(0.14f);
        c.drawText("MADE ON A PHONE WITH KILLA YuE", W / 2f, py + 72, tx);
        tx.setLetterSpacing(0f);
    }

    private static String clip(Paint p, String s, float width) {
        if (p.measureText(s) <= width) return s;
        while (s.length() > 3 && p.measureText(s + "…") > width) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ---------------- putting it together ----------------

    /** Makes the video. Blocks; run on a background thread. Returns the .mp4. */
    static File make(Context c, JSONObject m, Progress p) throws Exception {
        File song = new File(Store.library(c), m.optString("_audio"));
        if (!song.isFile()) throw new Exception("The song's audio file is missing");
        ArrayList<Sample> audio = new ArrayList<Sample>();
        MediaFormat audioFmt = encodeAudio(song, audio, p);
        long lastUs = audio.isEmpty() ? 0 : audio.get(audio.size() - 1).us;
        int durMs = (int) Math.max(lastUs / 1000, m.optInt("_seconds") * 1000L);
        int frames = (int) Math.ceil(durMs / 1000.0 * FPS);
        Look k = look(c, m, durMs);

        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 8000000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        if (android.os.Build.VERSION.SDK_INT >= 29) vf.setInteger("max-bframes", 0); // frames come out in the order drawn
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = enc.createInputSurface();
        enc.start();

        File out = outFile(m);
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        MediaMuxer mux = new MediaMuxer(tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int vTrack = -1;
        boolean started = false;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        int drawn = 0, written = 0;
        boolean eos = false;
        try {
            while (true) {
                if (p != null && p.cancelled()) throw new Exception("Cancelled");
                if (drawn < frames) {
                    Canvas cv = surface.lockHardwareCanvas();
                    try {
                        frame(cv, k, (int) (drawn * 1000L / FPS));
                    } finally {
                        surface.unlockCanvasAndPost(cv);
                    }
                    drawn++;
                    if (p != null && drawn % 15 == 0) p.at(15 + 85 * drawn / frames, "Drawing the video");
                } else if (!eos) {
                    enc.signalEndOfInputStream();
                    eos = true;
                }
                // drain whatever the encoder has ready
                while (true) {
                    int oi = enc.dequeueOutputBuffer(bi, drawn < frames ? 0 : 10000);
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        vTrack = mux.addTrack(enc.getOutputFormat());
                        int aTrack = mux.addTrack(audioFmt);
                        mux.start();
                        started = true;
                        MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
                        for (int i = 0; i < audio.size(); i++) {
                            Sample s = audio.get(i);
                            ai.set(0, s.data.length, s.us, s.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            mux.writeSampleData(aTrack, ByteBuffer.wrap(s.data), ai);
                        }
                        continue;
                    }
                    if (oi < 0) continue;
                    ByteBuffer b = enc.getOutputBuffer(oi);
                    boolean config = (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!config && bi.size > 0 && started) {
                        // the hardware canvas stamps frames with wall-clock time; the video's own clock is the frame count
                        bi.presentationTimeUs = written * 1000000L / FPS;
                        b.position(bi.offset);
                        b.limit(bi.offset + bi.size);
                        mux.writeSampleData(vTrack, b, bi);
                        written++;
                    }
                    boolean end = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    enc.releaseOutputBuffer(oi, false);
                    if (end) break;
                }
                if (eos && (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } finally {
            try {
                enc.stop();
            } catch (Exception e) {
                // already stopped
            }
            enc.release();
            surface.release();
            try {
                if (started) mux.stop();
            } catch (Exception e) {
                // an unfinished file is removed below
            }
            mux.release();
        }
        if (!started || written == 0) {
            tmp.delete();
            throw new Exception("The video encoder produced nothing");
        }
        out.delete();
        if (!tmp.renameTo(out)) throw new Exception("Could not save " + out.getName());
        try {
            MediaScannerConnection.scanFile(c, new String[] {out.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            // the gallery will find it later
        }
        if (p != null) p.at(100, "Done");
        return out;
    }
}
