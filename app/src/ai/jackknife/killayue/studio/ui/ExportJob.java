package ai.jackknife.killayue.studio.ui;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.jackknife.killayue.Store;
import ai.jackknife.killayue.studio.Clip;
import ai.jackknife.killayue.studio.Dither;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.NativeImport;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.Track;
import ai.jackknife.killayue.studio.WavIn;
import ai.jackknife.killayue.studio.WavOut;

/**
 * Exports from a project, on a worker thread, through an off-line engine of its own (never the one playing):
 * the mix as a float WAV, a CD-quality 16-bit WAV at 44.1 kHz (resampled, then TPDF-dithered), an M4A, a new
 * song in the library, or every track as its own stem (track inserts included; groups, sends and master processing bypassed).
 * Files go to Music/Killa YuE/Studio/<project>.
 */
final class ExportJob {
    static final int WAV = 0, CD = 1, M4A = 2, LIBRARY = 3, STEMS = 4;
    static final String[] NAMES = {"WAV, studio quality (32-bit float)", "WAV, CD quality (16-bit, 44.1 kHz)", "M4A (AAC, 256 kbps)",
            "Add to my library as a new song", "Stems: every track as its own WAV"};

    interface Progress {
        void at(String what, float fraction);  // the export's thread
    }

    private ExportJob() {}

    static File folder(Project p) {
        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        File d = new File(new File(new File(music, "Killa YuE"), "Studio"), safe(p.name));
        d.mkdirs();
        return d;
    }

    static String safe(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return t.length() == 0 ? "Studio" : t;
    }

    /** The end of the last clip that is heard (the length of the mix). */
    static long length(Project p,File dir) throws IOException { return p.renderFrames(dir); }
    static long length(Project p) {
        return p.renderFrames();
    }

    /** Runs one export; returns the files it made. */
    static ArrayList<File> run(Context c, Project project, File dir, int kind, Progress p, AtomicBoolean cancel) throws IOException {
        Project snap = project.copy();
        snap.performance.click = false;
        ArrayList<File> out = new ArrayList<File>();
        File folder = folder(snap);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        String base = safe(snap.name);
        long frames = length(snap, dir);
        if (frames <= 0 && kind != STEMS) throw new IOException("There is nothing to export: no track is heard.");
        ImportJob.checkSpace(folder, frames / (double) snap.rate * (kind == STEMS ? snap.tracks.size() : 2), snap.rate);
        File tmp = new File(c.getCacheDir(), "studio-export-" + stamp + ".wav");
        try {
            if (kind == WAV) {
                File f = new File(folder, base + " " + stamp + ".wav");
                render(snap, dir, f, frames, true, "Mixing", p, cancel);
                out.add(f);
            } else if (kind == CD || kind == LIBRARY) {
                render(snap, dir, tmp, frames, true, "Mixing", p, cancel);
                int rate = kind == CD ? 44100 : snap.rate;
                File f = kind == CD ? new File(folder, base + " " + stamp + " (CD).wav") : new File(Store.library(c), stamp + "-studio.wav");
                to16(tmp, f, rate, p, cancel);
                out.add(f);
                if (kind == LIBRARY) out.add(addToLibrary(c, snap, f, frames / (double) snap.rate, stamp));
            } else if (kind == M4A) {
                render(snap, dir, tmp, frames, true, "Mixing", p, cancel);
                File f = new File(folder, base + " " + stamp + ".m4a");
                aac(tmp, f, p, cancel);
                out.add(f);
            } else {
                File stems = new File(folder, base + " " + stamp + " stems");
                stems.mkdirs();
                long len = Math.max(0, length(snap, dir) - snap.mixer.tailFrames(snap.rate));
                for (Track t : snap.tracks) {
                    for (Clip cl : t.clips) len = Math.max(len, cl.start + cl.length);
                    len = Math.max(len, t.notes.end(snap));
                }
                long longestTail = 0;
                for (Track t : snap.tracks) longestTail = Math.max(longestTail, snap.mixer.stem(t.id).tailFrames(snap.rate));
                len += longestTail; // aligned stems keep the longest track insert tail
                for (int i = 0; i < snap.tracks.size(); i++) {
                    Project one = snap.copy();
                    Track keep = one.tracks.get(i);
                    one.tracks.clear();
                    keep.mute = false;
                    keep.solo = false;
                    one.tracks.add(keep);
                    one.mixer = one.mixer.stem(keep.id);
                    one.masterDb = 0;
                    one.performance.pattern = false;
                    File f = new File(stems, String.format(Locale.ROOT, "%02d ", i + 1) + safe(keep.name) + ".wav");
                    render(one, dir, f, len, false, "Stem " + (i + 1) + " of " + snap.tracks.size(), p, cancel);
                    out.add(f);
                }
                if (snap.performance.pattern) {
                    Project pads = snap.copy();
                    pads.tracks.clear();
                    pads.mixer = pads.mixer.stem(null);
                    pads.masterDb = 0;
                    File f = new File(stems, "Sampler.wav");
                    render(pads, dir, f, len, false, "Sampler stem", p, cancel);
                    out.add(f);
                }
            }
            return out;
        } catch (IOException e) {
            for (File f : out) f.delete();
            throw cancel.get() ? new IOException("Cancelled.") : e;
        } finally {
            tmp.delete();
        }
    }

