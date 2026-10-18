package ai.jackknife.killayue.studio;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Acoustic instruments from real recordings. A pack is a zip of root notes (FLAC, a few per octave, one to three
 * velocity layers) with pack.json and its licence, downloaded once, checked against its SHA-256 and kept beside the
 * projects in .killa-sounds, shared by every project. Each of the 88 keys is pitched from its nearest root with a
 * windowed-sinc resampler, once, and then streamed from storage like any other sample.
 *
 * Every recording is free to redistribute: the Salamander Grand Piano (Alexander Holm, CC BY 3.0) and Versilian
 * Studios' VSCO 2 Community Edition (CC0). See THIRD_PARTY_LICENSES.md.
 */
public final class SoundPacks {
    private SoundPacks() {}
    /** Beside the project folders, relative to a project folder, so clips can name it. */
    public static final String FOLDER = "../.killa-sounds";
    public static final String BASE = "https://github.com/JackKnifeAI/killa-yue/releases/download/sounds-v1/";

    /** A downloadable pack. Sizes and checksums are pinned: a pack can never change under a project. */
    public static final class Info {
        public final String id, sha256, credit;
        public final long bytes;
        Info(String id, long bytes, String sha256, String credit) {
            this.id = id;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.credit = credit;
        }
        public String url() { return BASE + "killa-" + id + ".zip"; }
    }
    static final String SALAMANDER = "Salamander Grand Piano V3 by Alexander Holm, CC BY 3.0";
    static final String VSCO = "VSCO 2 Community Edition by Versilian Studios, CC0";
    /** Filled by tools/sounds/build.py: the pack's byte size and SHA-256. */
    public static final Info[] PACKS = {
        new Info("grand", 0, "", SALAMANDER),
        new Info("upright", 0, "", VSCO),
        new Info("upright-bright", 0, "", VSCO),
        new Info("organ", 0, "", VSCO),
        new Info("strings", 0, "", VSCO),
        new Info("pizzicato", 0, "", VSCO),
        new Info("harp", 0, "", VSCO),
        new Info("flute", 0, "", VSCO),
        new Info("clarinet", 0, "", VSCO),
        new Info("trumpet", 0, "", VSCO),
        new Info("horn", 0, "", VSCO),
        new Info("trombone", 0, "", VSCO),
        new Info("marimba", 0, "", VSCO),
        new Info("glockenspiel", 0, "", VSCO),
        new Info("xylophone", 0, "", VSCO),
    };
    public static Info info(String pack) {
        for (Info i : PACKS)
            if (i.id.equals(pack))
                return i;
        return null;
    }

    public static String path(Sounds.Sound s, int rate, int key, int layer) {
        return FOLDER + "/" + s.pack + "/" + rate + "/" + key + "-" + layer + ".wav";
    }
    static File source(File projectDir, String pack) { return new File(new File(projectDir, FOLDER), pack + "/source"); }
    /** The studio folder's packs folder (projectsRoot is the folder that holds the project folders). */
    public static File packs(File projectsRoot) { return new File(projectsRoot, ".killa-sounds"); }

    public static boolean installed(File projectDir, Sounds.Sound s) {
        return new File(source(projectDir, s.pack), "pack.json").isFile();
    }
    public static boolean installedIn(File projectsRoot, String pack) {
        return new File(packs(projectsRoot), pack + "/source/pack.json").isFile();
    }
    /** Space a pack takes now (source and every rendered key). */
    public static long size(File projectsRoot, String pack) { return Projects.size(new File(packs(projectsRoot), pack)); }
    public static void remove(File projectsRoot, String pack) { Projects.delete(new File(packs(projectsRoot), pack)); }

    /** Turns any audio file Android can play into a float WAV at a rate. Set by the app (MediaCodec). */
    public interface Decoder {
        void decode(File in, File out, int rate) throws IOException;
    }
    public static volatile Decoder decoder;

