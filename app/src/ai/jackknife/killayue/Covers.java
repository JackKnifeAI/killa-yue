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

    // GPU covers, for phones without the 8 Elite NPU: name, size, SHA-256 (as published), URL
    static final String[][] GPU_FILES = {
        {"stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf", "1763578176", "d0555243938c62faeefb4ac93f6c7a053ad373a4290c5256bce229aeb193bf94",
            "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf"},
        {"lcm-lora-sdv1-5.safetensors", "134621556", "8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4",
            "https://huggingface.co/latent-consistency/lcm-lora-sdv1-5/resolve/main/pytorch_lora_weights.safetensors"},
        // the diffusers-format TAESD (stable-diffusion.cpp does not read the bare decoder file)
        {"taesd.safetensors", "9793292", "db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549",
            "https://huggingface.co/madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors"},
    };

    static File gpuDir(Context c) {
        return Store.dir(c, "cover-gpu");
    }

    static boolean gpuReady(Context c) {
        for (int i = 0; i < GPU_FILES.length; i++) {
            File f = new File(gpuDir(c), GPU_FILES[i][0]);
            if (!f.isFile() || f.length() != Long.parseLong(GPU_FILES[i][1])) return false;
        }
        return true;
    }

    static long gpuBytes() {
        long n = 0;
        for (int i = 0; i < GPU_FILES.length; i++) n += Long.parseLong(GPU_FILES[i][1]);
        return n;
    }

    /** "npu", "gpu", or "" when no cover model is installed. The NPU is used where it exists, unless the GPU is chosen. */
    static String painter(Context c) {
        boolean preferGpu = c.getSharedPreferences("cfg", 0).getBoolean("cover_gpu", false);
        boolean npu = PhoneCheck.npuCovers() && ready(c);
        if (npu && !preferGpu) return "npu";
        if (gpuReady(c)) return "gpu";
        return npu ? "npu" : "";
    }

    /** How long the next cover takes, for the message that announces it (GPU: about 4 minutes on a Galaxy S25 Ultra). */
    static String howLong(Context c) {
        return "gpu".equals(painter(c)) ? "On the GPU this takes about four minutes, longer on slower phones." : "It takes a few seconds.";
    }

    static boolean anyReady(Context c) {
        return painter(c).length() > 0;
    }

    /** Downloads the GPU cover pack file by file, checking each against its published SHA-256. */
    static void downloadGpu(final Context app) {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < GPU_FILES.length; i++) {
                        final String[] f = GPU_FILES[i];
                        File dest = new File(gpuDir(app), f[0]);
                        if (dest.isFile() && dest.length() == Long.parseLong(f[1])) continue;
                        File part = new File(gpuDir(app), f[0] + ".part");
                        final Store.Dl d = new Store.Dl();
                        d.total = Long.parseLong(f[1]);
                        final int k = i;
                        Thread watch = new Thread(new Runnable() {
                            public void run() {
                                while (busy && !"done".equals(d.state)) {
                                    state = "GPU cover model, file " + (k + 1) + " of " + GPU_FILES.length + ": "
                                            + MainActivity.size(d.done) + " of " + MainActivity.size(d.total);
                                    try {
                                        Thread.sleep(700);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }
                            }
                        });
                        watch.start();
                        Store.fetch(f[3], part, d);
                        d.state = "done";
                        state = "Checking " + f[0];
                        if (!Store.sha256(part).equals(f[2])) {
                            part.delete();
                            throw new Exception(f[0] + " did not match its checksum and was removed. Try again.");
                        }
                        if (!part.renameTo(dest)) throw new Exception("Could not move " + f[0] + " into place");
                    }
                    state = gpuReady(app) ? "GPU cover model ready" : "The GPU cover model is incomplete";
                } catch (Exception e) {
                    state = "GPU cover model download failed: " + e.getMessage();
                } finally {
                    busy = false;
                }
            }
        }, "cover-gpu-dl").start();
    }

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

    public static File coverFor(Context c, JSONObject meta) {
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
     * Paints a cover for the song and saves it beside the audio. Blocks for a few seconds on the NPU and minutes on the GPU;
     * call from a background thread. variation changes the picture for the same song.
     */
    static synchronized File make(Context c, JSONObject meta, int variation) throws Exception {
        String how = painter(c);
        if (how.length() == 0) throw new Exception("No cover model is installed yet. Get one on the Settings tab.");
        long seed = (Math.abs((long) (meta.optString("_audio") + meta.optString("lyrics")).hashCode()) + variation * 7919L) % 2000000000L;
        int[] px = "gpu".equals(how) ? paintGpu(c, prompt(meta), seed) : paintNpu(c, prompt(meta), seed);
        return finish(c, meta, px);
    }

    /**
     * Paints an artist portrait from the artist's own words, on the same NPU or GPU model as the covers, with no
     * lettering. Saves a 1024 px JPEG in the cache and returns it; call from a background thread.
     */
    static synchronized File portrait(Context c, String words, int variation) throws Exception {
        String how = painter(c);
        if (how.length() == 0) throw new Exception("No picture model is installed yet. Get the cover model on the Settings tab.");
        if (words.length() > 160) words = words.substring(0, 160);
        String prompt = "portrait of a music artist, " + words + ", album photo, dramatic studio lighting, rich colour, highly detailed";
        long seed = (Math.abs((long) words.hashCode()) + variation * 7919L) % 2000000000L;
        int[] px = "gpu".equals(how) ? paintGpu(c, prompt, seed) : paintNpu(c, prompt, seed);
        Bitmap art = Bitmap.createScaledBitmap(Bitmap.createBitmap(px, 512, 512, Bitmap.Config.ARGB_8888), 1024, 1024, true);
        File out = new File(c.getCacheDir(), "portrait.jpg");
        FileOutputStream o = new FileOutputStream(out);
        try {
            art.compress(Bitmap.CompressFormat.JPEG, 90, o);
        } finally {
            o.close();
        }
        return out;
    }

    private static final String NEGATIVE = "text, letters, words, watermark, signature, logo, blurry, low quality, deformed";

    /** 512x512 pixels from the NPU (Qualcomm's compiled model, 20 steps with guidance). */
    private static int[] paintNpu(Context c, String prompt, long seed) throws Exception {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File raw = new File(c.getCacheDir(), "cover.rgb");
        raw.delete();
        ProcessBuilder pb = new ProcessBuilder(lib + "/libsdnpu.so", "--dir", dir(c).getAbsolutePath(), "--lib", lib,
                "--tokens", tokens(c, prompt),
                "--neg", tokens(c, NEGATIVE),
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
        return px;
    }

    /**
     * 512x512 pixels from the GPU: stable-diffusion.cpp (shipped as libsdgpu.so) with Stable Diffusion 1.5,
     * the LCM LoRA (a good picture in 6 steps) and the tiny TAESD decoder, on the bundled Turnip driver
     * on Adreno phones or the phone's own Vulkan driver elsewhere.
     */
    private static int[] paintGpu(Context c, String prompt, long seed) throws Exception {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File d = gpuDir(c);
        File png = new File(c.getCacheDir(), "cover-gpu.png");
        png.delete();
        ProcessBuilder pb = new ProcessBuilder(lib + "/libsdgpu.so",
                "-m", new File(d, GPU_FILES[0][0]).getAbsolutePath(),
                "--lora-model-dir", d.getAbsolutePath(),
                "--taesd", new File(d, GPU_FILES[2][0]).getAbsolutePath(),
                "-p", prompt + " <lora:lcm-lora-sdv1-5:1>",
                "-n", NEGATIVE,
                "--sampling-method", "lcm", "--steps", "6", "--cfg-scale", "1.5",
                "-W", "512", "-H", "512", "-s", String.valueOf(seed), "-o", png.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.directory(c.getCacheDir());
        if (PhoneCheck.adrenoNumber() >= 600 || PhoneCheck.adrenoNumber() == 0) {
            File vk = EngineService.prepareDriver(c);   // our Turnip, as for songs
            pb.environment().put("LD_LIBRARY_PATH", vk.getAbsolutePath() + ":" + lib);
            String icd = new File(vk, "turnip_icd.json").getAbsolutePath();
            pb.environment().put("VK_DRIVER_FILES", icd);
            pb.environment().put("VK_ICD_FILENAMES", icd);
        } else {
            pb.environment().put("LD_LIBRARY_PATH", lib);  // the phone's own Vulkan driver
        }
        Process p = pb.start();
        String log = new String(Api.readAll(p.getInputStream()), "UTF-8");
        int code = p.waitFor();
        EngineService.addLog("[Cover/GPU] exit " + code + " " + log.substring(Math.max(0, log.length() - 400)).replace('\n', ' '));
        Bitmap b = png.isFile() ? BitmapFactory.decodeFile(png.getAbsolutePath()) : null;
        png.delete();
        if (code != 0 || b == null) {
            throw new Exception("The GPU could not paint the cover (code " + code + "). The engine log has the details.");
        }
        if (b.getWidth() != 512 || b.getHeight() != 512) b = Bitmap.createScaledBitmap(b, 512, 512, true);
        int[] px = new int[512 * 512];
        b.getPixels(px, 0, 512, 0, 0, 512, 512);
        return px;
    }

    /** Puts the title on the painted picture in the skin's lettering and saves the 1024 px cover. */
    private static File finish(Context c, JSONObject meta, int[] px) throws Exception {
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
