package ai.jackknife.killayue.peer;

import java.util.*;

/**
 * Keeps stations going without the artist: any host that shares every song on a station can carry the artist's signed
 * copy, so it stays on air while the artist's phone sleeps. Copies move two ways. A host gathers newer copies from
 * hosts it talks to, and a phone that sees a host holding an older copy, or none, hands it the newest. A new note or
 * going off air reaches every carrier the same way, as a newer revision.
 */
public final class HammerStations {
    private HammerStations() {}

    /** The newest copy of each artist's station among these. */
    public static Map<String, HammerStation> newest(Iterable<HammerStation> copies) {
        Map<String, HammerStation> out = new LinkedHashMap<String, HammerStation>();
        for (HammerStation s : copies)
            try {
                HammerStation old = out.get(s.id());
                if (old == null || s.revision > old.revision)
                    out.put(s.id(), s);
            } catch (Exception unreadableKey) {
            }
        return out;
    }

    /** Takes from a host the stations this host can carry. Returns how many were new here. */
    public static int gather(HammerPublic objects, Hammer.Card from, Hammer.Job job) throws Exception {
        int n = 0;
        for (HammerStation s : Hammer.stations(from, job))
            try {
                if (objects.carry(s))
                    n++;
            } catch (Exception refused) {
            }
        return n;
    }

    /** Hands a station to each host, skipping quietly the ones offline, older than 1.8 or without its songs. */
    public static int spread(HammerStation s, Collection<Hammer.Card> to, Hammer.Job job) {
        int n = 0;
        for (Hammer.Card card : to) {
            if (job.cancelled)
                break;
            try {
                if (Hammer.carryStation(card, s, job))
                    n++;
            } catch (Exception unavailable) {
            }
        }
        return n;
    }
}
