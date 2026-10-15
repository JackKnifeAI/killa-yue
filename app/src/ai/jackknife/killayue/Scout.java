package ai.jackknife.killayue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Research for the songwriter: web search and page reading straight from the phone, through
 * libkillascout.so (engine/scout, Rust). No search server in between: the engines and the sites
 * are asked directly. Calls block, so they run on background threads.
 */
public class Scout {
    static native String search(String spec);

    static native String read(String spec);

    private static volatile Boolean loaded;

    static boolean available(Context c) {
        if (loaded != null) return loaded;
        boolean ok = new File(c.getApplicationInfo().nativeLibraryDir, "libkillascout.so").isFile();
        if (ok) {
            try {
                System.loadLibrary("killascout");
            } catch (Throwable e) {
                ok = false;
            }
        }
        loaded = ok;
        return ok;
    }

    /** One page the songwriter read: what it will be shown, and whether it is used. */
    static class Source {
        String url, site, title, published, snippet, excerpt, error;
        int chars;
        boolean keep = true;

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("url", url).put("site", site).put("title", title).put("published", published);
            } catch (Exception e) {
                // only strings
            }
            return o;
        }
    }

    /**
     * Searches for every query, merges the answers, and reads the best `pages` pages in parallel,
     * each cut to `perPage` characters about `topic`. Pages that cannot be read are left out.
     */
    static ArrayList<Source> research(ArrayList<String> queries, String topic, int pages, int perPage, String braveKey) {
        ArrayList<ArrayList<Source>> perQuery = new ArrayList<ArrayList<Source>>();
        HashSet<String> seen = new HashSet<String>();
        for (int qi = 0; qi < queries.size(); qi++) {
            ArrayList<Source> found = new ArrayList<Source>();
            perQuery.add(found);
            try {
                JSONObject spec = new JSONObject().put("query", queries.get(qi)).put("max", 8);
                if (braveKey != null && braveKey.length() > 0) spec.put("brave_key", braveKey);
                JSONArray r = new JSONObject(search(spec.toString())).optJSONArray("results");
                if (r == null) continue;
                for (int i = 0; i < r.length(); i++) {
                    JSONObject h = r.getJSONObject(i);
                    String u = h.optString("url");
                    if (u.toLowerCase(java.util.Locale.US).endsWith(".pdf") || !seen.add(u)) continue;
                    Source s = new Source();
                    s.url = u;
                    s.site = h.optString("site");
                    s.title = h.optString("title");
                    s.snippet = h.optString("snippet");
                    found.add(s);
                }
            } catch (Exception e) {
                // one failed search does not stop the others
            }
        }
        // every query's best result first, then every query's second, and so on
        final ArrayList<Source> pick = new ArrayList<Source>();
        for (int pos = 0; pick.size() < pages * 2; pos++) {
            boolean any = false;
            for (ArrayList<Source> l : perQuery) {
                if (pos < l.size() && pick.size() < pages * 2) {
                    pick.add(l.get(pos));
                    any = true;
                }
            }
            if (!any) break;
        }
        final ArrayList<Source> out = new ArrayList<Source>();
        final String t = topic;
        final int budget = perPage;
        Thread[] th = new Thread[pick.size()];
        for (int i = 0; i < pick.size(); i++) {
            final Source s = pick.get(i);
            th[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        JSONObject r = new JSONObject(read(new JSONObject().put("url", s.url).put("topic", t).put("budget", budget).toString()));
                        if (r.has("error")) {
                            s.error = r.optString("error");
                            return;
                        }
                        if (r.optString("title").length() > 0) s.title = r.optString("title");
                        s.site = r.optString("site", s.site);
                        s.published = r.optString("published");
                        s.excerpt = r.optString("excerpt");
                        s.chars = r.optInt("chars");
                    } catch (Exception e) {
                        s.error = String.valueOf(e.getMessage());
                    }
                }
            }, "scout-read");
            th[i].start();
        }
        for (int i = 0; i < th.length; i++) {
            try {
                th[i].join(30000);
            } catch (InterruptedException e) {
                break;
            }
        }
        for (int i = 0; i < pick.size() && out.size() < pages; i++) {
            Source s = pick.get(i);
            if (s.error == null && s.excerpt != null && s.excerpt.length() > 200) out.add(s);
        }
        return out;
    }

    /** The research as the songwriter gets it: numbered sources with what each one says. */
    static String notes(ArrayList<Source> sources) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Source s : sources) {
            if (!s.keep) continue;
            n++;
            b.append("[").append(n).append("] ").append(s.title).append(" (").append(s.site);
            if (s.published != null && s.published.length() >= 10) b.append(", ").append(s.published.substring(0, 10));
            b.append(")\n").append(s.excerpt.replace("## ", "")).append("\n\n");
        }
        return b.toString().trim();
    }
}
