package ai.jackknife.killayue.compat;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

final class CompatibilityRunner {
    interface Progress { void update(String message); }
    private final Context context;
    private final DiagnosticProcess.Stop stop;
    private final Progress progress;
    private final File lib, root;
    private String stoppedReason = "";
    private long nextHealthCheck;

    CompatibilityRunner(Context context, DiagnosticProcess.Stop stop, Progress progress) {
        this.context = context.getApplicationContext(); this.stop = stop; this.progress = progress;
        lib = new File(context.getApplicationInfo().nativeLibraryDir);
        root = new File(context.getFilesDir(), "compatibility");
    }
    static String digest(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[32768]; int n;
            while ((n = input.read(bytes)) != -1) md.update(bytes, 0, n);
        }
        return hex(md.digest());
    }
    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    private String health() {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            if (battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) >= 400) return "Battery is at least 40°C. Let the phone cool before testing.";
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0 && level * 100 / scale < 20) return "Charge the battery above 20% before testing.";
        }
        if (Build.VERSION.SDK_INT >= 29 && ((PowerManager) context.getSystemService(Context.POWER_SERVICE)).getCurrentThermalStatus() >= 3)
            return "Android reports severe thermal pressure. Let the phone cool.";
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        if (memory.lowMemory || memory.availMem < 512L * 1024 * 1024) return "Not enough free memory for a calculation test. Close other apps.";
        return "";
    }
    JSONObject run(final CompatibilityPlan plan) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new Exception("Cannot create the private report folder");
        JSONObject report = new JSONObject();
        report.put("schema", 1).put("suite_version", CompatibilityPlan.SUITE_VERSION);
        report.put("suite", plan.suite).put("driver", plan.driver);
        report.put("app_version", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        report.put("package", context.getPackageName());
        report.put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL);
        report.put("android_api", Build.VERSION.SDK_INT).put("firmware", Build.ID);
        report.put("apk_sha256", digest(new File(context.getApplicationInfo().sourceDir)));
        report.put("request_id", plan.requestId).put("issue", plan.issue);
        report.put("scope", "portable-ggml-small-matrix; not full song generation or legacy bundled engine certification");
        if (plan.retest() && !plan.candidate.equals(report.getString("apk_sha256")))
            throw new Exception("This test request is for a different APK. Install the candidate linked in the GitHub request first.");
        JSONObject hashes = new JSONObject();
        for (String name : new String[] {"libcompatprobe.so", "libcompatmath.so", "libkyggml.so", "libkyggml-base.so", "libkyvulkan.so"}) {
            File file = new File(lib, name);
            if (!file.isFile()) throw new Exception("This APK is missing its compatibility test components");
            hashes.put(name, digest(file));
        }
        if ("turnip".equals(plan.driver)) hashes.put("libturnip.so", digest(new File(lib, "libturnip.so")));
        report.put("native_sha256", hashes);
        progress.update("Reading the GPU driver and capabilities…");
        // CPU tests still record the system GPU configuration so retests can be matched.
        ProcessBuilder probe = process(plan.driver, "libcompatprobe.so");
        probe.command().add("turnip".equals(plan.driver) ? new File(lib, "libvkloader.so").getAbsolutePath() : "/system/lib64/libvulkan.so");
        DiagnosticProcess.Result p = DiagnosticProcess.run(probe, 20000, stop);
        report.put("probe_status", p.status).put("probe_exit", p.exitCode);
        if (!"finished".equals(p.status) || p.exitCode != 0) {
            report.put("status", "incomplete"); return report;
        }
        JSONObject caps = new JSONObject(p.output.trim());
        // The native probe has a fixed allowlist. Its loader path and free-form errors stay local.
        JSONObject capabilities = new JSONObject();
        for (String key : new String[] {"page_bytes", "memory_total_kib", "cpu", "devices"})
            if (caps.has(key)) capabilities.put(key, caps.get(key));
        report.put("capabilities", capabilities);
        JSONArray devices = caps.optJSONArray("devices");
        String identity = Build.MANUFACTURER + "\n" + Build.MODEL + "\n" + Build.VERSION.SDK_INT + "\n" + Build.ID + "\n" + plan.driver;
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.getJSONObject(i);
            // Hardware cohort, not a unique phone identifier. A candidate Turnip version may change.
            identity += "\n" + d.getLong("vendor_id") + ":" + d.getLong("device_id");
        }
        String deviceKey = hex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes("UTF-8")));
        report.put("device_key", deviceKey);
        if (plan.retest() && !plan.deviceKey.equals(deviceKey))
            throw new Exception("This request targets a different phone/firmware/GPU configuration. Run a new check and ask for an updated request.");
        if ("capabilities".equals(plan.suite)) {
            report.put("status", devices != null && devices.length() > 0 && !caps.has("vulkan_error") ? "capabilities_only" : "unavailable");
            return report;
        }
        stoppedReason = health();
        if (!stoppedReason.isEmpty()) { report.put("status", "deferred"); report.put("reason", stoppedReason); return report; }
        progress.update("Checking F32, F16, Q5 and Q6 calculations. Keep this screen open; Stop cancels the test.");
        ProcessBuilder math = process(plan.driver, "libcompatmath.so");
        math.command().add(new File(root, "backends-" + plan.driver).getAbsolutePath());
        math.command().add("cpu".equals(plan.driver) ? "CPU" : "Vulkan0");
        DiagnosticProcess.Result result = DiagnosticProcess.run(math, CompatibilityPlan.PROCESS_TIMEOUT_MS, new DiagnosticProcess.Stop() {
            public boolean requested() {
                if (stop.requested()) return true;
                long now = android.os.SystemClock.elapsedRealtime();
                if (now >= nextHealthCheck) { nextHealthCheck = now + 1000; stoppedReason = health(); }
                return !stoppedReason.isEmpty();
            }
        });
        JSONArray tests = new JSONArray();
        String lastStarted = "";
        java.util.HashSet<String> passed = new java.util.HashSet<String>();
        for (String line : result.output.split("\n")) if (line.startsWith("KYTEST ")) {
            JSONObject item = new JSONObject(line.substring(7));
            String test = item.getString("test"), status = item.getString("status");
            if (!test.matches("(f32|f16|q5_K|q6_K)_(1|8)|backend")) throw new Exception("Invalid native test result");
            if (!status.matches("started|pass|fail|unsupported|unavailable")) throw new Exception("Invalid native test status");
            if (tests.length() >= 20) throw new Exception("Too many native results");
            if ("started".equals(status)) lastStarted = test;
            if (!"started".equals(status)) tests.put(item);
            if ("pass".equals(status) && item.optBoolean("finite", false)) passed.add(test);
        }
        report.put("tests", tests).put("process_status", result.status).put("exit_code", result.exitCode);
        report.put("last_started", lastStarted).put("native_errors", new JSONArray(result.errors));
        boolean pass = "finished".equals(result.status) && result.exitCode == 0 && passed.size() == 8 && tests.length() == 8;
        report.put("status", pass ? "math_pass" : "finished".equals(result.status) ? "math_failed" : "incomplete");
        if (!stoppedReason.isEmpty()) report.put("reason", stoppedReason);
        return report;
    }
    private void link(File directory, String name, String source) throws Exception {
        File target = new File(directory, name); target.delete();
        android.system.Os.symlink(new File(lib, source).getAbsolutePath(), target.getAbsolutePath());
    }
    private ProcessBuilder process(String driver, String executable) throws Exception {
        File backends = new File(root, "backends-" + driver), vk = new File(root, "turnip");
        if (!backends.isDirectory() && !backends.mkdirs()) throw new Exception("Cannot prepare tests");
        File[] files = lib.listFiles();
        if (files != null) for (File f : files) if (f.getName().startsWith("libkycpu-") && f.getName().endsWith(".so"))
            link(backends, "libggml-cpu-" + f.getName().substring("libkycpu-".length()), f.getName());
        if (!"cpu".equals(driver)) link(backends, "libggml-vulkan.so", "libkyvulkan.so");
        ProcessBuilder pb = new ProcessBuilder(new File(lib, executable).getAbsolutePath());
        pb.directory(root);
        Map<String, String> env = pb.environment();
        for (String key : new ArrayList<String>(env.keySet()))
            if (key.startsWith("GGML_") || key.startsWith("VK_") || key.startsWith("TU_") || key.startsWith("LD_") || "YUE_BACKEND_DIR".equals(key)) env.remove(key);
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        if ("cpu".equals(driver)) env.put("GGML_DISABLE_VULKAN", "1");
        if ("turnip".equals(driver)) {
            if (!new File("/dev/kgsl-3d0").exists()) throw new Exception("Bundled Turnip needs a Qualcomm KGSL device. Choose System driver.");
            if (!vk.isDirectory() && !vk.mkdirs()) throw new Exception("Cannot prepare bundled driver");
            link(vk, "libvulkan.so", "libvkloader.so"); link(vk, "libz.so.1", "libkz.so");
            link(vk, "libzstd.so.1", "libkzstd.so"); link(vk, "libexpat.so.1", "libkexpat.so");
            JSONObject icd = new JSONObject().put("file_format_version", "1.0.1").put("ICD", new JSONObject()
                .put("library_path", new File(lib, "libturnip.so").getAbsolutePath()).put("api_version", "1.4.0"));
            File icdFile = new File(vk, "turnip.json");
            try (FileOutputStream output = new FileOutputStream(icdFile)) { output.write(icd.toString().getBytes("UTF-8")); }
            env.put("LD_LIBRARY_PATH", vk.getAbsolutePath() + ":" + lib.getAbsolutePath());
            env.put("VK_DRIVER_FILES", icdFile.getAbsolutePath()); env.put("VK_ICD_FILENAMES", icdFile.getAbsolutePath());
        }
        return pb;
    }
}
