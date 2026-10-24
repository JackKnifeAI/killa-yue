package ai.jackknife.killayue.peer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Two processes, two Killa YuE network nodes, real libp2p and real TLS. The host publishes a public and a private
 * song and serves them only through its node. The listener knows nothing but the host's network card (PeerId and
 * pin): it finds the host on the local network, then reads the catalogue, streams an Opus segment, downloads and
 * verifies the master, and checks the private circle and a forged pin.
 *
 *   NetworkTest host IDENTITY.p12 DATA CARD_FILE      (prints "ready", serves until killed)
 *   NetworkTest listen IDENTITY.p12 DATA CARD_FILE
 */
public final class NetworkTest {
    static int passed;
    static void check(boolean ok, String name) throws Exception {
        if (!ok)
            throw new Exception("FAILED: " + name);
        passed++;
        System.out.println("ok " + passed + " " + name);
    }

    /**
     * relay  ID.p12 DATA ENTRY_OUT                 a helper node (relays, answers the DHT); writes its address
     * host   ID.p12 DATA CARD_OUT [FLAGS [ENTRY]]  publishes test tones and offers itself in the public host list
     * listen ID.p12 DATA CARD [FLAGS [ENTRY [relayed]]]
     * FLAGS as in KillaNet (2 no mDNS, 4 no Mainline, 8 relay only, 16 no UPnP); ENTRY a file of multiaddrs.
     */
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(args[1])) {
            keys.load(in, "hammer-test-only".toCharArray());
        }
        X509Certificate cert = (X509Certificate)keys.getCertificate("test");
        KeyPair pair = new KeyPair(cert.getPublicKey(), (PrivateKey)keys.getKey("test", "hammer-test-only".toCharArray()));
        File root = new File(args[2]);
        root.mkdirs();
        String pin = Hammer.hash(cert.getPublicKey().getEncoded());
        int flags = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        String entry = args.length > 5 ? new String(Files.readAllBytes(new File(args[5]).toPath()), "UTF-8") : "";
        File out = new File(args[3]);
        if ("relay".equals(args[0])) {
            String id = KillaNet.start(new File(root, "network"), pin, KillaNet.HELP | KillaNet.NO_MDNS | KillaNet.NO_MAINLINE |
                                       KillaNet.NO_UPNP | KillaNet.TEST_REACHABLE, 0, "");
            String quic = null;
            for (String a : KillaNet.addresses().split(" "))
                if (a.startsWith("/ip4/127.0.0.1/udp/") && a.endsWith("/quic-v1"))
                    quic = a;
            for (int i = 0; quic == null && i < 50; i++) {
                Thread.sleep(100);
                for (String a : KillaNet.addresses().split(" "))
                    if (a.startsWith("/ip4/127.0.0.1/udp/") && a.endsWith("/quic-v1"))
                        quic = a;
            }
            Files.write(out.toPath(), (quic + "/p2p/" + id + "\n").getBytes("UTF-8"));
            System.out.println("ready relay " + id);
            Thread.sleep(600000);
            return;
        }
        if ("host".equals(args[0])) {
            String id = KillaNet.start(new File(root, "network"), pin, flags, 0, entry);
            host(pair, cert, root, out);
            KillaNet.announce(true);
            String move = System.getenv("MOVE_AFTER");  // seconds: then act as if the phone changed networks
            if (move != null) {
                Thread.sleep(Long.parseLong(move) * 1000);
                KillaNet.moved();
            }
            System.out.println("ready " + id);
            Thread.sleep(600000);
            return;
        }
        KillaNet.start(new File(root, "network"), pin, flags, 0, entry);
        boolean relayed = args.length > 6 && "relayed".equals(args[6]);
        System.out.println(listen(root, out, 60000, relayed));
        String linger = System.getenv("LINGER");  // seconds to stay connected afterwards, as a listener browsing on would
        if (linger != null)
            Thread.sleep(Long.parseLong(linger) * 1000);
        System.exit(0);
    }

    /**
     * Publishes a test tone and a private-circle tone in root (never the Library) and serves them only through the
     * running network node; writes the public card, the circle card and the master's hash to cardFile.
     */
    public static Hammer.Server host(KeyPair pair, X509Certificate cert, File root, File cardFile) throws Exception {
        String pin = Hammer.hash(cert.getPublicKey().getEncoded());
        HammerStore store = new HammerStore(new File(root, "store"));
        File master = HammerIntegration.wave(root, "master.wav", 8, 44100, 3);
        store.publish(master, "Network tone", "network test", true, true, pair);
        store.publish(HammerIntegration.wave(root, "secret.wav", 2, 48000, 9), "Circle only", "", true, false, pair);
        // the host's own port is loopback only: the network node is the only way in
        Hammer.Server server =
            new Hammer.Server(Hammer.serverContext(pair, cert), new InetSocketAddress("127.0.0.1", 0), store);
        KillaNet.serve(server);
        String id = KillaNet.peerId();
        Files.write(cardFile.toPath(), (Hammer.Card.p2p(id, pin, "").encode() + "\n" +
                                        Hammer.Card.p2p(id, pin, store.token()).encode() + "\n" +
                                        Hammer.hash(Files.readAllBytes(master.toPath())))
                                           .getBytes("UTF-8"));
        return server;
    }

    /** Everything a listener does, knowing only the host's network card. The node must be running. */
    public static String listen(File root, File cardFile, long findMillis) throws Exception {
        return listen(root, cardFile, findMillis, false);
    }

    /** relayed: the host is reachable only through a relay, and the test checks the connection went that way. */
    public static String listen(File root, File cardFile, long findMillis, boolean relayed) throws Exception {
        passed = 0;
        List<String> lines = Files.readAllLines(cardFile.toPath());
        Hammer.Card card = Hammer.Card.parse(lines.get(0)), circle = Hammer.Card.parse(lines.get(1));
        String masterHash = lines.get(2).trim();
        check(card.isP2p() && Hammer.Card.parse(card.encode()).p2pId().equals(card.p2pId()), "network card round trip");
        // nearby hosts arrive by mDNS; others from the worldwide public host list in the DHT
        long t0 = System.nanoTime(), until = System.currentTimeMillis() + findMillis;
        KillaNet.Peer found = null;
        for (int round = 0; found == null && System.currentTimeMillis() < until; round++) {
            if (round % 10 == 0)
                KillaNet.lookup();
            for (KillaNet.Peer p : KillaNet.peers())
                if (p.id.equals(card.p2pId()) && p.pin.equals(card.pin))
                    found = p;
            if (found == null)
                Thread.sleep(300);
        }
        long findMs = (System.nanoTime() - t0) / 1000000;
        check(found != null, "host found with its pin" + (found == null ? "" : found.local ? " (same network)" : " (public host list)"));
        Hammer.Job job = new Hammer.Job();
        t0 = System.nanoTime();
        List<Hammer.Song> songs = Hammer.catalog(card, job);
        long catalogMs = (System.nanoTime() - t0) / 1000000;
        check(songs.size() == 1 && songs.get(0).title.equals("Network tone"), "public catalogue over the network");
        Hammer.Song song = songs.get(0);
        long frames = Hammer.frames(card, song.id, job);
        // streams are decoded at 48 kHz: 8 s of master is 384,000 frames whatever its own rate
        check(frames == 8 * 48000 || frames == 8 * 44100, "frame count over the network (" + frames + ")");
        t0 = System.nanoTime();
        byte[] segment = Hammer.segment(card, song.id, 0, 96000, job);
        long segmentMs = (System.nanoTime() - t0) / 1000000;
        check(segment != null && segment.length > 1000, "Opus stream segment over the network");
        File part = new File(root, "download.part"), target = new File(root, "download.wav");
        t0 = System.nanoTime();
        Hammer.download(card, song, part, target, job);
        long downloadMs = (System.nanoTime() - t0) / 1000000;
        check(Hammer.hash(Files.readAllBytes(target.toPath())).equals(masterHash), "verified download over the network");
        check(Hammer.catalog(circle, job).size() == 2, "private circle over the network");
        boolean refused = false;
        try {
            Hammer.catalog(Hammer.Card.p2p(card.p2pId(), Hammer.hash("forged".getBytes("UTF-8")), ""), job);
        } catch (Exception expected) {
            refused = true;
        }
        check(refused, "forged pin refused inside the network stream");
        String path = "";
        if (relayed) {
            boolean viaRelay = false, punched = false;
            for (KillaNet.Peer p : KillaNet.peers())
                if (p.id.equals(card.p2pId())) {
                    viaRelay |= p.relayed;
                    punched |= p.punched;
                }
            check(viaRelay, "the host was reached through a relay");
            path = punched ? "; reached through the relay, then hole-punched to a direct connection"
                           : "; stayed on the relay (hole punching did not upgrade it)";
        }
        return passed + " network checks passed; found in " + findMs + " ms, catalogue " + catalogMs + " ms, 2 s Opus segment " +
               segment.length + " bytes in " + segmentMs + " ms, " + target.length() + "-byte master downloaded and verified in " +
               downloadMs + " ms" + path;
    }
}
