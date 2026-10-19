package ai.jackknife.killayue.peer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Real signed records, persistent sidecars and pinned TLS; tests the public/private and ownership
 * boundaries.
 */
public final class CommunityIntegration {
    static int passed;
    interface Task {
        void run() throws Exception;
    }
    static void check(boolean ok, String name) throws Exception {
        if (!ok)
            throw new Exception("FAILED: " + name);
        passed++;
    }
    static void rejects(Task task, String name) throws Exception {
        boolean rejected = false;
        try {
            task.run();
        } catch (Exception e) {
            rejected = true;
        }
        check(rejected, name);
    }
    static KeyPair key() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }
    static HammerCommunity.Post post(String scope, String target, String parent, String kind, String audience,
                                     KeyPair key) throws Exception {
        HammerCommunity.Post p = new HammerCommunity.Post();
        p.scope = scope;
        p.target = target;
        p.parent = parent;
        p.kind = kind;
        p.audience = audience;
        p.name = "Test artist";
        p.body = "Open source music for everyone";
        p.sign(key);
        return p;
    }
    static HammerCommunity.Follow follow(String host, String action, KeyPair key) throws Exception {
        HammerCommunity.Follow f = new HammerCommunity.Follow();
        f.host = host;
        f.action = action;
        f.name = "Test fan";
        f.sign(key);
        return f;
    }
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(args[0])) {
            keys.load(in, "hammer-test-only".toCharArray());
        }
        X509Certificate cert = (X509Certificate)keys.getCertificate("test");
        KeyPair artist = new KeyPair(cert.getPublicKey(),
                                     (PrivateKey)keys.getKey("test", "hammer-test-only".toCharArray()));
        String pin = Hammer.hash(artist.getPublic().getEncoded());
        KeyPair fan = key(), stranger = key();
        File root = new File(args[1]);
        root.mkdirs();
        HammerStore store = new HammerStore(new File(root, "host"));
        HammerProfile profile = new HammerProfile();
        profile.name = "Test artist";
        profile.revision = 1;
        profile.sign(artist);
        store.publicObjects.publishProfile(profile);
        File wav = HammerIntegration.wave(root, "original.wav", 1, 48000, 0);
        Hammer.Song song = store.publish(wav, "Original", "lofi", true, true, artist);
        String audioHash = Hammer.hash(wav), signature = song.sig;
        HammerCommunity.Post rootPost = post("track", song.id, "", "message", "public", artist);
        rootPost.position = 125;
        rootPost.sign(artist);
        store.community.post(rootPost, "");
        store.community.post(rootPost, "");
        check(store.community.list("track", song.id, "").size() == 1, "post idempotency");
        HammerCommunity.Post reply = post("track", song.id, rootPost.id, "message", "public", fan);
        store.community.post(reply, "");
        check(Hammer.hash(wav).equals(audioHash) && song.sig.equals(signature),
              "sidecars do not modify audio/publication");
        HammerCommunity.Post forged = post("track", song.id, rootPost.id, "hide", "public", stranger);
        rejects(new Task() {
            public void run() throws Exception { store.community.post(forged, ""); }
        }, "forged moderation rejected");
        HammerCommunity.Post altered = post("track", song.id, "", "message", "public", stranger);
        altered.body = "tampered";
        rejects(new Task() {
            public void run() throws Exception { store.community.post(altered, ""); }
        }, "tampered body rejected");
        HammerCommunity.Post conflict = post("track", song.id, "", "message", "public", artist);
        conflict.id = rootPost.id;
        conflict.sign(artist);
        rejects(new Task() {
            public void run() throws Exception { store.community.post(conflict, ""); }
        }, "conflicting IDs rejected");
        HammerCommunity.Post orphan = post("track", song.id, Hammer.random(), "message", "public", artist);
        rejects(new Task() {
            public void run() throws Exception { store.community.post(orphan, ""); }
        }, "orphan reply rejected");
        HammerCommunity.Post board = post("board", pin, "", "message", "public", artist);
        store.community.post(board, "");
        HammerCommunity.Post fanRoot = post("board", pin, "", "message", "public", stranger);
        rejects(new Task() {
            public void run() throws Exception { store.community.post(fanRoot, ""); }
        }, "only artist starts board thread");
        HammerCommunity.Post boardReply = post("board", pin, board.id, "message", "public", stranger);
        store.community.post(boardReply, "");
        HammerCommunity.Post lock = post("board", pin, board.id, "lock", "public", artist);
        store.community.post(lock, "");
        HammerCommunity.Post afterLock = post("board", pin, board.id, "message", "public", key());
        rejects(new Task() {
            public void run() throws Exception { store.community.post(afterLock, ""); }
        }, "locked thread rejects fan reply");
        HammerCommunity.Post hide = post("track", song.id, reply.id, "hide", "public", artist);
        store.community.post(hide, "");
        check(store.community.visible("track", song.id, "").size() == 1,
              "hide applied without deleting signed history");
        HammerCommunity.Post pinPost = post("track", song.id, rootPost.id, "pin", "public", artist);
        store.community.post(pinPost, "");
        check(store.community.visible("track", song.id, "").get(0).id.equals(rootPost.id), "pin applied");
        String privateToken = store.token();
        Hammer.Song privateSong = store.publish(HammerIntegration.wave(root, "private.wav", 1, 48000, 1),
                                                "Private", "", false, false, artist);
        HammerCommunity.Post secret =
            post("track", privateSong.id, "", "message", HammerCommunity.audience(privateToken), artist);
        store.community.post(secret, privateToken);
        rejects(new Task() {
            public void run() throws Exception { store.community.list("track", privateSong.id, ""); }
        }, "private track metadata not publicly readable");
        rejects(new Task() {
            public void run() throws Exception { store.community.post(secret, ""); }
        }, "private post cannot be submitted publicly");
        File remixFile = HammerIntegration.wave(root, "remix.wav", 1, 48000, 2);
        Hammer.Song remix = store.publish(remixFile, "Remix", "", true, true, fan);
        HammerCommunity.Post link = post("track", song.id, "", "message", "public", artist);
        link.kind = "remix";
        link.remix = remix;
        link.host = new Hammer.Card("127.0.0.1", 8784, pin, privateToken).encode();
        link.sign(fan);
        rejects(new Task() {
            public void run() throws Exception { link.validate(); }
        }, "private capabilities cannot enter public remix links");
        link.host = new Hammer.Card("127.0.0.1", 8784, pin, "").encode();
        link.sign(artist);
        rejects(new Task() {
            public void run() throws Exception { link.validate(); }
        }, "cannot claim another artist's remix");
        link.sign(fan);
        link.validate();
        check(true, "signed remix and public route accepted");
        // Use a second identity to avoid the deliberate host posting rate limit.
        HammerCommunity.Post source = post("track", remix.id, "", "message", "public", fan);
        source.kind = "source";
        source.remix = song;
        source.host = link.host;
        source.sign(fan);
        store.community.post(source, "");
        check(store.community.list("track", remix.id, "").get(0).remix.id.equals(song.id),
              "original-track backlink is a sidecar");
        HammerStore replica = new HammerStore(new File(root, "replica"));
        Files.copy(wav.toPath(), replica.downloadFile(song).toPath());
        replica.rememberDownload(song);
        replica.community.importPublic(song, store.community.list("track", song.id, ""));
        replica.rehost(song, true);
        check(replica.community.visible("track", song.id, "").size() == 1,
              "public conversation and moderation survive rehosting");
        rejects(new Task() {
            public void run() throws Exception {
                replica.community.importPublic(song, Collections.singletonList(secret));
            }
        }, "private sidecar cannot be rehosted publicly");
        HammerStore restarted = new HammerStore(store.root);
        check(restarted.community.list("track", song.id, "").size() == 4, "metadata survives restart");
        check(store.community.follow(follow(pin, "follow", fan), pin).isEmpty(),
              "registration does not auto-grant private access");
        store.community.invite(Hammer.publicKey(fan.getPublic()));
        check(store.community.follow(follow(pin, "inbox", fan), pin).equals(privateToken),
              "invited follower retrieves circle");
        check(store.community.follow(follow(pin, "inbox", stranger), pin).isEmpty(),
              "other follower cannot retrieve grant");
        HammerCommunity.Follow imposter = follow(pin, "inbox", stranger);
        imposter.key = Hammer.publicKey(fan.getPublic());
        rejects(new Task() {
            public void run() throws Exception { store.community.follow(imposter, pin); }
        }, "recipient signature prevents impersonation");
        HammerCommunity.Follow wrongHost = follow(Hammer.random(), "inbox", fan);
        rejects(new Task() {
            public void run() throws Exception { store.community.follow(wrongHost, pin); }
        }, "request bound to intended host");
        HammerCommunity.Follow expired = follow(pin, "inbox", fan);
        expired.time = System.currentTimeMillis() - 180000;
        expired.sign(fan);
        rejects(new Task() {
            public void run() throws Exception { store.community.follow(expired, pin); }
        }, "stale request rejected");
        store.revoke();
        check(store.community.follow(follow(pin, "inbox", fan), pin).isEmpty(),
              "circle rotation invalidates queued grant");
        rejects(new Task() {
            public void run() throws Exception {
                store.community.list("track", privateSong.id, privateToken);
            }
        }, "old capability fails after rotation");
        store.community.follow(follow(pin, "unfollow", fan), pin);
        check(store.community.followers().isEmpty(), "unfollow deletes registration and grant");
        store.census.enabled = true;
        String month = HammerCensus.month(), seed = Hammer.random();
        String token = HammerCensus.token(seed, pin, month);
        check(!token.equals(HammerCensus.token(seed, pin, "2000-01")), "count token rotates monthly");
        check(!token.equals(HammerCensus.token(seed, Hammer.random(), month)),
              "count token separated by node");
        check(store.census.count(month, token, false) == 1 && store.census.count(month, token, false) == 1,
              "repeat check-ins count once");
        HammerCensus recovered = new HammerCensus(store.root);
        recovered.enabled = true;
        check(recovered.count(month, "", false) == 1, "count survives restart");
        check(recovered.count(month, token, true) == 0, "opt-out removes current token");
        rejects(new Task() {
            public void run() throws Exception { store.census.count("2000-01", token, false); }
        }, "old month cannot inflate current count");
        HammerCensus off = new HammerCensus(new File(root, "replica"));
        rejects(new Task() {
            public void run() throws Exception { off.count(month, token, false); }
        }, "count hosting off by default");
        File rolloverRoot = new File(root, "rollover");
        rolloverRoot.mkdir();
        try (DataOutputStream o =
                 new DataOutputStream(new FileOutputStream(new File(rolloverRoot, "community-count.bin")))) {
            Hammer.text(o, "2000-01", 7);
            o.writeInt(1);
            Hammer.text(o, token, 64);
        }
        HammerCensus rolled = new HammerCensus(rolloverRoot);
        rolled.enabled = true;
        check(rolled.count(month, "", false) == 0, "old-month tokens purged");
        try (Hammer.Server server = new Hammer.Server(Hammer.serverContext(artist, cert),
                                                      new InetSocketAddress("127.0.0.1", 0), store)) {
            Hammer.Card peer = new Hammer.Card("127.0.0.1", server.port(), pin, "");
            check(Hammer.catalog(peer, new Hammer.Job()).size() == 2, "old catalog protocol unchanged");
            check(HammerCommunity.list(peer, "track", song.id, new Hammer.Job()).size() == 4,
                  "conversation over pinned TLS");
            HammerCommunity.Post remote = post("track", song.id, rootPost.id, "message", "public", key());
            HammerCommunity.post(peer, remote, new Hammer.Job());
            check(store.community.list("track", song.id, "").size() == 5, "remote reply persisted on host");
            HammerCommunity.follow(peer, follow(pin, "follow", fan), new Hammer.Job());
            store.community.invite(Hammer.publicKey(fan.getPublic()));
            check(HammerCommunity.follow(peer, follow(pin, "inbox", fan), new Hammer.Job())
                      .equals(store.token()),
                  "recipient-only invite over TLS");
            check(HammerCensus.count(peer, Hammer.random(), false, new Hammer.Job()) == 2,
                  "counter over pinned TLS");
            check(Hammer.frames(peer, song.id, new Hammer.Job()) > 0 &&
                      Hammer.segment(peer, song.id, 0, 48000, new Hammer.Job()).length > 0,
                  "native volunteer audio compute still works");
        }
        System.out.println("Community integration: " + passed + " checks passed");
    }
}
