package ai.jackknife.killayue;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Hosts the song engine inside the app's own ":engine" process (see the manifest), as a library
 * called through JNI, instead of as a child process. Android limits and kills apps' child processes;
 * an app process bound by the foreground EngineService is protected like the app itself.
 *
 * EngineService binds here with the engine's arguments and environment. The engine's output goes to a
 * log file that EngineService reads. When the engine stops, or the binding goes away, this process ends,
 * which is also how the engine is stopped: it holds gigabytes of models and is never reused.
 */
public class EngineHost extends Service {
    static native int runEngine(String[] args, String[] env, String logPath, int nice);

    private static volatile boolean started;

    @Override
    public IBinder onBind(Intent in) {
        if (!started) {
            started = true;
            final String[] args = in.getStringArrayExtra("args");
            final String[] env = in.getStringArrayExtra("env");
            final String log = in.getStringExtra("log");
            final int nice = in.getIntExtra("nice", 0);
            final String library = in.getStringExtra("engine_library");
            Thread t = new Thread(new Runnable() {
                public void run() {
                    int code;
                    try {
                        if (!"yueengine".equals(library) && !"yueengine_portable".equals(library))
                            throw new IllegalArgumentException("Unknown song engine");
                        System.loadLibrary(library);
                        code = runEngine(args, env, log, nice);
                    } catch (Throwable e) {
                        EngineHost.append(log, "[App] The engine library could not start: " + e);
                        code = 120;
                    }
                    // the engine never runs twice in one process: end it, which EngineService notices
                    System.exit(code);
                }
            }, "yue-engine");
            t.start();
        }
        return new Binder();
    }

    @Override
    public boolean onUnbind(Intent in) {
        // the engine was stopped from the app: free its memory at once
        android.os.Process.killProcess(android.os.Process.myPid());
        return false;
    }

    static void append(String path, String line) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(path, true);
            w.write(line + "\n");
            w.close();
        } catch (Exception e) {
            // nothing else to tell
        }
    }
}
