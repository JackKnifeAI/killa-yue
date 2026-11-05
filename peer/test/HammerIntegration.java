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
    static byte[] envelopeBytes(HammerCircle.Envelope e) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        e.write(new DataOutputStream(b));
        return b.toByteArray();
    }
    static byte[] stationBytes(HammerStation s) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        s.write(new DataOutputStream(b));
        return b.toByteArray();
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
            .putShort((short)1)
            .putShort((short)2)
            .putInt(rate)
            .putInt(rate * 4)
            .putShort((short)4)
            .putShort((short)16)
            .put(new byte[] {'d', 'a', 't', 'a'})
            .putInt(bytes);
        for (int n = 0; n < frames; n++) {
            double t = (double)n / rate;
            double tone = .30 * Math.sin(2 * Math.PI * (220 + seed) * t) +
                          .15 * Math.sin(2 * Math.PI * 440 * t) + .10 * Math.sin(2 * Math.PI * 660 * t);
            short sample = (short)Math.round(tone * 32767);
            b.putShort(sample);
            b.putShort((short)(sample * .8));
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
            public void run(Runnable action) { action.run(); }
        });
    }
    public static String run(final KeyPair key, final X509Certificate cert, File root, Controls controls)
        throws Exception {
        passed = 0;
        root.mkdirs();
        File source = wave(root, "master.wav", 12, 44100, 0), secret =
                                                                  wave(root, "private.wav", 3, 48000, 10);
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
                public void run() throws Exception { Hammer.Card.parse("http://127.0.0.1/"); }
            }, "no plaintext fallback");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.catalog(new Hammer.Card("127.0.0.1", first.port(), Hammer.random(), ""),
                                   new Hammer.Job());
                }
            }, "wrong pinned identity refused");
            check(Hammer.catalog(publicCard, new Hammer.Job()).size() == 1,
                  "public catalog excludes private songs");
            check(Hammer.catalog(privateCard, new Hammer.Job()).size() == 2,
                  "private circle sees authorized catalog");
            String seenSong = Hammer.catalog(publicCard, new Hammer.Job()).get(0).id, circleSong = "";
            for (Hammer.Song song : Hammer.catalog(privateCard, new Hammer.Job()))
                if (!song.id.equals(seenSong))
                    circleSong = song.id;
            a.counted(seenSong, 0);
            a.counted(seenSong, 1);
            a.counted(seenSong, 1);
            a.counted(circleSong, 0);
            KeyPairGenerator stationKeys = KeyPairGenerator.getInstance("EC");
            stationKeys.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            KeyPair stationKey = stationKeys.generateKeyPair();
            final HammerStation station = new HammerStation();
            station.title = "Test radio";
            station.songs.add(Hammer.hash(new byte[] {1}));
            station.songs.add(Hammer.hash(new byte[] {2}));
            station.lengths.add(1000);
            station.lengths.add(3000);
            station.started = System.currentTimeMillis() - 60000;
            station.ends = station.started + 120000;
            station.revision = 5;
            station.sign(stationKey);
            long begun = station.started;
            check(station.at(begun + 500).index == 0 && station.at(begun + 500).offset == 500, "a station starts at its first song");
            check(station.at(begun + 1500).index == 1 && station.at(begun + 1500).offset == 500, "a station moves on by the clock");
            check(station.at(begun + 4500).index == 0 && station.at(begun + 4500).offset == 500, "a station loops");
            check(station.at(begun - 1) == null && station.at(station.ends) == null, "a station is off air outside its hours");
            station.note = "changed after signing";
            rejects(new Attempt() {
                public void run() throws Exception { station.validate(); }
            }, "a changed station is refused");
            station.note = "";
            a.publicObjects.putStation(station);
            check(Hammer.stations(publicCard, new Hammer.Job()).isEmpty(), "a station with songs this host lacks is not offered");
            final HammerStation real = new HammerStation();
            real.title = "On air";
            for (Hammer.Song song : Hammer.catalog(publicCard, new Hammer.Job())) {
                real.songs.add(song.id);
                real.lengths.add(2000);
            }
            real.started = System.currentTimeMillis() - 1000;
            real.ends = real.started + 120000;
            real.revision = 1;
            real.sign(key);
            a.publicObjects.putStation(real);
            check(Hammer.stations(publicCard, new Hammer.Job()).size() == 1
                      && Hammer.stations(publicCard, new Hammer.Job()).get(0).id().equals(real.id()),
                  "a station on air is offered and verified");
            rejects(new Attempt() {
                public void run() throws Exception { a.publicObjects.putStation(real); }
            }, "a station can't be replaced by the same revision");
            real.ends = real.started + 500;
            real.revision = 2;
            real.sign(key);
            a.publicObjects.putStation(real);
            check(Hammer.stations(publicCard, new Hammer.Job()).size() == 1
                      && !Hammer.stations(publicCard, new Hammer.Job()).get(0).live(System.currentTimeMillis()),
                  "a stopped station is still handed out, marked ended, so older copies learn it");
            // carrying: another host keeps the station going while the artist is away
            final HammerStore carrierStore = new HammerStore(new File(root, "c"));
            final Hammer.Server third =
                new Hammer.Server(Hammer.serverContext(key, cert), new InetSocketAddress("127.0.0.1", 0), carrierStore);
            try {
                final Hammer.Card carrier = new Hammer.Card("127.0.0.1", third.port(), fingerprint, "");
                real.ends = System.currentTimeMillis() + 120000;
                real.revision = 3;
                real.sign(key);
                a.publicObjects.putStation(real);
                check(!Hammer.carryStation(carrier, real, new Hammer.Job()) && carrierStore.publicObjects.station(real.id()) == null,
                      "a host without the station's songs doesn't carry it");
                carrierStore.publish(source, "Public tone", "jazz koto", true, true, key);  // the same signed recording
                check(Hammer.carryStation(carrier, real, new Hammer.Job()), "a host sharing every song carries the station");
                check(Hammer.stations(carrier, new Hammer.Job()).size() == 1
                          && Hammer.stations(carrier, new Hammer.Job()).get(0).revision == 3,
                      "a carried station is on air from the carrier");
                check(!Hammer.carryStation(carrier, real, new Hammer.Job()), "a carrier doesn't take the same revision twice");
                final String one = Hammer.random(), two = Hammer.random();
                check(Hammer.listening(carrier, real.id(), one, new Hammer.Job()).size() == 1
                          && Hammer.listening(carrier, real.id(), two, new Hammer.Job()).size() == 2
                          && Hammer.listening(carrier, real.id(), one, new Hammer.Job()).size() == 2
                          && Hammer.listening(carrier, real.id(), "", new Hammer.Job()).size() == 2,
                      "listeners are counted once each by their tune-in tags");
                java.util.Set<String> both = new java.util.HashSet<String>(Hammer.listening(carrier, real.id(), "", new Hammer.Job()));
                both.addAll(Hammer.listening(publicCard, real.id(), one, new Hammer.Job()));
                check(both.size() == 2 && !both.contains(one), "a listener heard by two hosts counts once, and no tag is given out");
                rejects(new Attempt() {
                    public void run() throws Exception { Hammer.listening(carrier, Hammer.random(), one, new Hammer.Job()); }
                }, "listeners are counted only for stations held here");
                final int[] passedOn = {0};
                carrierStore.publicObjects.carried = new HammerPublic.StationListener() {
                    public void carried(HammerStation s) { passedOn[0]++; }
                };
                real.note = "a new note";
                real.revision = 4;
                real.sign(key);
                a.publicObjects.putStation(real);
                check(HammerStations.gather(carrierStore.publicObjects, publicCard, new Hammer.Job()) == 1
                          && "a new note".equals(Hammer.stations(carrier, new Hammer.Job()).get(0).note),
                      "a carrier gathers a newer note from another host");
                real.ends = real.started + 500;
                real.revision = 5;
                real.sign(key);
                a.publicObjects.putStation(real);
                check(HammerStations.gather(carrierStore.publicObjects, publicCard, new Hammer.Job()) == 1
                          && !Hammer.stations(carrier, new Hammer.Job()).get(0).live(System.currentTimeMillis()),
                      "going off air reaches the carrier");
                check(passedOn[0] == 2, "a carrier is told of each newer revision, to pass it straight on");
                final HammerStation forged = HammerStation.read(new DataInputStream(new ByteArrayInputStream(
                    stationBytes(real))));
                forged.revision = 6;
                forged.ends = System.currentTimeMillis() + 120000;
                rejects(new Attempt() {
                    public void run() throws Exception { carrierStore.publicObjects.carry(forged); }
                }, "a carrier can't put a station back on air without the artist's signature");
                final HammerStore d = new HammerStore(new File(root, "d"));
                d.publish(source, "Public tone", "jazz koto", true, true, key);
                check(!d.publicObjects.carry(real) && d.publicObjects.station(real.id()) == null,
                      "a host that never held a station doesn't take it once ended");
                keeping(root, key, cert, fingerprint);
                // circles: rooms carried sealed by hosts that can't read them
                KeyPair starter = HammerCircle.memberKey(), member = HammerCircle.memberKey(), stranger = HammerCircle.memberKey();
                final HammerCircle room = HammerCircle.start("Late night", "Listening till dawn", true);
                room.hosts.add(carrier.encode());
                room.sign(starter);
                check(Hammer.carryCircle(carrier, room, new Hammer.Job()) && Hammer.circles(carrier, new Hammer.Job()).size() == 1
                          && Hammer.circles(carrier, new Hammer.Job()).get(0).secret.equals(room.secret),
                      "an open circle is carried and listed with its key");
                final HammerCircle impostor = HammerCircle.start("Late night", "", true);
                impostor.id = room.id;
                impostor.secret = room.secret;
                impostor.revision = room.revision + 1;
                impostor.sign(stranger);
                rejects(new Attempt() {
                    public void run() throws Exception { Hammer.carryCircle(carrier, impostor, new Hammer.Job()); }
                }, "only the circle's starter can change it");
                HammerCircle.Message hello = new HammerCircle.Message();
                hello.name = "Ava";
                hello.body = "hello room";
                HammerCircle.Envelope said = hello.seal(room, member);
                check(Hammer.circlePost(carrier, said, new Hammer.Job()) && !Hammer.circlePost(carrier, said, new Hammer.Job()),
                      "a sealed line is kept once");
                List<HammerCircle.Envelope> heard = Hammer.circleRead(carrier, room.id, HammerCircle.ROOM, 0, new Hammer.Job());
                HammerCircle.Message back = heard.size() == 1 ? HammerCircle.Message.open(room, heard.get(0)) : null;
                check(back != null && back.body.equals("hello room") && back.key.equals(hello.key),
                      "a member unseals the line and knows who said it");
                check(!new String(heard.get(0).sealed, "ISO-8859-1").contains("hello room"), "the host holds only ciphertext");
                HammerCircle other = HammerCircle.start("Elsewhere", "", true);
                other.sign(starter);
                check(HammerCircle.Message.open(other, heard.get(0)) == null, "another circle's key opens nothing");
                final HammerCircle.Envelope moved = HammerCircle.Envelope.read(new DataInputStream(new ByteArrayInputStream(envelopeBytes(said))));
                moved.time++;
                rejects(new Attempt() {
                    public void run() throws Exception { Hammer.circlePost(carrier, moved, new Hammer.Job()); }
                }, "a message moved in time is refused by its stamp");
                final HammerCircle.Envelope bent = HammerCircle.Envelope.read(new DataInputStream(new ByteArrayInputStream(envelopeBytes(said))));
                bent.sealed[bent.sealed.length - 1] ^= 1;
                bent.stamp = HammerCircle.work(bent.work(), 16);
                check(HammerCircle.Message.open(room, bent) == null, "a changed sealed line doesn't open");
                // invite-only: the key travels only in the invitation
                final HammerCircle quiet = HammerCircle.start("Just us", "", false);
                quiet.hosts.add(carrier.encode());
                quiet.sign(starter);
                check(Hammer.carryCircle(carrier, quiet, new Hammer.Job()) && Hammer.circles(carrier, new Hammer.Job()).size() == 1,
                      "an invite-only circle is carried but not listed");
                check(carrierStore.circles.record(quiet.id).secret.isEmpty()
                          && Hammer.circle(carrier, quiet.id, "", new Hammer.Job()).secret.isEmpty(),
                      "a host never holds an invite-only circle's key");
                HammerCircle joined = Hammer.circle(carrier, quiet.id, quiet.secret, new Hammer.Job());
                HammerCircle.Message whisper = new HammerCircle.Message();
                whisper.body = "only us";
                Hammer.circlePost(carrier, whisper.seal(joined, member), new Hammer.Job());
                HammerCircle.Envelope got = Hammer.circleRead(carrier, quiet.id, HammerCircle.ROOM, 0, new Hammer.Job()).get(0);
                check("only us".equals(HammerCircle.Message.open(joined, got).body)
                          && HammerCircle.Message.open(Hammer.circle(carrier, quiet.id, "", new Hammer.Job()), got) == null,
                      "the invitation's key opens it, the host's copy can't");
                rejects(new Attempt() {
                    public void run() throws Exception { Hammer.circle(carrier, quiet.id, Hammer.random(), new Hammer.Job()); }
                }, "a wrong invitation key is refused");
                // the board outlives a restart, and a second host catches up
                HammerCircle.Message post = new HammerCircle.Message();
                post.kind = "post";
                post.name = "Ava";
                post.body = "Tonight's theme: songs about rivers";
                Hammer.circlePost(carrier, post.seal(room, member), new Hammer.Job());
                check(new HammerCircles(new File(carrierStore.root, "circles")).read(room.id, HammerCircle.BOARD, 0).size() == 1,
                      "board posts are kept across a restart");
                b.circles.carry(room);
                check(b.circles.sync(carrier, room.id, new Hammer.Job()) == 2
                          && b.circles.read(room.id, HammerCircle.BOARD, 0).size() == 1,
                      "a second host of the circle catches up from the first");
                int took = 0;
                for (int i = 0; i < 22; i++) {
                    HammerCircle.Message m = new HammerCircle.Message();
                    m.kind = "react";
                    m.body = "!";
                    try {
                        if (Hammer.circlePost(carrier, m.seal(room, member), new Hammer.Job()))
                            took++;
                    } catch (Exception busy) {
                    }
                }
                check(took < 22, "a circle can't be flooded");
                HammerCircle.Message song = new HammerCircle.Message();
                song.kind = "add";
                song.song = Hammer.catalog(publicCard, new Hammer.Job()).get(0);
                song.cards.add(publicCard.encode());
                song.length = 12000;
                rejects(new Attempt() {
                    public void run() throws Exception {
                        HammerCircle.Message m = new HammerCircle.Message();
                        m.kind = "add";
                        m.length = 1000;
                        m.seal(room, HammerCircle.memberKey());
                    }
                }, "a song added needs its signed record and a host");
                check(HammerCircle.Message.open(room, song.seal(room, member)).song.id.equals(song.song.id),
                      "a song added to the queue carries its signed record");
            } finally {
                third.close();
            }
            java.util.Map<String, long[]> counts = Hammer.allStats(publicCard, new Hammer.Job());
            check(counts.size() == 1 && counts.get(seenSong)[0] == 1 && counts.get(seenSong)[1] == 2,
                  "all counts at once match the public songs");
            check(Hammer.allStats(privateCard, new Hammer.Job()).containsKey(circleSong),
                  "a circle member sees the counts of circle songs");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.catalog(new Hammer.Card("127.0.0.1", first.port(), fingerprint, Hammer.random()),
                                   new Hammer.Job());
                }
            }, "invalid capability fails closed");
            rejects(new Attempt() {
                public void run() throws Exception { Hammer.chunk(publicCard, priv, 0, new Hammer.Job()); }
            }, "private audio cannot be fetched by hash");
            rejects(new Attempt() {
                public void run() throws Exception { Hammer.frames(publicCard, priv.id, new Hammer.Job()); }
            }, "private native info protected");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.segment(publicCard, priv.id, 0, 48000, new Hammer.Job());
                }
            }, "private transcoder protected");
            // Adversarial object access: knowing a digest is not publication authority.
            File unpublished = wave(root, "never-published.wav", 1, 48000, 77);
            final String unpublishedId = Hammer.hash(unpublished);
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.frames(publicCard, unpublishedId, new Hammer.Job());
                }
            }, "unpublished file not readable by known digest");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.frames(publicCard, "../circle.key", new Hammer.Job());
                }
            }, "path traversal never reaches filesystem");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.segment(publicCard, pub.id, -1, 48000, new Hammer.Job());
                }
            }, "negative segment rejected");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.segment(publicCard, pub.id, 0, Integer.MAX_VALUE, new Hammer.Job());
                }
            }, "unsupported bitrate rejected");
            final byte[] imageBytes = new byte[] {(byte)255, (byte)216, 1, 2, 3, (byte)255, (byte)217};
            final String imageId = a.publicObjects.putImage(imageBytes);
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.image(publicCard, fingerprint, imageId, true, new Hammer.Job());
                }
            }, "staged artist image is not published");
            final HammerProfile artist = new HammerProfile();
            artist.name = "Test artist";
            artist.bio = "A public biography";
            artist.tags = "jazz";
            artist.image = imageId;
            artist.revision = 1;
            artist.sign(key);
            a.publicObjects.publishProfile(artist);
            check(Hammer.profiles(publicCard, new Hammer.Job()).get(0).id().equals(fingerprint),
                  "profile identity is signed by artist key");
            check(Arrays.equals(Hammer.image(publicCard, fingerprint, imageId, true, new Hammer.Job()),
                                imageBytes),
                  "explicitly published artist image accessible");
            rejects(new Attempt() {
                public void run() throws Exception { a.publicObjects.publishProfile(artist); }
            }, "profile revision cannot replay");
            artist.name = "Impersonation";
            rejects(new Attempt() {
                public void run() throws Exception { artist.validate(); }
            }, "forged artist rejected");
            artist.name = "Test artist";
            a.publicObjects.withdrawProfile();
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.image(publicCard, fingerprint, imageId, true, new Hammer.Job());
                }
            }, "withdrawn profile image no longer accessible");
            final HammerArt cover = new HammerArt();
            cover.song = priv.id;
            cover.image = imageId;
            cover.sign(key);
            a.publicObjects.cover(priv, cover);
            rejects(new Attempt() {
                public void run() throws Exception { Hammer.cover(publicCard, priv, new Hammer.Job()); }
            }, "private cover metadata hidden");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.image(publicCard, priv.id, imageId, false, new Hammer.Job());
                }
            }, "private cover bytes hidden even with correct digest");
            check(Hammer.cover(privateCard, priv, new Hammer.Job()).image.equals(imageId),
                  "authorized private cover available");
            check(Arrays.equals(Hammer.image(privateCard, priv.id, imageId, false, new Hammer.Job()),
                                imageBytes),
                  "authorized private image available");
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.image(publicCard, pub.id, imageId, false, new Hammer.Job());
                }
            }, "public song cannot borrow private artwork authorization");
            final HammerStore ownership = new HammerStore(new File(root, "cover-ownership"));
            Hammer.Song oldOwner = ownership.publish(source, "Old publisher", "", true, true, key);
            ownership.publicObjects.putImage(imageBytes);
            HammerArt oldArt = new HammerArt();
            oldArt.song = oldOwner.id;
            oldArt.image = imageId;
            oldArt.sign(key);
            ownership.publicObjects.cover(oldOwner, oldArt);
            check(ownership.publicObjects.cover(oldOwner.id, "") != null, "current publisher cover visible");
            KeyPairGenerator replacement = KeyPairGenerator.getInstance("EC");
            replacement.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            ownership.publish(source, "New publisher", "", true, true, replacement.generateKeyPair());
            check(ownership.publicObjects.cover(oldOwner.id, "") == null,
                  "stale cover cannot cross publisher identity");
            rejects(new Attempt() {
                public void run() throws Exception {
                    ownership.publicObjects.image(pub.id, imageId, false, "");
                }
            }, "stale publisher cannot authorize image bytes");
            check(!HammerPresence.publicAddress("127.0.0.1") &&
                      !HammerPresence.publicAddress("169.254.169.254") &&
                      !HammerPresence.publicAddress("192.168.0.1") &&
                      !HammerPresence.publicAddress("localhost") &&
                      !HammerPresence.publicAddress("100.64.0.1") &&
                      !HammerPresence.publicAddress("::ffff:127.0.0.1"),
                  "automatic directory rejects local destinations and DNS rebinding");
            HammerPresence presence = new HammerPresence();
            presence.card = new Hammer.Card("93.184.216.34", 8784, fingerprint, "");
            presence.expires = System.currentTimeMillis() + 600000;
            presence.sign(key);
            a.directory().announce(presence);
            check(Hammer.directory(publicCard, new Hammer.Job()).size() == 1,
                  "signed public directory exchange");
            final HammerPresence wrongHost = presence;
            rejects(new Attempt() {
                public void run() throws Exception {
                    Hammer.announce(publicCard, wrongHost, new Hammer.Job());
                }
            }, "cannot announce another device address");
            presence.expires = System.currentTimeMillis() - 1;
            rejects(new Attempt() {
                public void run() throws Exception { wrongHost.validate(); }
            }, "expired presence rejected");
            HammerGuard guard = new HammerGuard();
            long tick = System.nanoTime();
            for (int k = 0; k < 6; k++)
                check(guard.enter("test", tick), "admission within cap " + k);
            check(!guard.enter("test", tick), "per-address connection cap");
            for (int k = 0; k < 6; k++)
                guard.leave("test");
            int admitted = 0;
            for (int k = 0; k < 122; k++) {
                if (guard.enter("test", tick))
                    admitted++;
                guard.leave("test");
            }
            check(admitted == 122, "bounded request burst accepted before exhaustion");
            check(!guard.enter("test", tick), "request flood budget exhausted");
            check(guard.enter("test", tick + 1000000000L), "rate budget recovers");
            guard.leave("test");
            check(pub.matches("JAZZ tone") && !pub.matches("missing"),
                  "local case-insensitive multiword search");
            final Hammer.Song tampered =
                new Hammer.Song(pub.id, "Forgery", pub.tags, pub.type, pub.size, true);
            tampered.key = pub.key;
            tampered.sig = pub.sig;
            rejects(new Attempt() {
                public void run() throws Exception { tampered.validate(); }
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
                public void run() throws Exception { Hammer.comment(publicCard, forged, new Hammer.Job()); }
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
                    b.importPublicMetadata(pub, Arrays.asList(privateCopy),
                                           Collections.<Hammer.Vote>emptyList());
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
                public void run() { watch.cancel(); }
            });
            t.join(2000);
            check(!t.isAlive(), "subscription cancellation closes socket");
            final Hammer.Song missed = a.publish(wave(root, "missed.wav", 2, 48000, 33), "Released offline",
                                                 "test", true, true, key);
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
                public void run() { first.disconnect(); }
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
                            public void catalog(List<Hammer.Song> songs) { circleReady.countDown(); }
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
                public void run() throws Exception { Hammer.catalog(privateCard, new Hammer.Job()); }
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
                double secondsAt = (double)n / 48000;
                double expected = .30 * Math.sin(2 * Math.PI * 220 * secondsAt) +
                                  .15 * Math.sin(2 * Math.PI * 440 * secondsAt) +
                                  .10 * Math.sin(2 * Math.PI * 660 * secondsAt);
                short sample = (short)((decoded[n * 4] & 255) | ((decoded[n * 4 + 1] & 255) << 8));
                double value = (double)sample / 32767;
                errorPower += (value - expected) * (value - expected);
                signalPower += expected * expected;
            }
            check(10 * Math.log10(signalPower / errorPower) > 20,
                  "native decode retains waveform and alignment above 20 dB SNR");
            final byte[] malformed = encoded.clone();
            malformed[0] = 0;
            rejects(new Attempt() {
                public void run() throws Exception { HammerNative.decode(malformed); }
            }, "native malformed packet rejected");
            for (int n = 0; n < 50; n++) {
                final byte[] shortPacket = Arrays.copyOf(encoded, n);
                rejects(new Attempt() {
                    public void run() throws Exception { HammerNative.decode(shortPacket); }
                }, "truncated segment " + n);
            }
            List<Hammer.Card> routes = Arrays.asList(publicCard, replica);
            HammerBuffer originalBuffer = new HammerBuffer(routes, pub, HammerBuffer.ORIGINAL, 0);
            try {
                byte[] dest = new byte[9000];
                int got = originalBuffer.readAt(734567, dest, 0, dest.length);
                check(Arrays.equals(Arrays.copyOf(dest, got),
                                    Arrays.copyOfRange(original, 734567, 734567 + got)),
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
                    public void run() { first.close(); }
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
            check(HammerNative.autoBitrate(2000, 160000) == 48000 &&
                      HammerNative.autoBitrate(100, 48000) == 160000,
                  "native adaptive bitrate responds to transfer time");
            return "HAMMER: " + passed + " assertions passed; loopback push " + pushMillis +
                " ms; first 2 s Opus segment " + encoded.length + " bytes, " + encodeMs +
                " ms incl. TLS.\n";
        } finally {
            first.close();
            second.close();
        }
    }
    /** Keeping: an artist asks a helper to hold copies of their songs, changes the list, and takes them back. */
    static void keeping(File root, KeyPair key, X509Certificate cert, String fingerprint) throws Exception {
        final HammerStore artist = new HammerStore(new File(root, "keep-artist")), helper = new HammerStore(new File(root, "keep-helper"));
        final Hammer.Song one = artist.publish(wave(root, "keep1.wav", 3, 44100, 11), "Kept one", "", true, true, key),
                          two = artist.publish(wave(root, "keep2.wav", 3, 44100, 12), "Kept two", "", true, true, key);
        artist.publish(wave(root, "keep3.wav", 3, 44100, 13), "Streaming only", "", false, true, key);
        final Hammer.Song owners = helper.publish(wave(root, "keep4.wav", 3, 44100, 14), "Owner's own", "", true, true, key);
        final HammerKeeper keeper = new HammerKeeper(helper, new File(root, "keep-helper-kept"), fingerprint);
        helper.keeper = keeper;
        Hammer.Server from = new Hammer.Server(Hammer.serverContext(key, cert), new InetSocketAddress("127.0.0.1", 0), artist),
                      at = new Hammer.Server(Hammer.serverContext(key, cert), new InetSocketAddress("127.0.0.1", 0), helper);
        try {
            final Hammer.Card fromCard = new Hammer.Card("127.0.0.1", from.port(), fingerprint, ""),
                              helperCard = new Hammer.Card("127.0.0.1", at.port(), fingerprint, "");
            final HammerKeep k = new HammerKeep();
            k.helper = Hammer.hash(new byte[] {1});
            k.from = fromCard.encode();
            k.revision = 1;
            k.songs.add(one.id);
            k.songs.add(two.id);
            k.sign(key);
            rejects(new Attempt() {
                public void run() throws Exception { Hammer.keep(helperCard, k, new Hammer.Job()); }
            }, "a keep request meant for another helper is refused");
            k.helper = fingerprint;
            k.sign(key);
            boolean refusedWithReason = false;
            try {
                Hammer.keep(helperCard, k, new Hammer.Job());
            } catch (Hammer.Refused r) {
                refusedWithReason = r.getMessage().contains("stamp");
            }
            check(refusedWithReason, "a new artist must pay the stamp, and the refusal says why");
            k.stamp = HammerHidden.stamp(k.id(), new Hammer.Job());
            check(Hammer.keep(helperCard, k, new Hammer.Job()).isEmpty(), "a keep request is taken before anything is copied");
            final HammerKeep tampered = HammerKeep.read(new DataInputStream(new ByteArrayInputStream(k.bytes())));
            tampered.songs.remove(1);
            rejects(new Attempt() {
                public void run() throws Exception { keeper.ask(tampered); }
            }, "a changed keep request is refused");
            keeper.round(new Hammer.Job());
            List<String> held = Hammer.keep(helperCard, k, new Hammer.Job());
            check(held.size() == 2 && held.contains(one.id) && held.contains(two.id),
                  "the helper fetched both songs from the artist and says so");
            check(Hammer.catalog(helperCard, new Hammer.Job()).size() == 3, "kept songs are hosted publicly with the owner's own");
            from.close();
            check(Hammer.catalog(helperCard, new Hammer.Job()).size() == 3, "kept songs stay up while the artist is away");
            from = new Hammer.Server(Hammer.serverContext(key, cert), new InetSocketAddress("127.0.0.1", 0), artist);
            final Hammer.Card again = new Hammer.Card("127.0.0.1", from.port(), fingerprint, "");
            k.revision = 2;
            k.songs.remove(two.id);
            k.sign(key);
            check(Hammer.keep(helperCard, k, new Hammer.Job()).size() == 1
                      && Hammer.catalog(helperCard, new Hammer.Job()).size() == 2
                      && !helper.downloadFile(two).isFile(),
                  "a song left out of a newer request is dropped and deleted");
            final HammerKeep older = HammerKeep.read(new DataInputStream(new ByteArrayInputStream(k.bytes())));
            older.revision = 1;
            older.songs.clear();
            older.sign(key);
            check(Hammer.keep(helperCard, older, new Hammer.Job()).size() == 1, "an older request changes nothing");
            k.revision = 3;
            k.songs.clear();
            k.sign(key);
            check(Hammer.keep(helperCard, k, new Hammer.Job()).isEmpty() && Hammer.catalog(helperCard, new Hammer.Job()).size() == 1
                      && Hammer.catalog(helperCard, new Hammer.Job()).get(0).id.equals(owners.id) && keeper.records().isEmpty(),
                  "taking the songs back drops every kept copy, and only those");

            // the artist's side: proven helpers, three copies, taking back
            HammerKeeping side = new HammerKeeping(new File(root, "keep-artist"));
            long[] clock = {System.currentTimeMillis()};
            side.clock = clock;
            Map<String, Hammer.Card> online = new HashMap<String, Hammer.Card>();
            online.put(fingerprint, helperCard);
            side.look(online);
            check(!side.proven(fingerprint), "a helper seen once isn't proven");
            for (int i = 0; i < 80; i++) {
                clock[0] += HammerKeeping.HOUR;
                side.look(i % 10 == 0 ? new HashMap<String, Hammer.Card>() : online);
            }
            check(side.proven(fingerprint), "a helper online at nine looks in ten over three days is proven");
            String flaky = Hammer.hash(new byte[] {2});
            for (int i = 0; i < 80; i++) {
                clock[0] += HammerKeeping.HOUR;
                Map<String, Hammer.Card> m = new HashMap<String, Hammer.Card>(online);
                if (i % 2 == 0)
                    m.put(flaky, helperCard);
                side.look(m);
            }
            check(!side.proven(flaky), "a helper online at half the looks isn't proven");
            Map<String, Hammer.Card> steady = new HashMap<String, Hammer.Card>(online);
            check(side.tend(artist, key, again, steady, new Hammer.Job()).isEmpty() && keeper.records().isEmpty(),
                  "nothing is copied before the artist turns keeping on");
            side.turn(true);
            side.tend(artist, key, again, steady, new Hammer.Job());
            keeper.round(new Hammer.Job());
            side.tend(artist, key, again, steady, new Hammer.Job());
            HammerKeeping.Status st = side.status(artist, Hammer.publicKey(key.getPublic()), steady);
            check(st.songs == 2 && st.streamingOnly == 1 && st.whole() == 1 && st.carriers.size() == 1 && st.carriers.get(0)[1] == 1,
                  "turned on, the artist's songs that allow rehosting go to the proven helper");
            side.turn(false);
            side.tend(artist, key, again, steady, new Hammer.Job());
            check(keeper.records().isEmpty() && Hammer.catalog(helperCard, new Hammer.Job()).size() == 1
                      && side.status(artist, Hammer.publicKey(key.getPublic()), steady).carriers.isEmpty(),
                  "turned off, every copy is taken back");
        } finally {
            from.close();
            at.close();
            keeper.close();
        }
    }
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(args[0])) {
            keys.load(in, "hammer-test-only".toCharArray());
        }
        X509Certificate cert = (X509Certificate)keys.getCertificate("test");
        KeyPair pair = new KeyPair(cert.getPublicKey(),
                                   (PrivateKey)keys.getKey("test", "hammer-test-only".toCharArray()));
        System.out.print(run(pair, cert, new File(args[1])));
    }
}
