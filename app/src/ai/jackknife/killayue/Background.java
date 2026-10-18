package ai.jackknife.killayue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * What it takes for a song to keep going while the phone does other things or sleeps.
 *
 * The engine runs in a foreground service, which keeps the app itself alive. Two things
 * can still stop a song: battery optimisation (in Doze, an unplugged phone ignores the
 * app's wake lock and the GPU work stalls) and, from Android 12, the monitor that kills
 * apps' child processes (the engine is one) when there are too many of them on the phone.
 */
public class Background {
    /** True when Android lets the app run without battery optimisation. */
    static boolean exempt(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return true; // cannot tell; do not nag
        }
    }

    /** Shows Android's own dialog for running without battery optimisation. */
    static void askExemption(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        } catch (Exception e) {
            openAppSettings(a);
        }
    }

    static void openAppSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            // nothing to open on this phone
        }
    }

    /**
     * True when Android may kill child processes such as the engine (Android 12 and later,
     * unless "Disable child process restrictions" is on in Developer options).
     */
    static boolean childLimitsOn(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            String v = Settings.Global.getString(c.getContentResolver(), "settings_enable_monitor_phantom_procs");
            return !"false".equals(v);
        } catch (Exception e) {
            return true;
        }
    }

    static void openDeveloperOptions(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            openAppSettings(a);
        }
    }

    /** One paragraph for the Settings tab. */
    static String status(Context c) {
        StringBuilder b = new StringBuilder();
        b.append(exempt(c) ? "Battery: songs keep going when the phone sleeps."
                : "Battery: Android may pause a song when the phone sleeps unplugged. Allow the app to run in the background to stop that.");
        if (childLimitsOn(c)) {
            b.append("\nAndroid limits apps' child processes, and the engine is one. If a song ever stops by itself in the background, "
                    + "turn on \"Disable child process restrictions\" in Developer options (Android 14 and later).");
        }
        return b.toString();
    }

    /** Explains an engine that was killed rather than crashed (exit by SIGKILL). */
    static String killedMessage(Context c) {
        StringBuilder b = new StringBuilder("Android stopped the engine before the song was finished (it was killed, it did not crash). "
                + "That happens when the phone runs short of memory or limits work in the background.");
        if (!exempt(c)) b.append(" Allowing Killa YuE to run in the background (Settings tab) helps.");
        if (childLimitsOn(c)) b.append(" Turning on \"Disable child process restrictions\" in Developer options helps too.");
        b.append(" A shorter song or a smaller memory setting also uses less memory.");
        return b.toString();
    }
}
