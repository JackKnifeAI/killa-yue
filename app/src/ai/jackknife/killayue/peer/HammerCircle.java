package ai.jackknife.killayue.peer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A listening circle: a room where people listen together and talk, and its board. The circle is a record signed by
 * the person who started it, naming the few hosts that carry it. Everything said in it is sealed with the circle's
 * key before it leaves a phone, so a host carries only sealed envelopes: it can't read them, and it can't tell who
 * sent them. Inside, every message is signed by its sender's key for this circle, so nobody can speak as anyone else.
 * An open circle publishes its key in its record, so anyone can join; an invite-only circle's key travels only in
 * its invitation. The record is signed over everything but the key, so a host checks it without learning the key,
 * and the id is a hash of the key, which ties the two. See docs/designs/2026-11-04-circles.md.
 */
public final class HammerCircle {
    public static final int TITLE = 80, ABOUT = 280, HOSTS = 4;
    /** Lane 0 is the room: chat, reactions, the queue, what's playing. Lane 1 is the board, kept far longer. */
    public static final int ROOM = 0, BOARD = 1;
    /** The proof-of-work each envelope pays, in leading zero bits: a moment for a room line, a second for a post. */
    static final int ROOM_BITS = 16, BOARD_BITS = 18, RECORD_BITS = 18;
    /** The largest sealed envelope. */
    public static final int SEALED = 6144;

    public String id = "", title = "", about = "", secret = "", key = "", sig = "";
    public boolean open;
    public long created, revision, stamp;
    public List<String> hosts = new ArrayList<String>();

    // ---------- the record ----------

