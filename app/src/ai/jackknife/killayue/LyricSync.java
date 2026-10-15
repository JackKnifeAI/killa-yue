package ai.jackknife.killayue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Times a song's lyrics to when they are actually sung. A speech recogniser (whisper.cpp, shipped as
 * libwhisper.so, with the multilingual "small" model) listens to the finished song and returns every word
 * it hears with its time. The whole lyric is then aligned against what was heard, word by word and in
 * order, so a repeated chorus maps to the right repeat. Lines that were never heard (the model sometimes
 * skips or repeats sections) are placed between their neighbours. The result is saved like tap-to-time.
 */
public class LyricSync {
    static final String[] MODEL = {"ggml-small-q5_1.bin", "190085487",
        "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"};

    static volatile String state = "";
    static volatile int progress = -1;          // the recogniser's progress while it listens, 0 to 100; -1 when idle
    static volatile long listeningSince;        // when it started listening (ms), for the time left
    static volatile boolean busy;

    static File dir(Context c) {
        return Store.dir(c, "sync-model");
    }

    static File model(Context c) {
        return new File(dir(c), MODEL[0]);
    }

    static boolean ready(Context c) {
        File f = model(c);
        return f.isFile() && f.length() == Long.parseLong(MODEL[1]);
    }

    static void download(final Context app) {
        if (busy) return;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    File part = new File(dir(app), MODEL[0] + ".part");
                    final Store.Dl d = new Store.Dl();
                    d.total = Long.parseLong(MODEL[1]);
                    Thread watch = new Thread(new Runnable() {
                        public void run() {
                            while (busy && !"done".equals(d.state)) {
                                state = "Lyric sync model: " + MainActivity.size(d.done) + " of " + MainActivity.size(d.total);
                                try {
                                    Thread.sleep(700);
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                        }
                    });
                    watch.start();
                    Store.fetch(MODEL[3], part, d);
                    d.state = "done";
                    state = "Checking the lyric sync model";
                    if (!Store.sha256(part).equals(MODEL[2])) {
                        part.delete();
                        throw new Exception("the file did not match its checksum and was removed. Try again.");
                    }
                    if (!part.renameTo(model(app))) throw new Exception("could not move the file into place");
                    state = "Lyric sync ready";
                } catch (Exception e) {
                    state = "Lyric sync model download failed: " + e.getMessage();
                } finally {
                    busy = false;
                }
            }
        }, "sync-dl").start();
    }

    // ---------------- listening ----------------

    public static class Word {
        final String w;
        final int ms;

        public Word(String w, int ms) {
            this.w = w;
            this.ms = ms;
        }
    }

    /** Lower case, letters and digits only, accents removed: "There's" and "theres" match. */
    static String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static ArrayList<String> words(String line) {
        ArrayList<String> out = new ArrayList<String>();
        for (String t : line.split("[\\s\\-]+")) {
            String n = norm(t);
            if (n.length() > 0) out.add(n);
        }
        return out;
    }

    /** Runs the recogniser on the song. Returns the words heard with their start times. */
    static ArrayList<Word> listen(Context c, File audio, String hint) throws Exception {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File outBase = new File(c.getCacheDir(), "sync");
        File json = new File(c.getCacheDir(), "sync.json");
        json.delete();
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        ProcessBuilder pb = new ProcessBuilder(lib + "/libwhisper.so", "-m", model(c).getAbsolutePath(),
                "-f", audio.getAbsolutePath(), "-l", "auto", "-t", String.valueOf(threads),
                "-ml", "1", "-sow", "-oj", "-of", outBase.getAbsolutePath(), "-np", "-pp", "--prompt", hint);
        pb.redirectErrorStream(true);
        pb.directory(c.getCacheDir());
        pb.environment().put("LD_LIBRARY_PATH", lib);
        Process p = pb.start();
        // read as it comes: whisper's progress lines move the bar while it listens
        StringBuilder log = new StringBuilder();
        listeningSince = System.currentTimeMillis();
        progress = 0;
        int code;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            for (String line; (line = r.readLine()) != null; ) {
                int pc = SyncProgress.percent(line);
                if (pc >= 0) {
                    progress = pc;
                    state = "Syncing lyrics: " + pc + "%";
                }
                log.append(line).append('\n');
                if (log.length() > 8000) log.delete(0, log.length() - 4000);
            }
            code = p.waitFor();
        } finally {
            progress = -1;
        }
        EngineService.addLog("[Sync] exit " + code + " " + log.substring(Math.max(0, log.length() - 300)).replace('\n', ' '));
        if (code != 0 || !json.isFile()) throw new Exception("The recogniser could not listen to this song (code " + code + ").");
        JSONObject j = new JSONObject(new String(Store.read(json), "UTF-8"));
        json.delete();
        JSONArray t = j.optJSONArray("transcription");
        ArrayList<Word> out = new ArrayList<Word>();
        for (int i = 0; t != null && i < t.length(); i++) {
            JSONObject seg = t.getJSONObject(i);
            int from = seg.optJSONObject("offsets") != null ? seg.getJSONObject("offsets").optInt("from") : 0;
            for (String w : words(seg.optString("text"))) out.add(new Word(w, from));
        }
        return out;
    }

    // ---------------- aligning ----------------

    /**
     * Start time (ms) for each lyric line, aligning the lyric's words to the heard words in order
     * (longest common subsequence). matched[0] receives how many lines were heard at all.
     */
    public static int[] align(ArrayList<String> lines, ArrayList<Word> heard, int durationMs, int[] matched) {
        ArrayList<String> lyric = new ArrayList<String>();
        ArrayList<Integer> owner = new ArrayList<Integer>();
        for (int li = 0; li < lines.size(); li++) {
            for (String w : words(lines.get(li))) {
                lyric.add(w);
                owner.add(li);
            }
        }
        int n = lyric.size(), m = heard.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int k = m - 1; k >= 0; k--) {
                dp[i][k] = lyric.get(i).equals(heard.get(k).w) ? dp[i + 1][k + 1] + 1 : Math.max(dp[i + 1][k], dp[i][k + 1]);
            }
        }
        int[] hit = new int[n];
        java.util.Arrays.fill(hit, -1);
        for (int i = 0, k = 0; i < n && k < m; ) {
            if (lyric.get(i).equals(heard.get(k).w)) {
                hit[i] = k;
                i++;
                k++;
            } else if (dp[i + 1][k] >= dp[i][k + 1]) {
                i++;
            } else {
                k++;
            }
        }
        double[] t = new double[lines.size()];
        boolean[] known = new boolean[lines.size()];
        int heardLines = 0;
        for (int li = 0, i = 0; li < lines.size(); li++) {
            int first = i;
            int firstHit = -1;
            while (i < n && owner.get(i) == li) {
                if (firstHit < 0 && hit[i] >= 0) firstHit = i;
                i++;
            }
            if (firstHit >= 0) {
                // the line starts a little before its first recognised word, about 0.32 s per word skipped
                t[li] = Math.max(0, heard.get(hit[firstHit]).ms - 320.0 * (firstHit - first));
                known[li] = true;
                heardLines++;
            }
        }
        // lines never heard sit between their neighbours
        for (int li = 0; li < t.length; li++) {
            if (known[li]) continue;
            int a = li - 1, b = li + 1;
            while (a >= 0 && !known[a]) a--;
            while (b < t.length && !known[b]) b++;
            if (a >= 0 && b < t.length) t[li] = t[a] + (t[b] - t[a]) * (li - a) / (double) (b - a);
            else if (a >= 0) t[li] = Math.min(durationMs - 1000, t[a] + 3000.0 * (li - a));
            else t[li] = b < t.length ? Math.max(0, t[b] - 3000.0 * (b - li)) : 0;
        }
        int[] out = new int[t.length];
        for (int li = 0; li < t.length; li++) {
            out[li] = (int) Math.round(t[li]);
            if (li > 0 && out[li] <= out[li - 1]) out[li] = out[li - 1] + 400; // times must rise
        }
        if (matched != null) matched[0] = heardLines;
        return out;
    }

    /**
     * Listens to the song and saves the timing with it, as tap-to-time does. Blocks; run off the screen thread.
     * Returns a sentence for the user.
     */
    static String sync(Context c, JSONObject meta, File metaFile) throws Exception {
        if (!ready(c)) throw new Exception("The lyric sync model is not downloaded yet. Get it on the Engine tab.");
        File audio = new File(Store.library(c), meta.optString("_audio"));
        if (!audio.isFile()) throw new Exception("The song's audio file is missing.");
        ArrayList<Karaoke.Line> plain = Karaoke.lines(meta.optString("lyrics"));
        if (plain.size() == 0) throw new Exception("This song has no lyrics to sync.");
        ArrayList<String> lines = new ArrayList<String>();
        StringBuilder hint = new StringBuilder();
        for (int i = 0; i < plain.size(); i++) {
            lines.add(plain.get(i).text);
            if (hint.length() < 220) hint.append(plain.get(i).text).append(' ');
        }
        int durationMs = meta.optInt("_seconds") * 1000;
        ArrayList<Word> heard = listen(c, audio, hint.toString().trim());
        if (heard.size() < 3) throw new Exception("The recogniser heard almost no words in this song.");
        int[] matched = new int[1];
        int[] times = align(lines, heard, durationMs, matched);
        JSONArray t = new JSONArray();
        for (int v : times) t.put(v);
        meta.put("_times", t);
        meta.put("_synced", matched[0] + "/" + lines.size());
        long stamp = metaFile.lastModified();
        Store.write(metaFile, meta.toString().getBytes("UTF-8"));
        metaFile.setLastModified(stamp);
        Tags.write(audio, meta.optString("_title", "Untitled"), MainActivity.linesFor(meta, durationMs));
        if (matched[0] == lines.size()) return "Synced: every line was heard and timed.";
        return "Synced: " + matched[0] + " of " + lines.size() + " lines were heard and timed. The others were not sung as written "
                + "(the model sometimes skips or repeats a part) and are placed between their neighbours.";
    }
}
