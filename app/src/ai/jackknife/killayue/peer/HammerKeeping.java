package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.KeyPair;
import java.util.*;

/**
 * The artist's side of keeping: when the artist turns it on, three helpers that have proven themselves by staying
 * online keep copies of their songs, so the songs stay on the network while the phone sleeps. Nothing is copied
 * anywhere without the artist asking, and turning it off takes every copy back.
 *
 * Which helpers count as proven is this phone's own judgment, never a helper's claim: every hour it looks at which
 * helpers are online and remembers, per helper, how often it found it there and since when. A helper seen for three
 * days and online at four looks in five is proven. Copies go to proven helpers first; while there aren't three, the
 * steadiest others are asked and later swapped for proven ones. A carrier that stops answering for two days is
 * replaced and, when it comes back, told to drop its copies. See docs/designs/2026-11-05-keeping.md.
 */
public final class HammerKeeping {
    public static final int COPIES = 3;
    static final long HOUR = 3600L * 1000, DAY = 24 * HOUR;
    /** Proven: seen at least this long ago, at this share of this phone's looks, over at least this many looks. */
    static final long PROVEN_AGE = 3 * DAY;
    static final double PROVEN_SHARE = 0.8;
    static final int PROVEN_LOOKS = 6;
    /** Older looks fade: past this many, a helper's counts are halved, so how it behaves lately counts most. */
    static final int LOOKS_KEPT = 240;
    /** How often carriers holding everything are reminded, which renews the request on their side. */
    static final long RENEW = 12 * HOUR;
    /** A carrier not answering for this long is replaced. */
    static final long GONE = 2 * DAY;
    /** A helper that refused (older software, full, or keeping turned off) isn't asked again for this long. */
    static final long REFUSED = DAY;

    private final File file;
    private final Properties p = new Properties();
    /** What happened last, in words for the artist. */
    public volatile String said = "";
    /** For tests: what counts as now. */
    long now() { return clock != null ? clock[0] : System.currentTimeMillis(); }
    long[] clock;

    public HammerKeeping(File root) {
        file = new File(root, "keeping.properties");
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (IOException none) {
        }
    }

