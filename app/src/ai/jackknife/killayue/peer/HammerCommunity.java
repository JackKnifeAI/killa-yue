package ai.jackknife.killayue.peer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.*;

/** Versioned, signed sidecars. No changes to audio hashes or the original HAMMER wire records. */
public final class HammerCommunity {
    public static final int LIST = 16, POST = 17, FOLLOW = 18, COUNT = 19;
    public static final int LIMIT = 256;
    public static final class Post {
        public String scope = "track", target, audience = "public", id = Hammer.random();
        public String kind = "message", parent = "", name = "", body = "", host = "", key, sig;
        public long time = System.currentTimeMillis(), position = -1;
        public Hammer.Song remix;
        byte[] payload() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            Hammer.text(o, "HAMMER community 1", 32);
            Hammer.text(o, scope, 16);
            Hammer.text(o, target, 64);
            Hammer.text(o, audience, 64);
            Hammer.text(o, id, 64);
            Hammer.text(o, kind, 16);
            Hammer.text(o, parent, 64);
            Hammer.text(o, name, 128);
            Hammer.text(o, body, 2048);
            Hammer.text(o, host, 1024);
            o.writeLong(time);
            o.writeLong(position);
            o.writeBoolean(remix != null);
            if (remix != null)
                remix.write(o);
            return b.toByteArray();
        }
        public void sign(KeyPair pair) throws Exception {
            key = Hammer.publicKey(pair.getPublic());
            sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        }
        public boolean action() { return kind.equals("hide") || kind.equals("pin") || kind.equals("lock"); }
        public void validate() throws Exception {
            Hammer.require((scope.equals("track") || scope.equals("board")) && Hammer.digest(target) &&
                               Hammer.digest(id) && (audience.equals("public") || Hammer.digest(audience)) &&
                               (parent.isEmpty() || Hammer.digest(parent)) && Hammer.displayText(name) &&
                               Hammer.displayText(body) && time > 0 &&
                               time <= System.currentTimeMillis() + 120000 && position >= -1 &&
                               position <= 86400000,
                           "Invalid community metadata");
            Hammer.require(kind.equals("message") || kind.equals("remix") || kind.equals("source") ||
                               action(),
                           "Unknown post type");
            Hammer.require(!body.trim().isEmpty() && (!action() || !parent.isEmpty()),
                           "Empty post or action");
            Hammer.require(!scope.equals("board") || (audience.equals("public") && position == -1),
                           "Artist boards are public");
            if (kind.equals("remix") || kind.equals("source")) {
                Hammer.require(scope.equals("track") && audience.equals("public") && remix != null,
                               "Remix links belong to public tracks");
                remix.validate();
                Hammer.require((kind.equals("source") || remix.key.equals(key)) && !remix.id.equals(target),
                               "Sign your own remix");
                Hammer.require(!Hammer.Card.parse(host).isPrivate(), "Never publish a private invitation");
            } else
                Hammer.require(remix == null && host.isEmpty(), "Unexpected remix reference");
            Hammer.require(Hammer.verify(key, sig, payload()), "Community signature differs");
        }
        public void write(DataOutputStream o) throws IOException {
            o.write(payload());
            Hammer.text(o, key, 256);
            Hammer.text(o, sig, 256);
        }
        public static Post read(DataInputStream i) throws Exception {
            Hammer.require(Hammer.text(i, 32).equals("HAMMER community 1"), "Unsupported community record");
            Post p = new Post();
            p.scope = Hammer.text(i, 16);
            p.target = Hammer.text(i, 64);
            p.audience = Hammer.text(i, 64);
            p.id = Hammer.text(i, 64);
            p.kind = Hammer.text(i, 16);
            p.parent = Hammer.text(i, 64);
            p.name = Hammer.text(i, 128);
            p.body = Hammer.text(i, 2048);
            p.host = Hammer.text(i, 1024);
            p.time = i.readLong();
            p.position = i.readLong();
            if (i.readBoolean())
                p.remix = Hammer.Song.read(i);
            p.key = Hammer.text(i, 256);
            p.sig = Hammer.text(i, 256);
            p.validate();
            return p;
        }
    }
    /** Private request, never published. The recipient proves possession of its signing key on every poll. */
    public static final class Follow {
        public String host, action = "inbox", name = "", nonce = Hammer.random(), key, sig;
        public long time = System.currentTimeMillis();
        byte[] payload() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            Hammer.text(o, "HAMMER follower 1", 32);
            Hammer.text(o, host, 64);
            Hammer.text(o, action, 16);
            Hammer.text(o, name, 128);
            Hammer.text(o, nonce, 64);
            o.writeLong(time);
            return b.toByteArray();
        }
        public void sign(KeyPair pair) throws Exception {
            key = Hammer.publicKey(pair.getPublic());
            sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        }
        public void validate(String pin) throws Exception {
            Hammer.require(
                Hammer.digest(host) && host.equals(pin) && Hammer.digest(nonce) &&
                    time >= System.currentTimeMillis() - 120000 &&
                    time <= System.currentTimeMillis() + 120000 && Hammer.displayText(name) &&
                    (action.equals("follow") || action.equals("unfollow") || action.equals("inbox")),
                "Invalid follower request");
            Hammer.require(Hammer.verify(key, sig, payload()), "Follower proof differs");
        }
        public void write(DataOutputStream o) throws IOException {
            o.write(payload());
            Hammer.text(o, key, 256);
            Hammer.text(o, sig, 256);
        }
        public static Follow read(DataInputStream i) throws Exception {
            Hammer.require(Hammer.text(i, 32).equals("HAMMER follower 1"), "Unsupported follower request");
            Follow f = new Follow();
            f.host = Hammer.text(i, 64);
            f.action = Hammer.text(i, 16);
            f.name = Hammer.text(i, 128);
            f.nonce = Hammer.text(i, 64);
            f.time = i.readLong();
            f.key = Hammer.text(i, 256);
            f.sig = Hammer.text(i, 256);
            return f;
        }
    }
    public static final class Follower {
        public String key, name, granted = "";
        Follower(String k, String n) {
            key = k;
            name = n;
        }
    }
    private final HammerStore store;
    private final File root;
    private final Map<String, Long> rates = new HashMap<String, Long>();
    public HammerCommunity(HammerStore store) throws Exception {
        this.store = store;
        root = new File(store.root, "community");
        Hammer.require(root.isDirectory() || root.mkdir(), "Cannot open community storage");
    }
    static String author(String key) throws Exception { return Hammer.hash(Base64.getDecoder().decode(key)); }
    private File file(String scope, String target, String audience) throws Exception {
        Hammer.require((scope.equals("track") || scope.equals("board")) && Hammer.digest(target) &&
                           (audience.equals("public") || Hammer.digest(audience)),
                       "Invalid thread");
        return new File(root, scope + "-" + target + "-" + audience + ".bin");
    }
    private String owner(String scope, String target, String token) throws Exception {
        if (scope.equals("track")) {
            Hammer.require(store.audio(target, token) != null, "Track unavailable on this host");
            for (HammerStore.Hosted h : store.hosted())
                if (h.song.id.equals(target))
                    return h.song.key;
        } else if (scope.equals("board")) {
            store.catalog(token);
            for (HammerProfile p : store.publicObjects.profiles())
                if (p.id().equals(target))
                    return p.key;
        }
        throw new IOException("Artist board unavailable on this host");
    }
    public static String audience(String token) throws Exception {
        return token.isEmpty() ? "public" : Hammer.hash(token.getBytes(StandardCharsets.US_ASCII));
    }
    private List<Post> raw(String scope, String target, String audience) throws Exception {
        List<Post> posts = new ArrayList<Post>();
        File f = file(scope, target, audience);
        if (f.isFile())
            try (DataInputStream in =
                     new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 2097152)))) {
                int n = in.readInt();
                Hammer.require(n >= 0 && n <= LIMIT, "Thread capacity exceeded");
                for (int k = 0; k < n; k++) {
                    Post p = Post.read(in);
                    Hammer.require(p.scope.equals(scope) && p.target.equals(target) &&
                                       p.audience.equals(audience),
                                   "Wrong sidecar");
                    posts.add(p);
                }
                Hammer.require(in.read() == -1, "Trailing metadata");
            }
        return posts;
    }
    public synchronized List<Post> list(String scope, String target, String token) throws Exception {
        owner(scope, target, token);
        return raw(scope, target, scope.equals("board") ? "public" : audience(token));
    }
    public synchronized List<Post> visible(String scope, String target, String token) throws Exception {
        return visible(list(scope, target, token), store);
    }
    public static List<Post> visible(List<Post> posts, HammerStore local) throws Exception {
        Set<String> hidden = new HashSet<String>(), pinned = new HashSet<String>();
        for (Post p : posts) {
            if (p.kind.equals("hide"))
                hidden.add(p.parent);
            if (p.kind.equals("pin"))
                pinned.add(p.parent);
        }
        List<Post> result = new ArrayList<Post>();
        for (Post p : posts)
            if (!p.action() && !hidden.contains(p.id) && !local.blocked(p.key))
                result.add(p);
        final Set<String> pins = pinned;
        Collections.sort(result, new Comparator<Post>() {
            public int compare(Post a, Post b) {
                if (pins.contains(a.id) != pins.contains(b.id))
                    return pins.contains(a.id) ? -1 : 1;
                int c = Long.compare(a.time, b.time);
                return c == 0 ? a.id.compareTo(b.id) : c;
            }
        });
        return result;
    }
    private static Post find(List<Post> posts, String id) {
        for (Post p : posts)
            if (p.id.equals(id))
                return p;
        return null;
    }
    private void validateAppend(Post p, List<Post> posts, String owner) throws Exception {
        p.validate();
        Hammer.require(!store.blocked(p.key), "Author blocked on this host");
        if (p.action() || p.kind.equals("source"))
            Hammer.require(p.key.equals(owner), "Only the artist can moderate or link their source track");
        if (p.scope.equals("board") && p.parent.isEmpty())
            Hammer.require(p.key.equals(owner), "Artist starts board threads; fans reply");
        if (!p.parent.isEmpty()) {
            Post parent = find(posts, p.parent);
            Hammer.require(parent != null && !parent.action(), "Reply target is missing");
            String root = parent.id;
            for (int i = 0; !parent.parent.isEmpty() && i < LIMIT; i++) {
                parent = find(posts, parent.parent);
                Hammer.require(parent != null, "Broken thread");
                root = parent.id;
            }
            for (Post old : posts)
                if ((old.kind.equals("lock") && old.parent.equals(root) ||
                     old.kind.equals("hide") && old.parent.equals(p.parent)) &&
                    !p.action())
                    Hammer.require(p.key.equals(owner), "Thread is locked or reply target hidden");
        }
    }
    private void save(String scope, String target, String audience, List<Post> posts) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeInt(posts.size());
        for (Post p : posts)
            p.write(o);
        Hammer.require(b.size() <= 2097152, "Metadata storage full");
        HammerStore.atomic(file(scope, target, audience), b.toByteArray());
    }
    public synchronized void post(Post p, String token) throws Exception {
        String owner = owner(p.scope, p.target, token);
        Hammer.require(p.audience.equals(p.scope.equals("board") ? "public" : audience(token)),
                       "Wrong circle");
        List<Post> posts = raw(p.scope, p.target, p.audience);
        Post old = find(posts, p.id);
        if (old != null) {
            p.validate();
            Hammer.require(old.key.equals(p.key) && Arrays.equals(old.payload(), p.payload()),
                           "Post ID conflict");
            return;
        }
        validateAppend(p, posts, owner);
        Hammer.require(posts.size() < LIMIT, "Thread is full on this host");
        String author = author(p.key);
        long now = System.currentTimeMillis();
        Long previous = rates.get(author);
        Hammer.require(p.key.equals(owner) || previous == null || now - previous >= 3000,
                       "Please wait between posts");
        if (rates.size() >= 1024)
            for (Iterator<Long> it = rates.values().iterator(); it.hasNext();)
                if (now - it.next() >= 3000)
                    it.remove();
        Hammer.require(rates.size() < 1024 || rates.containsKey(author), "Host busy");
        posts.add(p);
        save(p.scope, p.target, p.audience, posts);
        rates.put(author, now);
    }
    /**
     * Merge only explicitly fetched public sidecars beside a verified local download. Never fetch host links
     * here.
     */
    public synchronized void importPublic(Hammer.Song song, List<Post> incoming) throws Exception {
        song.validate();
        File audio = store.downloadFile(song);
        Hammer.require(audio.isFile() && audio.length() == song.size && Hammer.hash(audio).equals(song.id),
                       "Verified download required");
        Hammer.require(incoming.size() <= LIMIT, "Too much metadata");
        List<Post> merged = raw("track", song.id, "public");
        for (Post p : incoming) {
            Hammer.require(p.scope.equals("track") && p.target.equals(song.id) && p.audience.equals("public"),
                           "Private or unrelated sidecar refused");
            Post old = find(merged, p.id);
            if (old != null) {
                p.validate();
                Hammer.require(old.key.equals(p.key) && Arrays.equals(old.payload(), p.payload()),
                               "Post ID conflict");
                continue;
            }
            validateAppend(p, merged, song.key);
            Hammer.require(merged.size() < LIMIT, "Thread full");
            merged.add(p);
        }
        save("track", song.id, "public", merged);
    }
    public synchronized List<Follower> followers() throws Exception {
        List<Follower> out = new ArrayList<Follower>();
        File f = new File(root, "followers.bin");
        if (f.isFile())
            try (DataInputStream i =
                     new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 262144)))) {
                int n = i.readInt();
                Hammer.require(n >= 0 && n <= 512, "Follower capacity");
                for (int k = 0; k < n; k++) {
                    Follower v = new Follower(Hammer.text(i, 256), Hammer.text(i, 128));
                    v.granted = Hammer.text(i, 64);
                    out.add(v);
                }
            }
        return out;
    }
    private void saveFollowers(List<Follower> followers) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeInt(followers.size());
        for (Follower f : followers) {
            Hammer.text(o, f.key, 256);
            Hammer.text(o, f.name, 128);
            Hammer.text(o, f.granted, 64);
        }
        HammerStore.atomic(new File(root, "followers.bin"), b.toByteArray());
    }
    public synchronized void invite(String recipient) throws Exception {
        List<Follower> fs = followers();
        boolean found = false;
        for (Follower f : fs)
            if (f.key.equals(recipient)) {
                Hammer.require(!store.blocked(f.key), "Follower blocked");
                f.granted = audience(store.token());
                found = true;
            }
        Hammer.require(found, "Follower no longer registered");
        saveFollowers(fs);
    }
    public synchronized String follow(Follow request, String pin) throws Exception {
        request.validate(pin);
        if (!request.action.equals("unfollow"))
            Hammer.require(!store.blocked(request.key), "Follower blocked");
        List<Follower> fs = followers();
        Follower found = null;
        for (Follower f : fs)
            if (f.key.equals(request.key))
                found = f;
        if (request.action.equals("unfollow")) {
            if (found != null) {
                fs.remove(found);
                saveFollowers(fs);
            }
            return "";
        }
        if (request.action.equals("follow")) {
            Hammer.require(store.publicObjects.own() != null,
                           "Publish an artist page before accepting followers");
            if (found == null) {
                Hammer.require(fs.size() < 512, "Follower registry full");
                found = new Follower(request.key, request.name);
                fs.add(found);
            }
            found.name = request.name;
            saveFollowers(fs);
        }
        return found != null && found.granted.equals(audience(store.token())) ? store.token() : "";
    }
    public static List<Post> list(Hammer.Card peer, String scope, String target, Hammer.Job job)
        throws Exception {
        List<Post> posts = new ArrayList<Post>();
        try (Hammer.Connection c = new Hammer.Connection(peer, LIST, job)) {
            Hammer.text(c.out, scope, 16);
            Hammer.text(c.out, target, 64);
            c.reply();
            int n = c.in.readInt();
            Hammer.require(n >= 0 && n <= LIMIT, "Thread capacity");
            Set<String> ids = new HashSet<String>();
            for (int k = 0; k < n; k++) {
                Post p = Post.read(c.in);
                Hammer.require(
                    p.scope.equals(scope) && p.target.equals(target) &&
                        p.audience.equals(scope.equals("board") ? "public" : audience(peer.token)) &&
                        ids.add(p.id),
                    "Wrong or duplicate thread record returned");
                posts.add(p);
            }
        }
        // Signature validity alone does not authorize moderation; validate against the signed publisher.
        String owner = null;
        if (scope.equals("track"))
            for (Hammer.Song s : Hammer.catalog(peer, job))
                if (s.id.equals(target))
                    owner = s.key;
        if (scope.equals("board"))
            for (HammerProfile p : Hammer.profiles(peer, job))
                if (p.id().equals(target))
                    owner = p.key;
        Hammer.require(owner != null, "Thread owner unavailable");
        for (Post p : posts)
            if (p.action() || p.kind.equals("source") || scope.equals("board") && p.parent.isEmpty())
                Hammer.require(p.key.equals(owner), "Forged artist action");
        return posts;
    }
    public static void post(Hammer.Card peer, Post post, Hammer.Job job) throws Exception {
        post.validate();
        try (Hammer.Connection c = new Hammer.Connection(peer, POST, job)) {
            post.write(c.out);
            c.reply();
        }
    }
    public static String follow(Hammer.Card peer, Follow request, Hammer.Job job) throws Exception {
        request.validate(peer.pin);
        try (Hammer.Connection c = new Hammer.Connection(peer, FOLLOW, job)) {
            request.write(c.out);
            c.reply();
            String token = Hammer.text(c.in, 64);
            Hammer.require(token.isEmpty() || Hammer.digest(token), "Invalid invitation");
            return token;
        }
    }
}