    /** A new circle with a fresh secret, the id being the secret's hash. Sign it before handing it out. */
    public static HammerCircle start(String title, String about, boolean open) throws Exception {
        HammerCircle c = new HammerCircle();
        byte[] s = new byte[32];
        new SecureRandom().nextBytes(s);
        c.secret = Hammer.hex(s);
        c.id = idOf(c.secret);
        c.title = title;
        c.about = about;
        c.open = open;
        c.created = System.currentTimeMillis();
        c.revision = c.created;
        return c;
    }
    public static String idOf(String secret) throws Exception {
        Hammer.require(Hammer.digest(secret), "Invalid circle key");
        return Hammer.hash(("HAMMER circle id 1" + secret).getBytes(StandardCharsets.US_ASCII));
    }
    private void fields(DataOutputStream o, boolean withSecret) throws IOException {
        Hammer.text(o, "HAMMER circle 1", 32);
        Hammer.text(o, id, 64);
        Hammer.text(o, title, TITLE * 4);
        Hammer.text(o, about, ABOUT * 4);
        o.writeBoolean(open);
        Hammer.text(o, open && withSecret ? secret : "", 64);
        o.writeLong(created);
        o.writeLong(revision);
        o.writeInt(hosts.size());
        for (String h : hosts)
            Hammer.text(o, h, 1024);
    }
    /** What the starter signs: everything but the key, so anyone can check a record, and the id binds the key. */
    private byte[] payload() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        fields(new DataOutputStream(b), false);
        return b.toByteArray();
    }
    /** Signs with the starter's key for this circle and pays the record's stamp. */
    public void sign(KeyPair pair) throws Exception {
        key = Hammer.publicKey(pair.getPublic());
        sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        stamp = work(recordWork(), RECORD_BITS);
        validate();
    }
    private byte[] recordWork() throws Exception {
        return (id + "/" + revision + "/" + key).getBytes(StandardCharsets.US_ASCII);
    }
    public void validate() throws Exception {
        Hammer.require(Hammer.digest(id) && !title.trim().isEmpty() && title.length() <= TITLE &&
                           about.length() <= ABOUT && Hammer.displayText(title) && Hammer.displayText(about) &&
                           created > 0 && revision > 0 && hosts.size() <= HOSTS,
                       "Invalid circle");
        Hammer.require(open ? Hammer.digest(secret) && idOf(secret).equals(id) : secret.isEmpty() || idOf(secret).equals(id),
                       "The circle's key doesn't match it");
        for (String h : hosts)
            Hammer.require(!Hammer.Card.parse(h).isPrivate(), "A circle names public hosts only");
        Hammer.require(stampOk(recordWork(), stamp, RECORD_BITS), "The circle's stamp doesn't match");
        Hammer.require(Hammer.verify(key, sig, payload()), "Circle signature does not match");
    }
    /** The record as handed out: an invite-only circle's key is left out, and travels only in its invitation. */
    public void write(DataOutputStream o) throws IOException {
        fields(o, true);
        Hammer.text(o, key, 256);
        Hammer.text(o, sig, 256);
        o.writeLong(stamp);
    }
    /** Reads a record; {@code secret} is the invitation's key for an invite-only circle, or empty. */
    public static HammerCircle read(DataInputStream in, String secret) throws Exception {
        Hammer.require("HAMMER circle 1".equals(Hammer.text(in, 32)), "Unsupported circle record");
        HammerCircle c = new HammerCircle();
        c.id = Hammer.text(in, 64);
        c.title = Hammer.text(in, TITLE * 4);
        c.about = Hammer.text(in, ABOUT * 4);
        c.open = in.readBoolean();
        c.secret = Hammer.text(in, 64);
        c.created = in.readLong();
        c.revision = in.readLong();
        int n = in.readInt();
        Hammer.require(n >= 0 && n <= HOSTS, "Invalid circle hosts");
        for (int i = 0; i < n; i++)
            c.hosts.add(Hammer.text(in, 1024));
        c.key = Hammer.text(in, 256);
        c.sig = Hammer.text(in, 256);
        c.stamp = in.readLong();
        if (!c.open && !secret.isEmpty())
            c.secret = secret;
        c.validate();
        return c;
    }
    /** This record with its key held back, as a host must keep an invite-only one. */
    public HammerCircle carried() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        String kept = secret;
        if (!open)
            secret = "";
        try {
            write(new DataOutputStream(b));
        } finally {
            secret = kept;
        }
        return read(new DataInputStream(new ByteArrayInputStream(b.toByteArray())), "");
    }
    public List<Hammer.Card> hostCards() {
        List<Hammer.Card> out = new ArrayList<Hammer.Card>();
        for (String h : hosts)
            try {
                out.add(Hammer.Card.parse(h));
            } catch (Exception unreadable) {
            }
        return out;
    }
    /** The invitation: the circle, its key and a host to find it at, nothing else. */
    public String invitation() throws Exception {
        Hammer.require(!secret.isEmpty(), "No key to invite with");
        String via = hosts.isEmpty() ? "" : "&via=" + java.net.URLEncoder.encode(hosts.get(0), "UTF-8");
        return "killayue://c/" + id + "?k=" + secret + via;
    }

    // ---------- proof of work ----------

    static long work(byte[] prefix, int bits) throws Exception {
        for (long n = 0;; n++)
            if (stampOk(prefix, n, bits))
                return n;
    }
    static boolean stampOk(byte[] prefix, long nonce, int bits) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update(prefix);
        for (int i = 7; i >= 0; i--)
            d.update((byte)(nonce >>> (8 * i)));
        byte[] h = d.digest();
        for (int i = 0; i < bits; i++)
            if ((h[i / 8] & (0x80 >>> (i % 8))) != 0)
                return false;
        return true;
    }

    // ---------- sealed envelopes ----------

    /** What a host holds: the circle, an id, the lane, when, the stamp, and bytes it can't read. */
    public static final class Envelope {
        public String circle, id;
        public int lane;
        public long time, stamp;
        public byte[] sealed;
        byte[] work() throws Exception {
            return (circle + "/" + id + "/" + lane + "/" + time + "/" + Hammer.hash(sealed))
                .getBytes(StandardCharsets.US_ASCII);
        }
        public void validate() throws Exception {
            Hammer.require(Hammer.digest(circle) && Hammer.digest(id) && (lane == ROOM || lane == BOARD) && time > 0 &&
                               sealed != null && sealed.length > 28 && sealed.length <= SEALED,
                           "Invalid circle message");
            Hammer.require(stampOk(work(), stamp, lane == BOARD ? BOARD_BITS : ROOM_BITS),
                           "The message's stamp doesn't match");
        }
        public void write(DataOutputStream o) throws IOException {
            Hammer.text(o, circle, 64);
            Hammer.text(o, id, 64);
            o.writeByte(lane);
            o.writeLong(time);
            o.writeLong(stamp);
            o.writeInt(sealed.length);
            o.write(sealed);
        }
        public static Envelope read(DataInputStream in) throws Exception {
            Envelope e = new Envelope();
            e.circle = Hammer.text(in, 64);
            e.id = Hammer.text(in, 64);
            e.lane = in.readUnsignedByte();
            e.time = in.readLong();
            e.stamp = in.readLong();
            int n = in.readInt();
            Hammer.require(n > 28 && n <= SEALED, "Invalid circle message");
            e.sealed = new byte[n];
            in.readFully(e.sealed);
            e.validate();
            return e;
        }
    }

    /** One thing said in a circle, as members read it once unsealed. */
    public static final class Message {
        /** room: chat, react, here, add, vote, now; board: post, remove. */
        public String kind = "chat", name = "", body = "", ref = "", key = "", sig = "";
        public Hammer.Song song;
        public List<String> cards = new ArrayList<String>();
        public long start;
        public int length;
        // filled in when unsealed
        public String id = "";
        public long time;
        public int lane;
        static final Set<String> ROOM_KINDS = new HashSet<String>(Arrays.asList("chat", "react", "here", "add", "vote", "now"));
        static final Set<String> BOARD_KINDS = new HashSet<String>(Arrays.asList("post", "remove"));
        public int lane() { return BOARD_KINDS.contains(kind) ? BOARD : ROOM; }
        private byte[] payload(String circle, String id, long time) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            Hammer.text(o, "HAMMER circle message 1", 32);
            Hammer.text(o, circle, 64);
            Hammer.text(o, id, 64);
            o.writeLong(time);
            Hammer.text(o, kind, 16);
            Hammer.text(o, name, 128);
            Hammer.text(o, body, 4000);
            Hammer.text(o, ref, 256);
            o.writeBoolean(song != null);
            if (song != null)
                song.write(o);
            o.writeInt(cards.size());
            for (String c : cards)
                Hammer.text(o, c, 1024);
            o.writeLong(start);
            o.writeInt(length);
            return b.toByteArray();
        }
        void check() throws Exception {
            Hammer.require(ROOM_KINDS.contains(kind) || BOARD_KINDS.contains(kind), "Unknown circle message");
            Hammer.require(Hammer.displayText(name) && name.length() <= 40 && Hammer.displayText(body) &&
                               body.length() <= (kind.equals("post") ? 1000 : 300) && cards.size() <= 3 &&
                               start >= 0 && length >= 0,
                           "Invalid circle message");
            if (kind.equals("chat") || kind.equals("post"))
                Hammer.require(!body.trim().isEmpty(), "Say something");
            if (kind.equals("react"))
                Hammer.require(!body.isEmpty() && body.length() <= 8, "A reaction is one mark");
            if (kind.equals("add") || kind.equals("now")) {
                Hammer.require(song != null && length > 0 && !cards.isEmpty(), "A song needs its record and a host");
                song.validate();
                for (String c : cards)
                    Hammer.require(!Hammer.Card.parse(c).isPrivate(), "Never share a private invitation");
            } else
                Hammer.require(song == null, "Unexpected song");
            if (kind.equals("now"))
                Hammer.require(start > 0 && Hammer.digest(ref), "Now needs its start and the song added");
            if (kind.equals("vote"))
                Hammer.require(Hammer.digest(ref), "Vote for a song added");
            if (kind.equals("remove"))
                Hammer.require(!ref.isEmpty(), "Remove whom?");
            if (kind.equals("post"))
                Hammer.require(ref.isEmpty() || Hammer.digest(ref), "Reply to a post");
        }
        /** Signs and seals it for this circle, paying the lane's stamp. */
        public Envelope seal(HammerCircle circle, KeyPair member) throws Exception {
            check();
            Envelope e = new Envelope();
            e.circle = circle.id;
            e.id = Hammer.random();
            e.lane = lane();
            e.time = System.currentTimeMillis();
            key = Hammer.publicKey(member.getPublic());
            sig = Base64.getEncoder().encodeToString(Hammer.signature(member.getPrivate(), payload(e.circle, e.id, e.time)));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            o.write(payload(e.circle, e.id, e.time));
            Hammer.text(o, key, 256);
            Hammer.text(o, sig, 256);
            e.sealed = circle.crypt(Cipher.ENCRYPT_MODE, e, b.toByteArray());
            Hammer.require(e.sealed.length <= SEALED, "That's too long to send");
            e.stamp = work(e.work(), e.lane == BOARD ? BOARD_BITS : ROOM_BITS);
            id = e.id;
            time = e.time;
            lane = e.lane;
            return e;
        }
        /** Unseals and checks one envelope of this circle: null when it isn't a true message of it. */
        public static Message open(HammerCircle circle, Envelope e) {
            try {
                Hammer.require(e.circle.equals(circle.id), "Another circle's message");
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(circle.crypt(Cipher.DECRYPT_MODE, e, e.sealed)));
                Hammer.require("HAMMER circle message 1".equals(Hammer.text(in, 32)), "Unsupported circle message");
                Hammer.require(e.circle.equals(Hammer.text(in, 64)) && e.id.equals(Hammer.text(in, 64)) &&
                                   e.time == in.readLong(),
                               "A message moved from where it was said");
                Message m = new Message();
                m.kind = Hammer.text(in, 16);
                m.name = Hammer.text(in, 128);
                m.body = Hammer.text(in, 4000);
                m.ref = Hammer.text(in, 256);
                if (in.readBoolean())
                    m.song = Hammer.Song.read(in);
                int n = in.readInt();
                Hammer.require(n >= 0 && n <= 3, "Invalid circle message");
                for (int i = 0; i < n; i++)
                    m.cards.add(Hammer.text(in, 1024));
                m.start = in.readLong();
                m.length = in.readInt();
                m.key = Hammer.text(in, 256);
                m.sig = Hammer.text(in, 256);
                m.check();
                Hammer.require(m.lane() == e.lane, "A message in the wrong lane");
                Hammer.require(Hammer.verify(m.key, m.sig, m.payload(e.circle, e.id, e.time)), "Message signature differs");
                m.id = e.id;
                m.time = e.time;
                m.lane = e.lane;
                return m;
            } catch (Exception notOurs) {
                return null;
            }
        }
        /** A short, stable name for the sender's key, for telling apart two people who chose the same name. */
        public String who() throws Exception { return Hammer.hash(Base64.getDecoder().decode(key)).substring(0, 6); }
    }

    /** AES-GCM with a key from the circle's secret, bound to where the envelope says it belongs. */
    byte[] crypt(int mode, Envelope e, byte[] data) throws Exception {
        Hammer.require(Hammer.digest(secret), "This circle's key isn't known here");
        byte[] k = MessageDigest.getInstance("SHA-256").digest(("HAMMER circle key 1" + secret).getBytes(StandardCharsets.US_ASCII));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] aad = (e.circle + "/" + e.id + "/" + e.lane + "/" + e.time).getBytes(StandardCharsets.US_ASCII);
        if (mode == Cipher.ENCRYPT_MODE) {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            c.init(mode, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            byte[] body = c.doFinal(data);
            byte[] out = new byte[12 + body.length];
            System.arraycopy(nonce, 0, out, 0, 12);
            System.arraycopy(body, 0, out, 12, body.length);
            return out;
        }
        c.init(mode, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, data, 0, 12));
        c.updateAAD(aad);
        return c.doFinal(data, 12, data.length - 12);
    }

    // ---------- keys ----------

    /** A new key for one member in one circle, so being in two circles links nothing. */
    public static KeyPair memberKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }
    public static String saveKey(KeyPair k) {
        return Base64.getEncoder().encodeToString(k.getPublic().getEncoded()) + ":" +
            Base64.getEncoder().encodeToString(k.getPrivate().getEncoded());
    }
    public static KeyPair loadKey(String saved) throws Exception {
        String[] p = saved.split(":");
        KeyFactory f = KeyFactory.getInstance("EC");
        return new KeyPair(f.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p[0]))),
                           f.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(p[1]))));
    }
}