    /** The project through a fresh off-line engine into a float WAV. */
    static void render(Project p, File dir, File out, long frames, boolean limiter, final String what, final Progress prog, final AtomicBoolean cancel)
            throws IOException {
        final NativeEngine e = NativeEngine.offline(p.rate);
        final long total = Math.max(1, frames);
        final AtomicBoolean done = new AtomicBoolean();
        Thread watch = new Thread(new Runnable() {
            public void run() {
                while (!done.get()) {
                    if (cancel.get()) e.cancelExport();
                    prog.at(what, Math.min(1f, e.exportProgress() / (float) total));
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException x) {
                        return;
                    }
                }
            }
        }, "studio-export-watch");
        try {
            e.load(p, dir);
            watch.start();
            e.export(out, frames, limiter);
        } finally {
            done.set(true);
            try {
                watch.join();
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
            }
            e.close();
        }
    }

    /** Float WAV to 16-bit at rate (resampled by the Studio's resampler when it differs), with TPDF dither. */
    private static void to16(File in, File out, int rate, Progress p, AtomicBoolean cancel) throws IOException {
        File src = in, resampled = null;
        WavIn w = WavIn.open(in);
        try {
            if (w.rate != rate) {
                resampled = new File(in.getPath() + ".rs.wav");
                NativeImport im = NativeImport.open(w.rate, w.channels, rate, resampled);
                try {
                    float[] buf = new float[8192 * w.channels];
                    for (long at = 0; at < w.frames; ) {
                        if (cancel.get()) throw new IOException("Cancelled.");
                        int n = w.read(at, buf, 8192);
                        im.push(buf, n);
                        at += n;
                        p.at("Converting to " + rate + " Hz", at / (float) w.frames);
                    }
                    im.finish();
                } finally {
                    im.close();
                }
                new File(resampled.getPath() + ".kpk").delete();
                src = resampled;
            }
        } finally {
            w.close();
        }
        WavIn r = WavIn.open(src);
        WavOut o = WavOut.create(out, r.rate, r.channels);
        boolean ok = false;
        try {
            Dither d = new Dither(r.frames);
            float[] buf = new float[8192 * r.channels];
            short[] s = new short[buf.length];
            for (long at = 0; at < r.frames; ) {
                if (cancel.get()) throw new IOException("Cancelled.");
                int n = r.read(at, buf, 8192);
                for (int i = 0; i < n * r.channels; i++) s[i] = d.quantise(buf[i]);
                o.write(s, n);
                at += n;
                p.at("Writing 16-bit", at / (float) r.frames);
            }
            o.finish();
            ok = true;
        } finally {
            r.close();
            if (!ok) o.abandon();
            if (resampled != null) resampled.delete();
        }
    }

    /** Float WAV to AAC-LC in an M4A. */
    private static void aac(File in, File out, Progress p, AtomicBoolean cancel) throws IOException {
        WavIn r = WavIn.open(in);
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, r.rate, r.channels);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 256000);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxTrack = -1;
        boolean ok = false;
        try {
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            Dither d = new Dither(1);
            float[] buf = new float[4096 * r.channels];
            long at = 0;
            boolean eosSent = false, done = false;
            while (!done) {
                if (cancel.get()) throw new IOException("Cancelled.");
                if (!eosSent) {
                    int ii = enc.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer b = enc.getInputBuffer(ii);
                        int room = b.remaining() / (2 * r.channels);
                        int n = r.read(at, buf, Math.min(4096, room));
                        if (n <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, at * 1000000L / r.rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosSent = true;
                        } else {
                            ByteBuffer pcm = b.order(ByteOrder.LITTLE_ENDIAN);
                            for (int i = 0; i < n * r.channels; i++) pcm.putShort(d.quantise(buf[i]));
                            enc.queueInputBuffer(ii, 0, n * 2 * r.channels, at * 1000000L / r.rate, 0);
                            at += n;
                            p.at("Encoding", at / (float) Math.max(1, r.frames));
                        }
                    }
                }
                int oi = enc.dequeueOutputBuffer(bi, 5000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                } else if (oi >= 0) {
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0 && muxTrack >= 0) {
                        ByteBuffer b = enc.getOutputBuffer(oi);
                        b.position(bi.offset);
                        b.limit(bi.offset + bi.size);
                        mux.writeSampleData(muxTrack, b, bi);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
            ok = true;
        } finally {
            r.close();
            try {
                enc.stop();
            } catch (RuntimeException e) {
                // already stopped
            }
            enc.release();
            try {
                if (muxTrack >= 0) mux.stop();
            } catch (RuntimeException e) {
                ok = false;
            }
            mux.release();
            if (!ok) out.delete();
        }
    }

    /** A new song in the library for the export (with the source song's lyrics, so it can be followed along). */
    private static File addToLibrary(Context c, Project p, File audio, double seconds, String stamp) throws IOException {
        try {
            JSONObject m = new JSONObject();
            if (p.sourceSong != null) {
                File src = new File(Store.library(c), p.sourceSong);
                if (src.isFile()) {
                    JSONObject s = new JSONObject(new String(Store.read(src), "UTF-8"));
                    for (String k : new String[] {"lyrics", "style", "_times", "_synced"}) if (s.has(k)) m.put(k, s.get(k));
                }
            }
            m.put("_title", p.name);
            m.put("_audio", audio.getName());
            m.put("_created", System.currentTimeMillis());
            m.put("_seconds", seconds);
            m.put("_studio", true);
            File meta = new File(Store.library(c), stamp + "-studio.json");
            FileOutputStream o = new FileOutputStream(meta);
            o.write(m.toString(2).getBytes("UTF-8"));
            o.close();
            return meta;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not add it to the library: " + e.getMessage(), e);
        }
    }
}
