package ai.jackknife.killayue;

import android.content.Context;
import ai.jackknife.killayue.peer.*;
import java.util.*;

/**
 * The phone's side of keeping (HammerKeeping): which helpers are online now, and tending in the background after
 * Discover looks around and every half hour while hosting. Only the artist's switch on their page sends anything;
 * the rest only watches which helpers stay online, so there are proven ones to choose when the artist asks.
 */
final class Keeping {
    private Keeping() {}
    private static HammerKeeping keeping;
    private static volatile boolean busy;
    private static long last;
    /** Told when a tend has finished, to show the page again. */
    static volatile Runnable changed;

    static synchronized HammerKeeping get(Context c) throws Exception {
        if (keeping == null)
            keeping = new HammerKeeping(HammerAndroid.store(c).root);
        return keeping;
    }

    /** Always-on computer helpers this phone can reach now, by HAMMER pin: the mix nodes it has met. */
    static Map<String, Hammer.Card> online() {
        Map<String, Hammer.Card> out = new HashMap<String, Hammer.Card>();
        if (!KillaNet.running())
            return out;
        Set<String> mix = new HashSet<String>(KillaNet.mixNodes());
        for (KillaNet.Peer p : KillaNet.peers())
            if (mix.contains(p.id))
                try {
                    out.put(p.pin, Hammer.Card.p2p(p.id, p.pin, ""));
                } catch (Exception unusable) {
                }
        return out;
    }

    /** Tends in the background, at most every ten minutes unless {@code now}. */
    static void tend(final Context context, boolean now) {
        final Context c = context.getApplicationContext();
        synchronized (Keeping.class) {
            if (busy || (!now && System.currentTimeMillis() - last < 10 * 60 * 1000))
                return;
            busy = true;
            last = System.currentTimeMillis();
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    HammerStore store = HammerAndroid.store(c);
                    String network = HammerAndroid.network(c);
                    Hammer.Card own = Hammer.Card.p2p(network, HammerAndroid.pin(), "");
                    for (int i = 0; i < 60 && online().isEmpty(); i++)  // helpers are met in the first half minute
                        Thread.sleep(500);
                    String done = get(c).tend(store, HammerAndroid.identity(), own, online(), new Hammer.Job());
                    if (!done.isEmpty())
                        EngineService.addLog("[Network] " + done);
                } catch (Exception e) {
                    EngineService.addLog("[Network] Keeping: " + e.getMessage());
                } finally {
                    busy = false;
                    Runnable r = changed;
                    if (r != null)
                        r.run();
                }
            }
        }, "hammer-keeping").start();
    }
}
