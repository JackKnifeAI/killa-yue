package ai.jackknife.killayue.peer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A helper adopting hidden artists' songs: they arrive in pieces through the mixnet from a phone it never sees, and
 * once whole and verified the helper hosts them like any song it rehosts, under the hidden artist's signed profile.
 * Anyone can send anonymously, so every offer pays a proof-of-work stamp and every hidden identity has a quota here;
 * the owner can turn adoption off and remove what was adopted, and a removed song is never taken again.
 * See docs/designs/2026-10-24-mixnet-and-hidden-artists.md.
 */
public final class HammerAdoption {
    /** Pieces of an upload in flight here at once; past it, new offers wait for one to finish or go stale. */
    static final int UPLOADS = 8;
    /** An unfinished upload not added to in this long is dropped. */
    static final long STALE = 48L * 3600 * 1000;

    private final HammerStore store;
    private final File dir;
    public volatile boolean enabled = true;
    public volatile long bytesPerArtist = 2L << 30;
    public volatile int songsPerArtist = 25;
    private final Set<String> removed = new HashSet<String>();

    public HammerAdoption(HammerStore store, File dir) throws IOException {
        this.store = store;
        this.dir = dir;
        Hammer.require(dir.isDirectory() || dir.mkdirs(), "Cannot prepare adoption");
        File r = new File(dir, "removed.txt");
        if (r.isFile())
            for (String line : new String(HammerStore.read(r, 1 << 20), StandardCharsets.US_ASCII).split("\n"))
                if (Hammer.digest(line.trim()))
                    removed.add(line.trim());
    }

    /** The offer of a hidden song: answers with the pieces already here (one bit each), so an upload resumes. */
    synchronized byte[] offer(DataInputStream in) throws Exception {
        Hammer.Song song = Hammer.Song.read(in);
        HammerProfile profile = HammerProfile.read(in);
        long stamp = in.readLong();
        Hammer.require(enabled, "This helper does not adopt hidden songs now");
        Hammer.require(profile.key.equals(song.key), "The profile and the song have different artists");
        Hammer.require(song.reshare, "A hidden song must allow rehosting");
        Hammer.require(song.size <= HammerHidden.MAX, "A hidden song is at most 32 MB");
        Hammer.require(HammerHidden.stampOk(song.id, stamp), "The proof-of-work stamp does not match");
        Hammer.require(!removed.contains(song.id), "This helper's owner removed that song");
        int count = HammerHidden.pieces(song.size);
        if (hosts(song.id))
            return have(count, true);
        File meta = new File(dir, song.id + ".offer");
        if (!meta.isFile()) {
            prune();
            Hammer.require(uploads() < UPLOADS, "This helper is busy with other uploads; try another");
            long bytes = song.size;
            int songs = 1;
            for (Hammer.Song s : adopted())
                if (s.key.equals(song.key)) {
                    bytes += s.size;
                    songs++;
                }
            Hammer.require(songs <= songsPerArtist && bytes <= bytesPerArtist,
                           "This hidden artist has reached its quota on this helper");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            song.write(out);
            profile.write(out);
            HammerStore.atomic(meta, b.toByteArray());
            try (RandomAccessFile f = new RandomAccessFile(new File(dir, song.id + ".part"), "rw")) {
                f.setLength(song.size);
            }
            HammerStore.atomic(new File(dir, song.id + ".have"), new byte[(count + 7) / 8]);
        }
        return HammerStore.read(new File(dir, song.id + ".have"), 1 << 16);
    }

