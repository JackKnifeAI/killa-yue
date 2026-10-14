package ai.jackknife.killayue;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Turns a song's chorus into a ringtone, notification sound or alarm sound.
 *
 * The chorus is found from the lyric times (set by hand on the Lyrics tab when they were, the
 * estimate from the score otherwise). The audio is decoded with the phone's own decoder, cut,
 * given a short fade at each end and saved as a WAV file in the phone's Ringtones,
 * Notifications or Alarms folder, where every sound picker finds it.
 */
public class Ringtones {
    static final String[] KINDS = {"Ringtone", "Notification sound", "Alarm sound"};
    private static final int[] TYPES = {RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM};
    private static final int MAX_MS = 30000, NOTIFY_MS = 6000;

    // a sound saved while Android had not yet let us change the phone's sounds; set when the user comes back
    private static Uri pendingUri;
    private static int pendingType;

    /** Start and end of the part to use, in milliseconds: the first chorus, or the first sung lines when there is none. */
    static int[] chorus(JSONObject m, int durationMs, boolean shortSound) {
        ArrayList<Karaoke.Line> lines = MainActivity.linesFor(m, durationMs);
        int from = -1, to = -1;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i).section;
            boolean hook = "chorus".equals(s) || "hook".equals(s) || "refrain".equals(s);
            if (hook && from < 0) from = lines.get(i).start;
            if (!hook && from >= 0) {
                to = lines.get(i).start;
                break;
            }
        }
        if (from < 0 && lines.size() > 0) from = lines.get(0).start; // no chorus marked: where the singing starts
        if (from < 0) from = durationMs / 4; // no lyrics at all
        from = Math.max(0, from - 300); // a breath before the first word
        if (to <= from) to = durationMs;
        int max = shortSound ? NOTIFY_MS : MAX_MS;
        to = Math.min(Math.min(to + 500, durationMs), from + max);
        if (to - from < Math.min(max, 10000)) to = Math.min(durationMs, from + Math.min(max, 10000));
        return new int[] {from, to};
    }

    /** Asks which kind of sound, then makes it in the background. */
    static void ask(final MainActivity a, final JSONObject m) {
        new Lux.Builder(a).setTitle("Make it a ringtone").setItems(KINDS, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int w) {
                a.toast("Cutting the chorus...");
                final Context app = a.getApplicationContext();
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            final File f = make(app, m, w);
                            MediaScannerConnection.scanFile(app, new String[] {f.getAbsolutePath()}, new String[] {"audio/wav"},
                                    new MediaScannerConnection.OnScanCompletedListener() {
                                        public void onScanCompleted(String path, final Uri uri) {
                                            a.ui.post(new Runnable() {
                                                public void run() {
                                                    use(a, uri, w, f);
                                                }
                                            });
                                        }
                                    });
                        } catch (final Exception e) {
                            a.ui.post(new Runnable() {
                                public void run() {
                                    a.alert("Could not make the ringtone", String.valueOf(e.getMessage()));
                                }
                            });
                        }
                    }
                }, "ringtone").start();
            }
        }).show();
    }

    /** Sets the new sound as the phone's sound of that kind, asking Android's permission first if needed. */
    private static void use(final MainActivity a, Uri uri, int kind, File f) {
        String what = KINDS[kind].toLowerCase();
        if (uri == null) {
            a.alert("Saved", "The " + what + " is saved as " + f.getAbsolutePath() + ". Choose it in the phone's sound settings.");
            return;
        }
        if (Settings.System.canWrite(a)) {
            RingtoneManager.setActualDefaultRingtoneUri(a, TYPES[kind], uri);
            a.toast("Your " + what + " is now this song's chorus");
            return;
        }
        pendingUri = uri;
        pendingType = TYPES[kind];
        new Lux.Builder(a).setTitle("Saved")
                .setMessage("The chorus is in the phone's list of sounds. Android asks before an app changes the " + what
                        + ": allow Killa YuE to modify system settings and it is set as soon as you come back. "
                        + "Or leave it, and pick it yourself in the phone's sound settings.")
                .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            a.toast("Open Settings > Apps > Killa YuE to allow it");
                        }
                    }
                })
                .setNegativeButton("Leave it", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        pendingUri = null;
                    }
                }).show();
    }

    /** Called when the app comes back to the front: finishes a sound left waiting for permission. */
    static void resume(MainActivity a) {
        if (pendingUri == null || !Settings.System.canWrite(a)) return;
        try {
            RingtoneManager.setActualDefaultRingtoneUri(a, pendingType, pendingUri);
            a.toast("Done: the phone now uses this song's chorus");
        } catch (Exception e) {
            a.toast("Could not set the sound: " + e.getMessage());
        }
        pendingUri = null;
    }

    /** Cuts the chorus out of the song into the right public folder and returns the file. */
    static File make(Context c, JSONObject m, int kind) throws Exception {
        File audio = new File(Store.library(c), m.optString("_audio"));
        String folder = kind == 1 ? Environment.DIRECTORY_NOTIFICATIONS : kind == 2 ? Environment.DIRECTORY_ALARMS : Environment.DIRECTORY_RINGTONES;
        File dir = Environment.getExternalStoragePublicDirectory(folder);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Could not open the phone's " + folder + " folder");
        File out = new File(dir, "Killa YuE - " + Albums.safeName(m.optString("_title", "Untitled")) + ".wav");
        cut(audio, m, kind == 1, out);
        return out;
    }

    /** Decodes the chosen part of the song to 16-bit PCM with fades and writes it as a WAV file. */
    static void cut(File audio, JSONObject m, boolean shortSound, File out) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(audio.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new Exception("The song file has no audio in it");
            int durMs = fmt.containsKey(MediaFormat.KEY_DURATION) ? (int) (fmt.getLong(MediaFormat.KEY_DURATION) / 1000)
                    : m.optInt("_seconds") * 1000;
            int[] span = chorus(m, durMs, shortSound);
            long fromUs = span[0] * 1000L, toUs = span[1] * 1000L;
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            ex.selectTrack(track);
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false;
            long guard = System.currentTimeMillis();
            while (true) {
                if (System.currentTimeMillis() - guard > 60000) throw new Exception("The phone's decoder stopped answering");
                if (!inDone) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0 || ex.getSampleTime() > toUs) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (o >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(o);
                    byte[] chunk = new byte[info.size];
                    b.position(info.offset);
                    b.get(chunk);
                    codec.releaseOutputBuffer(o, false);
                    // drop what the decoder gives before the start (it begins at the frame before it)
                    int frame = 2 * channels;
                    long startUs = info.presentationTimeUs;
                    int skip = 0;
                    if (startUs < fromUs) skip = (int) Math.min(chunk.length, (fromUs - startUs) * rate / 1000000L * frame);
                    if (skip < chunk.length) pcm.write(chunk, skip, chunk.length - skip);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    guard = System.currentTimeMillis();
                }
            }
            byte[] data = pcm.toByteArray();
            int frames = Math.min(data.length / (2 * channels), (int) ((toUs - fromUs) * rate / 1000000L));
            if (frames <= 0) throw new Exception("Nothing was decoded from the song");
            fade(data, frames, channels, rate);
            writeWav(out, data, frames * 2 * channels, rate, channels);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception e) {
                    // already stopped
                }
                codec.release();
            }
            ex.release();
        }
    }

    /** A quarter-second fade in, and a fade out over the last second and a half. */
    private static void fade(byte[] d, int frames, int channels, int rate) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int in = Math.min(frames / 4, rate / 4), out = Math.min(frames / 3, rate * 3 / 2);
        for (int f = 0; f < frames; f++) {
            double g = 1;
            if (f < in) g = f / (double) in;
            if (f >= frames - out) g = Math.min(g, (frames - f) / (double) out);
            if (g >= 1) continue;
            for (int ch = 0; ch < channels; ch++) {
                int at = (f * channels + ch) * 2;
                b.putShort(at, (short) (b.getShort(at) * g));
            }
        }
    }

    private static void writeWav(File out, byte[] data, int len, int rate, int channels) throws Exception {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes("US-ASCII")).putInt(36 + len).put("WAVE".getBytes("US-ASCII"));
        h.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.put("data".getBytes("US-ASCII")).putInt(len);
        FileOutputStream f = new FileOutputStream(out);
        try {
            f.write(h.array());
            f.write(data, 0, len);
        } finally {
            f.close();
        }
    }
}
