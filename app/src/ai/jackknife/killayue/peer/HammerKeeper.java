package ai.jackknife.killayue.peer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * A helper keeping copies of artists' songs at their request (HammerKeep), so the songs stay on the network while
 * the artist's phone sleeps. The helper fetches each song from the card the artist signed, the same way a listener
 * downloads it, checks it against the artist's signature, and hosts it publicly with the artist's page, covers,
 * lyrics, albums and station. A newer request that leaves a song out drops it here, and an empty one drops them all.
 * Only songs this helper fetched for the artist are ever dropped; anything its owner chose to host stays.
 * Requests are bounded: songs and bytes per artist, artists in all, bytes in all, and a proof-of-work stamp the
 * first time an artist asks. See docs/designs/2026-11-05-keeping.md.
 */
public final class HammerKeeper {
    /** A request not renewed by the artist's phone for this long is let go, songs and all. */
    public static final long KEEP_FOR = 180L * 24 * 3600 * 1000;
    private final HammerStore store;
    private final File dir;
    /** This helper's HAMMER pin: requests name it, so one meant for another helper is refused. */
    private final String self;
    public volatile boolean enabled = true;
    public volatile int artists = 32;
    public volatile long bytesPerArtist = 2L << 30, bytesInAll = 20L << 30;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "hammer-keeper");
            t.setDaemon(true);
            return t;
        }
    });
    private volatile boolean queued;
    /** Told what the keeper does, for the helper's log; may be null. */
    public interface Log {
        void said(String line);
    }
    public volatile Log log;

    public HammerKeeper(HammerStore store, File dir, String selfPin) throws IOException {
        Hammer.require(Hammer.digest(selfPin), "Invalid helper identity");
        this.store = store;
        this.dir = dir;
        this.self = selfPin;
        Hammer.require(dir.isDirectory() || dir.mkdirs(), "Cannot prepare keeping");
    }

    private void say(String line) {
        Log l = log;
        if (l != null)
            try {
                l.said(line);
            } catch (Exception ignored) {
            }
    }

    // ---------- requests ----------

    /**
     * An artist's request (op 32). Answers the songs of the artist's current request that are hosted here now, so the
     * artist's phone can show how far copying has come. An older revision changes nothing; the same one only renews.
     */
    public List<String> ask(HammerKeep k) throws Exception {
        k.validate();
        Hammer.require(enabled, "This helper doesn't keep songs for artists now");
        Hammer.require(k.helper.equals(self), "This request is meant for another helper");
        String id = k.id();
        Hammer.require(!new File(dir, id + ".refused").isFile(), "This helper's owner doesn't keep songs for this artist");
        boolean changed = false;
        synchronized (this) {
            HammerKeep old = record(id);
            if (old == null) {
                if (k.songs.isEmpty())
                    return new ArrayList<String>();  // nothing held here to take back
                Hammer.require(HammerHidden.stampOk(id, k.stamp), "The proof-of-work stamp does not match");
                Hammer.require(records().size() < artists, "This helper keeps songs for as many artists as it can");
            }
            if (old == null || k.revision > old.revision) {
                if (k.songs.isEmpty()) {
                    drop(id, Collections.<String>emptySet());
                    new File(dir, id + ".keep").delete();
                    say("Artist " + id.substring(0, 12) + " took their songs back");
                    return new ArrayList<String>();
                }
                HammerStore.atomic(new File(dir, id + ".keep"), k.bytes());
                drop(id, new HashSet<String>(k.songs));
                changed = true;
                say("Artist " + id.substring(0, 12) + " asked to keep " + k.songs.size() + " songs");
            }
            File keep = new File(dir, id + ".keep");
            if (keep.isFile())
                keep.setLastModified(System.currentTimeMillis());  // heard from the artist: renewed
            k = record(id);
        }
        if (changed)
            kick();
        return held(k);
    }

    /** The songs of this request hosted publicly here now. */
    private List<String> held(HammerKeep k) throws Exception {
        List<String> out = new ArrayList<String>();
        if (k == null)
            return out;
        Set<String> want = new HashSet<String>(k.songs);
        for (Hammer.Song s : store.catalog(""))
            if (want.contains(s.id) && s.key.equals(k.key))
                out.add(s.id);
        return out;
    }

    private synchronized HammerKeep record(String id) throws Exception {
        File f = new File(dir, id + ".keep");
        if (!f.isFile())
            return null;
        return HammerKeep.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 1 << 16))));
    }

    /** Every artist's current request here. */
    public synchronized List<HammerKeep> records() {
        List<HammerKeep> out = new ArrayList<HammerKeep>();
        File[] fs = dir.listFiles();
        if (fs != null)
            for (File f : fs)
                if (f.getName().matches("[0-9a-f]{64}\\.keep"))
                    try {
                        out.add(HammerKeep.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 1 << 16)))));
                    } catch (Exception damaged) {
                        f.delete();
                    }
        return out;
    }

    // ---------- what this helper fetched ----------

    private Set<String> heldIds(String artist) throws IOException {
        Set<String> out = new LinkedHashSet<String>();
        File f = new File(dir, artist + ".held");
        if (f.isFile())
            for (String line : new String(HammerStore.read(f, 1 << 16), StandardCharsets.US_ASCII).split("\n"))
                if (Hammer.digest(line.trim()))
                    out.add(line.trim());
        return out;
    }

    private void saveHeld(String artist, Set<String> ids) throws IOException {
        File f = new File(dir, artist + ".held");
        if (ids.isEmpty()) {
            f.delete();
            return;
        }
        StringBuilder b = new StringBuilder();
        for (String s : ids)
            b.append(s).append('\n');
        HammerStore.atomic(f, b.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** Stops hosting and deletes the songs fetched for this artist that aren't in {@code keep}. */
    private synchronized void drop(String artist, Set<String> keep) throws Exception {
        Set<String> held = heldIds(artist);
        Map<String, Hammer.Song> hosted = new HashMap<String, Hammer.Song>();
        for (HammerStore.Hosted h : store.hosted())
            hosted.put(h.song.id, h.song);
        for (Iterator<String> i = held.iterator(); i.hasNext();) {
            String id = i.next();
            if (keep.contains(id))
                continue;
            Hammer.Song s = hosted.get(id);
            store.unpublish(id);
            if (s != null)
                store.downloadFile(s).delete();
            new File(dir, id + ".part").delete();
            i.remove();
        }
        saveHeld(artist, held);
    }

    private synchronized long bytesHeld(String artist) throws Exception {
        Set<String> mine = artist == null ? null : heldIds(artist);
        Set<String> all = new HashSet<String>();
        if (mine == null) {
            File[] fs = dir.listFiles();
            if (fs != null)
                for (File f : fs)
                    if (f.getName().endsWith(".held"))
                        all.addAll(heldIds(f.getName().substring(0, f.getName().length() - 5)));
        } else
            all = mine;
        long n = 0;
        for (HammerStore.Hosted h : store.hosted())
            if (all.contains(h.song.id))
                n += h.song.size;
        return n;
    }

    // ---------- fetching ----------

    /** Runs a round soon, once, however many times it's asked. */
    public void kick() {
        if (queued)
            return;
        queued = true;
        worker.execute(new Runnable() {
            public void run() {
                queued = false;
                round(new Hammer.Job());
            }
        });
    }

    public void close() { worker.shutdownNow(); }

    /**
     * Brings every request up to date: lets go of those not renewed in KEEP_FOR, and fetches songs still missing from
     * the artist's card, with the artist's page and the rest. Run every ten minutes by the helper and after each
     * accepted request; an artist whose phone is away is tried again next round.
     */
    public void round(Hammer.Job job) {
        synchronized (rounds) {  // one round at a time, or two would download the same song into one file
            rounds(job);
        }
    }
    private final Object rounds = new Object();

    private void rounds(Hammer.Job job) {
        long now = System.currentTimeMillis();
        for (HammerKeep k : records()) {
            if (job.cancelled)
                return;
            try {
                String id = k.id();
                if (now - new File(dir, id + ".keep").lastModified() > KEEP_FOR) {
                    synchronized (this) {
                        drop(id, Collections.<String>emptySet());
                        new File(dir, id + ".keep").delete();
                    }
                    say("Let go of artist " + id.substring(0, 12) + ": not heard from in 180 days");
                    continue;
                }
                fetch(k, job);
            } catch (Exception artistAway) {
                say("Couldn't reach an artist this round: " + artistAway.getMessage());
            }
        }
    }

    private void fetch(HammerKeep k, Hammer.Job job) throws Exception {
        String id = k.id();
        Set<String> have = new HashSet<String>(held(k));
        Hammer.Card from = k.fromCard();
        boolean fetched = false;
        if (have.size() < k.songs.size()) {
            Set<String> want = new HashSet<String>(k.songs);
            for (Hammer.Song s : Hammer.catalog(from, job)) {
                if (job.cancelled)
                    return;
                if (!want.contains(s.id) || have.contains(s.id) || !s.key.equals(k.key) || !s.reshare)
                    continue;
                synchronized (this) {
                    HammerKeep now = record(id);  // taken back or changed while this round ran
                    if (now == null || !now.songs.contains(s.id))
                        continue;
                }
                if (bytesHeld(id) + s.size > bytesPerArtist || bytesHeld(null) + s.size > bytesInAll) {
                    say("No room for more of artist " + id.substring(0, 12) + "'s songs");
                    break;
                }
                File target = store.downloadFile(s);
                if (!target.isFile())
                    Hammer.download(from, s, new File(dir, s.id + ".part"), target, job);
                Hammer.require(Hammer.hash(target).equals(s.id), "The song did not arrive intact");
                synchronized (this) {
                    HammerKeep now = record(id);
                    if (now == null || !now.songs.contains(s.id)) {  // taken back during the download
                        target.delete();
                        continue;
                    }
                    store.rememberDownload(s);
                    store.rehost(s, true);
                    Set<String> held = heldIds(id);
                    held.add(s.id);
                    saveHeld(id, held);
                }
                fetched = true;
                extras(from, s, job);
                say("Keeping \"" + s.title + "\" for artist " + id.substring(0, 12));
            }
        }
        if (!fetched && have.isEmpty())
            return;
        // the artist's page, albums and station, newest copies, so Discover shows them as the artist made them
        try {
            for (HammerProfile p : Hammer.profiles(from, job))
                if (p.key.equals(k.key)) {
                    if (!p.image.isEmpty())
                        store.publicObjects.putImage(Hammer.image(from, p.id(), p.image, true, job));
                    store.publicObjects.rememberProfile(p);
                }
        } catch (Exception optional) {
        }
        try {
            for (HammerAlbum a : Hammer.albums(from, job))
                if (a.key.equals(k.key))
                    try {
                        store.publicObjects.putAlbum(a);
                    } catch (Exception notNewer) {
                    }
        } catch (Exception optional) {
        }
        try {
            for (HammerStation s : Hammer.stations(from, job))
                if (s.key.equals(k.key))
                    store.publicObjects.carry(s);
        } catch (Exception optional) {
        }
    }

    /** A kept song's cover and lyrics, when the artist published them. */
    private void extras(Hammer.Card from, Hammer.Song s, Hammer.Job job) {
        try {
            HammerArt art = Hammer.cover(from, s, job);
            if (art != null) {
                store.publicObjects.putImage(Hammer.image(from, s.id, art.image, false, job));
                store.publicObjects.cover(s, art);
            }
        } catch (Exception optional) {
        }
        try {
            HammerLyrics l = Hammer.lyrics(from, s, job);
            if (l != null)
                store.publicObjects.putLyrics(s, l);
        } catch (Exception optional) {
        }
    }

    /** One line per artist, for the helper's owner: id, songs asked, songs held, bytes. */
    public List<String> report() {
        List<String> out = new ArrayList<String>();
        for (HammerKeep k : records())
            try {
                out.add(k.id() + " | asked " + k.songs.size() + " | held " + held(k).size() + " | "
                        + (bytesHeld(k.id()) >> 20) + " MB | renewed "
                        + new Date(new File(dir, k.id() + ".keep").lastModified()));
            } catch (Exception damaged) {
            }
        return out;
    }

    /** The helper's owner lets an artist go: their songs fetched here are dropped, and not taken again. */
    public synchronized void remove(String artist) throws Exception {
        Hammer.require(Hammer.digest(artist), "Invalid artist");
        drop(artist, Collections.<String>emptySet());
        new File(dir, artist + ".keep").delete();
        HammerStore.atomic(new File(dir, artist + ".refused"), new byte[0]);
    }
}
