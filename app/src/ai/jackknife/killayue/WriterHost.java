package ai.jackknife.killayue;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Hosts the songwriter's language model inside the app's own ":writer" process (see the manifest):
 * llama.cpp's server, built as libkillawriter.so and called through JNI, listening on 127.0.0.1 with
 * a key only this app knows. Songwriter binds here while a song is being written; when the binding
 * goes away this process ends, which frees the model's memory at once.
 */
public class WriterHost extends Service {
    static native int runWriter(String[] args, String logPath);

    private static volatile boolean started;

    @Override
    public IBinder onBind(Intent in) {
        if (!started) {
            started = true;
            final String[] args = in.getStringArrayExtra("args");
            final String log = in.getStringExtra("log");
            Thread t = new Thread(new Runnable() {
                public void run() {
                    int code;
                    try {
                        System.loadLibrary("killawriter");
                        code = runWriter(args, log);
                    } catch (Throwable e) {
                        EngineHost.append(log, "[App] The songwriter library could not start: " + e);
                        code = 120;
                    }
                    System.exit(code);
                }
            }, "killa-writer");
            t.start();
        }
        return new Binder();
    }

    @Override
    public boolean onUnbind(Intent in) {
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }
}
