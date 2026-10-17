package ai.jackknife.killayue;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the native yue-server as a child process and follows its log. */
public class EngineService extends Service {
    static final int PORT = 8087;
    static final int LOG_MAX = 400;

    static volatile Process proc;
    static volatile boolean running;
    private volatile boolean launching, destroyed;

    /** Gentle mode: slower songs that leave the phone responsive. */
    static boolean gentle(Context c) {
        return c.getSharedPreferences("cfg", 0).getBoolean("gentle", true);
    }
    static volatile String stage = "";
    static volatile int progCur, progMax;
    static volatile long stageSince;
    static volatile String lastError = "";
    static final ArrayList<String> log = new ArrayList<String>();

    private static final Pattern P_SEM = Pattern.compile("\\[AR\\] Semantic (\\d+)/(\\d+)");
    private static final Pattern P_SCORE = Pattern.compile("\\[AR\\] Score (\\d+)/(\\d+)");
    private static final Pattern P_NAR = Pattern.compile("\\[NAR\\] Step (\\d+)/(\\d+)");

    private PowerManager.WakeLock wake;

    static void start(Context c) {
        Intent i = new Intent(c, EngineService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            c.startService(i);
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, EngineService.class));
    }

    static volatile java.io.PrintWriter logFile;

    /** Keeps the engine log on storage so a crash can be read afterwards. No lock is held while storage is touched. */
    static void openLogFile(Context c) {
        java.io.PrintWriter old = logFile;
        logFile = null;
        if (old != null) old.close();
        try {
            File dir = Store.dir(c, "logs");
            File last = new File(dir, "engine-last.log");
            if (last.isFile()) last.renameTo(new File(dir, "engine-previous.log"));
            logFile = new java.io.PrintWriter(new java.io.FileWriter(last), false);
        } catch (Exception e) {
            logFile = null;
        }
    }

    /** Bumped on every new line, so the screen only rebuilds the log text when it changed. */
    static volatile int logVersion;
    // Lines for the log file go through a queue to their own thread: a write to shared storage
    // can stall for seconds under load, and nothing that holds the log lock may wait for it.
    private static final java.util.concurrent.LinkedBlockingQueue<String> fileQueue = new java.util.concurrent.LinkedBlockingQueue<String>(5000);
    private static Thread fileWriter;

    static void addLog(String s) {
        synchronized (log) {
            log.add(s);
            while (log.size() > LOG_MAX) log.remove(0);
            logVersion++;
        }
        fileQueue.offer(s); // dropped, not waited for, if the writer has fallen far behind
        startFileWriter();
    }

    private static synchronized void startFileWriter() {
        if (fileWriter != null) return;
        fileWriter = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        String s = fileQueue.take();
                        java.io.PrintWriter w = logFile;
                        if (w != null) {
                            w.println(s);
                            if (fileQueue.isEmpty()) w.flush();
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        // a failed write loses a log line, never the engine
                    }
                }
            }
        }, "log-file");
        fileWriter.setDaemon(true);
        fileWriter.setPriority(Thread.MIN_PRIORITY);
        fileWriter.start();
    }

    static String logText() {
        StringBuilder b = new StringBuilder();
        synchronized (log) {
            for (int i = 0; i < log.size(); i++) b.append(log.get(i)).append('\n');
        }
        return b.toString();
    }

    static volatile EngineService self;

    static void setStage(String s, int cur, int max) {
        if (!s.equals(stage)) stageSince = System.currentTimeMillis();
        stage = s;
        progCur = cur;
        progMax = max;
        EngineService me = self;
        if (me != null && s.length() > 0) {
            me.show(1, max > 0 ? s + "  " + cur + " / " + max : s, true);
        }
        Widget.refresh(me, false);
    }

    /** Posts a notification: id 1 is the ongoing engine status, id 2 a finished or failed song. */
    static void announce(String text) {
        EngineService me = self;
        if (me != null) me.show(2, text, false);
    }

    private void show(int id, String text, boolean ongoing) {
        try {
            Notification n = note(text);
            if (!ongoing) n.flags &= ~Notification.FLAG_ONGOING_EVENT;
            if (!ongoing) n.flags |= Notification.FLAG_AUTO_CANCEL;
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(id, n);
        } catch (Exception e) {
            // a notification is a courtesy; the song itself is unaffected
        }
    }

    /** True once this engine has shown progress on a job. */
    static volatile boolean sawProgress;

    /** What this run's log showed going wrong: "gpu" (the Vulkan driver), "memory", or "" (nothing recognised). */
    static volatile String fault = "";
    /** True while the engine is being restarted on the GPU after a driver crash: a job waits, then sends its work again. */
    static volatile boolean recovering;
    /** Driver restarts since the engine was started for a job; each one runs the driver more carefully (recoveryEnv). */
    static volatile int gpuRestarts;
    static final int GPU_RESTARTS_MAX = 2;

    /** Thrown to a waiting job when the engine came back on the GPU after a driver crash: send the job again. */
    static final class Restarted extends Exception {
        Restarted() {
            super("The engine restarted after a GPU driver crash");
        }
    }

    /** Out of memory, in the engine's own words (ggml, Vulkan, the C++ runtime). Checked before the GPU markers. */
    static boolean memoryFault(String line) {
        return line.contains("Device memory allocation of size") || line.contains("ErrorOutOfDeviceMemory")
                || line.contains("ErrorOutOfHostMemory") || line.contains("bad_alloc") || line.contains("out of memory")
                || line.contains("Cannot allocate memory");
    }

    /** The Vulkan driver failed: device lost, a fence error, or the GPU could not be opened. */
    static boolean gpuFault(String line) {
        return line.contains("DeviceLost") || line.contains("VK_ERROR_DEVICE_LOST") || line.contains("vk::SystemError")
                || (line.contains("ggml_vulkan:") && (line.contains("lost") || line.contains("error") || line.contains("Fence")))
                || (line.contains("--device") && line.contains("not found"));
    }

    static void parse(String line) {
        Eta.line(line);
        if (!sawProgress && (line.startsWith("[AR] ") || line.startsWith("[NAR] Step"))) sawProgress = true;
        if (memoryFault(line)) fault = "memory";
        else if (fault.length() == 0 && gpuFault(line)) fault = "gpu";
        Matcher m = P_NAR.matcher(line);
        if (m.find()) {
            setStage("Rendering audio", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            return;
        }
        m = P_SEM.matcher(line);
        if (m.find()) {
            setStage("Writing the song", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            return;
        }
        m = P_SCORE.matcher(line);
        if (m.find()) {
            setStage("Composing the score", Integer.parseInt(m.group(1)), 0);
            return;
        }
        if (line.contains("[Store] Load") || line.contains("[Load]")) {
            if (stage.length() == 0 || stage.startsWith("Loading")) setStage("Loading model", 0, 0);
        } else if (line.contains("[NAR] Song")) {
            setStage("Rendering audio", 0, 0);
        } else if (line.contains("[VAE]")) {
            setStage("Decoding to sound", 0, 0);
        } else if (line.contains("[MP3]") || line.contains("[WAV]")) {
            setStage("Encoding", 0, 0);
        } else if (line.contains("[Sheet") || line.contains("[MERT")) {
            setStage("Listening to the recording", 0, 0);
        }
        if (line.contains("--device") && line.contains("not found")) {
            lastError = "The engine could not open the GPU through Vulkan. " + line;
        } else if (line.contains("FATAL") || line.contains("ERROR") || line.contains("error:")) {
            lastError = line;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        self = this;
        startForeground(1, note("Engine running"));
        // Starting touches shared storage, which can stall under load, so it runs off the main thread.
        if (proc == null && !launching && !recovering) {
            launching = true;
            gpuRestarts = 0;
            running = true; // "starting" until the engine answers, as before
            new Thread(new Runnable() {
                public void run() {
                    try {
                        launch();
                    } finally {
                        launching = false;
                    }
                }
            }, "engine-launch").start();
        }
        return START_NOT_STICKY;
    }

    private Notification note(String text) {
        Notification.Builder b = null;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object c = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance("engine", "Music engine", Integer.valueOf(2));
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, c);
                b = Notification.Builder.class.getConstructor(Context.class, String.class)
                        .newInstance(this, "engine");
            }
        } catch (Exception e) {
            b = null;
        }
        if (b == null) b = new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0x04000000);
        return b.setContentTitle("Killa YuE").setContentText(text)
                .setSmallIcon(R.drawable.ic_stat).setOngoing(true).setContentIntent(pi).build();
    }

    static List<String> buildArgs(Context c) {
        SharedPreferences sp = c.getSharedPreferences("cfg", 0);
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File models = Store.models(c);
        ArrayList<String> a = new ArrayList<String>();
        a.add(lib + "/libyueserver.so");
        a.add("--model");
        a.add(new File(models, Store.backbone(c)).getAbsolutePath());
        a.add("--vae");
        a.add(new File(models, Store.VAE).getAbsolutePath());
        a.add("--host");
        a.add("127.0.0.1");
        a.add("--port");
        a.add(String.valueOf(PORT));
        a.add("--device");
        a.add(sp.getString("device", "Vulkan0"));
        int seq = sp.getInt("maxseq", 8192);
        if (seq > 0) {
            a.add("--max-seq");
            a.add(String.valueOf(seq));
        }
        a.add("--max-batch");
        a.add("1");
        boolean mert = new File(models, Store.MERT).isFile();
        if (mert && new File(models, Store.SHEET).isFile()) {
            a.add("--transcriber");
            a.add(new File(models, Store.SHEET).getAbsolutePath());
        }
        if (mert && new File(models, Store.TOKENIZER).isFile()) {
            a.add("--tokenizer");
            a.add(new File(models, Store.TOKENIZER).getAbsolutePath());
        }
        a.add("--adapters");
        a.add(Store.adapters(c).getAbsolutePath());
        if (sp.getBoolean("keep", false)) a.add("--keep-loaded");
        return a;
    }

    /**
     * The engine uses our bundled Turnip driver, not the phone's stock one, which
     * crashes on the engine's shaders. This folder makes the engine's "libvulkan.so"
     * resolve to the bundled loader and tells that loader where the driver is.
     */
    static File prepareDriver(Context c) throws Exception {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File vk = new File(c.getFilesDir(), "vk");
        vk.mkdirs();
        // the names the engine and the driver ask for, mapped to the files the APK can carry
        String[][] links = {
            {"libvulkan.so", "libvkloader.so"}, {"libz.so.1", "libkz.so"},
            {"libzstd.so.1", "libkzstd.so"}, {"libexpat.so.1", "libkexpat.so"},
        };
        for (int i = 0; i < links.length; i++) {
            File link = new File(vk, links[i][0]);
            link.delete();
            android.system.Os.symlink(lib + "/" + links[i][1], link.getAbsolutePath());
        }
        String json = "{\"file_format_version\":\"1.0.1\",\"ICD\":{\"library_path\":\""
                + lib + "/libturnip.so\",\"api_version\":\"1.4.0\"}}";
        Store.write(new File(vk, "turnip_icd.json"), json.getBytes("UTF-8"));
        return vk;
    }

    /**
     * The engine's environment, for both ways of running it. After a GPU driver crash (level 1 and up) the driver
     * runs more carefully: one GPU job at a time and small ones, then without fused shaders. Slower, never the CPU.
     */
    static java.util.LinkedHashMap<String, String> engineEnv(Context c, File vk, boolean gentle, int level) {
        java.util.LinkedHashMap<String, String> env = new java.util.LinkedHashMap<String, String>();
        String icd = new File(vk, "turnip_icd.json").getAbsolutePath();
        env.put("VK_DRIVER_FILES", icd);   // read by our loader, libkvulkan.so, which the engine library links to by that name
        env.put("VK_ICD_FILENAMES", icd);
        env.put("HOME", c.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", c.getCacheDir().getAbsolutePath());
        if (gentle) {
            // smaller GPU jobs, so the screen gets the GPU between them
            env.put("GGML_VK_MAX_NODES_PER_SUBMIT", "8");
            // read by our engine and driver patches (engine/); older builds ignore them
            env.put("YUE_THREADS", "2");
            env.put("YUE_AR_PAUSE_MS", "15");
            env.put("YUE_NAR_PAUSE_MS", "1500");
            env.put("TU_KGSL_PRIORITY", "15");
        }
        if (level >= 1) {
            env.put("GGML_VK_SERIALIZE_SUBMISSIONS", "1");
            env.put("GGML_VK_MAX_NODES_PER_SUBMIT", "8");
            env.put("TU_KGSL_PRIORITY", "15");
        }
        if (level >= 2) {
            env.put("GGML_VK_MAX_NODES_PER_SUBMIT", "4");
            env.put("GGML_VK_DISABLE_FUSION", "1");
            env.put("GGML_VK_DISABLE_GRAPH_OPTIMIZE", "1");
        }
        return env;
    }

    private void launch() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "killayue:engine");
            }
            if (!wake.isHeld()) wake.acquire();
            fault = "";
            List<String> args = buildArgs(this);
            boolean gentle = gentle(this);
            // Gentle mode, CPU side: the engine runs at a lower priority than everything on screen.
            if (gentle && new File("/system/bin/nice").exists()) {
                args.add(0, "10");
                args.add(0, "-n");
                args.add(0, "/system/bin/nice");
            }
            String lib = getApplicationInfo().nativeLibraryDir;
            File vk = prepareDriver(this);
            if (inProcessMode(this)) {
                launchInProcess(buildArgs(this), gentle, vk);
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.directory(getFilesDir());
            pb.environment().put("LD_LIBRARY_PATH", vk.getAbsolutePath() + ":" + lib);
            pb.environment().putAll(engineEnv(this, vk, gentle, gpuRestarts));
            lastError = "";
            openLogFile(this);
            addLog("[App] Starting engine: " + args.toString());
            if (gpuRestarts > 0) addLog("[App] Careful GPU mode " + gpuRestarts + ": " + engineEnv(this, vk, gentle, gpuRestarts));
            final Process p = pb.start();
            if (destroyed) {
                // the service was stopped while the engine was being started
                p.destroy();
                running = false;
                return;
            }
            proc = p;
            running = true;
            sawProgress = false;
            setStage("", 0, 0);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) {
                            addLog(line);
                            parse(line);
                        }
                    } catch (Exception e) {
                        addLog("[App] Log reader stopped: " + e);
                    }
                    int code = -1;
                    try {
                        code = p.waitFor();
                    } catch (Exception e) {
                        // interrupted while waiting; the exit code stays unknown
                    }
                    addLog("[App] Engine exited with code " + code);
                    if (proc == p) {
                        proc = null;
                        engineEnded(code);
                    }
                }
            }, "engine-log");
            t.start();
        } catch (Exception e) {
            lastError = "Could not start the engine: " + e;
            addLog("[App] " + lastError);
            running = false;
            proc = null;
            stopSelf();
        }
    }

    /** After the engine stops, in either mode: explain why, if it was not a clean exit. code: exit code, 128+signal for a signal. */
    private void engineEnded(int code) {
        if ((code == 137 || code == 9) && lastError.length() == 0) {
            lastError = Background.killedMessage(EngineService.this); // SIGKILL: Android stopped it
        }
        // The device setting is never changed here: only the person using the phone picks the processor.
        boolean onGpu = "Vulkan0".equals(getSharedPreferences("cfg", 0).getString("device", "Vulkan0"));
        if (code != 0 && "gpu".equals(fault) && onGpu && !destroyed) {
            if (gpuRestarts < GPU_RESTARTS_MAX) {
                restartOnGpu();
                return;
            }
            lastError = "The GPU driver crashed " + (gpuRestarts + 1) + " times in a row, and restarting it more carefully did not help. "
                    + "Nothing was switched: songs are still made on the GPU. The Engine tab's \"Device report\" helps fix this phone. "
                    + "To make songs anyway, you can choose the processor (CPU) under Engine > Device; it is much slower.";
        } else if (code != 0 && "memory".equals(fault)) {
            lastError = "The engine ran out of memory. Close other apps, or pick a shorter Max length on the Engine tab, and try again.";
        }
        if (code != 0 && lastError.length() == 0) lastError = "Engine stopped (exit code " + code + ")";
        recovering = false;
        running = false;
        setStage("", 0, 0);
        stopSelf();
    }

    /** Starts the engine again on the GPU after a driver crash, more carefully each time; a waiting job sends its work again. */
    private void restartOnGpu() {
        gpuRestarts++;
        recovering = true;
        lastError = "";
        final long pause = gpuRestarts == 1 ? 3000 : 10000; // time for the driver to reset the GPU context
        addLog("[App] The GPU driver crashed: restarting the engine on the GPU, more carefully (" + gpuRestarts + " of "
                + GPU_RESTARTS_MAX + "). The crash log is kept as engine-previous.log.");
        setStage("Restarting the GPU driver", 0, 0);
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    // start now
                }
                if (destroyed) {
                    recovering = false;
                    running = false;
                    return;
                }
                launch();
                recovering = false;
            }
        }, "engine-recover").start();
    }

    // ---------------- the engine in its own app process (EngineHost, JNI) ----------------

    /** True when the engine runs in the ":engine" app process: the setting is on and the library is in the APK. */
    static boolean inProcessMode(Context c) {
        boolean have = new File(c.getApplicationInfo().nativeLibraryDir, "libyueengine.so").isFile();
        return have && c.getSharedPreferences("cfg", 0).getBoolean("engine_process", false);
    }

    private volatile ServiceConnection hostConn;
    private volatile boolean hostConnected, hostGone;

    private void launchInProcess(List<String> args, boolean gentle, File vk) throws Exception {
        final File logf = new File(getFilesDir(), "engine-run.log");
        logf.delete();
        ArrayList<String> env = new ArrayList<String>();
        for (java.util.Map.Entry<String, String> e : engineEnv(this, vk, gentle, gpuRestarts).entrySet())
            env.add(e.getKey() + "=" + e.getValue());
        args.set(0, "yue-server");
        lastError = "";
        openLogFile(this);
        addLog("[App] Starting engine in its own process: " + args.toString());
        if (gpuRestarts > 0) addLog("[App] Careful GPU mode " + gpuRestarts + ": " + env);
        final long since = System.currentTimeMillis();
        Intent in = new Intent(this, EngineHost.class)
                .putExtra("args", args.toArray(new String[0]))
                .putExtra("env", env.toArray(new String[0]))
                .putExtra("log", logf.getAbsolutePath())
                .putExtra("nice", gentle ? 10 : 0);
        hostConnected = false;
        hostGone = false;
        hostConn = new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
                hostConnected = true;
            }

            public void onServiceDisconnected(ComponentName n) {
                hostGone = true;   // the ":engine" process ended
            }
        };
        // BIND_IMPORTANT: the engine's process gets the protection of this foreground service
        if (!bindService(in, hostConn, BIND_AUTO_CREATE | BIND_IMPORTANT)) throw new Exception("the engine process could not be started");
        running = true;
        sawProgress = false;
        setStage("", 0, 0);
        new Thread(new Runnable() {
            public void run() {
                int code = Integer.MIN_VALUE;
                java.util.regex.Pattern end = java.util.regex.Pattern.compile("\\[App\\] Engine exited with code (-?\\d+)");
                try {
                    // follow the log file the engine writes in the other process
                    long pos = 0, quietSince = System.currentTimeMillis();
                    StringBuilder part = new StringBuilder();
                    while (true) {
                        boolean read = false;
                        if (logf.isFile() && logf.length() > pos) {
                            java.io.RandomAccessFile f = new java.io.RandomAccessFile(logf, "r");
                            try {
                                f.seek(pos);
                                byte[] buf = new byte[(int) Math.min(1 << 16, logf.length() - pos)];
                                int n = f.read(buf);
                                if (n > 0) {
                                    pos += n;
                                    read = true;
                                    part.append(new String(buf, 0, n, "UTF-8"));
                                    int nl;
                                    while ((nl = part.indexOf("\n")) >= 0) {
                                        String line = part.substring(0, nl);
                                        part.delete(0, nl + 1);
                                        addLog(line);
                                        parse(line);
                                        java.util.regex.Matcher m = end.matcher(line);
                                        if (m.find()) code = Integer.parseInt(m.group(1));
                                    }
                                }
                            } finally {
                                f.close();
                            }
                        }
                        if (read) quietSince = System.currentTimeMillis();
                        if ((hostGone || destroyed) && !read && System.currentTimeMillis() - quietSince > 600) break;
                        if (!read) Thread.sleep(200);
                    }
                } catch (Exception e) {
                    addLog("[App] Log reader stopped: " + e);
                }
                if (destroyed) {
                    running = false;
                    return;
                }
                if (code == Integer.MIN_VALUE) code = exitCodeFromSystem(since);
                addLog("[App] Engine process ended (" + code + ")");
                try {
                    unbindService(hostConn);
                } catch (Exception e) {
                    // already gone
                }
                hostConn = null;
                engineEnded(code);
            }
        }, "engine-log").start();
    }

    /**
     * Why the ":engine" process ended, from Android's own record (Android 11 and later), as an exit code:
     * the code itself, 128 + signal, 137 when Android killed it (memory, background limits), 139 for a native crash.
     */
    private int exitCodeFromSystem(long since) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.lang.reflect.Method get = android.app.ActivityManager.class.getMethod("getHistoricalProcessExitReasons",
                    String.class, int.class, int.class);
            for (Object o : (List<?>) get.invoke(am, getPackageName(), 0, 8)) {
                Class<?> k = o.getClass();
                String name = (String) k.getMethod("getProcessName").invoke(o);
                long at = (Long) k.getMethod("getTimestamp").invoke(o);
                if (name == null || !name.endsWith(":engine") || at < since) continue;
                int reason = (Integer) k.getMethod("getReason").invoke(o);
                int status = (Integer) k.getMethod("getStatus").invoke(o);
                addLog("[App] Android's record: reason " + reason + ", status " + status + ", " + k.getMethod("getDescription").invoke(o));
                if (reason == 1) return status;                    // exited by itself
                if (reason == 2) return 128 + status;              // a signal
                if (reason == 5 || reason == 4) return 139;        // crashed
                if (reason == 10 || reason == 11) return 0;        // the user stopped it
                return 137;                                        // low memory, background limits, other kills
            }
        } catch (Exception e) {
            // Android 10 and older keep no record: it is reported as a kill
        }
        return 137;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (self == this) self = null;
        ServiceConnection hc = hostConn;
        hostConn = null;
        if (hc != null) {
            try {
                unbindService(hc);   // EngineHost ends its process when unbound
            } catch (Exception e) {
                // already unbound
            }
        }
        Process p = proc;
        proc = null;
        running = false;
        if (p != null) p.destroy();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
