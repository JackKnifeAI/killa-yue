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

    /** Told how far asking helpers has come: which song of how many, and that song's share sent. */
    public interface Asking {
        void asking(int song, int songs, int percent);
    }

    /**
     * Asks helpers to carry a station when no host shares its songs: each song goes through the mixnet to up to
     * three helpers, as a hidden artist's would, the first song to whichever helpers take it and every later one to
     * those same helpers, so they end up with all of them; then they're handed the station. Returns the PeerIds of
     * the helpers that now carry it. Helpers take at most 100 songs from one artist.
     */
    public static List<String> askHelpers(HammerStore store, HammerStation s, HammerProfile profile, final Asking asking,
                                          Hammer.Job job) throws Exception {
        Hammer.require(s.songs.size() <= 100,
                       "Helpers take up to 100 songs from one artist, and this station has " + s.songs.size()
                       + ". Go on air with an album or fewer songs.");
        String why = HammerMix.unready();
        Hammer.require(why == null, why + " Nothing was sent.");
        Map<String, HammerStore.Hosted> files = new HashMap<String, HammerStore.Hosted>();
        for (HammerStore.Hosted h : store.hosted())
            if (h.publicSong && h.song.key.equals(s.key))
                files.put(h.song.id, h);
        List<String> helpers = KillaNet.mixNodes();
        for (int i = 0; i < s.songs.size(); i++) {
            HammerStore.Hosted h = files.get(s.songs.get(i));
            Hammer.require(h != null, "A song on this station isn't shared from this phone any more");
            HammerHidden.Prepared p = HammerHidden.shared(h.file, h.song, profile, job);
            final int n = i, songs = s.songs.size();
            helpers = HammerHidden.publish(p, helpers, new HammerHidden.Progress() {
                public void progress(int done, int total, List<String> hosting) {
                    if (asking != null)
                        asking.asking(n + 1, songs, 100 * done / Math.max(1, total));
                }
            }, job);
        }
        List<String> carrying = new ArrayList<String>();
        for (String helper : helpers)
            try {
                HammerMix.station(helper, s, job);
                carrying.add(helper);  // kept now, or held this revision already
            } catch (Exception refused) {
            }
        return carrying;
    }

    /** Hands a newer revision (a note, or off air) to the helpers that carry it, through the mixnet. */
    public static int tellHelpers(HammerStation s, Collection<String> helpers, Hammer.Job job) {
        int n = 0;
        for (String helper : helpers)
            try {
                if (HammerMix.station(helper, s, job))
                    n++;
            } catch (Exception unavailable) {
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
