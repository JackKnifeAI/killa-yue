package ai.jackknife.killayue.peer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Discover, the way a fresh phone does it, against the real network: start a node with no configuration (the
 * BitTorrent DHT as the meeting point, mDNS on this network), ask for public hosts, and read each host's catalogue
 * over the network. Prints what it finds and how long each step took.
 *
 *   DiscoverProbe IDENTITY.p12 DATA SECONDS
 */
public final class DiscoverProbe {
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(args[0])) {
            keys.load(in, "hammer-test-only".toCharArray());
        }
        X509Certificate cert = (X509Certificate)keys.getCertificate("test");
        String pin = Hammer.hash(cert.getPublicKey().getEncoded());
        long t0 = System.currentTimeMillis(), until = t0 + Long.parseLong(args[2]) * 1000;
        String id = KillaNet.start(new File(args[1], "network"), pin, 0, 0, "");
        System.out.println("node " + id + " up in " + (System.currentTimeMillis() - t0) + " ms");
        java.util.HashSet<String> tried = new java.util.HashSet<String>();
        for (int round = 0; System.currentTimeMillis() < until; round++) {
            if (round % 10 == 0)
                KillaNet.lookup();
            for (KillaNet.Peer p : KillaNet.peers()) {
                if (!(p.host || p.local) || !tried.add(p.id))
                    continue;
                long t = System.currentTimeMillis();
                System.out.println("found " + p.id + (p.local ? " nearby" : "") + (p.host ? " host" : "") +
                                   (p.relayed ? " relayed" : "") + " at " + (t - t0) + " ms");
                try {
                    List<Hammer.Song> songs = Hammer.catalog(Hammer.Card.p2p(p.id, p.pin, ""), new Hammer.Job());
                    System.out.println("  catalogue: " + songs.size() + " songs in " + (System.currentTimeMillis() - t) +
                                       " ms");
                    for (Hammer.Song s : songs) System.out.println("    " + s.title);
                } catch (Exception e) {
                    System.out.println("  catalogue FAILED after " + (System.currentTimeMillis() - t) + " ms: " + e);
                    String direct = System.getenv("DIRECT");  // host:port to try the same pin at directly
                    if (direct != null)
                        try {
                            Hammer.Card card = Hammer.Card.parse("hammer://" + direct + "/#" + p.pin);
                            List<Hammer.Song> songs = Hammer.catalog(card, new Hammer.Job());
                            System.out.println("  the same pin directly at " + direct + ": " + songs.size() + " songs");
                            for (Hammer.Song s : songs) System.out.println("    " + s.title);
                        } catch (Exception d) {
                            System.out.println("  directly at " + direct + " FAILED: " + d);
                        }
                }
            }
            Thread.sleep(500);
        }
        System.out.println(KillaNet.status());
        System.exit(0);
    }
}
