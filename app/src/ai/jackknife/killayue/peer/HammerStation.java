package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A station, signed by the artist: songs in order, their lengths, and the moment the first one began. Those few facts
 * tell any phone what is playing now and how far in, so nothing has to stay online to run it and a copy held by
 * anyone is as good as the artist's. It loops until it ends. An artist has one station; a newer revision replaces it.
 */
public final class HammerStation {
    public static final int SONGS = 512, NOTE = 140;
    /** A station stops by itself after this long, so one left behind doesn't stay on air for ever. */
    public static final long LONGEST = 30L * 24 * 3600 * 1000;
    /** A station that went off air is still handed out this long, so every copy of it learns that it ended. */
    public static final long KEPT = 7L * 24 * 3600 * 1000;
    public String title = "", note = "", key = "", sig = "";
    public long started, ends, revision;
    public List<String> songs = new ArrayList<String>();
    public List<Integer> lengths = new ArrayList<Integer>();  // milliseconds, one per song
    /** What is on at a moment: which song, and how far into it. */
    public static final class Spot {
        public final int index, offset;
        public final String song;
        Spot(int index, String song, int offset) {
            this.index = index;
            this.song = song;
            this.offset = offset;
        }
    }
    /** The artist's identity, the same id as their profile. */
    public String id() throws Exception { return Hammer.hash(Base64.getDecoder().decode(key)); }
    public boolean live(long now) { return now >= started && now < ends; }
    /** Still worth holding: on air, or off air recently enough that a stale copy somewhere needs telling. */
    public boolean kept(long now) { return now < ends + KEPT; }
    /** The song on at {@code now}, or null when the station is not on air then. */
    public Spot at(long now) {
        if (!live(now))
            return null;
        long total = 0;
        for (int length : lengths)
            total += length;
        long into = (now - started) % total;
        for (int i = 0; i < songs.size(); i++) {
            if (into < lengths.get(i))
                return new Spot(i, songs.get(i), (int)into);
            into -= lengths.get(i);
        }
        return new Spot(0, songs.get(0), 0);
    }
    private void fields(DataOutputStream out) throws IOException {
        Hammer.text(out, "HAMMER station 1", 32);
        Hammer.text(out, title, 160);
        Hammer.text(out, note, 560);
        out.writeLong(started);
        out.writeLong(ends);
        out.writeLong(revision);
        out.writeInt(songs.size());
        for (int i = 0; i < songs.size(); i++) {
            Hammer.text(out, songs.get(i), 64);
            out.writeInt(lengths.get(i));
        }
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
        Hammer.require(!title.trim().isEmpty() && Hammer.displayText(title) && Hammer.displayText(note) &&
                           note.length() <= NOTE && started > 0 && ends > started && ends - started <= LONGEST &&
                           revision > 0 && !songs.isEmpty() && songs.size() <= SONGS && songs.size() == lengths.size(),
                       "Invalid station");
        for (int i = 0; i < songs.size(); i++)
            Hammer.require(Hammer.digest(songs.get(i)) && lengths.get(i) > 0, "Invalid station song");
        Hammer.require(Hammer.verify(key, sig, payload()), "Station signature does not match");
    }
    public void write(DataOutputStream out) throws IOException {
        fields(out);
        Hammer.text(out, key, 256);
        Hammer.text(out, sig, 256);
    }
    public static HammerStation read(DataInputStream in) throws Exception {
        Hammer.require("HAMMER station 1".equals(Hammer.text(in, 32)), "Unsupported station record");
        HammerStation s = new HammerStation();
        s.title = Hammer.text(in, 160);
        s.note = Hammer.text(in, 560);
        s.started = in.readLong();
        s.ends = in.readLong();
        s.revision = in.readLong();
        int n = in.readInt();
        Hammer.require(n > 0 && n <= SONGS, "Invalid station length");
        for (int i = 0; i < n; i++) {
            s.songs.add(Hammer.text(in, 64));
            s.lengths.add(in.readInt());
        }
        s.key = Hammer.text(in, 256);
        s.sig = Hammer.text(in, 256);
        s.validate();
        return s;
    }
}
