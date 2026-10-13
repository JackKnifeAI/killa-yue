package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Album covers painted on the phone's NPU. The model is Stable Diffusion 1.5 as compiled
 * by Qualcomm for the Snapdragon 8 Elite; the native helper (engine/npu/sdnpu.cpp) runs it
 * and takes about three seconds a picture. The model cannot letter, so the title is drawn
 * here, on top, in the skin's typeface.
 */
public class Covers {
    static final String URL = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/"
            + "stable_diffusion_v1_5/releases/v0.64.0/stable_diffusion_v1_5-qnn_context_binary-w8a16-"
            + "qualcomm_snapdragon_8_elite_for_galaxy.zip";
    static final long ZIP_SIZE = 709785222L;
    static final String[] FILES = {"text_encoder.bin", "unet.bin", "vae.bin"};
    static final long[] SIZES = {163058576L, 880144176L, 60452704L};

    static volatile String state = "";
    static volatile boolean busy;

    static File dir(Context c) {
        return Store.dir(c, "cover-model");
    }

    static boolean ready(Context c) {
        for (int i = 0; i < FILES.length; i++) {
            File f = new File(dir(c), FILES[i]);
            if (!f.isFile() || f.length() != SIZES[i]) return false;
        }
        return true;
    }

    static File coverFor(Context c, JSONObject meta) {
        String a = meta.optString("_audio");
        int dot = a.lastIndexOf('.');
        return new File(Store.library(c), (dot > 0 ? a.substring(0, dot) : a) + ".cover.jpg");
    }

