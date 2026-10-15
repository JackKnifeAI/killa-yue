package ai.jackknife.killayue;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Write with AI: a language model on the phone writes the song's style and lyrics, straight into the
 * song screen, and rewrites either one on request.
 *
 * The model runs in the ":writer" process (WriterHost, llama.cpp's server through JNI) on 127.0.0.1 with
 * a key made up for each run, so nothing leaves the phone. Models are .gguf files in KillaYuE/writer.
 * The weights are memory-mapped, never copied into RAM, and the process ends a few minutes after the
 * last request, so the memory comes back for making songs. Nothing is written while a song is being made.
 */
public class Songwriter {
    // the model offered for download: Gemma 4 E4B, abliterated by huihui-ai, quantised by mradermacher
    static final String REC_NAME = "Huihui-gemma-4-E4B-it-abliterated.Q4_K_M.gguf";
    static final String REC_URL = "https://huggingface.co/mradermacher/Huihui-gemma-4-E4B-it-abliterated-GGUF/resolve/main/" + REC_NAME;
    static final long REC_SIZE = 5335286272L;
    static final String REC_SHA = "d793116ec11cacac0228e7c907a40e9765969d76dd45708f0f638df008718f87";

    private static final long IDLE_MS = 5 * 60 * 1000;

    private static ServiceConnection conn;
    private static volatile boolean gone;
    private static int port;
    private static String key;
    private static String loaded;           // the model the running process has
    private static long lastUse;
    private static HttpURLConnection current;
    private static volatile boolean stopped;

    interface Done {
        void text(String all, boolean finished, String error);
    }

    // ---------------- models ----------------

    static File dir(Context c) {
        return Store.dir(c, "writer");
    }

    static ArrayList<File> models(Context c) {
        ArrayList<File> out = new ArrayList<File>();
        File[] f = dir(c).listFiles();
        if (f == null) return out;
        Arrays.sort(f);
        for (int i = 0; i < f.length; i++) {
            String n = f[i].getName().toLowerCase(java.util.Locale.US);
            if (n.endsWith(".gguf") && !n.contains("mmproj") && f[i].length() > (100L << 20)) out.add(f[i]);
        }
        return out;
    }

    static File chosen(Context c) {
        ArrayList<File> m = models(c);
        String want = c.getSharedPreferences("cfg", 0).getString("writer_model", REC_NAME);
        for (int i = 0; i < m.size(); i++) if (m.get(i).getName().equals(want)) return m.get(i);
        return m.size() > 0 ? m.get(0) : null;
    }

    static String shortName(File f) {
        return f.getName().replaceAll("\\.gguf$", "").replaceAll("^Huihui-", "").replaceAll("-abliterated", " abliterated");
    }

    // ---------------- the model process ----------------

    /** Starts the model process if it is not running with this model, and waits until it answers. Off the main thread. */
    static void start(final MainActivity a, File model) throws Exception {
        synchronized (Songwriter.class) {
            if (conn != null && !gone && model.getAbsolutePath().equals(loaded)) {
                lastUse = System.currentTimeMillis();
                return;
            }
            stopNow(a);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            long freeMb = mi.availMem >> 20, sizeMb = model.length() >> 20;
            if (freeMb < 3000) throw new Exception("Only " + freeMb + " MB of memory is free. Close some apps and try again.");
            ServerSocket s = new ServerSocket(0);
            port = s.getLocalPort();
            s.close();
            byte[] k = new byte[24];
            new SecureRandom().nextBytes(k);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < k.length; i++) hex.append(String.format("%02x", k[i]));
            key = hex.toString();
            ArrayList<String> args = new ArrayList<String>(Arrays.asList(
                "llama-server", "-m", model.getAbsolutePath(), "--host", "127.0.0.1", "--port", String.valueOf(port),
                "--api-key", key, "-c", "16384", "-np", "1", "-t", "4", "--jinja", "--no-ui",
                // memory-map the weights (the pages stay reclaimable) and leave room for the rest of the phone
                "-lm", "mmap", "--fit", "on", "-fitt", "2048"));
            // repacking the weights for the CPU is faster but copies them into RAM: only when there is room to spare
            if (freeMb - sizeMb < 3500) args.add("--no-repack");
            File log = new File(a.getFilesDir(), "writer-run.log");
            log.delete();
            Intent in = new Intent(a, WriterHost.class)
                    .putExtra("args", args.toArray(new String[0]))
                    .putExtra("log", log.getAbsolutePath());
            gone = false;
            conn = new ServiceConnection() {
                public void onServiceConnected(ComponentName n, IBinder b) {
                }

                public void onServiceDisconnected(ComponentName n) {
                    gone = true;
                }
            };
            if (!a.getApplicationContext().bindService(in, conn, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT)) {
                conn = null;
                throw new Exception("The songwriter process could not be started");
            }
            loaded = model.getAbsolutePath();
        }
        long until = System.currentTimeMillis() + 240000;
        while (System.currentTimeMillis() < until) {
            if (gone) throw new Exception("The songwriter stopped while loading. " + tail(new File(a.getFilesDir(), "writer-run.log")));
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection();
                h.setConnectTimeout(1000);
                h.setReadTimeout(2000);
                if (h.getResponseCode() == 200) {
                    h.disconnect();
                    lastUse = System.currentTimeMillis();
                    idleWatch(a.getApplicationContext());
                    return;
                }
                h.disconnect();
            } catch (Exception e) {
                // still loading
            }
            Thread.sleep(700);
        }
        stopNow(a);
        throw new Exception("The songwriter took too long to load");
    }

    /** Ends the model process a few minutes after the last request. */
    private static void idleWatch(final Context app) {
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (Songwriter.class) {
                        if (conn == null) return;
                        if (current == null && System.currentTimeMillis() - lastUse > IDLE_MS) {
                            stopNow(app);
                            return;
                        }
                    }
                }
            }
        }, "writer-idle").start();
    }

    static synchronized void stopNow(Context c) {
        if (conn != null) {
            try {
                c.getApplicationContext().unbindService(conn);
            } catch (Exception e) {
                // already gone
            }
        }
        conn = null;
        loaded = null;
    }

    static String tail(File f) {
        try {
            String s = new String(Store.read(f), "UTF-8").trim();
            String[] l = s.split("\n");
            return l.length == 0 ? "" : l[l.length - 1];
        } catch (Exception e) {
            return "";
        }
    }

    /** One request, streamed: done.text is called with everything so far, then once more with finished = true. */
    static void chat(String system, String user, int maxTokens, Done done) {
        String all = "";
        HttpURLConnection h = null;
        try {
            JSONObject body = new JSONObject();
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", system));
            msgs.put(new JSONObject().put("role", "user").put("content", user));
            body.put("messages", msgs);
            body.put("stream", true);
            body.put("temperature", 0.9);
            body.put("top_p", 0.95);
            body.put("max_tokens", maxTokens);
            body.put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/chat/completions").openConnection();
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setConnectTimeout(5000);
            h.setReadTimeout(180000);
            h.setRequestProperty("Content-Type", "application/json");
            h.setRequestProperty("Authorization", "Bearer " + key);
            synchronized (Songwriter.class) {
                current = h;
                stopped = false;
            }
            OutputStream o = h.getOutputStream();
            o.write(body.toString().getBytes("UTF-8"));
            o.close();
            if (h.getResponseCode() != 200) throw new Exception("The songwriter answered " + h.getResponseCode());
            BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"));
            String line;
            StringBuilder b = new StringBuilder();
            long shown = 0;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String d = line.substring(5).trim();
                if (d.equals("[DONE]")) break;
                JSONObject delta = new JSONObject(d).getJSONArray("choices").getJSONObject(0).optJSONObject("delta");
                if (delta == null) continue;
                String piece = delta.optString("content", "");
                if (piece.length() == 0 || "null".equals(piece)) continue;
                b.append(piece);
                if (System.currentTimeMillis() - shown > 250) {
                    shown = System.currentTimeMillis();
                    done.text(b.toString(), false, null);
                }
            }
            all = b.toString();
            done.text(all, true, null);
        } catch (Exception e) {
            done.text(all, true, stopped ? null : String.valueOf(e.getMessage()));
        } finally {
            if (h != null) h.disconnect();
            synchronized (Songwriter.class) {
                current = null;
                lastUse = System.currentTimeMillis();
            }
        }
    }

    static void cancel() {
        synchronized (Songwriter.class) {
            stopped = true;
            if (current != null) current.disconnect();
        }
    }

    static String prompt(Context c) {
        try {
            InputStream in = c.getAssets().open("songwriter.txt");
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            in.close();
            return SongText.system(o.toString("UTF-8"), targetStyle(c), targetLyrics(c));
        } catch (Exception e) {
            return "You are a master songwriter.";
        }
    }

    static int targetStyle(Context c) {
        return c.getSharedPreferences("cfg", 0).getInt("writer_style_chars", SongText.STYLE_CHARS);
    }

    static int targetLyrics(Context c) {
        return c.getSharedPreferences("cfg", 0).getInt("writer_lyrics_chars", SongText.LYRICS_CHARS);
    }

    // ---------------- screens ----------------

    /** "Write with AI": the idea, then the model writes both parts into the song screen. */
    static void write(final MainActivity a) {
        if (!ready(a)) return;
        LinearLayout f = a.column();
        f.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        final EditText idea = a.edit("What is the song about?", 3);
        final EditText feel = a.edit("Genre and feel (optional)", 1);
        final EditText more = a.edit("Anything it must include (optional)", 2);
        f.addView(idea);
        f.addView(feel, a.gap(8));
        f.addView(more, a.gap(8));
        idea.setText(a.cfg.getString("writer_idea", ""));
        feel.setText(a.cfg.getString("writer_feel", ""));
        ScrollView sv = new ScrollView(a);
        sv.addView(f);
        new Lux.Builder(a).setTitle("Write with AI").setView(sv)
                .setPositiveButton("Write", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String i = MainActivity.val(idea);
                        if (i.length() == 0) {
                            a.toast("Tell the songwriter what the song is about");
                            return;
                        }
                        a.cfg.edit().putString("writer_idea", i).putString("writer_feel", MainActivity.val(feel)).apply();
                        run(a, "Writing your song", SongText.newSong(i, MainActivity.val(feel), MainActivity.val(more)), 4000, new Done() {
                            public void text(String all, boolean finished, String error) {
                                SongText.Song s = SongText.parse(all);
                                if (!s.complete) {
                                    a.alert("The songwriter's answer had no song in it", all.length() > 0 ? all : String.valueOf(error));
                                    return;
                                }
                                a.checkpoint();
                                a.style.setText(s.style);
                                a.lyrics.setText(s.lyrics);
                                a.settle();
                                a.toast(counts(a, s.style, s.lyrics));
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Rewrites one part ("style" or "lyrics"), with an optional note; the length note is filled in when it is off. */
    static void rewrite(final MainActivity a, final String part) {
        if (!ready(a)) return;
        final String st = MainActivity.val(a.style), ly = MainActivity.val(a.lyrics);
        if (st.length() == 0 && ly.length() == 0) {
            write(a);
            return;
        }
        final int target = "style".equals(part) ? targetStyle(a) : targetLyrics(a);
        final EditText note = a.edit("What should change? (optional)", 3);
        note.setText(SongText.lengthNote(part, ("style".equals(part) ? st : ly).length(), target));
        LinearLayout f = a.column();
        f.setPadding(a.dp(20), a.dp(8), a.dp(20), 0);
        f.addView(note);
        new Lux.Builder(a).setTitle("style".equals(part) ? "Rewrite the style" : "Rewrite the lyrics").setView(f)
                .setPositiveButton("Rewrite", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String ask = SongText.rewrite(part, st, ly, MainActivity.val(note), target);
                        run(a, "style".equals(part) ? "Rewriting the style" : "Rewriting the lyrics", ask,
                                "style".equals(part) ? 1200 : 4000, new Done() {
                            public void text(String all, boolean finished, String error) {
                                String got = SongText.parsePart(part, all);
                                if (got.length() == 0) {
                                    a.alert("The songwriter's answer was empty", String.valueOf(error));
                                    return;
                                }
                                a.checkpoint();
                                ("style".equals(part) ? a.style : a.lyrics).setText(got);
                                a.settle();
                                a.toast(counts(a, MainActivity.val(a.style), MainActivity.val(a.lyrics)));
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    static String counts(Context c, String style, String lyrics) {
        return "Style " + style.length() + " of " + targetStyle(c) + " characters, lyrics " + lyrics.length() + " of " + targetLyrics(c);
    }

    /** True when a model is there and nothing heavier is running; otherwise says why, or offers the download. */
    static boolean ready(final MainActivity a) {
        if (MainActivity.jobBusy) {
            a.alert("A song is being made", "The songwriter waits until it finishes: the phone runs one big model at a time.");
            return false;
        }
        if (chosen(a) == null) {
            if (Store.busy(REC_NAME)) {
                a.toast("The songwriter model is still downloading");
                return false;
            }
            new Lux.Builder(a).setTitle("Get the songwriter model")
                    .setMessage("Write with AI runs a language model on your phone. The suggested one is Gemma 4 E4B, abliterated "
                            + "(" + (REC_SIZE >> 20) + " MB). You can also put any .gguf model in " + dir(a).getAbsolutePath() + ".")
                    .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            download(a);
                        }
                    }).setNegativeButton("Not now", null).show();
            return false;
        }
        return true;
    }

    /** The model list: choose one, or forget the choice. */
    static void pickModel(final MainActivity a) {
        final ArrayList<File> m = models(a);
        if (m.size() == 0) {
            ready(a);
            return;
        }
        String[] names = new String[m.size()];
        File cur = chosen(a);
        for (int i = 0; i < m.size(); i++) {
            names[i] = (m.get(i).equals(cur) ? "✓ " : "") + shortName(m.get(i)) + " (" + (m.get(i).length() >> 20) + " MB)";
        }
        new Lux.Builder(a).setTitle("Songwriter model").setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                a.cfg.edit().putString("writer_model", m.get(w).getName()).apply();
                a.toast("The songwriter uses " + shortName(m.get(w)));
            }
        }).setNegativeButton("Close", null).show();
    }

    /** Downloads the suggested model into KillaYuE/writer and checks it against its published SHA-256. */
    static void download(final MainActivity a) {
        final Store.Dl d = new Store.Dl();
        d.total = REC_SIZE;
        synchronized (Store.downloads) {
            Store.downloads.put(REC_NAME, d);
        }
        final Context app = a.getApplicationContext();
        a.toast("Downloading the songwriter model. Write with AI is ready when it finishes.");
        new Thread(new Runnable() {
            public void run() {
                File part = new File(dir(app), REC_NAME + ".part");
                try {
                    Store.fetch(REC_URL, part, d);
                    if (d.cancel) {
                        d.state = "cancelled";
                        return;
                    }
                    d.state = "checking";
                    if (!Store.sha256(part).equals(REC_SHA)) {
                        part.delete();
                        throw new Exception("The file did not match its checksum and was removed. Try again.");
                    }
                    if (!part.renameTo(new File(dir(app), REC_NAME))) throw new Exception("Could not move the file into place");
                    d.state = "done";
                } catch (Exception e) {
                    d.error = String.valueOf(e.getMessage());
                    d.state = "failed";
                }
                final String msg = "done".equals(d.state) ? "The songwriter model is ready" : "The songwriter download failed: " + d.error;
                a.ui.post(new Runnable() {
                    public void run() {
                        a.toast(msg);
                    }
                });
            }
        }, "dl-writer").start();
    }

    /** Loads the model if needed, runs one request and shows it as it is written. done runs on the main thread at the end. */
    private static void run(final MainActivity a, String title, final String user, final int maxTokens, final Done done) {
        final File model = chosen(a);
        final TextView live = a.text("Loading " + shortName(model) + "…", MainActivity.TEXT, 14);
        live.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(8));
        live.setTextIsSelectable(true);
        final ScrollView sv = new ScrollView(a);
        sv.addView(live);
        final AlertDialog dlg = new Lux.Builder(a).setTitle(title).setView(sv)
                .setNegativeButton("Stop", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cancel();
                    }
                }).setCancelable(false).show();
        final String system = prompt(a);
        new Thread(new Runnable() {
            public void run() {
                try {
                    start(a, model);
                } catch (final Exception e) {
                    a.ui.post(new Runnable() {
                        public void run() {
                            dlg.dismiss();
                            a.alert("The songwriter could not start", String.valueOf(e.getMessage()));
                        }
                    });
                    return;
                }
                a.ui.post(new Runnable() {
                    public void run() {
                        live.setText("Writing…");
                    }
                });
                chat(system, user, maxTokens, new Done() {
                    public void text(final String all, final boolean finished, final String error) {
                        a.ui.post(new Runnable() {
                            public void run() {
                                if (!finished) {
                                    live.setText(all);
                                    sv.post(new Runnable() {
                                        public void run() {
                                            sv.fullScroll(View.FOCUS_DOWN);
                                        }
                                    });
                                    return;
                                }
                                dlg.dismiss();
                                if (error != null && all.length() == 0) {
                                    a.alert("The songwriter stopped", error);
                                    return;
                                }
                                if (all.length() > 0) done.text(all, true, error);
                            }
                        });
                    }
                });
            }
        }, "writer-request").start();
    }
}
