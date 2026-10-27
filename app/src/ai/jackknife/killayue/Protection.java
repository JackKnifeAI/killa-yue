package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

/**
 * Protection (experimental): votes and comments go through the mixnet, and while the app is open the phone sends
 * steady cover traffic so real messages hide among fake ones. Off by default. Nothing says it protects anyone until an
 * outside security review; see docs/designs/2026-10-24-mixnet-and-hidden-artists.md.
 */
final class Protection {
    private Protection() {}

    /** The cover clock while the app is open: one packet every this many ms on average, on Wi-Fi and on mobile data. */
    static final int COVER_WIFI = 2000, COVER_MOBILE = 5000;

    private static int visible;
    private static boolean watching;

    static boolean on(Context c) {
        return c.getSharedPreferences("hammer", Context.MODE_PRIVATE).getBoolean("protection", false);
    }

    static void set(Context c, boolean on) {
        c.getSharedPreferences("hammer", Context.MODE_PRIVATE).edit().putBoolean("protection", on).apply();
        refresh(c);
    }

    /** Counts the app's activities on screen, so cover traffic runs only while the app is open. */
    static synchronized void watch(Context c) {
        if (watching)
            return;
        watching = true;
        ((Application)c.getApplicationContext()).registerActivityLifecycleCallbacks(
            new Application.ActivityLifecycleCallbacks() {
                public void onActivityStarted(Activity a) {
                    synchronized (Protection.class) { visible++; }
                    refresh(a);
                }
                public void onActivityStopped(Activity a) {
                    synchronized (Protection.class) { visible = Math.max(0, visible - 1); }
                    refresh(a);
                }
                public void onActivityCreated(Activity a, Bundle b) {}
                public void onActivityResumed(Activity a) {}
                public void onActivityPaused(Activity a) {}
                public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                public void onActivityDestroyed(Activity a) {}
            });
    }

    /** Starts, changes or stops the cover clock: on Protection, the app being open, and Wi-Fi or mobile data. */
    static void refresh(Context c) {
        boolean open;
        synchronized (Protection.class) { open = visible > 0; }
        if (!KillaNet.running())
            return;
        if (!on(c) || !open) {
            KillaNet.mixCover(0);
            return;
        }
        boolean metered = true;
        try {
            metered = ((android.net.ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE))
                          .isActiveNetworkMetered();
        } catch (Exception keepMetered) {
        }
        KillaNet.mixCover(metered ? COVER_MOBILE : COVER_WIFI);
    }

    /** One line for the settings: what the mixnet can use now, in plain numbers. */
    static String status(Context c) {
        if (!KillaNet.running())
            return "The Killa YuE network is off.";
        long[] n = HammerMix.look();
        if (n == null)
            return "The mixnet is not running.";
        StringBuilder s = new StringBuilder();
        s.append(n[0]).append(n[0] == 1 ? " mix node" : " mix nodes").append(" on ").append(n[1])
            .append(n[1] == 1 ? " independent network" : " independent networks");
        if (n[1] < HammerMix.NETWORKS)
            s.append(": too few for a protected send (").append(HammerMix.NETWORKS).append(" needed)");
        else
            s.append(": ready");
        if (on(c))
            s.append(n[3] != 0 ? " · cover traffic on" : " · cover traffic off while the app is closed");
        if (n[4] > 0)
            s.append(" · cover loops back ").append(n[5]).append(" of ").append(n[4]);
        return s.toString();
    }

    /** What Protection hides and what it does not, shown under the switch. */
    static final String TEXT =
        "Experimental, and not yet reviewed by outside security experts: do not rely on it where your safety "
        + "depends on it.\n\n"
        + "When it is on, your votes and comments travel through three helpers (the mixnet), each holding them a "
        + "random moment, sealed so that only the artist's host can read them. The host learns what you sent, never "
        + "your internet address, and no single helper sees both you and where it goes. While the app is open, the "
        + "phone sends a steady trickle of fake packets so that real ones do not stand out (a few megabytes an hour "
        + "on mobile data, measured on a Galaxy S25); this stops when the app is closed.\n\n"
        + "What it does not hide: listening. Songs still stream directly or through a relay, so the host you play "
        + "from sees your address, and a vote right after you play may be linked to you by its timing. It does not "
        + "hide that you use Killa YuE. A comment carries your name and your profile key, so the host knows which "
        + "profile wrote it (votes use a separate key for every song). The mixnet hides you only among the people "
        + "using it at the same time, and anyone who runs most of the helpers can see through it; a protected send "
        + "needs helpers on at least three independent networks. Spyware on the phone sees everything.\n\n"
        + "When a protected send cannot go, nothing is sent directly instead: you are told why.";
}
