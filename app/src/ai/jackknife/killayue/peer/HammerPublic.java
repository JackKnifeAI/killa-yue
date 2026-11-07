package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.KeyPair;
import java.util.*;

/** Explicit public objects. The network can resolve identifiers, never filesystem paths. */
public final class HammerPublic {
    public static final int IMAGE_LIMIT = 262144;
    private final File root;
    private final HammerStore store;
    public HammerPublic(HammerStore store) throws Exception {
        this.store = store;
        root = new File(store.root, "public-objects");
        Hammer.require(root.isDirectory() || root.mkdir(), "Cannot open public objects");
    }
    private File object(String id, String suffix) throws IOException {
        Hammer.require(Hammer.digest(id), "Invalid public object");
        File f = new File(root, id + suffix);
        Hammer.require(!java.nio.file.Files.isSymbolicLink(f.toPath()), "Linked object refused");
        return f;
    }
    public synchronized String putImage(byte[] jpeg) throws Exception {
        Hammer.require(jpeg.length > 4 && jpeg.length <= IMAGE_LIMIT && (jpeg[0] & 255) == 255 &&
                           (jpeg[1] & 255) == 216,
                       "Choose a bounded JPEG image");
        String id = Hammer.hash(jpeg);
        File target = object(id, ".jpg");
        if (target.isFile() && Arrays.equals(localImage(id), jpeg))
            return id;
        File[] fs = root.listFiles();
        long total = 0;
        if (fs != null)
            for (File f : fs)
                total += f.length();
        Hammer.require(total + jpeg.length <= 64L * 1024 * 1024, "Artwork storage limit reached");
        HammerStore.atomic(target, jpeg);
        return id;
    }
    public synchronized byte[] localImage(String id) throws Exception {
        byte[] bytes = HammerStore.read(object(id, ".jpg"), IMAGE_LIMIT);
        Hammer.require(Hammer.hash(bytes).equals(id), "Artwork checksum differs");
        return bytes;
    }
    public synchronized HammerProfile own() throws Exception {
        File f = new File(root, "own.profile");
        if (!f.isFile())
            return null;
        return HammerProfile.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 4096))));
    }
    public synchronized void publishProfile(HammerProfile p) throws Exception {
        p.validate();
        if (!p.image.isEmpty())
            localImage(p.image);
        HammerProfile old = own();
        Hammer.require(old == null || (old.key.equals(p.key) && p.revision > old.revision),
                       "Artist update must advance its existing identity");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        p.write(new DataOutputStream(b));
        HammerStore.atomic(new File(root, "own.profile"), b.toByteArray());
    }
    public synchronized void withdrawProfile() throws Exception {
        File f = new File(root, "own.profile");
        Hammer.require(!f.exists() || f.delete(), "Could not withdraw profile");
    }
    public synchronized void rememberProfile(HammerProfile p) throws Exception {
        p.validate();
        File f = object(p.id(), ".profile");
        if (f.isFile()) {
            HammerProfile old =
                HammerProfile.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 4096))));
            if (old.revision >= p.revision)
                return;
        } else {
            int count = 0;
            File[] files = root.listFiles();
            if (files != null)
                for (File x : files)
                    if (x.getName().endsWith(".profile"))
                        count++;
            Hammer.require(count < 256, "Artist cache full");
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        p.write(new DataOutputStream(b));
        HammerStore.atomic(f, b.toByteArray());
    }
    public List<HammerProfile> profiles() throws Exception {
        List<Hammer.Song> publicSongs = store.catalog("");
        LinkedHashMap<String, HammerProfile> out = new LinkedHashMap<String, HammerProfile>();
        synchronized (this) {
            HammerProfile own = own();
            if (own != null)
                out.put(own.id(), own);
            for (Hammer.Song s : publicSongs) {
                String id = Hammer.hash(Base64.getDecoder().decode(s.key));
                File f = object(id, ".profile");
                if (!out.containsKey(id) && f.isFile() && out.size() < 64)
                    out.put(id, HammerProfile.read(new DataInputStream(
                                    new ByteArrayInputStream(HammerStore.read(f, 4096)))));
            }
        }
        return new ArrayList<HammerProfile>(out.values());
    }
    public synchronized void cover(Hammer.Song song, HammerArt art) throws Exception {
        art.validate();
        Hammer.require(art.song.equals(song.id) && art.key.equals(song.key), "Cover publisher differs");
        localImage(art.image);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        art.write(new DataOutputStream(b));
        HammerStore.atomic(object(song.id, ".cover"), b.toByteArray());
    }
    public HammerArt cover(String song, String capability) throws Exception {
        Hammer.require(store.audio(song, capability) != null, "Song unavailable");
        HammerArt art = localCover(song);
        if (art == null)
            return null;
        for (Hammer.Song current : store.catalog(capability))
            if (current.id.equals(song) && current.key.equals(art.key))
                return art;
        return null;
    }
    public synchronized HammerArt localCover(String song) throws Exception {
        File f = object(song, ".cover");
        return f.isFile()
            ? HammerArt.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 1024))))
            : null;
    }
    /** Stores a song's lyrics, signed by the song's publisher. */
    public synchronized void putLyrics(Hammer.Song song, HammerLyrics l) throws Exception {
        l.validate();
        Hammer.require(l.song.equals(song.id) && l.key.equals(song.key), "Lyrics publisher differs");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        l.write(new DataOutputStream(b));
        HammerStore.atomic(object(song.id, ".lyrics"), b.toByteArray());
    }
    public synchronized HammerLyrics localLyrics(String song) throws Exception {
        File f = object(song, ".lyrics");
        return f.isFile() ? HammerLyrics.read(new DataInputStream(new ByteArrayInputStream(
                                HammerStore.read(f, HammerLyrics.LIMIT + 1024))))
                          : null;
    }
    /** A visible song's lyrics, only while its publisher is the one who signed them. */
    public HammerLyrics lyrics(String song, String capability) throws Exception {
        Hammer.require(store.audio(song, capability) != null, "Song unavailable");
        HammerLyrics l = localLyrics(song);
        if (l == null)
            return null;
        for (Hammer.Song current : store.catalog(capability))
            if (current.id.equals(song) && current.key.equals(l.key))
                return l;
        return null;
    }
    /** Publishes an album or playlist; a newer revision of the same title replaces the older one. */
    public synchronized void putAlbum(HammerAlbum a) throws Exception {
        a.validate();
        File f = object(a.id(), ".album");
        if (f.isFile()) {
            HammerAlbum old = HammerAlbum.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 16384))));
            Hammer.require(a.revision > old.revision, "Album update must be newer");
        } else {
            int count = 0;
            File[] files = root.listFiles();
            if (files != null)
                for (File x : files)
                    if (x.getName().endsWith(".album"))
                        count++;
            Hammer.require(count < 64, "This host holds at most 64 albums");
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        a.write(new DataOutputStream(b));
        HammerStore.atomic(f, b.toByteArray());
    }
    /** Points this artist's albums at songs that were shared again under a new id ({@code moved}: old → new). */
    public synchronized void moveSongs(Map<String, String> moved, KeyPair pair) throws Exception {
        File[] files = root.listFiles();
        if (moved.isEmpty() || files == null)
            return;
        String key = Hammer.publicKey(pair.getPublic());
        for (File f : files) {
            if (!f.getName().endsWith(".album"))
                continue;
            HammerAlbum a = HammerAlbum.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 16384))));
            boolean changed = false;
            for (int i = 0; i < a.songs.size(); i++)
                if (moved.containsKey(a.songs.get(i))) {
                    a.songs.set(i, moved.get(a.songs.get(i)));
                    changed = true;
                }
            if (!a.key.equals(key) || !changed)
                continue;
            if (moved.containsKey(a.cover))
                a.cover = moved.get(a.cover);
            a.revision = Math.max(a.revision + 1, System.currentTimeMillis());
            a.sign(pair);
            putAlbum(a);
        }
    }
    public synchronized void withdrawAlbum(String id) throws Exception {
        File f = object(id, ".album");
        Hammer.require(!f.exists() || f.delete(), "Could not withdraw album");
    }
    /** Puts an artist's station here, their own or one carried for them; a newer revision replaces the older. */
    public synchronized void putStation(HammerStation s) throws Exception {
        s.validate();
        File f = object(s.id(), ".station");
        if (f.isFile()) {
            HammerStation old = readStation(f);
            Hammer.require(s.revision > old.revision, "Station update must be newer");
        } else {
            int count = 0;
            long now = System.currentTimeMillis();
            File[] files = root.listFiles();
            if (files != null)
                for (File x : files)
                    if (x.getName().endsWith(".station")) {
                        try {
                            if (!readStation(x).kept(now)) {  // long off air: its room goes to a live one
                                x.delete();
                                continue;
                            }
                        } catch (Exception damaged) {
                            x.delete();
                            continue;
                        }
                        count++;
                    }
            Hammer.require(count < 64, "This host holds at most 64 stations");
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        s.write(new DataOutputStream(b));
        HammerStore.atomic(f, b.toByteArray());
    }
    /**
     * Carries someone else's station, so it stays on air while their phone sleeps. Taken only when this host shares
     * every song on it publicly under the same artist's signature, so a station never points at music missing here
     * and nobody can fill a host with stations it has no part in. A newer revision replaces the older; an ended one
     * is taken only to replace a live copy. True when this copy was new here.
     */
    public boolean carry(HammerStation s) throws Exception {
        boolean kept = keep(s);
        StationListener l = carried;
        if (kept && l != null)
            try {
                l.carried(s);
            } catch (Exception ignored) {
            }
        return kept;
    }
    /** Told of each station newly kept here by {@link #carry}, so a helper can pass it straight on. */
    public interface StationListener {
        void carried(HammerStation s) throws Exception;
    }
    public volatile StationListener carried;
    /** How long a listener counts as listening after it last said so: a little over three songs. */
    public static final long LISTENING = 10L * 60 * 1000;
    private final Map<String, Map<String, Long>> listeners = new HashMap<String, Map<String, Long>>();
    /**
     * Notes that someone is listening to a station held here, by a random tag their phone made for this tune-in
     * (empty: only asking), and answers the tags heard in the last ten minutes as short hashes. The artist joins the
     * answers of every host, so a listener who moved between hosts counts once, and no tag says who anyone is.
     */
    public synchronized List<String> listening(String station, String tag) throws Exception {
        Hammer.require(Hammer.digest(station) && (tag.isEmpty() || Hammer.digest(tag)), "Invalid listener");
        Hammer.require(object(station, ".station").isFile(), "No such station here");
        long now = System.currentTimeMillis();
        for (Iterator<Map<String, Long>> i = listeners.values().iterator(); i.hasNext();) {
            Map<String, Long> m = i.next();
            for (Iterator<Long> t = m.values().iterator(); t.hasNext();)
                if (now - t.next() > LISTENING)
                    t.remove();
            if (m.isEmpty())
                i.remove();
        }
        Map<String, Long> m = listeners.get(station);
        if (m == null && !tag.isEmpty() && listeners.size() < 64)
            listeners.put(station, m = new HashMap<String, Long>());
        List<String> out = new ArrayList<String>();
        if (m == null)
            return out;
        if (!tag.isEmpty() && (m.containsKey(tag) || m.size() < 4096))
            m.put(tag, now);
        for (String t : m.keySet())
            out.add(shortTag(t));
        return out;
    }
    public static String shortTag(String tag) throws Exception {
        return Hammer.hash(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).substring(0, 16);
    }
    private boolean keep(HammerStation s) throws Exception {
        s.validate();
        long now = System.currentTimeMillis();
        if (!s.kept(now))
            return false;
        Map<String, String> visible = new HashMap<String, String>();
        for (Hammer.Song song : store.catalog(""))
            visible.put(song.id, song.key);
        for (String id : s.songs)
            if (!s.key.equals(visible.get(id)))
                return false;
        synchronized (this) {
            HammerStation old = station(s.id());
            if (old != null ? old.revision >= s.revision : !s.live(now))
                return false;
            putStation(s);
            return true;
        }
    }
    private static HammerStation readStation(File f) throws Exception {
        return HammerStation.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 1 << 18))));
    }
    /** This artist's station as stored here, on air or not, or null. */
    public synchronized HammerStation station(String artist) throws Exception {
        File f = object(artist, ".station");
        return f.isFile() ? readStation(f) : null;
    }
    /**
     * Stations whose every song this caller can reach here, so a listener never meets a gap in the music. Ones gone
     * off air in the last week come too, marked by their end, so phones and hosts holding an older copy learn it ended.
     */
    public List<HammerStation> stations(String capability) throws Exception {
        Map<String, String> visible = new HashMap<String, String>();
        for (Hammer.Song s : store.catalog(capability))
            visible.put(s.id, s.key);
        List<HammerStation> out = new ArrayList<HammerStation>();
        File[] files;
        synchronized (this) {
            files = root.listFiles();
        }
        if (files == null)
            return out;
        Arrays.sort(files);
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (!f.getName().endsWith(".station") || out.size() >= 64)
                continue;
            try {
                HammerStation s = readStation(f);
                boolean all = s.kept(now);
                for (String id : s.songs)
                    all &= s.key.equals(visible.get(id));
                if (all)
                    out.add(s);
            } catch (Exception damaged) {
            }
        }
        return out;
    }
    /** Albums whose songs this caller can see, each showing only the visible songs of its signer. */
    public List<HammerAlbum> albums(String capability) throws Exception {
        Map<String, String> visible = new HashMap<String, String>();  // song → publisher key
        for (Hammer.Song s : store.catalog(capability))
            visible.put(s.id, s.key);
        List<HammerAlbum> out = new ArrayList<HammerAlbum>();
        File[] files;
        synchronized (this) {
            files = root.listFiles();
        }
        if (files == null)
            return out;
        Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".album") || out.size() >= 64)
                continue;
            try {
                HammerAlbum a = HammerAlbum.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 16384))));
                boolean all = true;
                for (String s : a.songs)
                    all &= a.key.equals(visible.get(s));
                if (all)  // an album is offered whole, never with missing songs
                    out.add(a);
            } catch (Exception damaged) {
            }
        }
        return out;
    }
    public byte[] image(String owner, String image, boolean profile, String capability) throws Exception {
        Hammer.require(Hammer.digest(owner) && Hammer.digest(image), "Invalid artwork request");
        boolean allowed = false;
        if (profile) {
            for (HammerProfile p : profiles())
                if (p.id().equals(owner) && p.image.equals(image))
                    allowed = true;
        } else {
            HammerArt a = cover(owner, capability);
            allowed = a != null && a.image.equals(image);
        }
        Hammer.require(allowed, "Artwork not published");
        return localImage(image);
    }
}
