package ai.jackknife.killayue.peer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Shared Android/desktop protocol. TLS authenticates the invite's key before any capability is sent. */
public final class Hammer {
    private Hammer() {}
    public static final int MAGIC = 0x484d5231, PORT = 8784, CHUNK = 262144, MAX_SONGS = 256;
    public static final long MAX_AUDIO = 1024L * 1024 * 1024;
    public static final int CATALOG = 1, AUDIO = 2, COMMENTS = 3, COMMENT = 4, PEERS = 5, INFO = 6,
                            SEGMENT = 7, VOTES = 8, VOTE = 9, SUBSCRIBE = 10;
    public static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte v : bytes) b.append(String.format(Locale.US, "%02x", v & 255));
        return b.toString();
    }
    public static String hash(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static String hash(File file) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) d.update(b, 0, n);
        }
        return hex(d.digest());
    }
    public static String random() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }
    public static boolean displayText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 32 && c != 10 && c != 9) || (c >= 127 && c < 160))
                return false;
        }
        return true;
    }
    public static boolean digest(String s) {
        return s != null && s.matches("[0-9a-f]{64}");
    }
    public static void require(boolean ok, String why) throws IOException {
        if (!ok)
            throw new IOException(why);
    }
    public static void text(DataOutputStream out, String s, int max) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        require(b.length <= max, "Text too long");
        out.writeInt(b.length);
        out.write(b);
    }
    public static String text(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        require(n >= 0 && n <= max, "Invalid text length");
        byte[] b = new byte[n];
        in.readFully(b);
        String s = new String(b, StandardCharsets.UTF_8);
        require(Arrays.equals(b, s.getBytes(StandardCharsets.UTF_8)), "Invalid UTF-8");
        return s;
    }
    static byte[] signature(PrivateKey key, byte[] bytes) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(bytes);
        return s.sign();
    }
    static boolean verify(String key, String sig, byte[] bytes) throws Exception {
        PublicKey k = KeyFactory.getInstance("EC").generatePublic(
            new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
        if (!(k instanceof java.security.interfaces.ECPublicKey)
            || ((java.security.interfaces.ECPublicKey) k).getParams().getCurve().getField().getFieldSize()
                != 256)
            return false;
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(k);
        s.update(bytes);
        return s.verify(Base64.getDecoder().decode(sig));
    }
    public static String publicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** The fragment contains the pinned identity and optional private capability; never an HTTP URL. */
    public static final class Card {
        public final String host, pin, token;
        public final int port;
        public Card(String host, int port, String pin, String token) throws IOException {
            require(host != null && host.length() > 0 && host.length() <= 253
                    && host.matches("[a-zA-Z0-9.:%_-]+"),
                "Invalid host");
            require(port > 0 && port <= 65535 && digest(pin) && (token.isEmpty() || digest(token)),
                "Invalid peer card");
            this.host = host;
            this.port = port;
            this.pin = pin;
            this.token = token;
        }
        public boolean isPrivate() {
            return !token.isEmpty();
        }
        public String encode() {
            return "hammer://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + "/#" + pin
                + (isPrivate() ? "." + token : "");
        }
        public static Card parse(String value) throws Exception {
            require(value.length() <= 1024, "Peer card too long");
            URI u = new URI(value.trim());
            require("hammer".equals(u.getScheme()) && u.getRawUserInfo() == null && u.getRawQuery() == null
                    && "/".equals(u.getRawPath()) && u.getHost() != null,
                "Use a HAMMER peer card");
            String[] f = String.valueOf(u.getRawFragment()).split("\\.", -1);
            require(f.length == 1 || f.length == 2, "Invalid peer identity");
            String host = u.getHost();
            if (host.startsWith("["))
                host = host.substring(1, host.length() - 1);
            return new Card(host, u.getPort(), f[0], f.length == 2 ? f[1] : "");
        }
    }

    /** Signed publication metadata survives rehosting; visibility is local to each host. */
    public static final class Song {
        public String id, title, tags, type, key, sig;
        public long size;
        public boolean reshare;
        public Song(String id, String title, String tags, String type, long size, boolean reshare) {
            this.id = id;
            this.title = title;
            this.tags = tags;
            this.type = type;
            this.size = size;
            this.reshare = reshare;
        }
        byte[] payload() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            text(o, "HAMMER publication 1", 64);
            text(o, id, 64);
            text(o, title, 512);
            text(o, tags, 1024);
            text(o, type, 16);
            o.writeLong(size);
            o.writeBoolean(reshare);
            return b.toByteArray();
        }
        public void sign(KeyPair pair) throws Exception {
            key = publicKey(pair.getPublic());
            sig = Base64.getEncoder().encodeToString(signature(pair.getPrivate(), payload()));
        }
        public void validate() throws Exception {
            require(displayText(title) && displayText(tags), "Invalid display text");
            require(digest(id) && size > 0 && size <= MAX_AUDIO
                    && ("audio/wav".equals(type) || "audio/mpeg".equals(type) || "audio/flac".equals(type)),
                "Invalid song");
            require(verify(key, sig, payload()), "Song signature does not match");
        }
        public String extension() {
            return "audio/wav".equals(type) ? ".wav" : "audio/flac".equals(type) ? ".flac" : ".mp3";
        }
        public boolean matches(String q) {
            String all = (title + " " + tags).toLowerCase(Locale.ROOT);
            for (String word : q.toLowerCase(Locale.ROOT).trim().split("\\s+"))
                if (!all.contains(word))
                    return false;
            return true;
        }
        public void write(DataOutputStream o) throws IOException {
            text(o, id, 64);
            text(o, title, 512);
            text(o, tags, 1024);
            text(o, type, 16);
            o.writeLong(size);
            o.writeBoolean(reshare);
            text(o, key, 256);
            text(o, sig, 256);
        }
        public static Song read(DataInputStream i) throws Exception {
            Song s = new Song(
                text(i, 64), text(i, 512), text(i, 1024), text(i, 16), i.readLong(), i.readBoolean());
            s.key = text(i, 256);
            s.sig = text(i, 256);
            s.validate();
            return s;
        }
    }

    public static final class Comment {
        public String song, id, name, body, key, sig, audience = "public";
        public long time;
        byte[] payload() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            text(o, "HAMMER comment 1", 64);
            text(o, song, 64);
            text(o, id, 64);
            text(o, audience, 64);
            o.writeLong(time);
            text(o, name, 128);
            text(o, body, 2048);
            return b.toByteArray();
        }
        public void sign(KeyPair pair) throws Exception {
            key = publicKey(pair.getPublic());
            sig = Base64.getEncoder().encodeToString(signature(pair.getPrivate(), payload()));
        }
        public void validate() throws Exception {
            require(displayText(name) && displayText(body), "Invalid comment text");
            require(digest(song) && digest(id) && ("public".equals(audience) || digest(audience)) && time > 0
                    && !body.trim().isEmpty(),
                "Invalid comment");
            require(verify(key, sig, payload()), "Comment signature does not match");
        }
        public void write(DataOutputStream o) throws IOException {
            text(o, song, 64);
            text(o, id, 64);
            text(o, audience, 64);
            o.writeLong(time);
            text(o, name, 128);
            text(o, body, 2048);
            text(o, key, 256);
            text(o, sig, 256);
        }
        public static Comment read(DataInputStream i) throws Exception {
            Comment c = new Comment();
            c.song = text(i, 64);
            c.id = text(i, 64);
            c.audience = text(i, 64);
            c.time = i.readLong();
            c.name = text(i, 128);
            c.body = text(i, 2048);
            c.key = text(i, 256);
            c.sig = text(i, 256);
            c.validate();
            return c;
        }
    }

    public interface Source {
        List<Song> catalog(String capability) throws Exception;
        File audio(String id, String capability) throws Exception;
        List<Comment> comments(String id, String capability) throws Exception;
        void comment(Comment comment, String capability) throws Exception;
        List<Card> peers();
        List<Vote> votes(String id, String capability) throws Exception;
        void vote(Vote vote, String capability) throws Exception;
        List<Song> changed(String capability, String cursor, long waitMillis) throws Exception;
    }
    public static final class Vote {
        public String song, key, sig, audience = "public";
        public int value;
        public long sequence;
        byte[] payload() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            text(o, "HAMMER vote 1", 64);
            text(o, song, 64);
            text(o, audience, 64);
            o.writeInt(value);
            o.writeLong(sequence);
            return b.toByteArray();
        }
        public void sign(KeyPair pair) throws Exception {
            key = publicKey(pair.getPublic());
            sig = Base64.getEncoder().encodeToString(signature(pair.getPrivate(), payload()));
        }
        public void validate() throws Exception {
            require(digest(song) && ("public".equals(audience) || digest(audience)) && value >= -1
                    && value <= 1 && sequence > 0 && sequence <= 1000000000,
                "Invalid vote");
            require(verify(key, sig, payload()), "Vote signature does not match");
        }
        public void write(DataOutputStream o) throws IOException {
            text(o, song, 64);
            text(o, audience, 64);
            o.writeInt(value);
            o.writeLong(sequence);
            text(o, key, 256);
            text(o, sig, 256);
        }
        public static Vote read(DataInputStream i) throws Exception {
            Vote v = new Vote();
            v.song = text(i, 64);
            v.audience = text(i, 64);
            v.value = i.readInt();
            v.sequence = i.readLong();
            v.key = text(i, 256);
            v.sig = text(i, 256);
            v.validate();
            return v;
        }
    }
    public static String cursor(List<Song> songs) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        for (Song s : songs) {
            text(o, s.id, 64);
            text(o, s.sig, 256);
        }
        return hash(b.toByteArray());
    }
    static void secure(SSLSocket s) throws IOException {
        List<String> protocols = new ArrayList<String>(), suites = new ArrayList<String>();
        for (String p : s.getSupportedProtocols())
            if (p.equals("TLSv1.3") || p.equals("TLSv1.2"))
                protocols.add(p);
        for (String c : s.getSupportedCipherSuites())
            if (c.equals("TLS_AES_128_GCM_SHA256") || c.equals("TLS_AES_256_GCM_SHA384")
                || c.equals("TLS_CHACHA20_POLY1305_SHA256")
                || c.equals("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")
                || c.equals("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"))
                suites.add(c);
        require(!protocols.isEmpty() && !suites.isEmpty(), "Secure TLS unavailable");
        s.setEnabledProtocols(protocols.toArray(new String[0]));
        s.setEnabledCipherSuites(suites.toArray(new String[0]));
        // Release events and request headers should not wait for Nagle/delayed-ACK batching.
        s.setTcpNoDelay(true);
        s.setSoTimeout(15000);
    }
    public static SSLContext serverContext(final KeyPair pair, final X509Certificate cert) throws Exception {
        X509ExtendedKeyManager keys = new X509ExtendedKeyManager() {
            public String[] getClientAliases(String t, Principal[] p) {
                return null;
            }
            public String chooseClientAlias(String[] t, Principal[] p, Socket s) {
                return null;
            }
            public String[] getServerAliases(String t, Principal[] p) {
                return "EC".equals(t) ? new String[] {"hammer"} : null;
            }
            public String chooseServerAlias(String t, Principal[] p, Socket s) {
                return "EC".equals(t) ? "hammer" : null;
            }
            public X509Certificate[] getCertificateChain(String a) {
                return new X509Certificate[] {cert};
            }
            public PrivateKey getPrivateKey(String a) {
                return pair.getPrivate();
            }
        };
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(new KeyManager[] {keys}, null, new SecureRandom());
        return c;
    }
    public static SSLContext clientContext(final String pin) throws Exception {
        require(digest(pin), "Invalid pinned identity");
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
            public void checkClientTrusted(X509Certificate[] chain, String auth)
                throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("No client CA");
            }
            public void checkServerTrusted(X509Certificate[] chain, String auth)
                throws java.security.cert.CertificateException {
                try {
                    require(chain != null && chain.length > 0, "Missing identity");
                    chain[0].checkValidity();
                    require(
                        MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII),
                            hash(chain[0].getPublicKey().getEncoded()).getBytes(StandardCharsets.US_ASCII)),
                        "Peer identity changed");
                } catch (Exception e) {
                    throw new java.security.cert.CertificateException("Peer identity verification failed", e);
                }
            }
        };
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, new TrustManager[] {trust}, new SecureRandom());
        return c;
    }
    public static final class Job {
        public volatile boolean cancelled;
        public volatile long done, total;
        private Socket socket;
        public synchronized void cancel() {
            cancelled = true;
            close(socket);
        }
        synchronized void attach(Socket s) throws IOException {
            if (cancelled) {
                close(s);
                throw new IOException("Cancelled");
            }
            socket = s;
        }
        public void check() throws IOException {
            require(!cancelled, "Cancelled");
        }
    }
    static void close(Socket s) {
        if (s != null)
            try {
                s.close();
            } catch (IOException ignored) {
            }
    }
    static final ScheduledThreadPoolExecutor DEADLINES =
        new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "hammer-deadlines");
                t.setDaemon(true);
                return t;
            }
        });
    static {
        DEADLINES.setRemoveOnCancelPolicy(true);
    }
    static ScheduledFuture<?> deadline(final Socket s) {
        return DEADLINES.schedule(new Runnable() {
            public void run() {
                close(s);
            }
        }, 60, TimeUnit.SECONDS);
    }

    public static final class Server implements Closeable {
        private final SSLServerSocket listener;
        private final Source source;
        private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
        private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(4));
        private final ThreadPoolExecutor subscribers =
            new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
        public Server(SSLContext context, InetSocketAddress address, Source source) throws Exception {
            this.source = source;
            listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            listener.setReuseAddress(true);
            listener.bind(address, 8);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    accept();
                }
            }, "hammer-host");
            t.setDaemon(true);
            t.start();
        }
        public int port() {
            return listener.getLocalPort();
        }
        private void accept() {
            while (!listener.isClosed()) try {
                    final SSLSocket s = (SSLSocket) listener.accept();
                    clients.add(s);
                    try {
                        workers.execute(new Runnable() {
                            public void run() {
                                handle(s);
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        clients.remove(s);
                        Hammer.close(s);
                    }
                } catch (IOException e) {
                    break;
                }
        }
        private void handle(SSLSocket s) {
            ScheduledFuture<?> end = deadline(s);
            boolean handedOff = false;
            try {
                secure(s);
                s.startHandshake();
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                require(in.readInt() == MAGIC, "Unsupported HAMMER protocol");
                int op = in.readUnsignedByte();
                String token = text(in, 64);
                if (op == CATALOG) {
                    List<Song> songs = source.catalog(token);
                    require(songs.size() <= MAX_SONGS, "Catalog full");
                    out.writeInt(MAGIC);
                    out.writeInt(songs.size());
                    for (Song song : songs) song.write(out);
                } else if (op == AUDIO) {
                    String id = text(in, 64);
                    require(digest(id), "Invalid song");
                    long offset = in.readLong();
                    int count = in.readInt();
                    File f = source.audio(id, token);
                    require(f != null && f.isFile(), "Song unavailable");
                    require(offset >= 0 && offset < f.length() && count > 0 && count <= CHUNK,
                        "Invalid audio range");
                    int length = (int) Math.min(count, f.length() - offset);
                    byte[] bytes = HammerNative.range(f.getPath(), offset, length);
                    // Recheck visibility after disk I/O, so revocation cannot start a new response.
                    require(source.audio(id, token) != null, "Song withdrawn");
                    out.writeInt(MAGIC);
                    out.writeInt(length);
                    text(out, hash(bytes), 64);
                    out.write(bytes);
                } else if (op == COMMENTS) {
                    String id = text(in, 64);
                    List<Comment> cs = source.comments(id, token);
                    require(cs.size() <= 128, "Too many comments");
                    out.writeInt(MAGIC);
                    out.writeInt(cs.size());
                    for (Comment c : cs) c.write(out);
                } else if (op == COMMENT) {
                    source.comment(Comment.read(in), token);
                    out.writeInt(MAGIC);
                } else if (op == PEERS) {
                    source.catalog(token);
                    List<Card> ps = source.peers();
                    require(ps.size() <= 32, "Too many peers");
                    out.writeInt(MAGIC);
                    out.writeInt(ps.size());
                    for (Card p : ps) {
                        require(!p.isPrivate(), "Private invite in directory");
                        text(out, p.encode(), 1024);
                    }
                } else if (op == INFO) {
                    File f = source.audio(text(in, 64), token);
                    require(f != null, "Song unavailable");
                    long frames = HammerNative.frames(f.getPath());
                    out.writeInt(MAGIC);
                    out.writeLong(frames);
                } else if (op == SEGMENT) {
                    String id = text(in, 64);
                    int segment = in.readInt(), bitrate = in.readInt();
                    File f = source.audio(id, token);
                    require(f != null, "Song unavailable");
                    byte[] bytes = HammerNative.encode(f.getPath(), id, segment, bitrate);
                    require(source.audio(id, token) != null, "Song withdrawn");
                    out.writeInt(MAGIC);
                    out.writeInt(bytes.length);
                    text(out, hash(bytes), 64);
                    out.write(bytes);
                } else if (op == VOTES) {
                    List<Vote> votes = source.votes(text(in, 64), token);
                    out.writeInt(MAGIC);
                    out.writeInt(votes.size());
                    for (Vote v : votes) v.write(out);
                } else if (op == VOTE) {
                    source.vote(Vote.read(in), token);
                    out.writeInt(MAGIC);
                } else if (op == SUBSCRIBE) {
                    source.catalog(token);
                    final String capability = token;
                    final SSLSocket channel = s;
                    final DataOutputStream output = out;
                    subscribers.execute(new Runnable() {
                        public void run() {
                            subscription(channel, output, capability);
                        }
                    });
                    handedOff = true;
                    return;
                } else
                    throw new IOException("Unknown request");
                out.flush();
            } catch (Exception ignored) { /* No credentials, song names or request contents in logs. */
            } finally {
                end.cancel(false);
                if (!handedOff) {
                    clients.remove(s);
                    Hammer.close(s);
                }
            }
        }
        private void subscription(SSLSocket s, DataOutputStream out, String token) {
            try {
                String last = "";
                while (!s.isClosed()) {
                    List<Song> changed = source.changed(token, last, 25000);
                    ScheduledFuture<?> end = deadline(s);
                    try {
                        out.writeInt(MAGIC);
                        out.writeInt(changed == null ? -1 : changed.size());
                        if (changed != null) {
                            for (Song song : changed) song.write(out);
                            last = cursor(changed);
                        }
                        out.flush();
                    } finally {
                        end.cancel(false);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                clients.remove(s);
                Hammer.close(s);
            }
        }
        public void disconnect() {
            synchronized (clients) {
                for (Socket s : clients) Hammer.close(s);
            }
        }
        public void close() {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            disconnect();
            workers.shutdownNow();
            subscribers.shutdownNow();
        }
    }
    static final class Connection implements Closeable {
        final SSLSocket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final ScheduledFuture<?> end;
        Connection(Card card, int op, Job job) throws Exception {
            socket = (SSLSocket) clientContext(card.pin).getSocketFactory().createSocket();
            end = deadline(socket);
            try {
                job.attach(socket);
                secure(socket);
                socket.connect(new InetSocketAddress(card.host, card.port), 8000);
                socket.startHandshake();
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                out.writeInt(MAGIC);
                out.writeByte(op);
                text(out, card.token, 64);
            } catch (Exception e) {
                end.cancel(false);
                Hammer.close(socket);
                throw e;
            }
        }
        void reply() throws IOException {
            out.flush();
            require(in.readInt() == MAGIC, "Unsupported peer reply");
        }
        public void close() {
            end.cancel(false);
            Hammer.close(socket);
        }
    }
    public static List<Song> catalog(Card p, Job job) throws Exception {
        try (Connection c = new Connection(p, CATALOG, job)) {
            c.reply();
            int n = c.in.readInt();
            require(n >= 0 && n <= MAX_SONGS, "Invalid catalog size");
            List<Song> songs = new ArrayList<Song>();
            Set<String> ids = new HashSet<String>();
            for (int i = 0; i < n; i++) {
                Song s = Song.read(c.in);
                require(ids.add(s.id), "Duplicate song");
                songs.add(s);
            }
            return songs;
        }
    }
    public static byte[] chunk(Card p, Song s, long offset, Job job) throws Exception {
        require(offset >= 0 && offset < s.size, "Invalid audio offset");
        int count = (int) Math.min(CHUNK, s.size - offset);
        try (Connection c = new Connection(p, AUDIO, job)) {
            text(c.out, s.id, 64);
            c.out.writeLong(offset);
            c.out.writeInt(count);
            c.reply();
            require(c.in.readInt() == count, "Audio size changed");
            String hash = text(c.in, 64);
            byte[] b = new byte[count];
            c.in.readFully(b);
            require(hash(b).equals(hash), "Audio chunk damaged");
            return b;
        }
    }
    public static void download(Card peer, Song song, File part, File target, Job job) throws Exception {
        require(song.reshare, "Publisher has enabled streaming only");
        song.validate();
        require(!target.exists(), "Download already exists");
        long offset = part.isFile() ? part.length() : 0;
        if (offset > song.size) {
            require(part.delete(), "Cannot reset partial download");
            offset = 0;
        }
        job.total = song.size;
        job.done = offset;
        try (RandomAccessFile out = new RandomAccessFile(part, "rw")) {
            out.seek(offset);
            while (offset < song.size) {
                job.check();
                byte[] b = chunk(peer, song, offset, job);
                out.write(b);
                offset += b.length;
                job.done = offset;
            }
            out.getFD().sync();
        }
        job.check();
        if (!hash(part).equals(song.id)) {
            part.delete();
            throw new IOException("Download checksum failed; retry from the beginning");
        }
        require(part.renameTo(target), "Could not finish download");
    }
    public static List<Comment> comments(Card p, String song, Job job) throws Exception {
        try (Connection c = new Connection(p, COMMENTS, job)) {
            text(c.out, song, 64);
            c.reply();
            int n = c.in.readInt();
            require(n >= 0 && n <= 128, "Invalid comment count");
            List<Comment> cs = new ArrayList<Comment>();
            for (int i = 0; i < n; i++) {
                Comment v = Comment.read(c.in);
                require(song.equals(v.song), "Wrong comment thread");
                cs.add(v);
            }
            return cs;
        }
    }
    public static void comment(Card p, Comment comment, Job job) throws Exception {
        comment.validate();
        try (Connection c = new Connection(p, COMMENT, job)) {
            comment.write(c.out);
            c.reply();
        }
    }
    public static List<Card> peers(Card p, Job job) throws Exception {
        try (Connection c = new Connection(p, PEERS, job)) {
            c.reply();
            int n = c.in.readInt();
            require(n >= 0 && n <= 32, "Invalid directory size");
            List<Card> ps = new ArrayList<Card>();
            for (int i = 0; i < n; i++) {
                Card card = Card.parse(text(c.in, 1024));
                require(!card.isPrivate(), "Private invite rejected");
                ps.add(card);
            }
            return ps;
        }
    }
    public static long frames(Card p, String id, Job job) throws Exception {
        try (Connection c = new Connection(p, INFO, job)) {
            text(c.out, id, 64);
            c.reply();
            long frames = c.in.readLong();
            require(frames > 0 && frames < 1000000000L, "Unsupported stream duration");
            return frames;
        }
    }
    public static byte[] segment(Card p, String id, int segment, int bitrate, Job job) throws Exception {
        try (Connection c = new Connection(p, SEGMENT, job)) {
            text(c.out, id, 64);
            c.out.writeInt(segment);
            c.out.writeInt(bitrate);
            c.reply();
            int n = c.in.readInt();
            require(n > 0 && n <= 524288, "Invalid encoded segment size");
            String digest = text(c.in, 64);
            byte[] b = new byte[n];
            c.in.readFully(b);
            require(hash(b).equals(digest), "Encoded segment damaged");
            return b;
        }
    }
    public static List<Vote> votes(Card p, String song, Job job) throws Exception {
        try (Connection c = new Connection(p, VOTES, job)) {
            text(c.out, song, 64);
            c.reply();
            int n = c.in.readInt();
            require(n >= 0 && n <= 4096, "Too many votes");
            List<Vote> out = new ArrayList<Vote>();
            for (int i = 0; i < n; i++) {
                Vote v = Vote.read(c.in);
                require(song.equals(v.song), "Wrong vote thread");
                out.add(v);
            }
            return out;
        }
    }
    public static void vote(Card p, Vote vote, Job job) throws Exception {
        vote.validate();
        try (Connection c = new Connection(p, VOTE, job)) {
            vote.write(c.out);
            c.reply();
        }
    }
    public interface Listener {
        void catalog(List<Song> songs) throws Exception;
    }
    public static void subscribe(Card peer, Job job, Listener listener) throws Exception {
        try (Connection c = new Connection(peer, SUBSCRIBE, job)) {
            c.end.cancel(false);
            c.socket.setSoTimeout(65000);
            c.out.flush();
            while (!job.cancelled) {
                require(c.in.readInt() == MAGIC, "Invalid push event");
                int n = c.in.readInt();
                if (n == -1)
                    continue;
                require(n >= 0 && n <= MAX_SONGS, "Invalid pushed catalog");
                List<Song> songs = new ArrayList<Song>();
                Set<String> ids = new HashSet<String>();
                for (int i = 0; i < n; i++) {
                    Song song = Song.read(c.in);
                    require(ids.add(song.id), "Duplicate pushed song");
                    songs.add(song);
                }
                job.check();
                listener.catalog(songs);
            }
        }
    }
}
