package ai.jackknife.killayue.peer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hidden artists: publish a song and walk away. The song is cleaned and signed by a key made for the hidden name
 * only, and goes through the mixnet in pieces to three helpers that adopt it (HammerAdoption). No helper and no
 * listener learns which phone it came from, and the phone never serves it itself.
 * See docs/designs/2026-10-24-mixnet-and-hidden-artists.md.
 */
public final class HammerHidden {
    private HammerHidden() {}

    /** The largest hidden song: most MP3s; a long WAV is refused before anything is sent. */
    public static final long MAX = 32L << 20;
    /** The bytes one piece carries: about nine packets with its reply block, so a lost packet costs little to resend. */
    public static final int PIECE = 15 * 1024;
    /** Helpers that should hold every hidden song. */
    public static final int COPIES = 3;
    /** The proof-of-work an offer pays, in leading zero bits: a few seconds of phone CPU. */
    static final int BITS = 22;
    /** Pieces in flight to one helper at once. */
    static final int WINDOW = 12;
    /** ms between an upload's packets on average: 50 a second, well under the 128 a gateway takes from one peer. */
    static final int UPLOAD_PACE = 20;
    private static final int TRIES = 5;
    private static final long PIECE_WAIT = 30000;
    /** A helper that does not answer an offer (older software, or gone) is passed over after two of these. */
    private static final long OFFER_WAIT = 20000;

    public static int pieces(long size) { return (int)((size + PIECE - 1) / PIECE); }

