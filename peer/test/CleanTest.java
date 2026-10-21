package ai.jackknife.killayue.peer;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;

/**
 * Cleans each file given and says whether a clean copy was needed (clean.sh checks the copies); {@code --artist NAME}
 * first gives the artist name the copies carry, else they carry none. With {@code --store DIR TAGGED OWN}, publishes through HammerStore the way the app does and checks what it hosts.
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
