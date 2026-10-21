package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * An album or playlist, signed by the artist: a title and up to 64 of the artist's songs in order. Its cover is the
 * cover of one of its songs, so no new artwork path is needed. A newer revision replaces an older one.
 */
public final class HammerAlbum {
    public static final int SONGS = 64;
    public String title = "", cover = "", key = "", sig = "";
    public List<String> songs = new ArrayList<String>();
    public long revision;
    /** Stable across revisions: the publisher and the title. */
    public String id() throws Exception { return Hammer.hash((key + "\n" + title).getBytes("UTF-8")); }
    private void fields(DataOutputStream out) throws IOException {
        Hammer.text(out, "HAMMER album 1", 32);
        Hammer.text(out, title, 160);
        Hammer.text(out, cover, 64);
        out.writeInt(songs.size());
        for (String s : songs)
            Hammer.text(out, s, 64);
        out.writeLong(revision);
    }
    private byte[] payload() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        fields(new DataOutputStream(b));
        return b.toByteArray();
    }
    public void sign(KeyPair pair) throws Exception {
        key = Hammer.publicKey(pair.getPublic());
        sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        validate();
    }
    public void validate() throws Exception {
        Hammer.require(!title.trim().isEmpty() && Hammer.displayText(title) && songs.size() > 0 &&
                           songs.size() <= SONGS && (cover.isEmpty() || Hammer.digest(cover)) && revision > 0,
                       "Invalid album");
        for (String s : songs)
            Hammer.require(Hammer.digest(s), "Invalid album song");
        Hammer.require(Hammer.verify(key, sig, payload()), "Album signature does not match");
    }
    public void write(DataOutputStream out) throws IOException {
        fields(out);
        Hammer.text(out, key, 256);
        Hammer.text(out, sig, 256);
    }
    public static HammerAlbum read(DataInputStream in) throws Exception {
        Hammer.require("HAMMER album 1".equals(Hammer.text(in, 32)), "Unsupported album record");
        HammerAlbum a = new HammerAlbum();
        a.title = Hammer.text(in, 160);
        a.cover = Hammer.text(in, 64);
        int n = in.readInt();
        Hammer.require(n > 0 && n <= SONGS, "Invalid album length");
        for (int i = 0; i < n; i++)
            a.songs.add(Hammer.text(in, 64));
        a.revision = in.readLong();
        a.key = Hammer.text(in, 256);
        a.sig = Hammer.text(in, 256);
        a.validate();
        return a;
    }
}
