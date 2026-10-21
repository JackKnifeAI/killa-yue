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
    private final java.util.concurrent.ScheduledExecutorService discovery =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private final Hammer.Job announceJob = new Hammer.Job();
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
            String keytool =
                new File(new File(System.getProperty("java.home"), "bin"),
                         System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool")
                    .getPath();
            ProcessBuilder pb = new ProcessBuilder(
                keytool, "-genkeypair", "-alias", "hammer", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=HAMMER peer", "-validity", "3650", "-storetype",
                "PKCS12", "-keystore", file.getPath(), "-storepass:env", "HAMMER_KEY_PASSWORD",
                "-keypass:env", "HAMMER_KEY_PASSWORD", "-noprompt");
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
        X509Certificate cert = (X509Certificate)keys.getCertificate("hammer");
        identity = new KeyPair(cert.getPublicKey(), (PrivateKey)keys.getKey("hammer", password));
        pin = Hammer.hash(cert.getPublicKey().getEncoded());
        store = new HammerStore(data);
        int moved = store.recleanShared(identity, new Hammer.Job());
        if (moved > 0)
            System.out.println("Shared " + moved + " songs again without hidden data");
        this.address = address;
        server =
            new Hammer.Server(Hammer.serverContext(identity, cert), new InetSocketAddress(bind, port), store);
        discovery.scheduleWithFixedDelay(new Runnable() {
            public void run() { announce(); }
        }, 1, 600, java.util.concurrent.TimeUnit.SECONDS);
    }
    private static void privatePermissions(File file, boolean directory) throws Exception {
        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file.toPath(),
                                          java.nio.file.attribute.PosixFilePermissions.fromString(
                                              directory ? "rwx------" : "rw-------"));
    }
    public void close() {
        KillaNet.announce(false); KillaNet.serve(null); KillaNet.stop();
        discovery.shutdownNow();
        announceJob.cancel();
        for (Hammer.Job j : subscriptions)
            j.cancel();
        server.close();
        Arrays.fill(password, '\0');
    }
    /** Publishes one file with what it already carries: title (else the file name), genre and cover. */
    private Hammer.Song publishFile(File f, boolean pub, String title) throws Exception {
        EmbeddedArt e = EmbeddedArt.read(f);
        if (title.isEmpty())
            title = e.title;
        if (title.isEmpty())  // "Song name [a1b2c3d4].mp3" → "Song name"
            title = f.getName().replaceFirst("\\.[^.]+$", "").replaceFirst("\\s*\\[[0-9a-fA-F]{6,}\\]$", "").trim();
        Hammer.Song s = store.publish(f, title, e.genre, true, pub, identity);
        if (store.publicObjects.localCover(s.id) == null)
            attachCover(s, e);
        if (!e.lyrics.isEmpty() && store.publicObjects.localLyrics(s.id) == null)
            try {
                HammerLyrics l = new HammerLyrics();
                l.song = s.id;
                l.text = e.lyrics;
                l.sign(identity);
                store.publicObjects.putLyrics(s, l);
            } catch (Exception unusableLyrics) {
            }
        return s;
    }
    /**
     * Publishes the albums the songs' own tags describe: every album tag shared by two or more public hosted songs
     * becomes a signed album, in file-name order (track order for most tools), with the first song's cover.
     */
    private int albumsFromTags() throws Exception {
        Map<String, List<HammerStore.Hosted>> byAlbum = new TreeMap<String, List<HammerStore.Hosted>>();
        for (HammerStore.Hosted h : store.hosted()) {
            if (!h.publicSong)
                continue;
            String album = EmbeddedArt.read(h.source.isFile() ? h.source : h.file).album;
            if (album.isEmpty())
                continue;
            if (!byAlbum.containsKey(album))
                byAlbum.put(album, new ArrayList<HammerStore.Hosted>());
            byAlbum.get(album).add(h);
        }
        int n = 0;
        for (Map.Entry<String, List<HammerStore.Hosted>> e : byAlbum.entrySet()) {
            List<HammerStore.Hosted> songs = e.getValue();
            if (songs.size() < 2 || songs.size() > HammerAlbum.SONGS)
                continue;  // a single, or a whole catalogue tagged as one (a month of Suno songs) is no album
            Collections.sort(songs, new Comparator<HammerStore.Hosted>() {
                public int compare(HammerStore.Hosted a, HammerStore.Hosted b) { return a.file.getName().compareTo(b.file.getName()); }
            });
            HammerAlbum a = new HammerAlbum();
            a.title = e.getKey();
            for (HammerStore.Hosted h : songs.subList(0, Math.min(HammerAlbum.SONGS, songs.size()))) {
                a.songs.add(h.song.id);
                if (a.cover.isEmpty() && store.publicObjects.localCover(h.song.id) != null)
                    a.cover = h.song.id;
            }
            a.revision = System.currentTimeMillis();
            a.sign(identity);
            try {
                store.publicObjects.putAlbum(a);
                n++;
            } catch (Exception full) {
            }
        }
        return n;
    }
    /** Signs and stores a song's cover from its embedded or neighbouring art. False when there is none. */
    private boolean attachCover(Hammer.Song s, EmbeddedArt e) {
        try {
            byte[] jpeg = e.coverJpeg();
            if (jpeg == null)
                return false;
            HammerArt art = new HammerArt();
            art.song = s.id;
            art.image = store.publicObjects.putImage(jpeg);
            art.sign(identity);
            store.publicObjects.cover(s, art);
            return true;
        } catch (Exception noRoom) {
            return false;
        }
    }
    /**
     * Publishes every WAV, FLAC and MP3 under {@code dir} (three folders deep) that is not hosted yet, so an artist
     * brings a whole catalogue in one step. Returns {published, failed}.
     */
    private int[] publishFolder(File dir, boolean pub, boolean say) throws Exception {
        Hammer.require(dir.isDirectory(), "Choose a folder");
        Set<String> hosted = new HashSet<String>();
        for (HammerStore.Hosted h : store.hosted())
            hosted.add(h.source.getCanonicalPath());
        List<File> found = new ArrayList<File>();
        collect(dir, found, 0);
        Collections.sort(found);
        int done = 0, failed = 0;
        for (File f : found) {
            if (hosted.contains(f.getCanonicalPath()))
                continue;
            try {
                publishFile(f, pub, "");
                done++;
            } catch (Exception e) {
                failed++;
                if (say)
                    System.out.println("  " + f.getName() + ": " + e.getMessage());
                if (String.valueOf(e.getMessage()).contains("at most"))
                    break;  // the host is full
            }
        }
        if (done > 0) {
            try {
                albumsFromTags();  // a folder's albums come along
            } catch (Exception noAlbums) {
            }
            announce();
        }
        return new int[] {done, failed};
    }
    private static void collect(File dir, List<File> out, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 3)
            return;
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith("."))
                continue;
            if (f.isDirectory())
                collect(f, out, depth + 1);
            else if (n.matches(".*\\.(mp3|wav|flac)"))
                out.add(f);
        }
    }
    private void announce() {
        try {
            store.census.rollover();
            HammerStore.atomic(new File(store.root, "health.txt"),
                ("updated=" + System.currentTimeMillis() + "\nnetwork=" + KillaNet.status() +
                 "\nroles=hosting,community,audio-transcode\nremote-generation=disabled\ncount=" + store.census.enabled + "\n").getBytes(StandardCharsets.UTF_8));
            if (KillaNet.running()) KillaNet.announce(store.census.enabled || !store.catalog("").isEmpty() || store.publicObjects.own() != null);
            if (store.catalog("").isEmpty() && store.publicObjects.own() == null)
                return;
            HammerPresence p = new HammerPresence();
            p.card = card(false);
            if (!HammerPresence.publicAddress(p.card.host))
                return;
            p.expires = System.currentTimeMillis() + 1800000;
            p.sign(identity);
            store.directory().announce(p);
            for (HammerStore.Peer entry : store.followed())
                if (!entry.card.isPrivate() && !announceJob.cancelled) {
                    try {
                        Hammer.announce(entry.card, p, announceJob);
                    } catch (Exception unavailable) {
                    }
                }
        } catch (
            Exception invalidAddress) { /* LAN-only hosts remain available through their local address. */
        }
    }
    private Hammer.Card card(boolean circle) throws Exception {
        if (KillaNet.running()) return Hammer.Card.p2p(KillaNet.peerId(), pin, circle ? store.token() : "");
        return new Hammer.Card(address, server.port(), pin, circle ? store.token() : "");
    }
    private void peers() {
        List<HammerStore.Peer> ps = store.followed();
        for (int i = 0; i < ps.size(); i++)
            System.out.println(i + ": " + ps.get(i).label +
                               (ps.get(i).card.isPrivate() ? " [private]" : " [public]"));
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
            Hammer.download(selected.card, song, new File(store.root, song.id + ".part"), target,
                            new Hammer.Job());
        Hammer.require(Hammer.hash(target).equals(song.id), "Local audio differs");
        store.rememberDownload(song);
        try {
            for (HammerProfile p : Hammer.profiles(selected.card, new Hammer.Job()))
                if (p.key.equals(song.key))
                    store.publicObjects.rememberProfile(p);
            HammerArt art = Hammer.cover(selected.card, song, new Hammer.Job());
            if (art != null) {
                store.publicObjects.putImage(
                    Hammer.image(selected.card, song.id, art.image, false, new Hammer.Job()));
                store.publicObjects.cover(song, art);
            }
        } catch (Exception optionalArtwork) {
        }
        if (!selected.card.isPrivate())
            store.importPublicMetadata(song, Hammer.comments(selected.card, song.id, new Hammer.Job()),
                                       Hammer.votes(selected.card, song.id, new Hammer.Job()));
        if (!selected.card.isPrivate()) try {
            store.community.importPublic(song, HammerCommunity.list(selected.card, "track", song.id, new Hammer.Job()));
        } catch (Exception optionalCommunity) { System.out.println("Community metadata unavailable; audio remains verified."); }
        System.out.println("Verified download: " + target);
    }
    private void help() {
        System.out.println(
            "Commands (quote paths or names containing spaces):\n"
            + "  card public|private                   Show this host's peer card\n"
            + "  publish PATH public|private [TITLE]   Serve an existing WAV/FLAC/MP3; downloads enabled; the cover,\n"
            + "                                        title and genre inside the file come along\n"
            + "  publish-folder DIR public|private     Publish every song in a folder (and its folders) not yet hosted\n"
            + "  covers                                Attach the art and lyrics inside hosted songs that lack them\n"
            + "  lyrics                                Offer the lyrics inside or beside each hosted song again\n"
            + "  albums                                Publish the albums named in the songs' own tags\n"
            + "  album TITLE SONG_HASH...              Publish an album or playlist of hosted songs, in order\n"
            + "  stats                                 Plays and downloads served per song\n"
            + "  profile NAME BIO TAGS                  Publish signed public artist metadata\n"
            + "  withdraw-profile                      Remove this host public artist profile\n"
            + "  join CARD                             Join a public entry peer and announce this host\n"
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
            + "  board                                 Read this host's public artist board\n"
            + "  post TEXT                             Start an artist board thread\n"
            + "  followers                             List followers who requested circle invites\n"
            + "  invite FOLLOWER_NUMBER                Grant the existing private circle to a follower\n"
            + "  count                                 Show this node's participating installation count\n"
            + "  status                                Show node roles and bounded audio compute capabilities\n"
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
        else if (op.equals("status")) {
            System.out.println("Hosting and signed community metadata; native audio/transcode (Opus); remote generation: disabled");
            System.out.println("Network: " + (KillaNet.running() ? KillaNet.status() : "off"));
            System.out.println("Monthly counter: " + (store.census.enabled ? "enabled" : "off"));
        } else if (op.equals("count")) {
            System.out.println(store.census.count(HammerCensus.month(), "", false) + " participating installations · " + HammerCensus.month());
        } else if (op.equals("board")) {
            for (HammerCommunity.Post p : store.community.visible("board", pin, "")) System.out.println(p.id + " | " + p.name + " | " + p.body);
        } else if (op.equals("post")) {
            HammerProfile profile = store.publicObjects.own(); Hammer.require(profile != null, "Publish a profile first");
            HammerCommunity.Post p = new HammerCommunity.Post(); p.scope = "board"; p.target = pin; p.name = profile.name; p.body = a.get(1);
            p.sign(identity); store.community.post(p, ""); System.out.println("Posted " + p.id);
        } else if (op.equals("followers")) {
            List<HammerCommunity.Follower> fs = store.community.followers();
            for (int k = 0; k < fs.size(); k++) System.out.println(k + ": " + fs.get(k).name + " [" + Hammer.hash(Base64.getDecoder().decode(fs.get(k).key)).substring(0, 12) + "]");
        } else if (op.equals("invite")) {
            store.community.invite(store.community.followers().get(Integer.parseInt(a.get(1))).key);
            System.out.println("Invitation queued for authenticated follower retrieval.");
        } else if (op.equals("card"))
            System.out.println(card(scope(a.get(1)) == false).encode());
        else if (op.equals("publish")) {
            Hammer.Song s = publishFile(new File(a.get(1)), scope(a.get(2)), a.size() > 3 ? a.get(3) : "");
            System.out.println("Published " + s.id);
        } else if (op.equals("publish-folder")) {
            int[] n = publishFolder(new File(a.get(1)), scope(a.get(2)), true);
            System.out.println("Published " + n[0] + " new songs" + (n[1] > 0 ? "; " + n[1] + " could not be published" : ""));
        } else if (op.equals("covers")) {
            int n = 0, words = 0;
            for (HammerStore.Hosted h : store.hosted()) {
                EmbeddedArt e = EmbeddedArt.read(h.source.isFile() ? h.source : h.file);
                if (store.publicObjects.localCover(h.song.id) == null && attachCover(h.song, e))
                    n++;
                if (store.publicObjects.localLyrics(h.song.id) == null && !e.lyrics.isEmpty())
                    try {
                        HammerLyrics l = new HammerLyrics();
                        l.song = h.song.id;
                        l.text = e.lyrics;
                        l.sign(identity);
                        store.publicObjects.putLyrics(h.song, l);
                        words++;
                    } catch (Exception unusable) {
                    }
            }
            System.out.println("Covers attached: " + n + "; lyrics attached: " + words);
        } else if (op.equals("lyrics")) {  // lyrics again from the files, replacing what was offered
            int words = 0;
            for (HammerStore.Hosted h : store.hosted()) {
                EmbeddedArt e = EmbeddedArt.read(h.source.isFile() ? h.source : h.file);
                if (e.lyrics.isEmpty())
                    continue;
                try {
                    HammerLyrics l = new HammerLyrics();
                    l.song = h.song.id;
                    l.text = e.lyrics;
                    l.sign(identity);
                    store.publicObjects.putLyrics(h.song, l);
                    words++;
                } catch (Exception unusable) {
                }
            }
            System.out.println("Lyrics attached: " + words);
        } else if (op.equals("albums")) {
            System.out.println("Albums published: " + albumsFromTags());
        } else if (op.equals("album")) {
            HammerAlbum al = new HammerAlbum();
            al.title = a.get(1);
            for (String id : a.subList(2, a.size())) {
                Hammer.require(store.audio(id, "") != null, "Not a public hosted song: " + id);
                al.songs.add(id);
                if (al.cover.isEmpty() && store.publicObjects.localCover(id) != null)
                    al.cover = id;
            }
            al.revision = System.currentTimeMillis();
            al.sign(identity);
            store.publicObjects.putAlbum(al);
            System.out.println("Album published: " + al.title + " (" + al.songs.size() + " songs)");
        } else if (op.equals("stats")) {
            for (HammerStore.Hosted h : store.hosted()) {
                long[] n = store.stats(h.song.id, h.publicSong ? "" : store.token());
                System.out.println(n[0] + " plays | " + n[1] + " downloads | " + h.song.title);
            }
        } else if (op.equals("profile")) {
            HammerProfile p = new HammerProfile();
            p.name = a.get(1);
            p.bio = a.get(2);
            p.tags = a.get(3);
            HammerProfile old = store.publicObjects.own();
            p.revision = Math.max(System.currentTimeMillis(), old == null ? 1 : old.revision + 1);
            p.sign(identity);
            store.publicObjects.publishProfile(p);
            announce();
            System.out.println("Artist profile published.");
        } else if (op.equals("withdraw-profile")) {
            store.publicObjects.withdrawProfile();
            server.disconnect();
        } else if (op.equals("join")) {
            Hammer.Card entry = Hammer.Card.parse(a.get(1));
            Hammer.require(!entry.isPrivate(), "Use a public entry peer");
            Hammer.catalog(entry, new Hammer.Job());
            store.follow(new HammerStore.Peer(entry, "Community entry", true));
            announce();
            System.out.println(
                "Entry peer connected; public hosts announce when their address is reachable.");
        } else if (op.equals("hosted")) {
            for (HammerStore.Hosted h : store.hosted())
                System.out.println(h.song.id + " | " + (h.publicSong ? "public" : "private") + " | " +
                                   h.song.title);
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
                System.out.println(c.name + " [" +
                                   Hammer.hash(Base64.getDecoder().decode(c.key)).substring(0, 16) +
                                   "]: " + c.body);
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
        int port = Hammer.PORT; boolean daemon = false, network = false, helper = false, count = false, upnp = false;
        int networkPort = KillaNet.PORT; String entry = "", watch = ""; boolean watchPrivate = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data"))
                data = new File(args[++i]);
            else if (args[i].equals("--address"))
                address = args[++i];
            else if (args[i].equals("--bind"))
                bind = args[++i];
            else if (args[i].equals("--port"))
                port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--daemon")) daemon = true;
            else if (args[i].equals("--network")) network = true;
            else if (args[i].equals("--help-network")) { network = true; helper = true; }
            else if (args[i].equals("--community-count")) count = true;
            else if (args[i].equals("--upnp")) upnp = true;
            else if (args[i].equals("--network-port")) networkPort = Integer.parseInt(args[++i]);
            else if (args[i].equals("--entry")) entry = args[++i];
            else if (args[i].equals("--watch")) watch = args[++i];
            else if (args[i].equals("--watch-private")) watchPrivate = true;
            else
                throw new IllegalArgumentException(
                    "Use --daemon --network --help-network --community-count --network-port PORT --entry MULTIADDR --watch MUSIC_FOLDER --watch-private --data DIR --address REACHABLE_IP --bind BIND_IP --port PORT");
        }
        final HammerServer app = new HammerServer(data, address, bind, port);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() { app.close(); }
        }));
        System.out.println(
            "HAMMER desktop host listening on " + bind + ":" + app.server.port() +
            ". Nothing is public until you publish it.\nPublic card: " + app.card(false).encode());
        app.store.census.enabled = count;
        if (network) {
            try {
                KillaNet.start(new File(data, "network"), app.pin, (helper ? KillaNet.HELP : 0) | (upnp ? 0 : KillaNet.NO_UPNP), networkPort, entry);
                KillaNet.serve(app.server); app.announce();
                System.out.println("Network card: " + app.card(false).encode());
            } catch (Exception e) { app.close(); throw e; }
        }
        HammerStore.atomic(new File(data, "public.card"), app.card(false).encode().getBytes(StandardCharsets.US_ASCII));
        HammerStore.atomic(new File(data, "direct.card"), new Hammer.Card(address, app.server.port(), app.pin, "").encode().getBytes(StandardCharsets.US_ASCII));
        app.announce();
        if (!watch.isEmpty()) {  // keep a music folder published: new songs appear on the network by themselves
            final File folder = new File(watch);
            final boolean pub = !watchPrivate;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    while (true) {
                        try {
                            int[] n = app.publishFolder(folder, pub, false);
                            if (n[0] > 0)
                                System.out.println("Published " + n[0] + " new songs from " + folder);
                        } catch (Exception e) {
                            System.out.println("Watching " + folder + ": " + e.getMessage());
                        }
                        try {
                            Thread.sleep(5 * 60 * 1000);
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                }
            }, "watch-folder");
            t.setDaemon(true);
            t.start();
            System.out.println("Publishing every song in " + folder + (pub ? " (public)" : " (private)") + ", checking every five minutes.");
        }
        if (daemon) {
            System.out.println("Community node running: hosting, metadata, audio/transcode; remote generation disabled.");
            new java.util.concurrent.CountDownLatch(1).await();
            return;
        }
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