    private void save() {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            p.store(b, "Killa YuE: helpers keeping this artist's songs");
            HammerStore.atomic(file, b.toByteArray());
        } catch (IOException ignored) {
        }
    }

    private long num(String key) {
        try {
            return Long.parseLong(p.getProperty(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public synchronized boolean on() { return "1".equals(p.getProperty("on")); }

    /** The artist's choice. Turning off takes every copy back at the next tend. */
    public synchronized void turn(boolean on) {
        p.setProperty("on", on ? "1" : "0");
        advance();  // newer than anything a helper holds, so the take-back (or the new ask) is heard
        save();
    }

    // ---------- watching helpers ----------

    /** One helper as this phone has seen it: {first seen, looks since, times online, last online}. */
    private long[] watch(String pin) {
        String[] f = p.getProperty("w." + pin, "").split(",");
        long[] w = new long[4];
        if (f.length == 4)
            for (int i = 0; i < 4; i++)
                try {
                    w[i] = Long.parseLong(f[i]);
                } catch (NumberFormatException ignored) {
                }
        return w;
    }

    private void watch(String pin, long[] w) {
        p.setProperty("w." + pin, w[0] + "," + w[1] + "," + w[2] + "," + w[3]);
    }

    /**
     * Notes which helpers are online now, at most once an hour. {@code online}: helpers' HAMMER pins to their cards.
     */
    public synchronized void look(Map<String, Hammer.Card> online) {
        long now = now();
        if (now - num("looked") < HOUR - 60000)
            return;
        p.setProperty("looked", String.valueOf(now));
        Set<String> pins = new HashSet<String>(online.keySet());
        for (String k : p.stringPropertyNames())
            if (k.startsWith("w."))
                pins.add(k.substring(2));
        for (String pin : pins) {
            long[] w = watch(pin);
            if (w[0] == 0)
                w[0] = now;
            w[1]++;
            if (online.containsKey(pin)) {
                w[2]++;
                w[3] = now;
                p.setProperty("card." + pin, online.get(pin).encode());
            }
            if (w[1] > LOOKS_KEPT) {
                w[1] /= 2;
                w[2] /= 2;
            }
            if (w[2] == 0 && now - w[0] > 30 * DAY) {  // a helper not seen in a month is forgotten
                p.remove("w." + pin);
                p.remove("card." + pin);
                continue;
            }
            watch(pin, w);
        }
        save();
    }

    /** The share of this phone's looks a helper was online at. */
    synchronized double share(String pin) {
        long[] w = watch(pin);
        return w[1] == 0 ? 0 : (double)w[2] / w[1];
    }

    public synchronized boolean proven(String pin) {
        long[] w = watch(pin);
        return w[0] > 0 && now() - w[0] >= PROVEN_AGE && w[1] >= PROVEN_LOOKS && share(pin) >= PROVEN_SHARE;
    }

    /** Helpers online now, best first: proven before not, then by share of looks online, then the longest known. */
    private List<String> ranked(final Map<String, Hammer.Card> online) {
        List<String> out = new ArrayList<String>(online.keySet());
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) {
                if (proven(a) != proven(b))
                    return proven(a) ? -1 : 1;
                int s = Double.compare(share(b), share(a));
                return s != 0 ? s : Long.compare(watch(a)[0], watch(b)[0]);
            }
        });
        return out;
    }

    // ---------- carriers ----------

    private List<String> list(String key) {
        List<String> out = new ArrayList<String>();
        for (String s : p.getProperty(key, "").split(","))
            if (Hammer.digest(s.trim()))
                out.add(s.trim());
        return out;
    }

    private void list(String key, Collection<String> v) {
        StringBuilder b = new StringBuilder();
        for (String s : v)
            b.append(b.length() == 0 ? "" : ",").append(s);
        p.setProperty(key, b.toString());
    }

    /** One carrier: {songs it holds, last answered, revision it holds, first asked}. */
    private long[] carrier(String pin) {
        String[] f = p.getProperty("c." + pin, "").split(",");
        long[] c = new long[4];
        if (f.length == 4)
            for (int i = 0; i < 4; i++)
                try {
                    c[i] = Long.parseLong(f[i]);
                } catch (NumberFormatException ignored) {
                }
        return c;
    }

    private void carrier(String pin, long[] c) {
        p.setProperty("c." + pin, c[0] + "," + c[1] + "," + c[2] + "," + c[3]);
    }

    /** What the artist page shows. */
    public static final class Status {
        public boolean on;
        public int songs, streamingOnly, over, helpersOnline, provenOnline;
        /** Per carrier: songs held, and whether it's proven. */
        public List<int[]> carriers = new ArrayList<int[]>();
        public String said = "";
        /** Copies complete: carriers holding every song. */
        public int whole() {
            int n = 0;
            for (int[] c : carriers)
                if (c[0] >= songs && songs > 0)
                    n++;
            return n;
        }
    }

    public synchronized Status status(HammerStore store, String key, Map<String, Hammer.Card> online) {
        Status s = new Status();
        s.on = on();
        int[] counts = new int[2];
        s.songs = songs(store, key, counts).size();
        s.streamingOnly = counts[0];
        s.over = counts[1];
        s.helpersOnline = online.size();
        for (String pin : online.keySet())
            if (proven(pin))
                s.provenOnline++;
        for (String pin : list("carriers"))
            s.carriers.add(new int[] {(int)carrier(pin)[0], proven(pin) ? 1 : 0});
        s.said = said;
        return s;
    }

    /**
     * The songs to keep: this artist's public songs that allow rehosting, most played first, at most HammerKeep.SONGS.
     * {@code counts} gets how many were left out as streaming-only and as over the limit.
     */
    static List<String> songs(HammerStore store, String key, int[] counts) {
        final Map<String, long[]> stats = new HashMap<String, long[]>();
        try {
            Map<String, long[]> all = store.allStats("");
            if (all != null)
                stats.putAll(all);
        } catch (Exception uncounted) {
        }
        List<HammerStore.Hosted> mine = new ArrayList<HammerStore.Hosted>();
        for (HammerStore.Hosted h : store.hosted())
            if (h.publicSong && h.song.key.equals(key)) {
                if (h.song.reshare)
                    mine.add(h);
                else
                    counts[0]++;
            }
        Collections.sort(mine, new Comparator<HammerStore.Hosted>() {
            public int compare(HammerStore.Hosted a, HammerStore.Hosted b) {
                long[] x = stats.get(a.song.id), y = stats.get(b.song.id);
                int c = Long.compare(y == null ? 0 : y[0], x == null ? 0 : x[0]);
                return c != 0 ? c : a.song.id.compareTo(b.song.id);
            }
        });
        List<String> out = new ArrayList<String>();
        for (HammerStore.Hosted h : mine)
            if (out.size() < HammerKeep.SONGS)
                out.add(h.song.id);
            else
                counts[1]++;
        return out;
    }

    /** Asks one helper, signing a request meant only for it. Returns the songs it holds, or throws its refusal. */
    private List<String> ask(String pin, Hammer.Card card, List<String> songs, KeyPair identity, Hammer.Card own,
                             Hammer.Job job) throws Exception {
        HammerKeep k = new HammerKeep();
        k.helper = pin;
        k.from = own.encode();
        k.revision = num("revision");
        k.songs = new ArrayList<String>(songs);
        k.stamp = num("stamp");
        k.sign(identity);
        return Hammer.keep(card, k, job);
    }

    /** A new revision, newer than any a helper holds. */
    private void advance() {
        p.setProperty("revision", String.valueOf(Math.max(now(), num("revision") + 1)));
    }

    /**
     * Does what keeping needs now, in the background, after Discover looks around: notes the helpers online, sends
     * changed song lists, fills up to three copies on the best helpers, swaps steadier helpers in, replaces ones gone
     * two days, reminds carriers twice a day, and takes copies back from helpers no longer used or when the artist
     * turned keeping off. Returns what happened, in words, or "" when nothing needed doing.
     */
    public String tend(HammerStore store, KeyPair identity, Hammer.Card own, Map<String, Hammer.Card> online,
                       Hammer.Job job) {
        String key;
        List<String> songs;
        synchronized (this) {
            look(online);
            key = Hammer.publicKey(identity.getPublic());
            songs = songs(store, key, new int[2]);
            if (!list("carriers").isEmpty() || !list("retired").isEmpty() || on()) {
                if (!String.join(",", songs).equals(p.getProperty("songs", "")) || num("revision") == 0) {
                    p.setProperty("songs", String.join(",", songs));
                    advance();
                }
                save();
            }
        }
        if (on() && !songs.isEmpty() && num("stamp") == 0 && !p.containsKey("stamped"))
            try {  // paid once for this artist: a few seconds of work
                long n = HammerHidden.stamp(Hammer.hash(identity.getPublic().getEncoded()), job);
                synchronized (this) {
                    p.setProperty("stamp", String.valueOf(n));
                    p.setProperty("stamped", "1");
                    save();
                }
            } catch (Exception cancelled) {
                return "";
            }
        List<String> done = new ArrayList<String>();
        // copies to take back: every carrier when keeping is off or nothing is left to keep, and retired ones
        List<String> back = new ArrayList<String>(list("retired"));
        if (!on() || songs.isEmpty())
            back.addAll(list("carriers"));
        for (String pin : back) {
            if (job.cancelled)
                return "";
            Hammer.Card card = cardOf(pin, online);
            boolean gone = false;
            if (card != null)
                try {
                    ask(pin, card, Collections.<String>emptyList(), identity, own, job);
                    gone = true;
                } catch (Exception unreachable) {
                }
            synchronized (this) {
                long[] c = carrier(pin);
                if (gone || now() - Math.max(c[1], c[3]) > HammerKeeper.KEEP_FOR) {  // past that it let go by itself
                    removeFrom("carriers", pin);
                    removeFrom("retired", pin);
                    p.remove("c." + pin);
                    if (gone)
                        done.add("A helper dropped its copies");
                }
                save();
            }
        }
        if (!on() || songs.isEmpty()) {
            said = list("carriers").isEmpty() ? (on() ? "" : "No helper keeps your songs.") : "Taking your songs back…";
            return String.join(". ", done);
        }
        // fill and improve: up to three copies, proven helpers first
        List<String> carriers = list("carriers");
        long now = now();
        for (String pin : new ArrayList<String>(carriers)) {
            long[] c = carrier(pin);
            if (now - Math.max(c[1], c[3]) > GONE) {  // not answering for two days: replaced, told later to drop
                retire(pin);
                carriers.remove(pin);
                done.add("A helper stopped answering, so another is asked");
            }
        }
        List<String> ranked = ranked(online);
        for (String pin : ranked) {
            if (carriers.size() >= COPIES || job.cancelled)
                break;
            if (!carriers.contains(pin) && now - num("refused." + pin) > REFUSED)
                carriers.add(pin);
        }
        // a proven helper not yet carrying takes the place of one that isn't proven, once its copy is whole
        String better = null, worse = null;
        for (String pin : ranked)
            if (proven(pin) && !carriers.contains(pin) && now - num("refused." + pin) > REFUSED) {
                better = pin;
                break;
            }
        for (String pin : carriers)
            if (!proven(pin))
                worse = pin;
        if (better != null && worse != null && carriers.size() >= COPIES)
            carriers.add(better);
        synchronized (this) {
            list("carriers", carriers);
            save();
        }
        int whole = 0;
        for (String pin : carriers) {
            if (job.cancelled)
                return "";
            Hammer.Card card = cardOf(pin, online);
            long[] c = carrier(pin);
            if (c[3] == 0)
                c[3] = now;
            boolean due = c[2] != num("revision") || c[0] < songs.size() || now - c[1] > RENEW;
            if (card != null && due)
                try {
                    List<String> held = ask(pin, card, songs, identity, own, job);
                    c[0] = held.size();
                    c[1] = now();
                    c[2] = num("revision");
                    p.remove("fail." + pin);
                } catch (Exception refused) {
                    // online yet failing three times running: a helper from before 1.8, which just hangs up
                    long fails = num("fail." + pin) + (online.containsKey(pin) ? 1 : 0);
                    p.setProperty("fail." + pin, String.valueOf(fails));
                    if (refused instanceof Hammer.Refused || (fails >= 3 && c[0] == 0)) {
                        p.remove("fail." + pin);
                        synchronized (this) {  // a refusal, not an outage: ask someone else
                            p.setProperty("refused." + pin, String.valueOf(now));
                            removeFrom("carriers", pin);
                            p.remove("c." + pin);
                            save();
                        }
                        continue;
                    }
                }
            if (c[0] >= songs.size())
                whole++;
            synchronized (this) {
                if (list("carriers").contains(pin))
                    carrier(pin, c);
                save();
            }
        }
        // swapped in and whole: the unproven one steps back
        if (better != null && worse != null && carrier(better)[0] >= songs.size() && list("carriers").size() > COPIES) {
            retire(worse);
            done.add("Your songs moved to a helper that has stayed online longer");
        }
        int copies = list("carriers").size();
        said = whole >= COPIES ? "Kept by " + whole + " helpers"
             : whole > 0 ? "Kept by " + whole + (whole == 1 ? " helper" : " helpers")
                           + (copies > whole ? ", " + (copies - whole) + " more copying" : "")
             : copies > 0 ? "Helpers are copying your songs"
             : online.isEmpty() ? "No helper is online right now. Waiting for one."
             : "No helper took your songs yet";
        return String.join(". ", done);
    }

    private synchronized void retire(String pin) {
        removeFrom("carriers", pin);
        if (carrier(pin)[1] == 0) {  // never answered: it holds nothing to take back
            p.remove("c." + pin);
            p.setProperty("refused." + pin, String.valueOf(now()));
            save();
            return;
        }
        List<String> r = list("retired");
        if (!r.contains(pin))
            r.add(pin);
        list("retired", r);
        advance();  // the take-back is newer than anything it holds
        save();
    }

    private synchronized void removeFrom(String key, String pin) {
        List<String> l = list(key);
        l.remove(pin);
        list(key, l);
    }

    private synchronized Hammer.Card cardOf(String pin, Map<String, Hammer.Card> online) {
        if (online.containsKey(pin))
            return online.get(pin);
        try {
            return Hammer.Card.parse(p.getProperty("card." + pin, ""));
        } catch (Exception unknown) {
            return null;
        }
    }
}
