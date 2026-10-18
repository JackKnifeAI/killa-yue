package ai.jackknife.killayue.peer;

import java.util.*;

/** Bounded public routing hints. Not an authority over artists or a copy of private invitations. */
public final class HammerDirectory {
    private final LinkedHashMap<String, HammerPresence> nodes = new LinkedHashMap<String, HammerPresence>();
    public synchronized void announce(HammerPresence p) throws Exception {
        p.validate();
        expire();
        HammerPresence old = nodes.get(p.card.pin);
        if (old != null && old.expires >= p.expires)
            return;
        Hammer.require(old != null || nodes.size() < 128, "Public directory full");
        nodes.put(p.card.pin, p);
    }
    private void expire() {
        long now = System.currentTimeMillis();
        for (Iterator<HammerPresence> i = nodes.values().iterator(); i.hasNext();)
            if (i.next().expires <= now)
                i.remove();
    }
    public synchronized List<HammerPresence> list() {
        expire();
        return new ArrayList<HammerPresence>(nodes.values());
    }
}
