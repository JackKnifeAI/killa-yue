package ai.jackknife.killayue;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * The wake-up song: an alarm that plays the newest song in the library, which after a night
 * of the queue running is the last song it made.
 *
 * It is a real alarm clock to Android (the alarm icon shows in the status bar and the time is
 * exact). Alarms are forgotten when the phone restarts, so this receiver sets it again then.
 */
public class Wake extends BroadcastReceiver {
    static final int SNOOZE_MIN = 9;

    static SharedPreferences cfg(Context c) {
        return c.getSharedPreferences("cfg", 0);
    }

    static boolean on(Context c) {
        return cfg(c).getBoolean("wake_on", false);
    }

    /** "7:05 every morning", "7:05 tomorrow", or "" when off. */
    static String describe(Context c) {
        SharedPreferences s = cfg(c);
        if (!s.getBoolean("wake_on", false)) return "";
        int h = s.getInt("wake_h", 7), m = s.getInt("wake_m", 0);
        return h + ":" + (m < 10 ? "0" : "") + m + (s.getBoolean("wake_daily", true) ? " every morning" : " once");
    }

    /** Turns the alarm on at the given time, once or every day. */
    static void set(Context c, int hour, int minute, boolean daily) {
        cfg(c).edit().putBoolean("wake_on", true).putInt("wake_h", hour).putInt("wake_m", minute)
                .putBoolean("wake_daily", daily).apply();
        at(c, next(hour, minute));
    }

    static void off(Context c) {
        cfg(c).edit().putBoolean("wake_on", false).apply();
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(operation(c));
    }

    /** The next time the clock shows hour:minute. */
    static long next(int hour, int minute) {
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, hour);
        k.set(Calendar.MINUTE, minute);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis() + 1000) k.add(Calendar.DAY_OF_MONTH, 1);
        return k.getTimeInMillis();
    }

    /** Sets the alarm for an exact moment (used for the daily time and for snoozing). */
    static void at(Context c, long when) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent show = PendingIntent.getActivity(c, 8, new Intent(c, MainActivity.class), 0x04000000);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), operation(c));
    }

    /** After the alarm rang: the next morning's, or off if it was a one-off. */
    static void rang(Context c) {
        SharedPreferences s = cfg(c);
        if (!s.getBoolean("wake_on", false)) return;
        if (s.getBoolean("wake_daily", true)) at(c, next(s.getInt("wake_h", 7), s.getInt("wake_m", 0)));
        else s.edit().putBoolean("wake_on", false).apply();
    }

    private static PendingIntent operation(Context c) {
        Intent i = new Intent(c, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 7, i, 0x04000000 | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context c, Intent in) {
        // the phone restarted or the app was updated: both forget alarms and stop the host
        SharedPreferences s = cfg(c);
        if (s.getBoolean("wake_on", false)) at(c, next(s.getInt("wake_h", 7), s.getInt("wake_m", 0)));
        // hosting left on comes back by itself after a restart or an update
        HammerService.resume(c);
    }
}
