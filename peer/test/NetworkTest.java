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
 *
 * The mixnet (experimental), for peer/test/network.sh:
 *   NetworkTest mixlisten ID.p12 DATA PEERID_OUT [FLAGS [ENTRY]]   a client behind a relay, waiting for a message
 *   NetworkTest mixsend ID.p12 DATA RECEIVER_FILE [FLAGS [ENTRY]]  sends one, waits for the answer to its reply block
 *   NetworkTest mixhost ID.p12 DATA CARD_OUT [FLAGS [ENTRY]]       a host behind a relay, taking votes and comments
 *                                                                  through the mixnet (Protection)
 *   NetworkTest mixvote ID.p12 DATA CARD [FLAGS [ENTRY]]           votes and comments on its song through the mixnet
 *   NetworkTest adopter ID.p12 DATA ENTRY_OUT [FLAGS [ENTRY]]      a helper that mixes and adopts hidden songs; its card
 *                                                                  goes to ENTRY_OUT.card
 *   NetworkTest hidden ID.p12 DATA CARDS [FLAGS [ENTRY]]           publishes a hidden song through the mixnet; CARDS
 *                                                                  lists the adopters, which must all end up hosting it
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
     * relay  ID.p12 DATA ENTRY_OUT [FLAGS [ENTRY]] a helper node (relays, answers the DHT, mixes); writes its
     *                                              address; FLAGS are ignored, ENTRY joins other helpers
     * host   ID.p12 DATA CARD_OUT [FLAGS [ENTRY]]  publishes test tones and offers itself in the public host list
     * listen ID.p12 DATA CARD [FLAGS [ENTRY [relayed]]]
     * mixlisten ID.p12 DATA PEERID_OUT [FLAGS [ENTRY]]  a client behind a relay, waiting for a mixnet message;
     *                                                   writes its PeerId, answers through the reply block
     * mixsend ID.p12 DATA RECEIVER_FILE [FLAGS [ENTRY]] sends a message with a reply block through three mix
     *                                                   nodes, waits for the answer
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
        if ("relay".equals(args[0]) || "adopter".equals(args[0])) {
            String id = KillaNet.start(new File(root, "network"), pin, KillaNet.HELP | KillaNet.NO_MDNS | KillaNet.NO_MAINLINE |
                                       KillaNet.NO_UPNP | KillaNet.TEST_REACHABLE, 0, entry);
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
            if ("adopter".equals(args[0])) {
                // a helper as the desktop companion runs one: hosting, and adopting hidden songs from the mixnet
                HammerStore store = new HammerStore(new File(root, "store"));
                Hammer.Server server =
                    new Hammer.Server(Hammer.serverContext(pair, cert), new InetSocketAddress("127.0.0.1", 0), store);
                KillaNet.serve(server);
                HammerMix.adoption = new HammerAdoption(store, new File(root, "adopted"));
                HammerMix.start();
                Files.write(new File(out.getPath() + ".card").toPath(),
                            (Hammer.Card.p2p(id, pin, "").encode() + "\n").getBytes("UTF-8"));
            }
            Files.write(out.toPath(), (quic + "/p2p/" + id + "\n").getBytes("UTF-8"));
            System.out.println("ready relay " + id);
            Thread.sleep(600000);
            return;
        }
        if ("mixlisten".equals(args[0])) {
            // a phone-like client: no address of its own, reached through a relay, waiting on the mixnet
            String id = KillaNet.start(new File(root, "network"), pin, flags | KillaNet.RELAY_ONLY, 0, entry);
            Files.write(out.toPath(), (id + "\n").getBytes("UTF-8"));
            System.out.println("ready " + id);
            long until = System.currentTimeMillis() + 120000;
            String got = null;
            while (got == null && System.currentTimeMillis() < until) {
                for (String line : KillaNet.mixInbox().split("\n"))
                    if (line.startsWith("got\t"))
                        got = line;
                if (got == null)
                    Thread.sleep(300);
            }
            check(got != null, "a message arrived through the mixnet");
            byte[] message = unhex(got.split("\t")[2]);
            check(message.length > 8 && new String(message, 0, 8, "UTF-8").equals("MIXSRB01"),
                  "the message carried a reply block");
            String r = KillaNet.mixReply(java.util.Arrays.copyOfRange(message, 8, message.length),
                                        "answered through the mixnet".getBytes("UTF-8"));
            check("ok".equals(r), "the answer was queued through the reply block");
            System.exit(0);
        }
        if ("mixsend".equals(args[0])) {
            // the sender: three mix nodes carry its message, and only the first knows the phone at all
            KillaNet.start(new File(root, "network"), pin, flags, 0, entry);
            String to = new String(Files.readAllBytes(out.toPath()), "UTF-8").trim();
            int mixes = 0;
            for (int i = 0; i < 300 && mixes < 3; i++) {
                String look = KillaNet.mixLook();
                if (!look.startsWith("error"))
                    mixes = Integer.parseInt(look.split("\t")[0]);
                if (mixes < 3)
                    Thread.sleep(200);
            }
            check(mixes >= 3, "three mix nodes found in the DHT");
            byte[] block = KillaNet.mixSurb();
            check(block != null && block.length > 0, "a reply block was made");
            byte[] head = "MIXSRB01".getBytes("UTF-8");
            byte[] message = new byte[head.length + block.length];
            System.arraycopy(head, 0, message, 0, head.length);
            System.arraycopy(block, 0, message, head.length, block.length);
            String sent = KillaNet.mixSend(to, message);
            check("ok".equals(sent), "the message was queued for the mixnet");
            long until = System.currentTimeMillis() + 120000;
            String back = null;
            while (back == null && System.currentTimeMillis() < until) {
                for (String line : KillaNet.mixInbox().split("\n"))
                    if (line.startsWith("back\t"))
                        back = line;
                if (back == null)
                    Thread.sleep(300);
            }
            check(back != null, "the answer came back through the reply block");
            check("answered through the mixnet".equals(new String(unhex(back.split("\t")[2]), "UTF-8")),
                  "the answer arrived intact");
            // cover traffic: loops leave through the gateway and come back to us through it
            KillaNet.mixCover(100);
            long loopsBack = 0;
            for (int i = 0; i < 150 && loopsBack == 0; i++) {
                Thread.sleep(200);
                String[] look = KillaNet.mixLook().split("\t");
                if (look.length >= 6)
                    loopsBack = Long.parseLong(look[5]);
            }
            check(loopsBack > 0, "cover loops came back through the gateway");
            KillaNet.mixCover(0);
            System.out.println("ok mixnet message through three helpers, answer through the reply block");
            System.exit(0);
        }
        if ("mixhost".equals(args[0])) {
            String id = KillaNet.start(new File(root, "network"), pin, flags | KillaNet.RELAY_ONLY, 0, entry);
            host(pair, cert, root, out);
            HammerMix.start();
            System.out.println("ready " + id);
            Thread.sleep(600000);
            return;
        }
        if ("mixvote".equals(args[0])) {
            // Protection: the vote and the comment reach the host through three mixes, sealed, and its answers
            // come back through reply blocks; only the test reads the song list and checks the result directly
            KillaNet.start(new File(root, "network"), pin, flags | KillaNet.TEST_NETWORKS, 0, entry);
            Hammer.Card card = Hammer.Card.parse(Files.readAllLines(out.toPath()).get(0));
            String why = null;
            for (int i = 0; i < 300; i++) {
                why = HammerMix.unready(card);
                if (why == null)
                    break;
                Thread.sleep(200);
            }
            check(why == null, "ready for a protected send" + (why == null ? "" : ": " + why));
            check(HammerMix.unready(new Hammer.Card("192.0.2.1", 7000, card.pin, "")) != null,
                  "a host reached by address is refused, not sent to directly");
            Hammer.Job job = new Hammer.Job();
            Hammer.Song song = Hammer.catalog(card, job).get(0);
            Hammer.Vote v = new Hammer.Vote();
            v.song = song.id;
            v.value = 1;
            v.sequence = (System.currentTimeMillis() - 1767225600000L) / 60000;
            v.sign(pair);
            long t0 = System.nanoTime();
            HammerMix.vote(card, v, job);
            long voteMs = (System.nanoTime() - t0) / 1000000;
            boolean counted = false;
            for (Hammer.Vote got : Hammer.votes(card, song.id, job))
                counted |= got.key.equals(v.key) && got.value == 1;
            check(counted, "the host took the vote that came through the mixnet (" + voteMs + " ms)");
            Hammer.Comment c = new Hammer.Comment();
            c.song = song.id;
            c.id = Hammer.random();
            c.time = System.currentTimeMillis();
            c.name = "mixnet test";
            StringBuilder body = new StringBuilder();
            while (body.length() < 1800)
                body.append("A comment long enough for two packets. ");
            c.body = body.toString();
            c.sign(pair);
            t0 = System.nanoTime();
            HammerMix.comment(card, c, job);
            long commentMs = (System.nanoTime() - t0) / 1000000;
            boolean posted = false;
            for (Hammer.Comment got : Hammer.comments(card, song.id, job))
                posted |= got.id.equals(c.id) && got.body.equals(c.body);
            check(posted, "the host took a comment in two parts, whole (" + commentMs + " ms)");
            Hammer.Vote stray = new Hammer.Vote();
            stray.song = Hammer.hash("no such song".getBytes("UTF-8"));
            stray.value = 1;
            stray.sequence = 1;
            stray.sign(pair);
            String refused = null;
            try {
                HammerMix.vote(card, stray, job);
            } catch (Exception e) {
                refused = e.getMessage();
            }
            check(refused != null && refused.startsWith("The host refused"),
                  "a refusal comes back with the host's reason (" + refused + ")");
            System.out.println("ok protected vote and comment through three helpers");
            System.exit(0);
        }
        if ("hidden".equals(args[0])) {
            // a hidden artist: the song goes in pieces through three mixes to helpers that adopt it, offered to every
            // mix node in turn (the ones that do not adopt refuse); then a listener finds it on each adopter
            KillaNet.start(new File(root, "network"), pin, flags | KillaNet.TEST_NETWORKS, 0, entry);
            List<String> cards = Files.readAllLines(out.toPath());
            String why = null;
            for (int i = 0; i < 300; i++) {
                why = HammerMix.unready();
                if (why == null && KillaNet.mixNodes().size() >= cards.size() + 3)
                    break;
                Thread.sleep(200);
            }
            check(why == null, "ready for a hidden upload" + (why == null ? "" : ": " + why));
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
            g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            KeyPair hidden = g.generateKeyPair();
            File tone = HammerIntegration.wave(root, "hidden.wav", 6, 44100, 5); // about 1 MB: 70 pieces for each helper
            long t0 = System.nanoTime();
            HammerHidden.Prepared p = HammerHidden.prepare(tone, new File(root, "hidden"), "Hidden tone", "test",
                                                           "Nobody in particular", hidden, new Hammer.Job());
            check(HammerHidden.stampOk(p.song.id, p.stamp), "a clean, signed song with its stamp (" +
                  (System.nanoTime() - t0) / 1000000 + " ms)");
            t0 = System.nanoTime();
            List<String> hosting = HammerHidden.publish(p, KillaNet.mixNodes(), new HammerHidden.Progress() {
                public void progress(int done, int total, List<String> hosts) {}
            }, new Hammer.Job());
            long ms = (System.nanoTime() - t0) / 1000000;
            check(hosting.size() == HammerHidden.COPIES, hosting.size() + " helpers adopted the " +
                  HammerHidden.pieces(p.song.size) + "-piece song (" + ms + " ms)");
            Hammer.Job job = new Hammer.Job();
            for (String line : cards) {
                Hammer.Card card = Hammer.Card.parse(line.trim());
                boolean listed = false, named = false;
                for (Hammer.Song s : Hammer.catalog(card, job))
                    listed |= s.id.equals(p.song.id);
                for (HammerProfile pr : Hammer.profiles(card, job))
                    named |= pr.name.equals("Nobody in particular") && pr.key.equals(p.song.key);
                check(listed && named, "an adopter lists the song under the hidden name");
            }
            Hammer.Card first = Hammer.Card.parse(cards.get(0).trim());
            File got = new File(root, "got.part"), whole = new File(root, "got.wav");
            Hammer.download(first, p.song, got, whole, job);
            check(Hammer.hash(whole).equals(p.song.id), "a listener downloads it whole from an adopter");
            System.out.println("ok hidden song adopted by three helpers");
            System.exit(0);
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

    static byte[] unhex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte)Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
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
