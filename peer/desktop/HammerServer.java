package ai.jackknife.killayue.peer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

/** Desktop companion: the same protocol, storage and native pipeline as the phone. */
public final class HammerServer {
    private final HammerStore store;
    private final KeyStore keys;
    private final char[] password;
    private final KeyPair identity;
    private final Hammer.Server server;
    private final String address, pin;
    private final List<Hammer.Job> subscriptions = new ArrayList<Hammer.Job>();
    private List<Hammer.Song> viewed = Collections.emptyList();
    private HammerStore.Peer selected;
    public HammerServer(File data, String address, String bind, int port) throws Exception {
        Hammer.require(data.isDirectory() || data.mkdirs(), "Cannot create data directory");
        privatePermissions(data, true);
        File pw = new File(data, "identity.password");
        if (!pw.isFile())
            HammerStore.atomic(pw, Hammer.random().getBytes(StandardCharsets.US_ASCII));
        privatePermissions(pw, false);
        password = new String(HammerStore.read(pw, 64), StandardCharsets.US_ASCII).toCharArray();
        File file = new File(data, "identity.p12");
        if (!file.isFile()) {
            String keytool = new File(new File(System.getProperty("java.home"), "bin"),
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool")
                                 .getPath();
            ProcessBuilder pb = new ProcessBuilder(keytool, "-genkeypair", "-alias", "hammer", "-keyalg",
                "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-dname", "CN=HAMMER peer",
                "-validity", "3650", "-storetype", "PKCS12", "-keystore", file.getPath(), "-storepass:env",
                "HAMMER_KEY_PASSWORD", "-keypass:env", "HAMMER_KEY_PASSWORD", "-noprompt");
            pb.environment().put("HAMMER_KEY_PASSWORD", new String(password));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[1024];
                while (in.read(b) != -1) { /* keytool status is not application telemetry */
                }
            }
            Hammer.require(p.waitFor() == 0, "Could not create desktop identity with keytool");
            privatePermissions(file, false);
        }
        keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(file)) {
            keys.load(in, password);
        }
        X509Certificate cert = (X509Certificate) keys.getCertificate("hammer");
        identity = new KeyPair(cert.getPublicKey(), (PrivateKey) keys.getKey("hammer", password));
        pin = Hammer.hash(cert.getPublicKey().getEncoded());
        store = new HammerStore(data);
        this.address = address;
        server =
            new Hammer.Server(Hammer.serverContext(identity, cert), new InetSocketAddress(bind, port), store);
    }
    private static void privatePermissions(File file, boolean directory) throws Exception {
        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------"));
    }
    public void close() {
        for (Hammer.Job j : subscriptions) j.cancel();
        server.close();
        Arrays.fill(password, '\0');
    }
    private Hammer.Card card(boolean circle) throws Exception {
        return new Hammer.Card(address, server.port(), pin, circle ? store.token() : "");
    }
    private void peers() {
        List<HammerStore.Peer> ps = store.followed();
        for (int i = 0; i < ps.size(); i++)
            System.out.println(
                i + ": " + ps.get(i).label + (ps.get(i).card.isPrivate() ? " [private]" : " [public]"));
    }
    private Hammer.Song song(int index) throws Exception {
        Hammer.require(index >= 0 && index < viewed.size(), "Run catalog PEER_NUMBER first");
        return viewed.get(index);
    }
    private void catalog(int index) throws Exception {
        selected = store.followed().get(index);
        viewed = Hammer.catalog(selected.card, new Hammer.Job());
        for (int i = 0; i < viewed.size(); i++) {
            Hammer.Song s = viewed.get(i);
            System.out.println(i + ": " + s.title + " | " + s.tags + " | " + s.id);
        }
    }
    private void download(Hammer.Song song) throws Exception {
        File target = store.downloadFile(song);
        if (!target.isFile())
            Hammer.download(
                selected.card, song, new File(store.root, song.id + ".part"), target, new Hammer.Job());
        Hammer.require(Hammer.hash(target).equals(song.id), "Local audio differs");
        store.rememberDownload(song);
        if (!selected.card.isPrivate())
            store.importPublicMetadata(song, Hammer.comments(selected.card, song.id, new Hammer.Job()),
                Hammer.votes(selected.card, song.id, new Hammer.Job()));
        System.out.println("Verified download: " + target);
    }
    private void help() {
        System.out.println("Commands (quote paths or names containing spaces):\n"
            + "  card public|private                   Show this host's peer card\n"
            + "  publish PATH public|private TITLE     Serve an existing WAV/FLAC/MP3; downloads enabled\n"
            + "  hosted                                List hosted songs\n"
            + "  unpublish SONG_HASH                    Stop offering a song; keep its master\n"
            + "  revoke                                Replace all private invitations\n"
            + "  follow CARD NAME                      Follow a peer (no public introduction)\n"
            + "  introduce PEER_NUMBER                 Opt in to introducing a public peer\n"
            + "  peers                                 List followed peers\n"
            + "  catalog PEER_NUMBER                    Read a peer's signed catalog\n"
            + "  search WORDS                           Search the last catalog locally\n"
            + "  download SONG_NUMBER                   Resume and verify the original audio\n"
            + "  rehost SONG_NUMBER public|private      Host a verified download with original signature\n"
            + "  comments SONG_NUMBER                   Read signed comments\n"
            + "  comment SONG_NUMBER NAME TEXT          Post a signed comment\n"
            + "  votes SONG_NUMBER                      Read anonymous-display votes\n"
            + "  vote SONG_NUMBER up|down|clear         Vote with a separate key per track/circle\n"
            + "  watch PEER_NUMBER                      Receive live encrypted catalog events\n"
            + "  quit                                  Stop the server\n"
            + "Public sharing is explicit. Private cards grant access; keep them private. No central "
              + "services.\n"
            + "Reachability: same LAN, IPv6/public port, or your own VPN. NAT relays are not bundled.");
    }
    private KeyPair voter(String song, String audience) throws Exception {
        File file = new File(
            store.root, "voter-" + Hammer.hash((song + audience).getBytes(StandardCharsets.UTF_8)) + ".key");
        if (file.isFile())
            try (DataInputStream in =
                     new DataInputStream(new ByteArrayInputStream(HammerStore.read(file, 2048)))) {
                KeyFactory f = KeyFactory.getInstance("EC");
                return new KeyPair(f.generatePublic(new java.security.spec.X509EncodedKeySpec(
                                       Base64.getDecoder().decode(Hammer.text(in, 512)))),
                    f.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(
                        Base64.getDecoder().decode(Hammer.text(in, 1024)))));
            }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair p = g.generateKeyPair();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        Hammer.text(out, Base64.getEncoder().encodeToString(p.getPublic().getEncoded()), 512);
        Hammer.text(out, Base64.getEncoder().encodeToString(p.getPrivate().getEncoded()), 1024);
        HammerStore.atomic(file, b.toByteArray());
        privatePermissions(file, false);
        return p;
    }
    private boolean command(List<String> a) throws Exception {
        if (a.isEmpty())
            return true;
        String op = a.get(0);
        if (op.equals("help"))
            help();
        else if (op.equals("quit"))
            return false;
        else if (op.equals("card"))
            System.out.println(card(scope(a.get(1)) == false).encode());
        else if (op.equals("publish")) {
            Hammer.Song s = store.publish(new File(a.get(1)), a.get(3), "", true, scope(a.get(2)), identity);
            System.out.println("Published " + s.id);
        } else if (op.equals("hosted")) {
            for (HammerStore.Hosted h : store.hosted())
                System.out.println(
                    h.song.id + " | " + (h.publicSong ? "public" : "private") + " | " + h.song.title);
        } else if (op.equals("unpublish")) {
            store.unpublish(a.get(1));
            server.disconnect();
        } else if (op.equals("revoke")) {
            store.revoke();
            server.disconnect();
            System.out.println("Old private invitations revoked.");
        } else if (op.equals("follow")) {
            store.follow(new HammerStore.Peer(Hammer.Card.parse(a.get(1)), a.get(2), false));
        } else if (op.equals("introduce")) {
            HammerStore.Peer p = store.followed().get(Integer.parseInt(a.get(1)));
            store.follow(new HammerStore.Peer(p.card, p.label, true));
        } else if (op.equals("peers"))
            peers();
        else if (op.equals("catalog"))
            catalog(Integer.parseInt(a.get(1)));
        else if (op.equals("search")) {
            for (int i = 0; i < viewed.size(); i++)
                if (viewed.get(i).matches(a.get(1)))
                    System.out.println(i + ": " + viewed.get(i).title);
        } else if (op.equals("download"))
            download(song(Integer.parseInt(a.get(1))));
        else if (op.equals("rehost")) {
            Hammer.Song s = song(Integer.parseInt(a.get(1)));
            store.rehost(s, scope(a.get(2)));
            System.out.println("Rehosting the original signed recording.");
        } else if (op.equals("comments")) {
            for (Hammer.Comment c :
                Hammer.comments(selected.card, song(Integer.parseInt(a.get(1))).id, new Hammer.Job()))
                System.out.println(c.name + " ["
                    + Hammer.hash(Base64.getDecoder().decode(c.key)).substring(0, 16) + "]: " + c.body);
        } else if (op.equals("comment")) {
            Hammer.Comment c = new Hammer.Comment();
            c.song = song(Integer.parseInt(a.get(1))).id;
            c.id = Hammer.random();
            c.time = System.currentTimeMillis();
            c.name = a.get(2);
            c.body = a.get(3);
            c.audience = selected.card.isPrivate()
                ? Hammer.hash(selected.card.token.getBytes(StandardCharsets.US_ASCII))
                : "public";
            c.sign(identity);
            Hammer.comment(selected.card, c, new Hammer.Job());
        } else if (op.equals("votes")) {
            int up = 0, down = 0;
            for (Hammer.Vote v :
                Hammer.votes(selected.card, song(Integer.parseInt(a.get(1))).id, new Hammer.Job())) {
                if (v.value == 1)
                    up++;
                if (v.value == -1)
                    down++;
            }
            System.out.println(up + " up / " + down + " down");
        } else if (op.equals("vote")) {
            Hammer.Vote v = new Hammer.Vote();
            v.song = song(Integer.parseInt(a.get(1))).id;
            v.audience = selected.card.isPrivate()
                ? Hammer.hash(selected.card.token.getBytes(StandardCharsets.US_ASCII))
                : "public";
            String choice = a.get(2);
            Hammer.require(choice.equals("up") || choice.equals("down") || choice.equals("clear"),
                "Use up, down or clear");
            v.value = choice.equals("up") ? 1 : choice.equals("down") ? -1 : 0;
            KeyPair key = voter(v.song, v.audience);
            v.sequence = 1;
            for (Hammer.Vote old : Hammer.votes(selected.card, v.song, new Hammer.Job()))
                if (old.key.equals(Hammer.publicKey(key.getPublic())) && old.audience.equals(v.audience))
                    v.sequence = Math.max(v.sequence, old.sequence + 1);
            v.sign(key);
            Hammer.vote(selected.card, v, new Hammer.Job());
        } else if (op.equals("watch")) {
            final Hammer.Card peer = store.followed().get(Integer.parseInt(a.get(1))).card;
            final Hammer.Job job = new Hammer.Job();
            subscriptions.add(job);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        final Set<String> seen = new HashSet<String>();
                        Hammer.subscribe(peer, job, new Hammer.Listener() {
                            boolean baseline = true;
                            public void catalog(List<Hammer.Song> songs) {
                                for (Hammer.Song s : songs)
                                    if (seen.add(s.id) && !baseline)
                                        System.out.println("New release: " + s.title);
                                baseline = false;
                            }
                        });
                    } catch (Exception e) {
                        if (!job.cancelled)
                            System.out.println("Live peer disconnected; run watch again to reconnect.");
                    }
                }
            }, "hammer-desktop-live");
            t.setDaemon(true);
            t.start();
        } else
            throw new IOException("Unknown command. Type help.");
        return true;
    }
    private static boolean scope(String s) throws IOException {
        Hammer.require(s.equals("public") || s.equals("private"), "Choose public or private");
        return s.equals("public");
    }
    private static List<String> words(String line) throws IOException {
        List<String> out = new ArrayList<String>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (char c : line.toCharArray()) {
            if (quote != 0) {
                if (c == quote)
                    quote = 0;
                else
                    word.append(c);
                started = true;
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    out.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
            } else {
                word.append(c);
                started = true;
            }
        }
        Hammer.require(quote == 0, "Close the quote");
        if (started)
            out.add(word.toString());
        return out;
    }
    public static void main(String[] args) throws Exception {
        File data = new File(System.getProperty("user.home"), ".hammer");
        String address = "127.0.0.1", bind = "0.0.0.0";
        int port = Hammer.PORT;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data"))
                data = new File(args[++i]);
            else if (args[i].equals("--address"))
                address = args[++i];
            else if (args[i].equals("--bind"))
                bind = args[++i];
            else if (args[i].equals("--port"))
                port = Integer.parseInt(args[++i]);
            else
                throw new IllegalArgumentException(
                    "Use --data DIR --address REACHABLE_IP --bind BIND_IP --port PORT");
        }
        final HammerServer app = new HammerServer(data, address, bind, port);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                app.close();
            }
        }));
        System.out.println("HAMMER desktop host listening on " + bind + ":" + app.server.port()
            + ". Nothing is public until you publish it.\nPublic card: " + app.card(false).encode());
        app.help();
        try (BufferedReader in =
                 new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("hammer> ");
                String line = in.readLine();
                if (line == null)
                    break;
                try {
                    if (!app.command(words(line)))
                        break;
                } catch (Exception e) {
                    System.out.println("Could not complete command: " + e.getMessage());
                }
            }
        } finally {
            app.close();
        }
    }
}