    /** One piece of an offered song; answers "hosted" once the last piece is in and the song verified. */
    synchronized byte[] piece(DataInputStream in) throws Exception {
        String id = Hammer.text(in, 64);
        int index = in.readInt();
        int n = in.readInt();
        Hammer.require(Hammer.digest(id) && n > 0 && n <= HammerHidden.PIECE, "Invalid piece");
        File meta = new File(dir, id + ".offer"), part = new File(dir, id + ".part"), haveFile = new File(dir, id + ".have");
        if (hosts(id))
            return "hosted".getBytes(StandardCharsets.US_ASCII);
        Hammer.require(meta.isFile() && part.isFile() && haveFile.isFile(), "No offer for that song here");
        DataInputStream m = new DataInputStream(new ByteArrayInputStream(HammerStore.read(meta, 16384)));
        Hammer.Song song = Hammer.Song.read(m);
        HammerProfile profile = HammerProfile.read(m);
        int count = HammerHidden.pieces(song.size);
        long at = (long)index * HammerHidden.PIECE;
        Hammer.require(index >= 0 && index < count && n == Math.min(HammerHidden.PIECE, song.size - at),
                       "Piece out of place");
        byte[] bytes = new byte[n];
        in.readFully(bytes);
        try (RandomAccessFile f = new RandomAccessFile(part, "rw")) {
            f.seek(at);
            f.write(bytes);
        }
        byte[] have = HammerStore.read(haveFile, 1 << 16);
        have[index / 8] |= (byte)(1 << (index % 8));
        HammerStore.atomic(haveFile, have);
        for (int i = 0; i < count; i++)
            if ((have[i / 8] & (1 << (i % 8))) == 0)
                return new byte[0];
        // whole: verify it as any rehosted song, then host it
        try {
            Hammer.require(Hammer.hash(part).equals(song.id), "The song did not arrive intact");
            Hammer.require(HammerHidden.audio(part, song.type), "That is not " + song.type + " audio");
            File target = store.downloadFile(song);
            Hammer.require(part.renameTo(target), "Cannot keep the song");
            store.rememberDownload(song);
            store.publicObjects().rememberProfile(profile);
            store.rehost(song, true);
        } finally {
            part.delete();
            haveFile.delete();
        }
        File adopted = new File(dir, id + ".adopted");
        HammerStore.atomic(adopted, HammerStore.read(meta, 16384));
        meta.delete();
        return "hosted".getBytes(StandardCharsets.US_ASCII);
    }

    /** The owner removes an adopted song: no longer hosted here, and never taken again. */
    public synchronized void remove(String id) throws Exception {
        Hammer.require(Hammer.digest(id), "Invalid song");
        removed.add(id);
        StringBuilder b = new StringBuilder();
        for (String r : removed)
            b.append(r).append('\n');
        HammerStore.atomic(new File(dir, "removed.txt"), b.toString().getBytes(StandardCharsets.US_ASCII));
        for (Hammer.Song s : adopted())
            if (s.id.equals(id)) {
                store.unpublish(id);
                store.downloadFile(s).delete();
            }
        new File(dir, id + ".adopted").delete();
        for (String end : new String[] {".offer", ".part", ".have"})
            new File(dir, id + end).delete();
    }

    /** The hidden songs this helper adopted and still hosts. */
    public synchronized List<Hammer.Song> adopted() throws Exception {
        List<Hammer.Song> out = new ArrayList<Hammer.Song>();
        File[] fs = dir.listFiles();
        if (fs != null)
            for (File f : fs)
                if (f.getName().matches("[0-9a-f]{64}\\.adopted"))
                    out.add(Hammer.Song.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 16384)))));
        return out;
    }

    private boolean hosts(String id) throws Exception {
        for (HammerStore.Hosted h : store.hosted())
            if (h.song.id.equals(id))
                return true;
        return false;
    }

    private int uploads() {
        int n = 0;
        File[] fs = dir.listFiles();
        if (fs != null)
            for (File f : fs)
                if (f.getName().endsWith(".offer"))
                    n++;
        return n;
    }

    /** Unfinished uploads nobody added to for STALE are let go. */
    private void prune() {
        File[] fs = dir.listFiles();
        if (fs == null)
            return;
        long now = System.currentTimeMillis();
        for (File f : fs)
            if (f.getName().endsWith(".have") && now - f.lastModified() > STALE) {
                String id = f.getName().substring(0, f.getName().length() - 5);
                for (String end : new String[] {".offer", ".part", ".have"})
                    new File(dir, id + end).delete();
            }
    }

    private static byte[] have(int count, boolean all) {
        byte[] b = new byte[(count + 7) / 8];
        if (all)
            for (int i = 0; i < count; i++)
                b[i / 8] |= (byte)(1 << (i % 8));
        return b;
    }
}
