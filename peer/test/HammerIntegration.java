package ai.jackknife.killayue.peer;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;

/** Real TLS sockets, native codecs, storage and replica semantics; no mocked encryption. */
public final class HammerIntegration {
    static int passed;
    interface Attempt {
        void run() throws Exception;
    }
    static void check(boolean ok, String name) throws Exception {
        if (!ok)
            throw new Exception("FAILED: " + name);
        passed++;
    }
    static void rejects(Attempt action, String name) throws Exception {
        boolean failed = false;
        try {
            action.run();
        } catch (Exception expected) {
            failed = true;
        }
        check(failed, name);
    }
    public static File wave(File root, String name, int seconds, int rate, int seed) throws Exception {
        File f = new File(root, name);
        int frames = seconds * rate, bytes = frames * 4;
        java.nio.ByteBuffer b =
            java.nio.ByteBuffer.allocate(44 + bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R', 'I', 'F', 'F'})
            .putInt(bytes + 36)
            .put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
            .putInt(16)
            .putShort((short) 1)
            .putShort((short) 2)
            .putInt(rate)
            .putInt(rate * 4)
            .putShort((short) 4)
            .putShort((short) 16)
            .put(new byte[] {'d', 'a', 't', 'a'})
            .putInt(bytes);
        for (int n = 0; n < frames; n++) {
            double t = (double) n / rate;
            double tone = .30 * Math.sin(2 * Math.PI * (220 + seed) * t)
                + .15 * Math.sin(2 * Math.PI * 440 * t) + .10 * Math.sin(2 * Math.PI * 660 * t);
            short sample = (short) Math.round(tone * 32767);
            b.putShort(sample);
            b.putShort((short) (sample * .8));
        }
        Files.write(f.toPath(), b.array());
        return f;
    }
    static Hammer.Comment comment(String id, String audience, KeyPair key, int n) throws Exception {
        Hammer.Comment c = new Hammer.Comment();
        c.song = id;
        c.audience = audience;
        c.id = Hammer.random();
        c.time = System.currentTimeMillis();
        c.name = "Test author";
        c.body = "Comment " + n;
        c.sign(key);
        return c;
    }
    static Hammer.Vote vote(String id, String audience, KeyPair key, int value, long sequence)
        throws Exception {
        Hammer.Vote v = new Hammer.Vote();
        v.song = id;
        v.audience = audience;
        v.value = value;
        v.sequence = sequence;
        v.sign(key);
        return v;
    }
    public interface Controls {
        void run(Runnable action) throws Exception;
    }
    public static String run(final KeyPair key, final X509Certificate cert, File root) throws Exception {
        return run(key, cert, root, new Controls() {
            public void run(Runnable action) {
                action.run();
            }
        });
    }
    public static String run(final KeyPair key, final X509Certificate cert, File root, Controls controls)
        throws Exception {
        passed = 0;
        root.mkdirs();
        File source = wave(root, "master.wav", 12, 44100, 0),
             secret = wave(root, "private.wav", 3, 48000, 10);
        final HammerStore a = new HammerStore(new File(root, "a")), b = new HammerStore(new File(root, "b"));
        final Hammer.Song pub = a.publish(source, "Public tone", "jazz koto", true, true, key),
                          priv = a.publish(secret, "Secret title", "secret", true, false, key);
        check(a.hosted().get(0).file.getCanonicalFile().equals(source.getCanonicalFile()),
            "single master reference");
        check(!new File(a.root, pub.id + pub.extension()).exists(), "no permanent publication copy");
        final String fingerprint = Hammer.hash(cert.getPublicKey().getEncoded());
        final Hammer.Server first =
            new Hammer.Server(Hammer.serverContext(key, cert), new InetSocketAddress("127.0.0.1", 0), a);
        final Hammer.Server second =
            new Hammer.Server(Hammer.serverContext(key, cert), new InetSocketAddress("127.0.0.1", 0), b);
        final Hammer.Card publicCard = new Hammer.Card("127.0.0.1", first.port(), fingerprint, "");
        final Hammer.Card privateCard = new Hammer.Card("127.0.0.1", first.port(), fingerprint, a.token());
        try {
            check(Hammer.Card.parse(privateCard.encode()).token.equals(privateCard.token),
                "private invitation round trip");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.Card.parse("http://127.0.0.1/");
                }
            }, "no plaintext fallback");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.catalog(
                        new Hammer.Card("127.0.0.1", first.port(), Hammer.random(), ""), new Hammer.Job());
                }
            }, "wrong pinned identity refused");
            check(Hammer.catalog(publicCard, new Hammer.Job()).size() == 1,
                "public catalog excludes private songs");
            check(Hammer.catalog(privateCard, new Hammer.Job()).size() == 2,
                "private circle sees authorized catalog");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.catalog(new Hammer.Card("127.0.0.1", first.port(), fingerprint, Hammer.random()),
                        new Hammer.Job());
                }
            }, "invalid capability fails closed");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.chunk(publicCard, priv, 0, new Hammer.Job());
                }
            }, "private audio cannot be fetched by hash");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.frames(publicCard, priv.id, new Hammer.Job());
                }
            }, "private native info protected");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.segment(publicCard, priv.id, 0, 48000, new Hammer.Job());
                }
            }, "private transcoder protected");
            check(pub.matches("JAZZ tone") && !pub.matches("missing"),
                "local case-insensitive multiword search");
            final Hammer.Song tampered =
                new Hammer.Song(pub.id, "Forgery", pub.tags, pub.type, pub.size, true);
            tampered.key = pub.key;
            tampered.sig = pub.sig;
            rejects(new Attempt() {
                public void run() throws Exception {
                    tampered.validate();
                }
            }, "forged publication refused");
            rejects(new Attempt() {
                public void run() throws Exception {
                    a.follow(new HammerStore.Peer(privateCard, "leak", true));
                }
            }, "private directory introductions refused");
            a.follow(new HammerStore.Peer(publicCard, "public", true));
            check(Hammer.peers(publicCard, new Hammer.Job()).size() == 1, "opt-in public introduction");
            File target = new File(b.root, pub.id + pub.extension()),
                 part = new File(b.root, pub.id + ".part");
            byte[] original = Files.readAllBytes(source.toPath());
            Files.write(part.toPath(), Arrays.copyOf(original, 7777));
            Hammer.download(publicCard, pub, part, target, new Hammer.Job());
            check(Hammer.hash(target).equals(pub.id), "unaligned resumed download verifies whole recording");
            b.rememberDownload(pub);
            b.rehost(pub, true);
            check(b.catalog("").get(0).sig.equals(pub.sig), "rehosting preserves publisher signature");
            final Hammer.Card replica = new Hammer.Card("127.0.0.1", second.port(), fingerprint, "");
            check(Arrays.equals(Hammer.chunk(replica, pub, 1234, new Hammer.Job()),
                      Arrays.copyOfRange(original, 1234, 1234 + Hammer.CHUNK)),
                "replica serves byte-identical ranges");
            File bad = new File(root, "bad.part"), badTarget = new File(root, "bad.wav");
            Files.write(bad.toPath(), new byte[100]);
            final File corrupt = bad, wrong = badTarget;
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.download(publicCard, pub, corrupt, wrong, new Hammer.Job());
                }
            }, "corrupt partial cannot become a verified download");
            check(!bad.exists() && !badTarget.exists(), "corrupt partial removed");
            Hammer.Comment c = comment(pub.id, "public", key, 1);
            Hammer.comment(publicCard, c, new Hammer.Job());
            Hammer.comment(publicCard, c, new Hammer.Job());
            check(Hammer.comments(publicCard, pub.id, new Hammer.Job()).size() == 1,
                "signed comment replicas deduplicate");
            final Hammer.Comment forged = comment(pub.id, "public", key, 2);
            forged.body = "tampered";
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.comment(publicCard, forged, new Hammer.Job());
                }
            }, "forged comment rejected");
            String circle = Hammer.hash(a.token().getBytes("US-ASCII"));
            Hammer.comment(privateCard, comment(pub.id, circle, key, 3), new Hammer.Job());
            check(Hammer.comments(publicCard, pub.id, new Hammer.Job()).size() == 1,
                "private comments hidden on public songs");
            check(Hammer.comments(privateCard, pub.id, new Hammer.Job()).size() == 2,
                "circle gets private comments");
            final Hammer.Comment privateCopy = Hammer.comments(privateCard, pub.id, new Hammer.Job()).get(1);
            rejects(new Attempt() {
                public void run() throws Exception {
                    b.importPublicMetadata(
                        pub, Arrays.asList(privateCopy), Collections.<Hammer.Vote>emptyList());
                }
            }, "private comments cannot enter public replica metadata");
            Hammer.Vote up = vote(pub.id, "public", key, 1, 1);
            Hammer.vote(publicCard, up, new Hammer.Job());
            Hammer.vote(publicCard, up, new Hammer.Job());
            check(Hammer.votes(publicCard, pub.id, new Hammer.Job()).size() == 1,
                "duplicate votes counted once");
            Hammer.Vote down = vote(pub.id, "public", key, -1, 2);
            Hammer.vote(publicCard, down, new Hammer.Job());
            Hammer.vote(publicCard, up, new Hammer.Job());
            check(Hammer.votes(publicCard, pub.id, new Hammer.Job()).get(0).value == -1,
                "new vote wins; stale replica cannot undo it");
            Hammer.vote(privateCard, vote(pub.id, circle, key, 1, 1), new Hammer.Job());
            check(Hammer.votes(publicCard, pub.id, new Hammer.Job()).size() == 1, "private votes hidden");
            b.importPublicMetadata(pub, Hammer.comments(publicCard, pub.id, new Hammer.Job()),
                Hammer.votes(publicCard, pub.id, new Hammer.Job()));
            check(Hammer.votes(replica, pub.id, new Hammer.Job()).get(0).value == -1,
                "track votes travel with replica metadata");
            check(Hammer.hash(source).equals(pub.id), "metadata changes never rewrite master audio");
            byte[] metadata = a.publicMetadata(pub);
            check(new File(a.root, pub.id + ".metadata").isFile(),
                "portable metadata is saved beside each hosted track");
            check(!new String(metadata, "ISO-8859-1").contains(circle),
                "public portable metadata excludes private audience tokens");
            String publicCursor = Hammer.cursor(a.catalog(""));
            a.visibility(priv.id, false);
            check(a.changed("", publicCursor, 25) == null,
                "private publications do not emit public catalog changes");
            final CountDownLatch baseline = new CountDownLatch(1), event = new CountDownLatch(1);
            final Hammer.Job watch = new Hammer.Job();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        Hammer.subscribe(publicCard, watch, new Hammer.Listener() {
                            public void catalog(List<Hammer.Song> songs) {
                                if (baseline.getCount() > 0)
                                    baseline.countDown();
                                else
                                    event.countDown();
                            }
                        });
                    } catch (Exception e) {
                        if (!watch.cancelled)
                            e.printStackTrace();
                    }
                }
            });
            t.start();
            check(baseline.await(5, TimeUnit.SECONDS), "live subscription receives baseline");
            long sent = System.nanoTime();
            a.visibility(priv.id, true);
            check(event.await(5, TimeUnit.SECONDS), "new release pushed without polling");
            long pushMillis = (System.nanoTime() - sent) / 1000000;
            controls.run(new Runnable() {
                public void run() {
                    watch.cancel();
                }
            });
            t.join(2000);
            check(!t.isAlive(), "subscription cancellation closes socket");
            final Hammer.Song missed = a.publish(
                wave(root, "missed.wav", 2, 48000, 33), "Released offline", "test", true, true, key);
            final CountDownLatch caughtUp = new CountDownLatch(1);
            final Hammer.Job reconnect = new Hammer.Job();
            Thread resumed = new Thread(new Runnable() {
                public void run() {
                    try {
                        Hammer.subscribe(publicCard, reconnect, new Hammer.Listener() {
                            public void catalog(List<Hammer.Song> songs) {
                                for (Hammer.Song song : songs)
                                    if (song.id.equals(missed.id))
                                        caughtUp.countDown();
                            }
                        });
                    } catch (Exception expected) {
                    }
                }
            });
            resumed.start();
            check(caughtUp.await(5, TimeUnit.SECONDS),
                "reconnected subscription catches releases published while offline");
            controls.run(new Runnable() {
                public void run() {
                    first.disconnect();
                }
            });
            resumed.join(2000);
            check(!resumed.isAlive(), "host disconnect ends subscription for retry");
            reconnect.cancel();
            final Hammer.Job cancelled = new Hammer.Job();
            cancelled.cancel();
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.subscribe(publicCard, cancelled, new Hammer.Listener() {
                        public void catalog(List<Hammer.Song> songs) {
                            throw new AssertionError("Cancelled watcher delivered an event");
                        }
                    });
                }
            }, "cancelled follow cannot start a subscription");
            final CountDownLatch circleReady = new CountDownLatch(1);
            final Hammer.Job circleWatch = new Hammer.Job();
            Thread privateWatch = new Thread(new Runnable() {
                public void run() {
                    try {
                        Hammer.subscribe(privateCard, circleWatch, new Hammer.Listener() {
                            public void catalog(List<Hammer.Song> songs) {
                                circleReady.countDown();
                            }
                        });
                    } catch (Exception expected) {
                    }
                }
            });
            privateWatch.start();
            check(circleReady.await(5, TimeUnit.SECONDS), "private push authenticates before subscription");
            a.revoke();
            privateWatch.join(2000);
            check(!privateWatch.isAlive(), "revocation terminates active private subscription");
            circleWatch.cancel();
            a.revoke();
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.catalog(privateCard, new Hammer.Job());
                }
            }, "revoked invitations rejected");
            long frames = Hammer.frames(publicCard, pub.id, new Hammer.Job());
            check(frames == 12L * 48000, "native sample-rate conversion keeps duration");
            long start = System.nanoTime();
            byte[] encoded = Hammer.segment(publicCard, pub.id, 0, 96000, new Hammer.Job());
            long encodeMs = (System.nanoTime() - start) / 1000000;
            byte[] decoded = HammerNative.decode(encoded);
            check(decoded.length == 96000 * 4, "native Opus decodes exact two-second block");
            check(encoded.length < 96000 * 4 / 4, "compressed segment reduces transport bytes");
            double errorPower = 0, signalPower = 0;
            for (int n = 4800; n < 94000; n++) {
                double secondsAt = (double) n / 48000;
                double expected = .30 * Math.sin(2 * Math.PI * 220 * secondsAt)
                    + .15 * Math.sin(2 * Math.PI * 440 * secondsAt)
                    + .10 * Math.sin(2 * Math.PI * 660 * secondsAt);
                short sample = (short) ((decoded[n * 4] & 255) | ((decoded[n * 4 + 1] & 255) << 8));
                double value = (double) sample / 32767;
                errorPower += (value - expected) * (value - expected);
                signalPower += expected * expected;
            }
            check(10 * Math.log10(signalPower / errorPower) > 20,
                "native decode retains waveform and alignment above 20 dB SNR");
            final byte[] malformed = encoded.clone();
            malformed[0] = 0;
            rejects(new Attempt() {
                public void run() throws Exception {
                    HammerNative.decode(malformed);
                }
            }, "native malformed packet rejected");
            for (int n = 0; n < 50; n++) {
                final byte[] shortPacket = Arrays.copyOf(encoded, n);
                rejects(new Attempt() {
                    public void run() throws Exception {
                        HammerNative.decode(shortPacket);
                    }
                }, "truncated segment " + n);
            }
            List<Hammer.Card> routes = Arrays.asList(publicCard, replica);
            HammerBuffer originalBuffer = new HammerBuffer(routes, pub, HammerBuffer.ORIGINAL, 0);
            try {
                byte[] dest = new byte[9000];
                int got = originalBuffer.readAt(734567, dest, 0, dest.length);
                check(Arrays.equals(
                          Arrays.copyOf(dest, got), Arrays.copyOfRange(original, 734567, 734567 + got)),
                    "native original-source seek is exact");
                check(originalBuffer.readAt(pub.size, dest, 0, 1) == -1, "stream EOF");
            } finally {
                originalBuffer.close();
            }
            HammerBuffer compressed = new HammerBuffer(routes, pub, HammerBuffer.AUTO, frames);
            try {
                byte[] header = new byte[44];
                check(compressed.readAt(0, header, 0, 44) == 44 && header[0] == 'R',
                    "compressed stream has seekable PCM container");
                byte[] block = new byte[1024];
                check(compressed.readAt(44 + 3L * 384000, block, 0, block.length) == block.length,
                    "native compressed seek");
                check(compressed.bufferedBytes <= 3 * 1024 * 1024, "native playback cache bounded");
                controls.run(new Runnable() {
                    public void run() {
                        first.close();
                    }
                });
                check(compressed.readAt(44 + 5L * 384000, block, 0, block.length) == block.length,
                    "playback survives original host leaving");
            } finally {
                compressed.close();
            }
            HammerStore reopened = new HammerStore(b.root);
            check(reopened.votes(pub.id, "").get(0).value == -1, "vote metadata survives host restart");
            b.block(c.key);
            check(b.comments(pub.id, "").isEmpty(), "host moderation hides author");
            check(HammerNative.autoBitrate(2000, 160000) == 48000
                    && HammerNative.autoBitrate(100, 48000) == 160000,
                "native adaptive bitrate responds to transfer time");
            return "HAMMER: " + passed + " assertions passed; loopback push " + pushMillis
                + " ms; first 2 s Opus segment " + encoded.length + " bytes, " + encodeMs
                + " ms incl. TLS.\n";
        } finally {
            first.close();
            second.close();
        }
    }
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(args[0])) {
            keys.load(in, "hammer-test-only".toCharArray());
        }
        X509Certificate cert = (X509Certificate) keys.getCertificate("test");
        KeyPair pair = new KeyPair(
            cert.getPublicKey(), (PrivateKey) keys.getKey("test", "hammer-test-only".toCharArray()));
        System.out.print(run(pair, cert, new File(args[1])));
    }
}