    /** Downloads Qualcomm's bundle and unpacks the three graphs. Progress is in {@link #state}. */
    static void download(final Context app) {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                File zip = new File(dir(app), "bundle.zip.part");
                try {
                    final Store.Dl d = new Store.Dl();
                    d.total = ZIP_SIZE;
                    Thread watch = new Thread(new Runnable() {
                        public void run() {
                            while (busy && !"done".equals(d.state)) {
                                state = "Downloading the cover model: " + MainActivity.size(d.done) + " of " + MainActivity.size(d.total);
                                try {
                                    Thread.sleep(700);
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                        }
                    });
                    watch.start();
                    Store.fetch(URL, zip, d);
                    d.state = "done";
                    if (zip.length() != ZIP_SIZE) throw new Exception("the download came up short");
                    state = "Unpacking the cover model";
                    ZipInputStream in = new ZipInputStream(new java.io.FileInputStream(zip));
                    try {
                        ZipEntry e;
                        byte[] buf = new byte[1 << 16];
                        while ((e = in.getNextEntry()) != null) {
                            String name = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                            boolean wanted = false;
                            for (int i = 0; i < FILES.length; i++) if (FILES[i].equals(name)) wanted = true;
                            if (!wanted) continue;
                            FileOutputStream o = new FileOutputStream(new File(dir(app), name));
                            try {
                                int n;
                                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                            } finally {
                                o.close();
                            }
                        }
                    } finally {
                        in.close();
                    }
                    zip.delete();
                    state = ready(app) ? "Cover model ready" : "The cover model did not unpack correctly";
                } catch (Exception e) {
                    state = "Cover model download failed: " + e.getMessage();
                } finally {
                    busy = false;
                }
            }
        }, "cover-dl").start();
    }

    // ---------- CLIP's byte-pair tokenizer ----------

    private static HashMap<String, Integer> vocab, ranks;
    private static String[] byteChar;
    private static final Pattern WORDS = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+");

    private static synchronized void loadVocab(Context c) throws Exception {
        if (vocab != null) return;
        ArrayList<String> merges = new ArrayList<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(c.getAssets().open("clip/bpe_simple_vocab_16e6.txt.gz")), "UTF-8"));
        try {
            r.readLine(); // version line
            String line;
            while (merges.size() < 49152 - 256 - 2 && (line = r.readLine()) != null) merges.add(line);
        } finally {
            r.close();
        }
        // printable bytes keep their own character; the rest are moved above 255
        ArrayList<Integer> bs = new ArrayList<Integer>();
        for (int b = '!'; b <= '~'; b++) bs.add(Integer.valueOf(b));
        for (int b = 0xa1; b <= 0xac; b++) bs.add(Integer.valueOf(b));
        for (int b = 0xae; b <= 0xff; b++) bs.add(Integer.valueOf(b));
        byteChar = new String[256];
        ArrayList<String> order = new ArrayList<String>();
        for (int i = 0; i < bs.size(); i++) {
            String ch = String.valueOf((char) bs.get(i).intValue());
            byteChar[bs.get(i).intValue()] = ch;
            order.add(ch);
        }
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (byteChar[b] == null) {
                byteChar[b] = String.valueOf((char) (256 + n++));
                order.add(byteChar[b]);
            }
        }
        HashMap<String, Integer> v = new HashMap<String, Integer>();
        for (int i = 0; i < order.size(); i++) v.put(order.get(i), Integer.valueOf(v.size()));
        for (int i = 0; i < order.size(); i++) v.put(order.get(i) + "</w>", Integer.valueOf(v.size()));
        HashMap<String, Integer> rk = new HashMap<String, Integer>();
        for (int i = 0; i < merges.size(); i++) {
            String[] p = merges.get(i).split(" ");
            v.put(p[0] + p[1], Integer.valueOf(v.size()));
            rk.put(merges.get(i), Integer.valueOf(i));
        }
        ranks = rk;
        vocab = v;
    }

    private static ArrayList<String> bpe(String token) {
        ArrayList<String> w = new ArrayList<String>();
        for (int i = 0; i < token.length(); i++) w.add(String.valueOf(token.charAt(i)));
        w.set(w.size() - 1, w.get(w.size() - 1) + "</w>");
        while (w.size() > 1) {
            int best = Integer.MAX_VALUE, at = -1;
            for (int i = 0; i + 1 < w.size(); i++) {
                Integer r = ranks.get(w.get(i) + " " + w.get(i + 1));
                if (r != null && r.intValue() < best) {
                    best = r.intValue();
                    at = i;
                }
            }
            if (at < 0) break;
            String a = w.get(at), b = w.get(at + 1);
            ArrayList<String> out = new ArrayList<String>();
            for (int i = 0; i < w.size(); i++) {
                if (i + 1 < w.size() && w.get(i).equals(a) && w.get(i + 1).equals(b)) {
                    out.add(a + b);
                    i++;
                } else {
                    out.add(w.get(i));
                }
            }
            w = out;
        }
        return w;
    }

    /** The 77 token ids the text encoder expects, as a comma separated list. */
    static String tokens(Context c, String text) throws Exception {
        loadVocab(c);
        ArrayList<Integer> ids = new ArrayList<Integer>();
        ids.add(Integer.valueOf(49406));
        Matcher m = WORDS.matcher(text.replaceAll("\\s+", " ").trim().toLowerCase());
        while (m.find() && ids.size() < 76) {
            byte[] raw = m.group().getBytes("UTF-8");
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < raw.length; i++) t.append(byteChar[raw[i] & 0xff]);
            ArrayList<String> parts = bpe(t.toString());
            for (int i = 0; i < parts.size() && ids.size() < 76; i++) {
                Integer id = vocab.get(parts.get(i));
                if (id != null) ids.add(id);
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 77; i++) b.append(i > 0 ? "," : "").append(i < ids.size() ? ids.get(i).intValue() : 49407);
        return b.toString();
    }

    static String prompt(JSONObject meta) {
        String style = meta.optString("style");
        if (style.length() > 160) style = style.substring(0, 160);
        String title = meta.optString("_title").replaceAll("[\\[\\]()]", "");
        return "album cover art, " + title + ", " + style + ", striking composition, rich colour, highly detailed illustration";
    }

    /**
     * Paints a cover for the song and saves it beside the audio. Blocks for a few seconds;
     * call from a background thread. variation changes the picture for the same song.
     */
    static File make(Context c, JSONObject meta, int variation) throws Exception {
        if (!ready(c)) throw new Exception("The cover model is not downloaded yet. Get it on the Engine tab.");
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File raw = new File(c.getCacheDir(), "cover.rgb");
        raw.delete();
        long seed = (Math.abs((long) (meta.optString("_audio") + meta.optString("lyrics")).hashCode()) + variation * 7919L) % 2000000000L;
        ProcessBuilder pb = new ProcessBuilder(lib + "/libsdnpu.so", "--dir", dir(c).getAbsolutePath(), "--lib", lib,
                "--tokens", tokens(c, prompt(meta)),
                "--neg", tokens(c, "text, letters, words, watermark, signature, logo, blurry, low quality, deformed"),
                "--steps", "20", "--guidance", "7.5", "--seed", String.valueOf(seed), "--out", raw.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", lib + ":/vendor/lib64");
        pb.environment().put("ADSP_LIBRARY_PATH", lib + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp");
        Process p = pb.start();
        String log = new String(Api.readAll(p.getInputStream()), "UTF-8");
        int code = p.waitFor();
        EngineService.addLog("[Cover] exit " + code + " " + log.substring(Math.max(0, log.length() - 400)).replace('\n', ' '));
        if (code != 0 || raw.length() != 512 * 512 * 3) {
            throw new Exception("The NPU could not paint the cover (code " + code + "). The engine log has the details.");
        }
        byte[] rgb = Store.read(raw);
        raw.delete();
        int[] px = new int[512 * 512];
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xFF000000 | ((rgb[i * 3] & 0xff) << 16) | ((rgb[i * 3 + 1] & 0xff) << 8) | (rgb[i * 3 + 2] & 0xff);
        }
        Bitmap art = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(art);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(Bitmap.createBitmap(px, 512, 512, Bitmap.Config.ARGB_8888), null, new android.graphics.Rect(0, 0, 1024, 1024), paint);
        // a soft shade along the bottom, then the title in the skin's lettering
        paint.setShader(new LinearGradient(0, 640, 0, 1024, 0x00000000, 0xD0000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, 640, 1024, 1024, paint);
        paint.setShader(null);
        String title = meta.optString("_title", "Untitled");
        paint.setTypeface(Skin.script());
        paint.setColor(Skin.HI);
        paint.setTextAlign(Paint.Align.CENTER);
        float size = Skin.SCRIPT_TITLES ? 96 : 72;
        paint.setTextSize(size);
        float w = paint.measureText(title);
        if (w > 920) paint.setTextSize(Math.max(34, size * 920 / w));
        paint.setShadowLayer(18, 0, 4, 0xE0000000);
        cv.drawText(title, 512, 930, paint);
        paint.clearShadowLayer();
        paint.setTypeface(Skin.text());
        paint.setTextSize(30);
        paint.setColor(0xCCFFFFFF);
        cv.drawText("KILLA YuE", 512, 985, paint);

        File out = coverFor(c, meta);
        FileOutputStream o = new FileOutputStream(out);
        try {
            art.compress(Bitmap.CompressFormat.JPEG, 90, o);
        } finally {
            o.close();
        }
        return out;
    }

    static Bitmap load(File f, int sample) {
        if (f == null || !f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    static byte[] bytes(InputStream in) throws Exception {
        return Api.readAll(in);
    }
}
