package ai.jackknife.killayue;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;

/** Folders, the model list, and the resumable checked downloader. */
public class Store {
    static final String BASE = "https://huggingface.co/Serveurperso/YuE2-GGUF/resolve/main/";
    static final String VAE = "YuE2-Vae-F32.gguf";
    static final String MERT = "MERT-v2-FullSong-Q8_0.gguf";
    static final String SHEET = "SheetSage2-Q8_0.gguf";
    static final String TOKENIZER = "yue2-mothersuperior-realaudio-tokenizer-v4-Q8_0.gguf";

    // name, size in bytes, SHA-256 as published on Hugging Face
    static final String[][] FILES = {
        {"YuE2-3B-Q5_K_M.gguf", "2622936832", "cd3efd250b734a229172800f08f33a893ad1f66b521666e176cdae4a0729b281"},
        {"YuE2-3B-Q6_K.gguf", "2943331072", "42068d5e57713df11b9f6a5a3751072d9bbefe231d1d088f9c100e026ffa4ef9"},
        {"YuE2-3B-Q8_0.gguf", "3810232064", "41121ce97786d7795a325bcf123ca196956c03bb252c9e75384cfc1f2fc19e6b"},
        {VAE, "530497344", "93e49dfb1970e89ad64cacb17cf13b5d05f6bb30ef7ed3adae3050bcb728638a"},
        {MERT, "902283712", "35a6032ec7e8123c02becdefc7bfe10a3adb33faa8a1951dc0af864b4494b8ef"},
        {SHEET, "105629856", "95d8fa8761753b4833782a52b68d5c0afa3bd3cfbc03d3dcbbf8ded0c13f611e"},
        {TOKENIZER, "46527328", "ab860529c61bcef0350d5e43d4291835b380e76246c48be4f26fa8839fe31ce5"},
    };

    static final String[] QUANTS = {"Q5_K_M", "Q6_K", "Q8_0"};

    // Shared storage goes through a slow userspace filesystem; under a heavy song it can stall
    // for seconds, so the folder checks are done once and remembered, not on every call.
    private static volatile File sharedRoot;
    private static final java.util.Set<String> made = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public static File root(Context c) {
        File r = sharedRoot;
        if (r != null) return r;
        File f = new File(Environment.getExternalStorageDirectory(), "KillaYuE");
        if ((f.isDirectory() || f.mkdirs()) && f.canWrite()) {
            sharedRoot = f; // only the shared folder is remembered: before storage permission it falls back
            return f;
        }
        return c.getExternalFilesDir(null);
    }

    public static File dir(Context c, String name) {
        File f = new File(root(c), name);
        if (made.add(f.getPath())) f.mkdirs();
        return f;
    }

    static File models(Context c) {
        return dir(c, "models");
    }

    public static File library(Context c) {
        return dir(c, "library");
    }

    static File adapters(Context c) {
        return dir(c, "adapters");
    }

    static String backbone(Context c) {
        return "YuE2-3B-" + c.getSharedPreferences("cfg", 0).getString("quant", "Q6_K") + ".gguf";
    }

    static String[] info(String name) {
        for (int i = 0; i < FILES.length; i++) if (FILES[i][0].equals(name)) return FILES[i];
        return null;
    }

    static boolean have(Context c, String name) {
        String[] f = info(name);
        File file = new File(models(c), name);
        return f != null && file.isFile() && file.length() == Long.parseLong(f[1]);
    }

    static boolean coreReady(Context c) {
        return have(c, backbone(c)) && have(c, VAE);
    }

    // ---- downloads ----

    public static class Dl {
        public volatile long done, total;
        public volatile String state = "queued"; // queued, downloading, checking, done, failed, cancelled
        public volatile String error = "";
        public volatile boolean cancel;
    }

    static final HashMap<String, Dl> downloads = new HashMap<String, Dl>();

    static Dl status(String name) {
        synchronized (downloads) {
            return downloads.get(name);
        }
    }

    static boolean busy(String name) {
        Dl d = status(name);
        return d != null && (d.state.equals("queued") || d.state.equals("downloading") || d.state.equals("checking"));
    }

    static void cancel(String name) {
        Dl d = status(name);
        if (d != null) d.cancel = true;
    }

    static void download(final Context app, final String name) {
        download(app, name, BASE);
    }

    /**
     * Fetches a model from base + name: Hugging Face, or a friend's phone sharing its models
     * (ShareServer). Either way the file must match the SHA-256 published on Hugging Face.
     */
    static void download(final Context app, final String name, final String base) {
        if (busy(name)) return;
        final String[] f = info(name);
        if (f == null) return;
        final Dl d = new Dl();
        d.total = Long.parseLong(f[1]);
        synchronized (downloads) {
            downloads.put(name, d);
        }
        final File dest = new File(models(app), name);
        final File part = new File(models(app), name + ".part");
        new Thread(new Runnable() {
            public void run() {
                try {
                    fetch(base + name, part, d);
                    if (d.cancel) {
                        d.state = "cancelled";
                        return;
                    }
                    d.state = "checking";
                    String sum = sha256(part);
                    if (!sum.equals(f[2])) {
                        part.delete();
                        throw new Exception("The file did not match its checksum and was removed. Try again.");
                    }
                    if (!part.renameTo(dest)) throw new Exception("Could not move the file into place");
                    d.state = "done";
                } catch (Exception e) {
                    d.error = String.valueOf(e.getMessage());
                    d.state = d.cancel ? "cancelled" : "failed";
                }
            }
        }, "dl-" + name).start();
    }

    public static void fetch(String url, File part, Dl d) throws Exception {
        int tries = 0;
        while (true) {
            long have = part.isFile() ? part.length() : 0;
            d.done = have;
            if (have >= d.total) return;
            if (d.cancel) return;
            HttpURLConnection con = null;
            try {
                d.state = "downloading";
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setInstanceFollowRedirects(true);
                con.setConnectTimeout(20000);
                con.setReadTimeout(30000);
                if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
                int code = con.getResponseCode();
                if (code == 200 && have > 0) {
                    part.delete();
                    have = 0;
                } else if (code != 200 && code != 206) {
                    throw new Exception("Server answered " + code);
                }
                InputStream in = con.getInputStream();
                RandomAccessFile out = new RandomAccessFile(part, "rw");
                try {
                    out.seek(have);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        have += n;
                        d.done = have;
                        if (d.cancel) return;
                    }
                } finally {
                    out.close();
                    in.close();
                }
                tries = 0;
            } catch (Exception e) {
                if (++tries > 8) throw new Exception("Download kept failing: " + e.getMessage());
                Thread.sleep(3000);
            } finally {
                if (con != null) con.disconnect();
            }
        }
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        byte[] h = md.digest();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.length; i++) b.append(String.format("%02x", h[i] & 0xff));
        return b.toString();
    }

    static void write(File f, byte[] data) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }

    static byte[] read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            return Api.readAll(in);
        } finally {
            in.close();
        }
    }
}
