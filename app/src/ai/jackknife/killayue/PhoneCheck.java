package ai.jackknife.killayue;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.util.Locale;

/**
 * What this phone can do, worked out without running anything heavy.
 *
 * The bundled GPU driver (Mesa Turnip) drives Qualcomm Adreno GPUs from the 600 series on,
 * through the kgsl kernel driver; on any other GPU the engine has to use the processor.
 * The cover painter needs the Hexagon NPU of the Snapdragon 8 Elite (SM8750).
 */
public class PhoneCheck {
    static String read(String path) {
        try {
            return new String(Store.read(new File(path)), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** "Adreno830v2", or "" when the phone has no Adreno GPU (or will not say). */
    static String gpuModel() {
        String m = read("/sys/class/kgsl/kgsl-3d0/gpu_model");
        if (m.length() == 0 && new File("/dev/kgsl-3d0").exists()) m = "Adreno (model unknown)";
        return m;
    }

    /** The Adreno series number (830, 740, ...), 0 if unknown, -1 if there is no Adreno GPU. */
    static int adrenoNumber() {
        String m = gpuModel();
        if (m.length() == 0) return -1;
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("(\\d{3})").matcher(m);
        return x.find() ? Integer.parseInt(x.group(1)) : 0;
    }

    /** True when our GPU driver can be expected to work (Adreno 600 series or later, or an Adreno we cannot number). */
    static boolean gpuSupported() {
        int n = adrenoNumber();
        return n == 0 || n >= 600;
    }

    static String socModel() {
        String s = "";
        try {
            if (Build.VERSION.SDK_INT >= 31) s = (String) Build.class.getField("SOC_MODEL").get(null);
        } catch (Exception e) {
            // older Android: fall back to the board name
        }
        if (s == null || s.length() == 0 || "unknown".equals(s)) s = Build.HARDWARE;
        return s == null ? "" : s;
    }

    /** True on the Snapdragon 8 Elite, whose NPU the bundled cover model was compiled for. */
    static boolean npuCovers() {
        return socModel().toUpperCase(Locale.US).startsWith("SM8750");
    }

    static long memoryGb(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return Math.round(mi.totalMem / 1073741824.0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Run once, on first launch: on a phone without a supported GPU the engine is set to the
     * processor. Returns a message for the user, or "" when the phone looks right for the app.
     */
    static String firstLaunch(Context c) {
        SharedPreferences cfg = c.getSharedPreferences("cfg", 0);
        if (cfg.getBoolean("phone_checked", false)) return "";
        cfg.edit().putBoolean("phone_checked", true).apply();
        StringBuilder b = new StringBuilder();
        if (!gpuSupported()) {
            cfg.edit().putString("device", "CPU").apply();
            String g = gpuModel();
            b.append("This phone's GPU (").append(g.length() > 0 ? g : "not a Qualcomm Adreno")
                    .append(") is not one Killa YuE's GPU driver can run yet, so songs will be made on the processor. ")
                    .append("That works, but it is very slow: expect hours for a short song.\n\n");
        }
        long gb = memoryGb(c);
        if (gb > 0 && gb < 8) {
            b.append("This phone has about ").append(gb).append(" GB of memory. Short songs should work; full-length songs need about 6 GB free ")
                    .append("and may stop. The Engine tab's memory setting can help.\n\n");
        }
        if (b.length() > 0) {
            b.append("A device report (Engine tab) posted to the project's GitHub helps add support for more phones.");
        }
        return b.toString();
    }

    /** For the GitHub device-report form. */
    static String report(Context c) {
        SharedPreferences cfg = c.getSharedPreferences("cfg", 0);
        String version = "";
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            // leave it blank
        }
        StringBuilder b = new StringBuilder();
        b.append("Killa YuE ").append(version).append('\n');
        b.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        b.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("Chip: ").append(socModel()).append('\n');
        b.append("GPU: ").append(gpuModel().length() > 0 ? gpuModel() : "not Adreno").append('\n');
        b.append("GPU clock now: ").append(read("/sys/class/kgsl/kgsl-3d0/max_gpuclk")).append('\n');
        b.append("Memory: about ").append(memoryGb(c)).append(" GB\n");
        b.append("Engine device: ").append(cfg.getString("device", "Vulkan0")).append(", gentle mode: ")
                .append(cfg.getBoolean("gentle", true) ? "on" : "off").append('\n');
        b.append("NPU covers supported: ").append(npuCovers() ? "yes" : "no").append('\n');
        SharedPreferences eta = c.getSharedPreferences("eta", 0);
        String[] keys = {"tok_n", "tok_g", "step_n", "step_g"};
        String[] names = {"writing ms/token (normal)", "writing ms/token (gentle)", "render s/step for 20 s (normal)", "render s/step for 20 s (gentle)"};
        for (int i = 0; i < keys.length; i++) {
            float v = eta.getFloat(keys[i], -1f);
            if (v > 0) b.append("Measured ").append(names[i]).append(": ").append(String.format(Locale.US, "%.1f", v)).append('\n');
        }
        String last = EngineService.lastError;
        if (last != null && last.length() > 0) b.append("Last engine error: ").append(last).append('\n');
        return b.toString();
    }
}
