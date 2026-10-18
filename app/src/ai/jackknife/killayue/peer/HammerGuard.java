package ai.jackknife.killayue.peer;

import java.util.*;

/** Bounded admission before TLS work. Peer identity cannot bypass a device-wide budget. */
public final class HammerGuard {
    private static final class Budget {
        double tokens = 128;
        long time;
        int active;
        Budget(long now) { time = now; }
        boolean spend(long now, double rate, double burst) {
            tokens = Math.min(burst, tokens + Math.max(0, now - time) / 1e9 * rate);
            time = now;
            if (tokens < 1)
                return false;
            tokens--;
            return true;
        }
    }
    private final LinkedHashMap<String, Budget> clients = new LinkedHashMap<String, Budget>();
    private final Budget global = new Budget(System.nanoTime());
    public synchronized boolean enter(String address) { return enter(address, System.nanoTime()); }
    synchronized boolean enter(String address, long now) {
        Budget b = clients.get(address);
        if (b == null) {
            for (Iterator<Budget> it = clients.values().iterator(); it.hasNext();) {
                Budget old = it.next();
                if (old.active == 0 && now - old.time > 300000000000L)
                    it.remove();
            }
            if (clients.size() >= 256)
                return false;
            b = new Budget(now);
            clients.put(address, b);
        }
        if (global.active >= 22 || b.active >= 6)
            return false;
        if (!global.spend(now, 48, 128) || !b.spend(now, 24, 128))
            return false;
        global.active++;
        b.active++;
        return true;
    }
    public synchronized void leave(String address) {
        Budget b = clients.get(address);
        if (b != null && b.active > 0) {
            b.active--;
            global.active--;
        }
    }
}