    /** Download, verify and unpack a pack, then decode its roots. pack.json is written last: it marks it complete. */
    public static void install(File projectsRoot, Info info, Sounds.Progress progress, AtomicBoolean cancel)
        throws IOException {
        if (info.sha256.isEmpty())
            throw new IOException("This sound pack is not published yet.");
        File dir = new File(packs(projectsRoot), info.id), src = new File(dir, "source"), zip = new File(dir, "pack.zip.part");
        if (!src.isDirectory() && !src.mkdirs())
            throw new IOException("Could not make the sound pack folder.");
        if (projectsRoot.getUsableSpace() < info.bytes * 4 + 300L * 1024 * 1024)
            throw new IOException("Not enough free space for this pack (about " + (info.bytes * 4 / 1048576 + 300) + " MB).");
        download(info, zip, progress, cancel);
        ArrayList<String> flacs = new ArrayList<String>();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            byte[] buf = new byte[65536];
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                String name = e.getName();
                if (e.isDirectory() || name.contains("/") || name.contains("\\") || name.startsWith("."))
                    continue;
                File out = new File(src, "pack.json".equals(name) ? "pack.json.part" : name);
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = in.read(buf)) > 0;) o.write(buf, 0, n);
                }
                if (name.endsWith(".flac"))
                    flacs.add(name);
            }
        }
        zip.delete();
        File manifest = new File(src, "pack.json.part");
        if (!manifest.isFile())
            throw new IOException("The sound pack is damaged (no pack.json).");
        Decoder d = decoder;
        if (d == null)
            throw new IOException("This build cannot decode sound packs.");
        for (int i = 0; i < flacs.size(); i++) {
            if (cancel.get())
                throw new IOException("Cancelled.");
            File f = new File(src, flacs.get(i)), wav = new File(src, flacs.get(i).replace(".flac", ".wav"));
            d.decode(f, wav, 48000);
            f.delete();
            progress.at(i + 1, flacs.size());
        }
        if (!manifest.renameTo(new File(src, "pack.json")))
            throw new IOException("Could not finish installing the sound pack.");
    }
    static void download(Info info, File part, Sounds.Progress progress, AtomicBoolean cancel) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e);
        }
        URL url = new URL(info.url());
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                url = new URL(url, c.getHeaderField("Location"));
                c.disconnect();
                continue;
            }
            if (code != 200)
                throw new IOException("The sound pack download failed (HTTP " + code + ").");
            long done = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[131072];
                for (int n; (n = in.read(buf)) > 0;) {
                    if (cancel.get())
                        throw new IOException("Cancelled.");
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    if (done > info.bytes)
                        throw new IOException("The sound pack is larger than it should be.");
                    progress.at((int) (done / 1024), (int) Math.max(1, info.bytes / 1024));
                }
            } finally {
                c.disconnect();
            }
            if (done != info.bytes || !hex(sha.digest()).equals(info.sha256)) {
                part.delete();
                throw new IOException("The sound pack did not verify; nothing was installed.");
            }
            return;
        }
        throw new IOException("Too many redirects downloading the sound pack.");
    }
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    /** A root recording: the key it sounds, its layer, and its decoded file. */
    static final class Root {
        int key, layer;
        String file;
    }
    static List<Root> roots(File src) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(new File(src, "pack.json").toPath());
        Object o = Json.parse(new String(bytes, "UTF-8"));
        if (!(o instanceof Map) || !(((Map<?, ?>) o).get("roots") instanceof List))
            throw new IOException("The sound pack is damaged.");
        ArrayList<Root> out = new ArrayList<Root>();
        for (Object r : (List<?>) ((Map<?, ?>) o).get("roots")) {
            Map<?, ?> m = (Map<?, ?>) r;
            Root root = new Root();
            root.key = ((Number) m.get("key")).intValue();
            root.layer = ((Number) m.get("layer")).intValue();
            root.file = ((String) m.get("file")).replace(".flac", ".wav");
            if (root.file.contains("/") || root.file.contains(".."))
                throw new IOException("The sound pack is damaged.");
            out.add(root);
        }
        return out;
    }
    /** Pitching down sounds more natural than up, so a tie goes to the root above. */
    static Root nearest(List<Root> roots, int key, int layer) {
        Root best = null;
        int layers = 0;
        for (Root r : roots) layers = Math.max(layers, r.layer + 1);
        int want = Math.min(layer, layers - 1);
        for (Root r : roots) {
            if (r.layer != want)
                continue;
            if (best == null)
                best = r;
            else {
                int d = Math.abs(r.key - key), bd = Math.abs(best.key - key);
                if (d < bd || (d == bd && r.key > best.key))
                    best = r;
            }
        }
        return best;
    }

    public static synchronized File ensure(File dir, int rate, Sounds.Sound s, int key, int layer) throws IOException {
        File out = new File(dir, path(s, rate, key, layer));
        if (out.isFile())
            return out;
        return render(dir, rate, s, key, layer, out);
    }
    static File render(File dir, int rate, Sounds.Sound s, int key, int layer, File out) throws IOException {
        File src = source(dir, s.pack);
        if (!installed(dir, s))
            throw new IOException("Download the " + s.name + " sound first (Studio → Sounds).");
        Root root = nearest(roots(src), key, layer);
        if (root == null)
            throw new IOException("The " + s.name + " pack has no recordings.");
        out.getParentFile().mkdirs();
        try (WavIn in = WavIn.open(new File(src, root.file))) {
            double ratio = Math.pow(2, (key - root.key) / 12.0) * in.rate / rate;
            int ch = in.channels, frames = (int) Math.min((long) (s.seconds * rate), (long) (in.frames / ratio));
            float[] source = new float[(int) in.frames * ch];
            in.read(0, source, (int) in.frames);
            short[] pcm = new short[frames * ch];
            Resampler.pitch(source, ch, (int) in.frames, ratio, pcm, frames);
            WavOut w = WavOut.create(out, rate, ch);  // written beside it and renamed when complete
            try {
                w.write(pcm, frames);
                w.finish();
            } catch (IOException e) {
                w.abandon();
                throw e;
            }
        }
        return out;
    }

    /** Every key and layer, four at a time. */
    static void bank(final File dir, final int rate, final Sounds.Sound s, final Sounds.Progress progress,
                     final AtomicBoolean cancel) throws IOException {
        if (!installed(dir, s))
            throw new IOException("Download the " + s.name + " sound first (Studio → Sounds).");
        final int total = MidiNotes.KEYS * s.layers;
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        final IOException[] failure = new IOException[1];
        Thread[] workers = new Thread[Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2))];
        for (int w = 0; w < workers.length; w++) {
            workers[w] = new Thread(new Runnable() {
                public void run() {
                    for (int i; (i = next.getAndIncrement()) < total;) {
                        if (cancel.get() || failure[0] != null)
                            return;
                        try {
                            File out = new File(dir, path(s, rate, MidiNotes.LOW + i / s.layers, i % s.layers));
                            if (!out.isFile())
                                render(dir, rate, s, MidiNotes.LOW + i / s.layers, i % s.layers, out);
                        } catch (IOException e) {
                            synchronized (failure) {
                                failure[0] = e;
                            }
                            return;
                        }
                        progress.at(done.incrementAndGet(), total);
                    }
                }
            }, "studio-sound-pack-" + w);
            workers[w].start();
        }
        for (Thread t : workers)
            try {
                t.join();
            } catch (InterruptedException e) {
                cancel.set(true);
                Thread.currentThread().interrupt();
            }
        if (failure[0] != null)
            throw failure[0];
        if (cancel.get())
            throw new IOException("Cancelled.");
    }

    /** Kaiser-windowed sinc, 16 zero crossings, table-interpolated; low-passes when pitching up. */
    static final class Resampler {
        static final int ZEROS = 16, PHASES = 256;
        static final float[] TABLE = table();
        static float[] table() {
            float[] t = new float[ZEROS * PHASES + 2];
            double beta = 8.6, i0b = bessel(beta);
            for (int i = 0; i < t.length; i++) {
                double x = i / (double) PHASES;
                if (x >= ZEROS) {
                    t[i] = 0;
                    continue;
                }
                double sinc = x == 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x), r = x / ZEROS;
                t[i] = (float) (sinc * bessel(beta * Math.sqrt(1 - r * r)) / i0b);
            }
            return t;
        }
        static double bessel(double x) {
            double sum = 1, term = 1;
            for (int k = 1; k < 40; k++) {
                term *= (x / (2 * k)) * (x / (2 * k));
                sum += term;
            }
            return sum;
        }
        /** out[i] = in at i * ratio, for frames frames, with a 30 ms fade where the recording runs out. */
        static void pitch(float[] in, int ch, int inFrames, double ratio, short[] out, int frames) {
            double cutoff = Math.min(1, 1 / ratio) * .97;
            int fade = Math.max(1, Math.min(frames, 1440));
            for (int i = 0; i < frames; i++) {
                double pos = i * ratio;
                int center = (int) Math.floor(pos);
                double frac = pos - center;
                int reach = (int) Math.ceil(ZEROS / cutoff);
                for (int c = 0; c < ch; c++) {
                    double acc = 0;
                    for (int k = -reach + 1; k <= reach; k++) {
                        int j = center + k;
                        if (j < 0 || j >= inFrames)
                            continue;
                        double x = Math.abs((k - frac) * cutoff) * PHASES;
                        int xi = (int) x;
                        if (xi >= TABLE.length - 1)
                            continue;
                        double w = TABLE[xi] + (TABLE[xi + 1] - TABLE[xi]) * (x - xi);
                        acc += in[j * ch + c] * w;
                    }
                    acc *= cutoff;
                    if (i > frames - fade)
                        acc *= (frames - i) / (double) fade;
                    long v = Math.round(acc * 32767);
                    out[i * ch + c] = (short) Math.max(-32768, Math.min(32767, v));
                }
            }
        }
    }
}
