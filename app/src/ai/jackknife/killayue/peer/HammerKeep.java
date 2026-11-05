package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * An artist asking one helper to keep copies of their songs, signed by the artist: which songs, where to fetch them,
 * and which helper it is meant for, so a copy can't be replayed to another helper. A newer revision replaces the
 * older one; songs it no longer lists are dropped, and an empty list takes everything back. Only the artist can ask,
 * and only the artist can take back. See docs/designs/2026-11-05-keeping.md.
 */
public final class HammerKeep {
    /** The most songs one artist may ask one helper to keep. */
    public static final int SONGS = 100;
    public String key = "", helper = "", from = "", sig = "";
    public long revision;
    public List<String> songs = new ArrayList<String>();
    /** Proof-of-work over the artist's id, paid once per artist (HammerHidden.stampOk); not part of the signature. */
    public long stamp;

    /** The artist's identity, the same id as their profile. */
    public String id() throws Exception { return Hammer.hash(Base64.getDecoder().decode(key)); }

    private byte[] payload() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        Hammer.text(out, "HAMMER keep 1", 32);
        Hammer.text(out, helper, 64);
        Hammer.text(out, from, 1024);
        out.writeLong(revision);
        out.writeInt(songs.size());
        for (String s : songs)
            Hammer.text(out, s, 64);
        return b.toByteArray();
    }

    public void sign(KeyPair pair) throws Exception {
        key = Hammer.publicKey(pair.getPublic());
        sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        validate();
    }

    public void validate() throws Exception {
        Hammer.require(Hammer.digest(helper) && revision > 0 && songs.size() <= SONGS, "Invalid keep request");
        Hammer.require(!Hammer.Card.parse(from).isPrivate(), "Songs are kept only from a public card");
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String s : songs)
            Hammer.require(Hammer.digest(s) && seen.add(s), "Invalid song in keep request");
        Hammer.require(Hammer.verify(key, sig, payload()), "Keep request signature does not match");
    }

    public Hammer.Card fromCard() throws Exception { return Hammer.Card.parse(from); }

    public void write(DataOutputStream out) throws IOException {
        out.write(payload());
        Hammer.text(out, key, 256);
        Hammer.text(out, sig, 256);
        out.writeLong(stamp);
    }

    public static HammerKeep read(DataInputStream in) throws Exception {
        Hammer.require("HAMMER keep 1".equals(Hammer.text(in, 32)), "Unsupported keep request");
        HammerKeep k = new HammerKeep();
        k.helper = Hammer.text(in, 64);
        k.from = Hammer.text(in, 1024);
        k.revision = in.readLong();
        int n = in.readInt();
        Hammer.require(n >= 0 && n <= SONGS, "Invalid keep request length");
        for (int i = 0; i < n; i++)
            k.songs.add(Hammer.text(in, 64));
        k.key = Hammer.text(in, 256);
        k.sig = Hammer.text(in, 256);
        k.stamp = in.readLong();
        k.validate();
        return k;
    }

    byte[] bytes() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        write(new DataOutputStream(b));
        return b.toByteArray();
    }
}
