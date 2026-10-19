package ai.jackknife.killayue.peer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Private application storage on Android, an owner-only data directory on the desktop. */
public final class HammerStore implements Hammer.Source {
    public static final class Hosted {
        public final Hammer.Song song;
        public final boolean publicSong;
        public final File file;
        public final long modified;
        Hosted(Hammer.Song song, boolean publicSong, File file, long modified) {
            this.song = song;
            this.publicSong = publicSong;
            this.file = file;
            this.modified = modified;
        }
    }
    public static final class Peer {
        public final Hammer.Card card;
        public final String label;
        public final boolean introduce;
        public Peer(Hammer.Card card, String label, boolean introduce) throws IOException {
            Hammer.require(!introduce || !card.isPrivate(),
                           "Private invitations cannot be introduced publicly");
            Hammer.require(label.length() <= 80, "Peer name too long");
            this.card = card;
            this.label = label;
            this.introduce = introduce;
        }
    }
    public final File root;
    public final HammerPublic publicObjects;
    public final HammerCommunity community;
    public final HammerCensus census;
    public HammerCommunity community() { return community; }
    public HammerCensus census() { return census; }
    public synchronized boolean blocked(String key) throws Exception {
        return blocked.contains(Hammer.hash(Base64.getDecoder().decode(key)));
    }
    private final HammerDirectory directory = new HammerDirectory();
    public HammerDirectory directory() { return directory; }
    private final Map<String, Hosted> songs = new LinkedHashMap<String, Hosted>();
    private final Map<String, Peer> peers = new LinkedHashMap<String, Peer>();
    private final Set<String> blocked = new HashSet<String>();
    private final Map<String, List<Long>> rates = new HashMap<String, List<Long>>();
    private String token;
    public HammerStore(File root) throws Exception {
        this.root = root;
        Hammer.require(root.isDirectory() || root.mkdirs(), "Cannot create HAMMER storage");
        publicObjects = new HammerPublic(this);
        community = new HammerCommunity(this);
        census = new HammerCensus(root);
        File cap = new File(root, "circle.key");
        if (cap.isFile()) {
            token = new String(read(cap, 64), StandardCharsets.US_ASCII);
            Hammer.require(Hammer.digest(token), "Damaged circle key");
        } else {
            token = Hammer.random();
            atomic(cap, token.getBytes(StandardCharsets.US_ASCII));
        }
        File[] files = root.listFiles();
        Hammer.require(files != null, "Cannot read HAMMER storage");
        for (File f : files)
            if (f.getName().matches("[0-9a-f]{64}\\.song")) {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(read(f, 8192)))) {
                    boolean pub = in.readBoolean();
                    Hammer.Song s = Hammer.Song.read(in);
                    Hammer.require(f.getName().equals(s.id + ".song"), "Invalid publication filename");
                    File audio = new File(Hammer.text(in, 4096));
                    long modified = in.readLong();
                    songs.put(s.id, new Hosted(s, pub, audio, modified));
                    Hammer.require(songs.size() <= Hammer.MAX_SONGS, "Too many hosted songs");
                }
            }
        File pf = new File(root, "peers.bin");
        if (pf.isFile())
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(read(pf, 65536)))) {
                int n = in.readInt();
                Hammer.require(n >= 0 && n <= 32, "Invalid saved peers");
                for (int i = 0; i < n; i++) {
                    Peer p = new Peer(Hammer.Card.parse(Hammer.text(in, 1024)), Hammer.text(in, 320),
                                      in.readBoolean());
                    peers.put(peerId(p.card), p);
                }
            }
        File bf = new File(root, "blocked.bin");
        if (bf.isFile())
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(read(bf, 70000)))) {
                int n = in.readInt();
                Hammer.require(n >= 0 && n <= 1000, "Invalid blocked authors");
                for (int i = 0; i < n; i++) {
                    String id = Hammer.text(in, 64);
                    Hammer.require(Hammer.digest(id), "Invalid author");
                    blocked.add(id);
                }
            }
    }
    public static byte[] read(File file, int max) throws IOException {
        Hammer.require(file.length() <= max, "File exceeds limit");
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) {
                Hammer.require(out.size() + n <= max, "File exceeds limit");
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }
    public static void atomic(File dest, byte[] bytes) throws IOException {
        File tmp = File.createTempFile("hammer-", ".tmp", dest.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING);
        } finally {
            tmp.delete();
        }
    }
    public HammerPublic publicObjects() { return publicObjects; }
    public synchronized String token() { return token; }
    public synchronized void revoke() throws Exception {
        String next = Hammer.random();
        atomic(new File(root, "circle.key"), next.getBytes(StandardCharsets.US_ASCII));
        token = next;
        notifyAll();
    }
    private synchronized boolean member(String supplied) throws IOException {
        if (supplied.isEmpty())
            return false;
        Hammer.require(MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                                             supplied.getBytes(StandardCharsets.US_ASCII)),
                       "Invitation revoked or invalid");
        return true;
    }
    public synchronized List<Hosted> hosted() { return new ArrayList<Hosted>(songs.values()); }
    public synchronized List<Hammer.Song> catalog(String capability) throws Exception {
        boolean privateAccess = member(capability);
        List<Hammer.Song> out = new ArrayList<Hammer.Song>();
        for (Hosted h : songs.values())
            if ((h.publicSong || privateAccess) && available(h))
                out.add(h.song);
        return out;
    }
    public synchronized File audio(String id, String capability) throws Exception {
        Hammer.require(Hammer.digest(id), "Invalid song identifier");
        boolean privateAccess = member(capability);
        Hosted h = songs.get(id);
        return h != null && (h.publicSong || privateAccess) && available(h) ? h.file : null;
    }
    private boolean available(Hosted h) {
        return !Files.isSymbolicLink(h.file.toPath()) && h.file.isFile() && h.file.length() == h.song.size &&
            h.file.lastModified() == h.modified;
    }
    public Hammer.Song publish(File input, String title, String tags, boolean reshare, boolean pub,
                               KeyPair pair) throws Exception {
        return publish(input, title, tags, reshare, pub, pair, new Hammer.Job());
    }
    public Hammer.Song publish(File input, String title, String tags, boolean reshare, boolean pub,
                               KeyPair pair, Hammer.Job job) throws Exception {
        input = input.getCanonicalFile();
        Hammer.require(input.isFile() && input.length() > 0 && input.length() <= Hammer.MAX_AUDIO,
                       "Choose an audio file up to 1 GiB");
        String name = input.getName().toLowerCase(Locale.ROOT);
        String type = name.endsWith(".wav")    ? "audio/wav"
                      : name.endsWith(".flac") ? "audio/flac"
                                               : "audio/mpeg";
        Hammer.require(name.matches(".*\\.(wav|mp3|flac)"), "Choose WAV, FLAC or MP3 audio");
        long size = input.length(), modified = input.lastModified();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(input)) {
            byte[] bytes = new byte[65536];
            int n;
            while ((n = in.read(bytes)) != -1) {
                job.check();
                digest.update(bytes, 0, n);
            }
        }
        String id = Hammer.hex(digest.digest());
        Hammer.require(input.length() == size && input.lastModified() == modified,
                       "Source changed while publishing");
        Hammer.Song song = new Hammer.Song(id, title, tags, type, size, reshare);
        song.sign(pair);
        song.validate();
        synchronized (this) {
            job.check();
            Hammer.require(songs.containsKey(song.id) || songs.size() < Hammer.MAX_SONGS,
                           "This host holds at most 256 songs");
            save(song, pub, input, modified);
        }
        return song;
    }
    private void save(Hammer.Song song, boolean pub, File file, long modified) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeBoolean(pub);
        song.write(out);
        Hammer.text(out, file.getCanonicalPath(), 4096);
        out.writeLong(modified);
        atomic(new File(root, song.id + ".song"), b.toByteArray());
        songs.put(song.id, new Hosted(song, pub, file, modified));
        writeMetadata(song);
        notifyAll();
    }
    public synchronized void rehost(Hammer.Song song, boolean pub) throws Exception {
        song.validate();
        Hammer.require(song.reshare, "Publisher did not enable rehosting");
        Hammer.require(songs.containsKey(song.id) || songs.size() < Hammer.MAX_SONGS,
                       "This host holds at most 256 songs");
        File f = downloadFile(song);
        Hammer.require(f.isFile() && f.length() == song.size && Hammer.hash(f).equals(song.id),
                       "Download must finish and verify before hosting");
        save(song, pub, f, f.lastModified());
    }
    public synchronized void visibility(String id, boolean pub) throws Exception {
        Hosted h = songs.get(id);
        Hammer.require(h != null, "Unknown song");
        save(h.song, pub, h.file, h.modified);
    }
    public synchronized void unpublish(String id) throws IOException {
        Hammer.require(Hammer.digest(id), "Invalid song");
        File f = new File(root, id + ".song");
        Hammer.require(!f.exists() || f.delete(), "Cannot unpublish");
        songs.remove(id);
        notifyAll();
        // Keep the verified local copy: unpublishing is not deleting a person's music.
    }
    public synchronized void rememberDownload(Hammer.Song song) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        song.write(out);
        Hammer.text(out, downloadFile(song).getCanonicalPath(), 4096);
        atomic(new File(root, song.id + ".download"), b.toByteArray());
        writeMetadata(song);
    }
    public synchronized List<Hammer.Song> downloads() throws Exception {
        List<Hammer.Song> out = new ArrayList<Hammer.Song>();
        File[] fs = root.listFiles();
        if (fs == null)
            return out;
        for (File f : fs)
            if (f.getName().matches("[0-9a-f]{64}\\.download")) {
                try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(read(f, 8192)))) {
                    Hammer.Song s = Hammer.Song.read(in);
                    if (new File(Hammer.text(in, 4096)).isFile())
                        out.add(s);
                }
            }
        return out;
    }
    public synchronized File downloadFile(Hammer.Song song) throws Exception {
        File meta = new File(root, song.id + ".download");
        if (!meta.isFile())
            return new File(root, song.id + song.extension());
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(read(meta, 8192)))) {
            Hammer.Song.read(in);
            return new File(Hammer.text(in, 4096));
        }
    }
    public synchronized void relocateDownload(Hammer.Song song, File file) throws Exception {
        Hammer.require(file.length() == song.size && Hammer.hash(file).equals(song.id),
                       "Relocated audio does not match");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        song.write(out);
        Hammer.text(out, file.getCanonicalPath(), 4096);
        atomic(new File(root, song.id + ".download"), b.toByteArray());
        Hosted h = songs.get(song.id);
        if (h != null)
            save(song, h.publicSong, file, file.lastModified());
    }
    public static String peerId(Hammer.Card card) {
        return card.pin + (card.isPrivate() ? ":private" : ":public");
    }
    public synchronized List<Peer> followed() { return new ArrayList<Peer>(peers.values()); }
    public synchronized void follow(Peer peer) throws Exception {
        String id = peerId(peer.card);
        Hammer.require(peers.containsKey(id) || peers.size() < 32, "Follow at most 32 peers");
        Map<String, Peer> next = new LinkedHashMap<String, Peer>(peers);
        next.put(id, peer);
        savePeers(next);
        peers.clear();
        peers.putAll(next);
    }
    public synchronized void forget(Hammer.Card card) throws Exception {
        Map<String, Peer> next = new LinkedHashMap<String, Peer>(peers);
        next.remove(peerId(card));
        savePeers(next);
        peers.clear();
        peers.putAll(next);
    }
    private void savePeers(Map<String, Peer> list) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeInt(list.size());
        for (Peer p : list.values()) {
            Hammer.text(out, p.card.encode(), 1024);
            Hammer.text(out, p.label, 320);
            out.writeBoolean(p.introduce);
        }
        atomic(new File(root, "peers.bin"), b.toByteArray());
    }
    public synchronized List<Hammer.Card> peers() {
        List<Hammer.Card> out = new ArrayList<Hammer.Card>();
        for (Peer p : peers.values())
            if (p.introduce && !p.card.isPrivate())
                out.add(p.card);
        return out;
    }
    public synchronized List<Hammer.Comment> comments(String id, String capability) throws Exception {
        Hammer.require(audio(id, capability) != null, "Song unavailable");
        boolean privateAccess = member(capability);
        List<Hammer.Comment> out = new ArrayList<Hammer.Comment>();
        String circle = Hammer.hash(token.getBytes(StandardCharsets.US_ASCII));
        for (Hammer.Comment c : readComments(id))
            if ("public".equals(c.audience) || (privateAccess && circle.equals(c.audience)))
                out.add(c);
        return out;
    }
    private List<Hammer.Comment> readComments(String id) throws Exception {
        Hammer.require(Hammer.digest(id), "Invalid song");
        List<Hammer.Comment> out = new ArrayList<Hammer.Comment>();
        File f = new File(root, id + ".comments");
        if (f.isFile())
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(read(f, 524288)))) {
                int n = in.readInt();
                Hammer.require(n >= 0 && n <= 128, "Invalid comment count");
                for (int i = 0; i < n; i++) {
                    Hammer.Comment c = Hammer.Comment.read(in);
                    Hammer.require(c.song.equals(id), "Wrong comment thread");
                    if (!blocked.contains(Hammer.hash(Base64.getDecoder().decode(c.key))))
                        out.add(c);
                }
            }
        return out;
    }
    public synchronized void comment(Hammer.Comment c, String capability) throws Exception {
        Hammer.require(audio(c.song, capability) != null, "Song unavailable");
        c.validate();
        String author = Hammer.hash(Base64.getDecoder().decode(c.key));
        if ("public".equals(c.audience))
            Hammer.require(songs.get(c.song).publicSong, "Public comments require a public publication");
        else
            Hammer.require(member(capability) &&
                               c.audience.equals(Hammer.hash(token.getBytes(StandardCharsets.US_ASCII))),
                           "Private comments belong to their original circle");
        Hammer.require(!blocked.contains(author), "Author blocked on this host");
        List<Hammer.Comment> list = readComments(c.song);
        for (Hammer.Comment old : list)
            if (old.id.equals(c.id)) {
                Hammer.require(old.sig.equals(c.sig), "Comment ID conflict");
                return;
            }
        Hammer.require(list.size() < 128, "This host's thread is full");
        long now = System.currentTimeMillis();
        List<Long> times = rates.get(author);
        if (times == null)
            times = new ArrayList<Long>();
        for (Iterator<Long> it = times.iterator(); it.hasNext();)
            if (now - it.next().longValue() > 3600000)
                it.remove();
        Hammer.require(times.size() < 10, "Comment limit: ten per author per hour on this host");
        Hammer.require(rates.containsKey(author) || rates.size() < 1024,
                       "Comment authors at capacity; host must restart");
        list.add(c);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeInt(list.size());
        for (Hammer.Comment v : list)
            v.write(out);
        atomic(new File(root, c.song + ".comments"), b.toByteArray());
        times.add(Long.valueOf(now));
        rates.put(author, times);
        writeMetadata(songs.get(c.song).song);
    }
    public synchronized void block(String key) throws Exception {
        String id = Hammer.hash(Base64.getDecoder().decode(key));
        Hammer.require(blocked.size() < 1000 || blocked.contains(id), "Block list full");
        Set<String> next = new HashSet<String>(blocked);
        next.add(id);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeInt(next.size());
        for (String v : next)
            Hammer.text(out, v, 64);
        atomic(new File(root, "blocked.bin"), b.toByteArray());
        blocked.add(id);
    }
    private List<Hammer.Vote> readVotes(String id) throws Exception {
        Hammer.require(Hammer.digest(id), "Invalid song");
        List<Hammer.Vote> out = new ArrayList<Hammer.Vote>();
        File file = new File(root, id + ".votes");
        if (file.isFile())
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(read(file, 3145728)))) {
                int n = in.readInt();
                Hammer.require(n >= 0 && n <= 4096, "Too many stored votes");
                for (int i = 0; i < n; i++) {
                    Hammer.Vote v = Hammer.Vote.read(in);
                    Hammer.require(v.song.equals(id), "Wrong vote metadata");
                    out.add(v);
                }
            }
        return out;
    }
    public synchronized List<Hammer.Vote> votes(String id, String capability) throws Exception {
        Hammer.require(audio(id, capability) != null, "Song unavailable");
        boolean privateAccess = member(capability);
        String circle = Hammer.hash(token.getBytes(StandardCharsets.US_ASCII));
        List<Hammer.Vote> out = new ArrayList<Hammer.Vote>();
        for (Hammer.Vote v : readVotes(id))
            if ("public".equals(v.audience) || (privateAccess && circle.equals(v.audience)))
                out.add(v);
        return out;
    }
    public synchronized void vote(Hammer.Vote vote, String capability) throws Exception {
        Hammer.require(audio(vote.song, capability) != null, "Song unavailable");
        vote.validate();
        if ("public".equals(vote.audience))
            Hammer.require(songs.get(vote.song).publicSong, "Public votes require a public publication");
        else
            Hammer.require(member(capability) &&
                               vote.audience.equals(Hammer.hash(token.getBytes(StandardCharsets.US_ASCII))),
                           "Private votes stay in their original circle");
        List<Hammer.Vote> list = readVotes(vote.song);
        mergeVote(list, vote);
        writeVotes(vote.song, list);
        writeMetadata(songs.get(vote.song).song);
    }
    private void mergeVote(List<Hammer.Vote> list, Hammer.Vote vote) throws Exception {
        for (int i = 0; i < list.size(); i++) {
            Hammer.Vote old = list.get(i);
            if (old.key.equals(vote.key) && old.audience.equals(vote.audience)) {
                // Signed monotonic revisions deduplicate replicas. A deterministic tie-break converges forks.
                if (vote.sequence > old.sequence || (vote.sequence == old.sequence && vote.value > old.value))
                    list.set(i, vote);
                return;
            }
        }
        Hammer.require(list.size() < 4096, "Track vote metadata is full");
        list.add(vote);
    }
    private void writeVotes(String id, List<Hammer.Vote> list) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeInt(list.size());
        for (Hammer.Vote v : list)
            v.write(out);
        atomic(new File(root, id + ".votes"), b.toByteArray());
    }
    /**
     * Public annotations travel with verified downloads. Private-circle records never cross this boundary.
     */
    public synchronized void importPublicMetadata(Hammer.Song song, List<Hammer.Comment> comments,
                                                  List<Hammer.Vote> votes) throws Exception {
        File file = downloadFile(song);
        Hammer.require(file.isFile() && file.length() == song.size,
                       "Keep the verified recording before metadata");
        List<Hammer.Vote> merged = readVotes(song.id);
        for (Hammer.Vote v : votes) {
            v.validate();
            Hammer.require(v.song.equals(song.id) && "public".equals(v.audience),
                           "Private or unrelated vote refused");
            mergeVote(merged, v);
        }
        writeVotes(song.id, merged);
        List<Hammer.Comment> cs = readComments(song.id);
        Set<String> ids = new HashSet<String>();
        for (Hammer.Comment c : cs)
            ids.add(c.id);
        for (Hammer.Comment c : comments) {
            c.validate();
            Hammer.require(c.song.equals(song.id) && "public".equals(c.audience),
                           "Private or unrelated comment refused");
            if (!blocked.contains(Hammer.hash(Base64.getDecoder().decode(c.key))) && ids.add(c.id) &&
                cs.size() < 128)
                cs.add(c);
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeInt(cs.size());
        for (Hammer.Comment c : cs)
            c.write(out);
        atomic(new File(root, song.id + ".comments"), b.toByteArray());
        writeMetadata(song);
    }
    public synchronized byte[] publicMetadata(Hammer.Song song) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeInt(0x484d4d31);
        song.write(out);
        List<Hammer.Comment> comments = new ArrayList<Hammer.Comment>();
        for (Hammer.Comment c : readComments(song.id))
            if ("public".equals(c.audience))
                comments.add(c);
        out.writeInt(comments.size());
        for (Hammer.Comment c : comments)
            c.write(out);
        List<Hammer.Vote> votes = new ArrayList<Hammer.Vote>();
        for (Hammer.Vote v : readVotes(song.id))
            if ("public".equals(v.audience))
                votes.add(v);
        out.writeInt(votes.size());
        for (Hammer.Vote v : votes)
            v.write(out);
        return b.toByteArray();
    }
    private void writeMetadata(Hammer.Song song) throws Exception {
        atomic(new File(root, song.id + ".metadata"), publicMetadata(song));
    }
    public synchronized List<Hammer.Song> changed(String capability, String cursor, long waitMillis)
        throws Exception {
        long deadline = System.nanoTime() + Math.min(25000, Math.max(0, waitMillis)) * 1000000;
        while (true) {
            List<Hammer.Song> songs = catalog(capability);
            if (!Hammer.cursor(songs).equals(cursor))
                return songs;
            long remaining = (deadline - System.nanoTime()) / 1000000;
            if (remaining <= 0)
                return null;
            wait(remaining);
        }
    }
}
