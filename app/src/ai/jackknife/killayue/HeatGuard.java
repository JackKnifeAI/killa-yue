package ai.jackknife.killayue;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;

import java.util.Locale;

/**
 * Cooling breaks: how hot the phone is, and whether a song should stop for a while to let it cool.
 *
 * Three readings, whichever the phone gives: Android's own thermal status (Android 10 and later), its forecast of
 * how close the phone is to throttling (thermal headroom, Android 11 and later) and the battery temperature (every
 * phone). A break is taken only between render steps, where the song's checkpoint has just been saved, so the
 * engine can stop completely and carry on from the same step once the phone is cool (EngineService.coolDown).
 */
public class HeatGuard {
    // PowerManager.THERMAL_STATUS_*: the names Android gives them
    static final int LIGHT = 1, MODERATE = 2, SEVERE = 3, CRITICAL = 4;
    private static final String[] STATUS = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};

    // battery temperatures, °C: the battery warms more slowly than the chip, so these sit below phone warnings
    static final double BATTERY_HOT = 44.0, BATTERY_CRITICAL = 47.0, BATTERY_COOL = 39.5;
    // thermal headroom: 1.0 means the phone is at the point where it throttles hard
    static final double HEADROOM_HOT = 0.95, HEADROOM_COOL = 0.75;

    /** A break lasts at least this long, so the engine is not restarted for a few seconds of cooling. */
    static final long BREAK_MIN_MS = 60_000;
    /** And at most this long: a phone that never cools (in the sun, in a case) still finishes its song. */
    static final long BREAK_MAX_MS = 15 * 60_000;
    /** After a break the song runs at least this long before the next one. */
    static final long RUN_MIN_MS = 90_000;

    /** Whether cooling breaks are on (Settings), on unless turned off. */
    static boolean on(Context c) {
        return c.getSharedPreferences("cfg", 0).getBoolean("cool_breaks", true);
    }

    static void on(Context c, boolean on) {
        c.getSharedPreferences("cfg", 0).edit().putBoolean("cool_breaks", on).apply();
    }

    /** One look at the phone's temperature. Fields the phone does not report are -1 or NaN. */
    static final class Reading {
        int status = -1;
        double headroom = Double.NaN, battery = Double.NaN;

        boolean hot() {
            return status >= SEVERE || headroom >= HEADROOM_HOT || battery >= BATTERY_HOT;
        }

        /** So hot that a break is worth even losing the song's unsaved writing. */
        boolean critical() {
            return status >= CRITICAL || battery >= BATTERY_CRITICAL;
        }

        boolean cool() {
            return status <= LIGHT && !(headroom > HEADROOM_COOL) && !(battery > BATTERY_COOL);
        }

        /** "battery 44.6 °C, Android: severe", for the log and the screen. */
        String describe() {
            StringBuilder b = new StringBuilder();
            if (!Double.isNaN(battery)) b.append(String.format(Locale.US, "battery %.1f °C", battery));
            if (status >= 0) {
                if (b.length() > 0) b.append(", ");
                b.append("Android: ").append(status < STATUS.length ? STATUS[status] : "status " + status);
            }
            if (!Double.isNaN(headroom)) {
                if (b.length() > 0) b.append(", ");
                b.append(String.format(Locale.US, "headroom %.2f", headroom));
            }
            return b.length() > 0 ? b.toString() : "no temperature readings";
        }
    }

    static Reading read(Context c) {
        Reading r = new Reading();
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            // called by name: the app is compiled against an older Android than these
            r.status = (Integer) PowerManager.class.getMethod("getCurrentThermalStatus").invoke(pm);
            float h = (Float) PowerManager.class.getMethod("getThermalHeadroom", int.class).invoke(pm, 10);
            if (h >= 0 && !Float.isNaN(h)) r.headroom = h;
        } catch (Throwable e) {
            // older Android: the battery is the reading left
        }
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int t = b == null ? Integer.MIN_VALUE : b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            // tenths of a degree; 0 and below is what phones without a sensor report
            if (t > 0 && t < 1000) r.battery = t / 10.0;
        } catch (Throwable e) {
            // no battery reading
        }
        return r;
    }

    // ---- what the last songs went through, for the device report ----

    static void noteBreak(Context c, long ms, double battery) {
        SharedPreferences p = c.getSharedPreferences("heat", 0);
        SharedPreferences.Editor e = p.edit()
                .putInt("breaks", p.getInt("breaks", 0) + 1)
                .putLong("break_ms", p.getLong("break_ms", 0) + ms)
                .putLong("last_at", System.currentTimeMillis());
        if (!Double.isNaN(battery) && battery > p.getFloat("hottest", 0f)) e.putFloat("hottest", (float) battery);
        e.apply();
    }

    /** The hottest battery reading while a song was being made, kept for the report. */
    static void noteReading(Context c, Reading r) {
        if (Double.isNaN(r.battery)) return;
        SharedPreferences p = c.getSharedPreferences("heat", 0);
        if (r.battery > p.getFloat("hottest", 0f)) p.edit().putFloat("hottest", (float) r.battery).apply();
    }

    /** Lines for the device report. */
    static String report(Context c) {
        SharedPreferences p = c.getSharedPreferences("heat", 0);
        StringBuilder b = new StringBuilder();
        b.append("Temperature now: ").append(read(c).describe()).append('\n');
        b.append("Cooling breaks: ").append(on(c) ? "on" : "off");
        int n = p.getInt("breaks", 0);
        if (n > 0) b.append(", ").append(n).append(" taken, ").append(p.getLong("break_ms", 0) / 60_000).append(" min in all");
        b.append('\n');
        float hot = p.getFloat("hottest", 0f);
        if (hot > 0) b.append(String.format(Locale.US, "Hottest battery while making songs: %.1f °C\n", hot));
        return b.toString();
    }
}
