package ai.jackknife.killayue.peer;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A helper keeps itself current: see docs/designs/2026-11-02-helper-updates.md. Only the signed manifest is trusted,
 * wherever its bytes came from. Runs only when started by launcher.sh, which sets KILLA_HELPER_HOME.
 */
final class HelperUpdate implements Runnable {
    static final String HEADER = "killa-update 1\n";
    static final int RESTART = 75, UNHEALTHY = 76;
    static final int MAX_MANIFEST = 8192;
    static final long MAX_ZIP = 256L << 20;
    static final String DEFAULT_FROM = "https://github.com/JackKnifeAI/killa-yue/releases/latest/download";
    static volatile String status = "Updates: not managed here";

    String from = DEFAULT_FROM;
    long everyMillis = 6 * 3600 * 1000L, spreadMillis = 24 * 3600 * 1000L;
    long healthyAfterMillis = 5 * 60 * 1000L, deadlineMillis = 30 * 60 * 1000L;
    int needPeers = 1;
    boolean off;
    private final File home, dir;
    private final long running;
    private final String version;

    private HelperUpdate(File home, long running, String version) {
        this.home = home;
        this.dir = new File(home, "update");
        this.running = running;
        this.version = version;
    }

    /** The updater for this run, or null when this helper isn't managed by the launcher. */
    static HelperUpdate create() throws IOException {
        String home = System.getenv("KILLA_HELPER_HOME");
        if (home == null || home.isEmpty())
            return null;
        Properties p = new Properties();
        try (InputStream in = HelperUpdate.class.getResourceAsStream("/update-version.properties")) {
            if (in == null)
                return null;
            p.load(in);
        }
        return new HelperUpdate(new File(home), Long.parseLong(p.getProperty("sequence")), p.getProperty("version"));
    }

    void start() {
        status = "Updates: on, running " + version;
        Thread health = new Thread(new Runnable() {
            public void run() { watchHealth(); }
        }, "update-health");
        health.setDaemon(true);
        health.start();
        Thread check = new Thread(this, "update-check");
        check.setDaemon(true);
        check.start();
    }

    /** A new version proves itself by holding peers; one that can't is sent back by the launcher. */
    private void watchHealth() {
        File trying = new File(dir, "trying");
        if (!trying.exists())
            return;
        long started = System.currentTimeMillis(), good = 0;
        try {
            while (true) {
                Thread.sleep(Math.min(1000, healthyAfterMillis));
                long now = System.currentTimeMillis();
                good = KillaNet.peers().size() >= needPeers ? (good == 0 ? now : good) : 0;
                if (good != 0 && now - good >= healthyAfterMillis) {
                    Files.deleteIfExists(trying.toPath());
                    prune();
                    System.out.println("Update healthy: running " + version);
                    return;
                }
                if (now - started >= deadlineMillis) {
                    System.out.println("Update not healthy: no peers, going back");
                    System.exit(UNHEALTHY);
                }
            }
        } catch (InterruptedException | IOException e) {
            System.out.println("Update health check stopped: " + e.getMessage());
        }
    }

    private void prune() throws IOException {
        String current = read(new File(dir, "current")), previous = read(new File(dir, "previous"));
        File[] all = new File(home, "versions").listFiles();
        if (all == null)
            return;
        for (File f : all)
            if (!f.getName().equals(current) && !f.getName().equals(previous))
                delete(f);
    }

    public void run() {
        while (true) {
            try {
                check();
            } catch (Exception e) {
                // a failed check changes nothing: the running version carries on
                System.out.println("Update check: " + e.getMessage());
            }
            try {
                Thread.sleep(everyMillis);
            } catch (InterruptedException stop) {
                return;
            }
        }
    }

    private boolean off() { return off || new File(dir, "off").exists(); }

    private void check() throws Exception {
        Manifest m = Manifest.parse(fetch(from + "/update.txt", MAX_MANIFEST), keys(), retired());
        if (m.retire != null)
            retire(m.retire);
        if (m.sequence <= running) {
            status = "Updates: " + (off() ? "off" : "on") + ", running " + version + ", the newest";
            return;
        }
        if (off()) {
            status = "Updates: off, running " + version + ", " + m.version + " is out";
            System.out.println("A helper update is out (" + m.version + "). Updates are off, so this helper stays on " + version + ".");
            return;
        }
        if (bad().contains(Long.toString(m.sequence)))
            return;
        long due = m.published * 1000L + turn(m);
        long wait = due - System.currentTimeMillis();
        if (wait > 0) {
            status = "Updates: on, " + m.version + " in " + (wait / 60000) + " min";
            Thread.sleep(wait);
        }
        apply(m);
    }

