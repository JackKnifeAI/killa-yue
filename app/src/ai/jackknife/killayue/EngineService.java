package ai.jackknife.killayue;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

    static java.io.PrintWriter logFile;

    /** Keeps the engine log on storage so a crash can be read afterwards. */
    static void openLogFile(Context c) {
        synchronized (log) {
            try {
                File dir = Store.dir(c, "logs");
                File last = new File(dir, "engine-last.log");
                if (last.isFile()) last.renameTo(new File(dir, "engine-previous.log"));
                if (logFile != null) logFile.close();
                logFile = new java.io.PrintWriter(new java.io.FileWriter(last), true);
            } catch (Exception e) {
                logFile = null;
            }
        }
    }

    static void addLog(String s) {
        synchronized (log) {
            if (logFile != null) logFile.println(s);
            log.add(s);
            while (log.size() > LOG_MAX) log.remove(0);
        }
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

    static void parse(String line) {
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
        if (proc == null) launch();
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
    private File prepareDriver(String lib) throws Exception {
        File vk = new File(getFilesDir(), "vk");
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

    private void launch() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "killayue:engine");
            wake.acquire();
            List<String> args = buildArgs(this);
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            pb.directory(getFilesDir());
            String lib = getApplicationInfo().nativeLibraryDir;
            File vk = prepareDriver(lib);
            pb.environment().put("LD_LIBRARY_PATH", vk.getAbsolutePath() + ":" + lib);
            String icd = new File(vk, "turnip_icd.json").getAbsolutePath();
            pb.environment().put("VK_DRIVER_FILES", icd);
            pb.environment().put("VK_ICD_FILENAMES", icd);
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            lastError = "";
            openLogFile(this);
            addLog("[App] Starting engine: " + args.toString());
            final Process p = pb.start();
            proc = p;
            running = true;
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
                        if (code != 0 && lastError.length() == 0) lastError = "Engine stopped (exit code " + code + ")";
                        proc = null;
                        running = false;
                        setStage("", 0, 0);
                        stopSelf();
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

    @Override
    public void onDestroy() {
        if (self == this) self = null;
        Process p = proc;
        proc = null;
        running = false;
        if (p != null) p.destroy();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