    public static boolean stampOk(String id, long nonce) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update("killa stamp 1".getBytes(StandardCharsets.US_ASCII));
            d.update(id.getBytes(StandardCharsets.US_ASCII));
            for (int i = 7; i >= 0; i--)
                d.update((byte)(nonce >>> (8 * i)));
            byte[] h = d.digest();
            int zeros = 0;
            for (byte b : h) {
                if (b == 0) {
                    zeros += 8;
                    continue;
                }
                zeros += Integer.numberOfLeadingZeros(b & 0xff) - 24;
                break;
            }
            return zeros >= BITS;
        } catch (Exception e) {
            return false;
        }
    }

    static long stamp(String id, Hammer.Job job) throws Exception {
        for (long n = 0;; n++) {
            if ((n & 0xffff) == 0)
                job.check();
            if (stampOk(id, n))
                return n;
        }
    }

    /** Whether the file starts like audio of the type it claims: a helper hosts nothing else. */
    static boolean audio(File f, String type) throws IOException {
        byte[] h = new byte[12];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(h) < 12)
                return false;
        }
        String s = new String(h, StandardCharsets.ISO_8859_1);
        if ("audio/wav".equals(type))
            return s.startsWith("RIFF") && s.substring(8, 12).equals("WAVE");
        if ("audio/flac".equals(type))
            return s.startsWith("fLaC");
        return s.startsWith("ID3") || ((h[0] & 0xff) == 0xff && (h[1] & 0xe0) == 0xe0);
    }

    /** A hidden song ready to go: its clean file, the signed song and profile, and the stamp. */
    public static final class Prepared {
        public final File file;
        public final Hammer.Song song;
        public final HammerProfile profile;
        public final long stamp;
        Prepared(File file, Hammer.Song song, HammerProfile profile, long stamp) {
            this.file = file;
            this.song = song;
            this.profile = profile;
            this.stamp = stamp;
        }
    }

    /**
     * A song an artist already shares under their own name, made ready to offer to helpers the same way, so that
     * helpers host it and can carry the artist's station. It pays the same stamp and counts against the same quota.
     */
    public static Prepared shared(File file, Hammer.Song song, HammerProfile profile, Hammer.Job job) throws Exception {
        song.validate();
        profile.validate();
        Hammer.require(profile.key.equals(song.key), "The page and the song have different artists");
        Hammer.require(song.reshare, "\"" + song.title + "\" doesn't allow rehosting, so helpers can't carry it");
        Hammer.require(file.isFile() && file.length() == song.size && song.size <= MAX,
                       "\"" + song.title + "\" is over 32 MB, more than a helper takes: share it as MP3");
        return new Prepared(file, song, profile, stamp(song.id, job));
    }

    /**
     * Cleans the song (no device, place or encoder tags; the artist fields say the hidden name), signs it and a
     * profile with the hidden identity, and pays the stamp. The clean copy is kept in work, by its id.
     */
    public static Prepared prepare(File input, File work, String title, String tags, String name, KeyPair hidden,
                                   Hammer.Job job) throws Exception {
        String lower = input.getName().toLowerCase(Locale.ROOT);
        Hammer.require(lower.matches(".*\\.(wav|mp3|flac)"), "Choose WAV, FLAC or MP3 audio");
        Hammer.require(input.isFile() && input.length() > 0 && input.length() <= MAX,
                       "A hidden song is at most 32 MB: choose an MP3, or a shorter song");
        String type = lower.endsWith(".wav") ? "audio/wav" : lower.endsWith(".flac") ? "audio/flac" : "audio/mpeg";
        Hammer.require(work.isDirectory() || work.mkdirs(), "Cannot prepare the song");
        File part = new File(work, Hammer.random() + ".part");
        try {
            if (!HammerClean.clean(input, part, name, job))
                copy(input, part);
            Hammer.require(part.length() <= MAX, "A hidden song is at most 32 MB once cleaned");
            String id = Hammer.hash(part);
            File clean = new File(work, id + lower.substring(lower.lastIndexOf('.')));
            if (!part.renameTo(clean)) {
                clean.delete();
                Hammer.require(part.renameTo(clean), "Cannot keep the clean copy");
            }
            Hammer.Song song = new Hammer.Song(id, title, tags, type, clean.length(), true);
            song.sign(hidden);
            song.validate();
            HammerProfile p = new HammerProfile();
            p.name = name;
            p.revision = System.currentTimeMillis();
            p.sign(hidden);
            return new Prepared(clean, song, p, stamp(id, job));
        } finally {
            part.delete();
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1)
                out.write(b, 0, n);
        }
    }

    public interface Progress {
        /** pieces taken by helpers so far, of all the pieces COPIES helpers need; helpers that hold it whole */
        void progress(int done, int total, List<String> hosting);
    }

    /** Helpers offered the song at once, so that ones not answering (older software, or gone) cost no extra time. */
    static final int OFFERS = 8;

    /**
     * Offers the song to up to OFFERS of the candidates at once, then uploads to the first COPIES that took the offer
     * until they host it whole, moving on to the next that took it when one fails. Returns the PeerIds of the helpers
     * that host it (fewer than COPIES when not enough would). While it runs the sender's clock only paces, at
     * UPLOAD_PACE and without cover loops: a cover clock that fast filled every empty slot with a loop and flooded
     * the path, and with no clock the first pieces left at once, faster than a gateway takes from one peer, which
     * then closed the link. An upload is seen as one either way. The caller sets cover traffic back as it wants it.
     */
    public static List<String> publish(final Prepared p, List<String> candidates, final Progress progress,
                                       final Hammer.Job job) throws Exception {
        String why = HammerMix.unready();
        if (why != null)
            throw new IOException(why + " Nothing was sent.");
        List<String> offered = new ArrayList<String>(candidates);
        Collections.shuffle(offered);
        if (offered.size() > OFFERS)
            offered = offered.subList(0, OFFERS);
        final List<String> hosting = Collections.synchronizedList(new ArrayList<String>());
        final int count = pieces(p.song.size), total = count * COPIES;
        final AtomicInteger done = new AtomicInteger();
        final List<String> refusals = Collections.synchronizedList(new ArrayList<String>());
        // the helpers that took the offer, in the order they answered, with the pieces each already has; uploads
        // start with the first ones, while the others may still be answering
        final BlockingQueue<Object[]> took = new LinkedBlockingQueue<Object[]>();
        final AtomicInteger unanswered = new AtomicInteger(offered.size());
        KillaNet.mixPace(UPLOAD_PACE);
        ExecutorService pool = Executors.newFixedThreadPool(offered.size() + COPIES);
        try {
            for (final String helper : offered)
                pool.submit(new Runnable() {
                    public void run() {
                        try {
                            took.add(new Object[] {helper, offer(p, helper, 2, job)});
                        } catch (Exception e) {
                            refusals.add(e.getMessage());
                        } finally {
                            unanswered.decrementAndGet();
                        }
                    }
                });
            List<Future<?>> running = new ArrayList<Future<?>>();
            for (int i = 0; i < COPIES; i++)
                running.add(pool.submit(new Callable<Void>() {
                    public Void call() throws Exception {
                        while (true) {
                            Object[] next = took.poll(500, TimeUnit.MILLISECONDS);
                            job.check();
                            if (next == null) {
                                if (unanswered.get() == 0 && took.isEmpty())
                                    return null;
                                continue;
                            }
                            String helper = (String)next[0];
                            try {
                                upload(p, helper, (byte[])next[1], count, done, total, hosting, progress, job);
                                hosting.add(helper);
                                progress.progress(done.get(), total, new ArrayList<String>(hosting));
                                return null;
                            } catch (IOException failed) {
                                refusals.add(failed.getMessage());
                            }
                        }
                    }
                }));
            for (Future<?> f : running)
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception ? (Exception)e.getCause() : e;
                }
        } finally {
            pool.shutdownNow();
            KillaNet.mixCover(0);
        }
        if (hosting.isEmpty())
            throw new IOException("No helper took the song" + (refusals.isEmpty() ? "" : ": " + refusals.get(0)));
        return new ArrayList<String>(hosting);
    }

    /** One helper that took the offer: every piece it lacks, WINDOW at a time, then the offer again to confirm. */
    private static void upload(final Prepared p, final String helper, byte[] have, final int count,
                               final AtomicInteger done, final int total, final List<String> hosting,
                               final Progress progress, final Hammer.Job job) throws Exception {
        final List<Integer> missing = new ArrayList<Integer>();
        for (int i = 0; i < count; i++)
            if ((have[i / 8] & (1 << (i % 8))) == 0)
                missing.add(i);
            else
                done.incrementAndGet();
        progress.progress(done.get(), total, new ArrayList<String>(hosting));
        ExecutorService window = Executors.newFixedThreadPool(WINDOW);
        try {
            List<Future<?>> sent = new ArrayList<Future<?>>();
            for (final int index : missing)
                sent.add(window.submit(new Callable<Void>() {
                    public Void call() throws Exception {
                        piece(p, helper, index, job);
                        done.incrementAndGet();
                        progress.progress(done.get(), total, new ArrayList<String>(hosting));
                        return null;
                    }
                }));
            for (Future<?> f : sent)
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception ? (Exception)e.getCause() : e;
                }
        } finally {
            window.shutdownNow();
        }
        // the check goes more times than the first offer: on mobile data an answer was lost twice, and a helper
        // holding the whole song was counted as one that did not take it
        have = offer(p, helper, 4, job);
        for (int i = 0; i < count; i++)
            Hammer.require((have[i / 8] & (1 << (i % 8))) != 0, "The helper is missing part of the song");
    }

    private static byte[] offer(final Prepared p, String helper, int tries, Hammer.Job job) throws Exception {
        byte[] have = HammerMix.check(HammerMix.request(helper, HammerMix.ADOPT, "", new HammerMix.Body() {
            public void write(DataOutputStream o) throws IOException {
                p.song.write(o);
                p.profile.write(o);
                o.writeLong(p.stamp);
            }
        }, tries, OFFER_WAIT, job));
        Hammer.require(have.length == (pieces(p.song.size) + 7) / 8, "The helper answered the offer wrongly");
        return have;
    }

    private static void piece(final Prepared p, String helper, final int index, Hammer.Job job) throws Exception {
        long at = (long)index * PIECE;
        final byte[] bytes = new byte[(int)Math.min(PIECE, p.song.size - at)];
        try (RandomAccessFile f = new RandomAccessFile(p.file, "r")) {
            f.seek(at);
            f.readFully(bytes);
        }
        HammerMix.check(HammerMix.request(helper, HammerMix.PIECE, "", new HammerMix.Body() {
            public void write(DataOutputStream o) throws IOException {
                Hammer.text(o, p.song.id, 64);
                o.writeInt(index);
                o.writeInt(bytes.length);
                o.write(bytes);
            }
        }, TRIES, PIECE_WAIT, job));
    }
}