    /** This helper's own place in the rollout: fixed for a helper and a release, so restarts don't move it. */
    private long turn(Manifest m) throws Exception {
        long spread = m.urgent ? spreadMillis / 24 : spreadMillis;
        if (spread <= 0)
            return 0;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] h = sha.digest((KillaNet.peerId() + "|" + m.sequence).getBytes(StandardCharsets.UTF_8));
        long n = 0;
        for (int i = 0; i < 6; i++)
            n = n << 8 | h[i] & 255;
        return n % spread;
    }

    private void apply(Manifest m) throws Exception {
        File zip = new File(dir, "download.zip");
        File part = new File(home, "versions/" + m.sequence + ".part");
        File done = new File(home, "versions/" + m.sequence);
        delete(part);
        try {
            download(from + "/" + m.zip, zip, m.size);
            if (zip.length() != m.size || !hex(sha256(zip)).equals(m.sha256))
                throw new IOException("the download does not match its signed hash");
            unpack(zip, part);
            delete(done);
            Files.move(part.toPath(), done.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(zip.toPath());
            delete(part);
        }
        atomic(new File(dir, "next"), Long.toString(m.sequence));
        System.out.println("Update " + m.version + " ready, restarting");
        System.exit(RESTART);
    }

    static void unpack(File zip, File to) throws IOException {
        Path root = to.toPath().toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null;) {
                Path p = root.resolve(e.getName()).normalize();
                if (!p.startsWith(root))
                    throw new IOException("a file in the update points outside it");
                if (e.isDirectory()) {
                    Files.createDirectories(p);
                    continue;
                }
                Files.createDirectories(p.getParent());
                Files.copy(in, p, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Map<String, Boolean> keys() throws IOException {
        Map<String, Boolean> keys = new LinkedHashMap<String, Boolean>();  // key -> is it the recovery key
        try (InputStream in = HelperUpdate.class.getResourceAsStream("/update-keys.txt")) {
            if (in == null)
                return keys;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null;) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 2 && (f[0].equals("release") || f[0].equals("recovery")))
                    keys.put(f[1], f[0].equals("recovery"));
            }
        }
        return keys;
    }

    private Set<String> lines(String name) throws IOException {
        File f = new File(dir, name);
        if (!f.exists())
            return new HashSet<String>();
        return new HashSet<String>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
    }
    private Set<String> retired() throws IOException { return lines("retired"); }
    private Set<String> bad() throws IOException { return lines("bad"); }
    private void retire(String key) throws IOException {
        Set<String> all = retired();
        if (all.add(key))
            atomic(new File(dir, "retired"), String.join("\n", all) + "\n");
    }

    /** One release as signed: anything not covered by the signature is never read. */
    static final class Manifest {
        long sequence, size, published;
        String version, zip, sha256, retire;
        boolean urgent;

        static Manifest parse(byte[] raw, Map<String, Boolean> keys, Set<String> retired) throws Exception {
            String text = new String(raw, StandardCharsets.UTF_8);
            int at = text.lastIndexOf("\nsig=");
            if (!text.startsWith(HEADER) || at < 0)
                throw new IOException("not an update manifest");
            byte[] body = text.substring(0, at + 1).getBytes(StandardCharsets.UTF_8);
            byte[] sig = Base64.getDecoder().decode(text.substring(at + 5).trim());
            boolean recovery = false, trusted = false;
            for (Map.Entry<String, Boolean> k : keys.entrySet())
                if (!retired.contains(k.getKey()) && verify(k.getKey(), body, sig)) {
                    trusted = true;
                    recovery = k.getValue();
                    break;
                }
            if (!trusted)
                throw new IOException("the manifest's signature is not trusted");
            Manifest m = new Manifest();
            for (String line : text.substring(HEADER.length(), at).split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0)
                    continue;
                String key = line.substring(0, eq), value = line.substring(eq + 1);
                if (key.equals("sequence")) m.sequence = Long.parseLong(value);
                else if (key.equals("version")) m.version = value;
                else if (key.equals("zip")) m.zip = value;
                else if (key.equals("zip_sha256")) m.sha256 = value;
                else if (key.equals("zip_size")) m.size = Long.parseLong(value);
                else if (key.equals("published")) m.published = Long.parseLong(value);
                else if (key.equals("urgent")) m.urgent = value.equals("1");
                else if (key.equals("retire")) m.retire = value;
            }
            if (m.sequence <= 0 || m.version == null || !m.version.matches("[0-9A-Za-z.-]{1,32}") || m.zip == null ||
                !m.zip.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || m.sha256 == null ||
                !m.sha256.matches("[0-9a-f]{64}") || m.size <= 0 || m.size > MAX_ZIP || m.published <= 0)
                throw new IOException("the manifest is incomplete");
            // only the recovery key may retire a key, so a stolen release key can't lock the recovery key out
            if (m.retire != null && !recovery)
                m.retire = null;
            return m;
        }

        static boolean verify(String key, byte[] body, byte[] sig) throws Exception {
            byte[] raw = Base64.getDecoder().decode(key);
            byte[] der = new byte[44];
            byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
            System.arraycopy(prefix, 0, der, 0, 12);
            if (raw.length != 32)
                return false;
            System.arraycopy(raw, 0, der, 12, 32);
            PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pub);
            s.update(body);
            try {
                return s.verify(sig);
            } catch (java.security.SignatureException wrongLength) {
                return false;
            }
        }
    }

    private static InputStream open(String url) throws IOException {
        URLConnection c = new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        return c.getInputStream();
    }
    private static byte[] fetch(String url, int limit) throws IOException {
        try (InputStream in = open(url)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0;) {
                out.write(b, 0, n);
                if (out.size() > limit)
                    throw new IOException("the manifest is too long");
            }
            return out.toByteArray();
        }
    }
    private static void download(String url, File to, long size) throws IOException {
        try (InputStream in = open(url); OutputStream out = new FileOutputStream(to)) {
            byte[] b = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(b)) > 0;) {
                total += n;
                if (total > size)
                    throw new IOException("the download is larger than signed");
                out.write(b, 0, n);
            }
        }
    }
    private static byte[] sha256(File f) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0;)
                sha.update(b, 0, n);
        }
        return sha.digest();
    }
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b)
            s.append(String.format("%02x", x & 255));
        return s.toString();
    }
    private static String read(File f) throws IOException {
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : "";
    }
    private static void atomic(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void delete(File f) throws IOException {
        if (!f.exists())
            return;
        File[] kids = f.listFiles();
        if (kids != null && !Files.isSymbolicLink(f.toPath()))
            for (File k : kids)
                delete(k);
        Files.deleteIfExists(f.toPath());
    }
}
