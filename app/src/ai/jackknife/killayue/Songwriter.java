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
        final android.widget.CheckBox look = new android.widget.CheckBox(a);
        look.setText("Look it up on the web first");
        look.setTextColor(MainActivity.TEXT);
        look.setChecked(a.cfg.getBoolean("writer_research", true));
        if (Scout.available(a)) f.addView(look, a.gap(8));
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
                        boolean research = Scout.available(a) && look.isChecked();
                        a.cfg.edit().putString("writer_idea", i).putString("writer_feel", MainActivity.val(feel))
                                .putBoolean("writer_research", look.isChecked()).apply();
                        final String ask = SongText.newSong(i, MainActivity.val(feel), MainActivity.val(more));
                        if (research) {
                            research(a, i, ask, null);
                            return;
                        }
                        writeSong(a, ask);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Writes a whole song from a request and puts it into the song screen. */
    static void writeSong(final MainActivity a, String ask) {
                        run(a, "Writing your song", ask, 4000, new Done() {
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

    // ---------------- research ----------------

    static final String PLAN = "You plan web research for a songwriter. Answer with two or three web search queries, "
            + "one per line and nothing else, that will find the facts, names, dates, numbers and quotes behind the song's subject.";

    /**
     * Looks the subject up before writing: the model chooses what to search for (unless queries are given),
     * the app searches and reads the pages, then the sources screen shows exactly what was found.
     */
    static void research(final MainActivity a, final String idea, final String ask, final ArrayList<String> given) {
        stopped = false;
        final File model = chosen(a);
        final TextView live = a.text("Loading " + shortName(model) + "…", MainActivity.TEXT, 14);
        live.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(8));
        final AlertDialog dlg = new Lux.Builder(a).setTitle("Looking it up").setView(live)
                .setNegativeButton("Stop", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cancel();
                    }
                }).setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                final ArrayList<String> queries = new ArrayList<String>();
                try {
                    if (given != null) {
                        queries.addAll(given);
                    } else {
                        start(a, model);
                        status(a, live, "Choosing what to look up…");
                        final StringBuilder plan = new StringBuilder();
                        String today = new java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US).format(new java.util.Date());
                        chat(PLAN + " Today is " + today + ": prefer recent coverage when the subject is current.", idea, 120, new Done() {
                            public void text(String all, boolean finished, String error) {
                                if (finished) plan.append(all);
                            }
                        });
                        for (String l : plan.toString().split("\n")) {
                            String q = l.replaceAll("^[\\s\\d.)*#-]+", "").replace("\"", "").trim();
                            if (q.length() > 3 && queries.size() < 3) queries.add(q);
                        }
                    }
                    if (queries.isEmpty()) queries.add(idea);
                    if (stopped) throw new Exception("stopped");
                    status(a, live, "Searching for:\n• " + android.text.TextUtils.join("\n• ", queries) + "\n\nThen reading the best pages…");
                    final ArrayList<Scout.Source> found = Scout.research(queries, idea, 6,
                            a.cfg.getInt("writer_per_page", 1500), a.cfg.getString("brave_key", ""));
                    a.ui.post(new Runnable() {
                        public void run() {
                            dlg.dismiss();
                            if (stopped) return;
                            sources(a, idea, ask, queries, found);
                        }
                    });
                } catch (final Exception e) {
                    a.ui.post(new Runnable() {
                        public void run() {
                            dlg.dismiss();
                            if (!stopped) a.alert("The research stopped", String.valueOf(e.getMessage()));
                        }
                    });
                }
            }
        }, "writer-research").start();
    }

    private static void status(MainActivity a, final TextView t, final String s) {
        a.ui.post(new Runnable() {
            public void run() {
                t.setText(s);
            }
        });
    }

    static ArrayList<Scout.Source> lastSources = new ArrayList<Scout.Source>();

    /**
     * The pause before writing: every page the songwriter read, with the exact passage it will be given.
     * Untick a page to leave it out, change the searches and look again, or write from what is ticked.
     */
    static void sources(final MainActivity a, final String idea, final String ask, ArrayList<String> queries,
                        final ArrayList<Scout.Source> found) {
        LinearLayout f = a.column();
        f.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8));
        TextView intro = a.text(found.isEmpty() ? "Nothing readable was found. Change the searches and look again, or write without research."
                : "This is what the songwriter read. Untick anything wrong or off topic; the song is written from what stays ticked.",
                MainActivity.DIM, 13);
        f.addView(intro);
        final EditText q = a.edit("Searches, one per line", 2);
        q.setText(android.text.TextUtils.join("\n", queries));
        f.addView(q, a.gap(8));
        final ArrayList<String> qs0 = queries;
        final AlertDialog[] self = new AlertDialog[1];
        // add a page by address, live or from the Wayback Machine
        final EditText addr = a.edit("Paste a web address to add it", 1);
        f.addView(addr, a.gap(8));
        LinearLayout ar = a.row();
        android.widget.Button readIt = a.chip("Read it");
        android.widget.Button older = a.chip("Wayback history");
        ar.addView(readIt);
        ar.addView(older);
        f.addView(ar, a.gap(4));
        readIt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String u = MainActivity.val(addr);
                if (!u.startsWith("http")) {
                    a.toast("Paste a full web address, starting with https://");
                    return;
                }
                addPage(a, self[0], idea, ask, qs0, found, u);
            }
        });
        older.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String u = MainActivity.val(addr);
                if (u.length() < 4) {
                    a.toast("Paste the address of the page whose history you want");
                    return;
                }
                wayback(a, self[0], idea, ask, qs0, found, u);
            }
        });
        for (final Scout.Source s : found) {
            LinearLayout card = a.column();
            card.setBackground(a.glass(MainActivity.EDGE));
            card.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(10));
            final android.widget.CheckBox keep = new android.widget.CheckBox(a);
            keep.setText(s.title);
            keep.setTextColor(MainActivity.TEXT);
            keep.setTextSize(15);
            keep.setChecked(s.keep);
            keep.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    s.keep = on;
                }
            });
            card.addView(keep);
            String when = s.published != null && s.published.length() >= 10 ? " · " + s.published.substring(0, 10) : "";
            card.addView(a.text(s.site + when, MainActivity.ACCENT, 12));
            final TextView ex = a.text(s.excerpt.replace("## ", ""), MainActivity.TEXT, 13);
            ex.setMaxLines(6);
            ex.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ex.setPadding(0, a.dp(6), 0, 0);
            ex.setOnClickListener(new View.OnClickListener() {
                boolean open;

                public void onClick(View v) {
                    open = !open;
                    ex.setMaxLines(open ? Integer.MAX_VALUE : 6);
                }
            });
            card.addView(ex);
            TextView link = a.text(s.url, Skin.FAINT, 11);
            link.setTextIsSelectable(true);
            card.addView(link);
            f.addView(card, a.gap(10));
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(f);
        AlertDialog.Builder b = new Lux.Builder(a).setTitle("What the songwriter read").setView(sv)
                .setNeutralButton("Search again", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ArrayList<String> qs = new ArrayList<String>();
                        for (String l : MainActivity.val(q).split("\n")) if (l.trim().length() > 0) qs.add(l.trim());
                        research(a, idea, ask, qs);
                    }
                })
                .setNegativeButton("Cancel", null);
        b.setPositiveButton(found.isEmpty() ? "Write without" : "Write with these", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                lastSources = found;
                String notes = Scout.notes(found);
                writeSong(a, notes.length() == 0 ? ask : ask + "\n\nResearch notes. Use these facts accurately (names, numbers, dates); "
                        + "do not invent facts beyond them, and do not cite the sources in the lyrics:\n\n" + notes);
            }
        });
        self[0] = b.show();
    }

    /** Reads one more page and shows the sources again with it on top. */
    private static void addPage(final MainActivity a, final AlertDialog current, final String idea, final String ask,
                                final ArrayList<String> queries, final ArrayList<Scout.Source> found, final String url) {
        a.toast("Reading the page…");
        new Thread(new Runnable() {
            public void run() {
                final Scout.Source s = Scout.readOne(url, idea, a.cfg.getInt("writer_per_page", 1500));
                a.ui.post(new Runnable() {
                    public void run() {
                        if (s.error != null) {
                            a.alert("That page could not be read", s.error);
                            return;
                        }
                        found.add(0, s);
                        if (current != null) current.dismiss();
                        sources(a, idea, ask, queries, found);
                    }
                });
            }
        }, "writer-add-page").start();
    }

    /** The page's saved copies in the Wayback Machine; the chosen one is read and added. */
    private static void wayback(final MainActivity a, final AlertDialog current, final String idea, final String ask,
                                final ArrayList<String> queries, final ArrayList<Scout.Source> found, final String url) {
        a.toast("Asking the Wayback Machine…");
        new Thread(new Runnable() {
            public void run() {
                ArrayList<Scout.Snapshot> list = null;
                String err = null;
                try {
                    list = Scout.snapshots(url);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final ArrayList<Scout.Snapshot> snaps = list;
                final String error = err;
                a.ui.post(new Runnable() {
                    public void run() {
                        if (error != null || snaps == null || snaps.isEmpty()) {
                            a.alert("Wayback Machine", error != null ? error : "It has no saved copies of that page.");
                            return;
                        }
                        String[] names = new String[snaps.size()];
                        for (int i = 0; i < snaps.size(); i++) names[i] = snaps.get(i).date + "   " + snaps.get(i).url;
                        new Lux.Builder(a).setTitle("Saved copies, newest first (" + snaps.size() + " changes)")
                                .setItems(names, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        addPage(a, current, idea, ask, queries, found, snaps.get(w).archived);
                                    }
                                }).setNegativeButton("Back", null).show();
                    }
                });
            }
        }, "writer-wayback").start();
    }

    /** The pages the last researched song was written from. */
    static void showSources(final MainActivity a) {
        if (lastSources.isEmpty()) {
            a.toast("No researched song yet in this session");
            return;
        }
        StringBuilder b = new StringBuilder();
        for (Scout.Source s : lastSources) {
            if (!s.keep) continue;
            b.append(s.title).append("\n").append(s.site);
            if (s.published != null && s.published.length() >= 10) b.append(" · ").append(s.published.substring(0, 10));
            b.append("\n").append(s.url).append("\n\n");
        }
        TextView t = a.text(b.toString().trim(), MainActivity.TEXT, 13);
        t.setTextIsSelectable(true);
        t.setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(8));
        ScrollView sv = new ScrollView(a);
        sv.addView(t);
        new Lux.Builder(a).setTitle("Sources").setView(sv).setPositiveButton("Close", null).show();
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
