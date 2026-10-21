package ai.jackknife.killayue.peer;

import java.io.*;
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
    public synchronized void withdrawAlbum(String id) throws Exception {
        File f = object(id, ".album");
        Hammer.require(!f.exists() || f.delete(), "Could not withdraw album");
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
