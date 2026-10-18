package ai.jackknife.killayue.peer;

import java.util.*;

/** Local load distribution across copies the listener has already authenticated. No routing service. */
public final class HammerRoutes {
    private HammerRoutes() {}
    private static final Map<String, Health> health = new HashMap<String, Health>();
    private static final class Health {
        double millis = 300;
        int active;
        long retryAfter;
    }
    private static Health state(Hammer.Card p) {
        String id = HammerStore.peerId(p);
        Health h = health.get(id);
        if (h == null) {
            if (health.size() > 128)
                health.clear();
            h = new Health();
            health.put(id, h);
        }
        return h;
    }
    public static synchronized List<Hammer.Card> order(List<Hammer.Card> peers) {
        List<Hammer.Card> out = new ArrayList<Hammer.Card>(peers);
        Collections.shuffle(out);
        Collections.sort(out, new Comparator<Hammer.Card>() {
            public int compare(Hammer.Card a, Hammer.Card b) {
                return Double.compare(score(state(a)), score(state(b)));
            }
        });
        return out;
    }
    private static double score(Health h) {
        return h.millis + h.active * 500 + (h.retryAfter > System.nanoTime() ? 60000 : 0);
    }
    public static synchronized void begin(Hammer.Card p) {
        state(p).active++;
    }
    public static synchronized void end(Hammer.Card p, long millis, boolean ok) {
        Health h = state(p);
        h.active = Math.max(0, h.active - 1);
        if (ok) {
            h.millis = h.millis * .7 + millis * .3;
            h.retryAfter = 0;
        } else {
            h.retryAfter = System.nanoTime() + 30000000000L;
            h.millis = Math.max(h.millis, 2000);
        }
    }
}
