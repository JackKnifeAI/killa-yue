package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.studio.NativeEngine;
import android.media.*;
import android.media.projection.*;
import android.os.*;
/** Android supplies only audio whose owner allows playback capture. The system owns consent and revocation. */
final class CaptureSource {
    private final MediaProjection projection;
    private AudioRecord input;
    private Thread thread;
    private volatile boolean stop;
    private final Runnable ended, silent;
    private final Handler ui = new Handler(Looper.getMainLooper());
    CaptureSource(MediaProjection p, Runnable ended, Runnable silent) {
        projection = p;
        this.ended = ended;
        this.silent = silent;
        p.registerCallback(new MediaProjection.Callback() {
            public void onStop() {
                if (!stop)
                    ui.post(CaptureSource.this.ended);
                stop = true;
                if (input != null)
                    try {
                        input.stop();
                    } catch (Exception ignored) {
                    }
            }
        }, new Handler(Looper.getMainLooper()));
    }
    void start(final NativeEngine engine) {
        int rate = engine.rate();
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                                                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                                    .excludeUid(android.os.Process.myUid())
                                                    .build();
        AudioFormat format = new AudioFormat.Builder()
                                 .setSampleRate(rate)
                                 .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                 .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                 .build();
        input = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(
                        16384, AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4))
                    .build();
        if (input.getState() != AudioRecord.STATE_INITIALIZED)
            throw new IllegalStateException("This device could not open playback capture.");
        input.startRecording();
        thread = new Thread(new Runnable() {
            public void run() {
                float[] b = new float[1024];
                long pos = 0;
                boolean heard = false;
                while (!stop) {
                    int n = input.read(b, 0, b.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) {
                        if (!stop)
                            ui.post(ended);
                        break;
                    }
                    for (int i = 0; i < n; i++)
                        heard |= Math.abs(b[i]) > 1e-7f;
                    if (!heard && pos > 5L * engine.rate()) {
                        ui.post(silent);
                        break;
                    }
                    engine.external(b, n, pos);
                    pos += n;
                }
            }
        }, "studio-playback-capture");
        thread.start();
    }
    void close() {
        stop = true;
        if (input != null)
            try {
                input.stop();
            } catch (Exception ignored) {
            }
        if (thread != null)
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        if (input != null)
            input.release();
        projection.stop();
    }
}
