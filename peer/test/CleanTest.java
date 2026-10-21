package ai.jackknife.killayue.peer;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;

/**
 * Cleans each file given and says whether a clean copy was needed (clean.sh checks the copies); {@code --artist NAME}
 * first gives the artist name the copies carry, else they carry none. With {@code --store DIR TAGGED OWN}, publishes
 * through HammerStore the way the app does and checks what it hosts. With {@code --reclean DIR TAGGED OWN COVER},
 * checks that songs shared the 1.7.4 way, as they were, are shared again clean.
 */
public final class CleanTest {
    private static int failed;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok)
            failed++;
    }
    private static HammerStore.Hosted hosted(HammerStore store, String id) {
        for (HammerStore.Hosted h : store.hosted())
            if (h.song.id.equals(id))
                return h;
        return null;
    }
    private static boolean contains(File f, String s) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), "ISO-8859-1").contains(s);
    }
    /** Also finds text an ID3v2.3 tag stores as UTF-16. */
    private static boolean containsText(File f, String s) throws Exception {
        return contains(f, s) || contains(f, new String(s.getBytes("UTF-16BE"), "ISO-8859-1"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--store")) {
            store(new File(args[1]), new File(args[2]), new File(args[3]));
            return;
        }
        if (args.length > 0 && args[0].equals("--reclean")) {
            reclean(new File(args[1]), new File(args[2]), new File(args[3]), new File(args[4]));
            return;
        }
        String artist = null;
        int from = 0;
        if (args.length > 1 && args[0].equals("--artist")) {
            artist = args[1];
            from = 2;
        }
        for (String a : Arrays.copyOfRange(args, from, args.length)) {
            File in = new File(a), out = new File(a.replaceFirst("(\\.[a-z0-9]+)$", ".clean$1"));
            boolean changed = HammerClean.clean(in, out, artist, new Hammer.Job());
            System.out.println((changed ? "cleaned " : "unchanged ") + in.getName());
        }
    }

    private static KeyPair identity() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }
    /** A song as 1.7.4 hosted it: the artist's own file, as it was. */
    private static Hammer.Song sharedAsItWas(File root, File audio, String title, KeyPair artist) throws Exception {
        Hammer.Song s = new Hammer.Song(Hammer.hash(audio), title, "protest", "audio/mpeg", audio.length(), true);
        s.sign(artist);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(b);
        out.writeBoolean(true);
        s.write(out);
        Hammer.text(out, audio.getCanonicalPath(), 4096);
        out.writeLong(audio.lastModified());
        Files.write(new File(root, s.id + ".song").toPath(), b.toByteArray());
        return s;
    }

    private static void reclean(File root, File tagged, File own, File cover) throws Exception {
        KeyPair artist = identity(), other = identity();
        root.mkdirs();
        HammerStore before = new HammerStore(root);
        HammerProfile profile = new HammerProfile();
        profile.name = "Ghost Name";
        profile.revision = 1;
        profile.sign(artist);
        before.publicObjects.publishProfile(profile);
        Hammer.Song old = sharedAsItWas(root, tagged, "Old Song", artist);
        Hammer.Song mine = sharedAsItWas(root, own, "Made in Killa", artist);
        File download = new File(root.getParentFile(), "download.mp3");
        Files.write(download.toPath(), Arrays.copyOf(Files.readAllBytes(tagged.toPath()), (int)tagged.length() + 1));
        Hammer.Song theirs = sharedAsItWas(root, download, "Someone Else's", other);  // a rehosted download
        Files.move(new File(root, theirs.id + ".song").toPath(), new File(root, "x.tmp").toPath());
        String image = before.publicObjects.putImage(Files.readAllBytes(cover.toPath()));
        HammerArt art = new HammerArt();
        art.song = old.id;
        art.image = image;
        art.sign(artist);
        before.publicObjects.cover(old, art);
        HammerLyrics lyrics = new HammerLyrics();
        lyrics.song = old.id;
        lyrics.text = "line one\nline two";
        lyrics.sign(artist);
        before.publicObjects.putLyrics(old, lyrics);
        HammerAlbum album = new HammerAlbum();
        album.title = "Album X";
        album.songs.add(mine.id);
        album.songs.add(old.id);
        album.cover = old.id;
        album.revision = 5;
        album.sign(artist);
        before.publicObjects.putAlbum(album);

        HammerStore store = new HammerStore(root);
        check("the 1.7.4 song is hosted as it was before", hosted(store, old.id) != null &&
              hosted(store, old.id).file.equals(tagged.getCanonicalFile()));
        int moved = store.recleanShared(artist, new Hammer.Job());
        check("one song shared again", moved == 1);
        check("the old song is gone", hosted(store, old.id) == null && !new File(root, old.id + ".song").exists());
        HammerStore.Hosted n = null;
        for (HammerStore.Hosted h : store.hosted())
            if (h.song.title.equals("Old Song"))
                n = h;
        check("shared again with its title, tags and settings", n != null && n.song.tags.equals("protest") &&
              n.song.reshare && n.publicSong && n.song.key.equals(old.key));
        check("from a clean copy with the Killa YuE name", n != null && !n.file.equals(n.source) &&
              !contains(n.file, "Secret") && containsText(n.file, "Ghost Name") && Hammer.hash(n.file).equals(n.song.id));
        check("its source is still the artist's file", n != null && n.source.equals(tagged.getCanonicalFile()) &&
              contains(tagged, "Secret"));
        HammerArt c = n == null ? null : store.publicObjects.localCover(n.song.id);
        check("the cover moved", c != null && c.image.equals(image) && c.key.equals(old.key));
        HammerLyrics l = n == null ? null : store.publicObjects.localLyrics(n.song.id);
        check("the lyrics moved", l != null && l.text.equals(lyrics.text));
        HammerAlbum a = null;
        for (HammerAlbum x : store.publicObjects.albums(""))
            a = x;
        check("the album points at the new song and keeps its order", a != null && n != null && a.songs.size() == 2 &&
              a.songs.get(0).equals(mine.id) && a.songs.get(1).equals(n.song.id) && a.cover.equals(n.song.id) &&
              a.revision > 5);
        check("the song made in Killa YuE keeps its id", hosted(store, mine.id) != null);

        Files.move(new File(root, "x.tmp").toPath(), new File(root, theirs.id + ".song").toPath());
        new File(root, "recleaned-1").delete();
        HammerStore again = new HammerStore(root);
        check("someone else's song this phone rehosts is left alone",
              again.recleanShared(artist, new Hammer.Job()) == 0 && hosted(again, theirs.id) != null);
        check("it runs once", new File(root, "recleaned-1").isFile() &&
              new HammerStore(root).recleanShared(artist, new Hammer.Job()) == 0);
        System.out.println(failed == 0 ? "reclean: all checks passed" : "reclean: " + failed + " FAILED");
        if (failed > 0)
            System.exit(1);
    }

    private static void store(File root, File tagged, File own) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair artist = g.generateKeyPair();
        HammerStore store = new HammerStore(root);
        File clean = new File(root, "clean");
        HammerProfile profile = new HammerProfile();
        profile.name = "Ghost Name";
        profile.revision = 1;
        profile.sign(artist);
        store.publicObjects.publishProfile(profile);

        Hammer.Song s = store.publish(tagged, "Song Title", "protest", true, true, artist);
        HammerStore.Hosted h = hosted(store, s.id);
        check("a tagged song is hosted from a clean copy", h != null && !h.file.equals(h.source) &&
              clean.equals(h.file.getParentFile()));
        check("the song id is the clean copy's hash", h != null && Hammer.hash(h.file).equals(s.id) &&
              h.file.length() == s.size);
        check("the clean copy carries no secrets", h != null && !contains(h.file, "Secret") && !contains(h.file, "GPS"));
        check("the clean copy carries the artist's Killa YuE name, not the file's",
              h != null && containsText(h.file, "Ghost Name") && !containsText(h.file, "Stage Name"));
        check("the source is remembered", h != null && h.source.equals(tagged.getCanonicalFile()));
        check("the artist's own file is untouched", contains(tagged, "Secret"));
        check("what a listener downloads is the clean copy", h != null && h.file.equals(store.audio(s.id, "")));

        Hammer.Song again = store.publish(tagged, "Song Title", "protest", true, true, artist);
        String[] left = clean.list();
        check("publishing the same file again gives the same song", again.id.equals(s.id));
        check("one clean copy, no leftovers", left != null && left.length == 1 && !left[0].endsWith(".part"));

        HammerStore reloaded = new HammerStore(root);
        HammerStore.Hosted r = hosted(reloaded, s.id);
        check("the source survives a restart", r != null && r.source.equals(tagged.getCanonicalFile()) &&
              r.file.equals(h.file));

        Hammer.Song mine = store.publish(own, "Made in Killa", "", true, true, artist);
        HammerStore.Hosted m = hosted(store, mine.id);
        check("a song made in Killa YuE is hosted as it is", m != null && m.file.equals(own.getCanonicalFile()) &&
              m.source.equals(m.file) && Hammer.hash(own).equals(mine.id));

        store.unpublish(s.id);
        check("unpublishing removes the clean copy", !h.file.exists());
        check("unpublishing keeps the artist's own file", tagged.isFile());
        store.unpublish(mine.id);
        check("unpublishing a song made in Killa YuE keeps it", own.isFile());
        System.out.println(failed == 0 ? "store: all checks passed" : "store: " + failed + " FAILED");
        if (failed > 0)
            System.exit(1);
    }
}
