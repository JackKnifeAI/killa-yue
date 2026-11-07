package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.Hammer;
import ai.jackknife.killayue.peer.HammerCommunity;
import ai.jackknife.killayue.peer.HammerLyrics;
import ai.jackknife.killayue.peer.HammerProfile;
import ai.jackknife.killayue.peer.HammerStore;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Remix chains. "Remix" on a shared song opens Create with its style and lyrics; the song made from it keeps its
 * parent in the Library ("_remix_of": the original's signed record and where it is hosted). When the remix is shared
 * publicly, two signed posts link the songs (HammerCommunity, kinds "source" and "remix"): one on this phone's host
 * beside the remix, naming the original, and one on the original's host beside it, naming the remix, so the original
 * artist sees it. The original's host may be a phone that is off: that post is kept and tried again later.
 */
final class Remixes {
    static final String ACTION = "ai.jackknife.killayue.REMIX";

    private Remixes() {
    }

    // ---- the parent, as kept with the remix ----

    static JSONObject parent(Hammer.Song original, Hammer.Card host, String artist) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        original.write(new DataOutputStream(b));
        JSONObject p = new JSONObject();
        p.put("id", original.id);
        p.put("title", original.title);
        p.put("artist", artist == null ? "" : artist);
        p.put("key", original.key);
        p.put("card", host.isPrivate() ? "" : host.encode());   // a private circle's invitation is never passed on
        p.put("song", Base64.getEncoder().encodeToString(b.toByteArray()));
        return p;
    }

    static Hammer.Song original(JSONObject p) throws Exception {
        Hammer.Song s = Hammer.Song.read(new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(p.getString("song")))));
        s.validate();
        return s;
    }

    /** "Remix of “Night” by ddeer", or "" for a song that is not a remix. */
    static String credit(JSONObject meta) {
        JSONObject p = meta == null ? null : meta.optJSONObject("_remix_of");
        if (p == null) return "";
        String artist = p.optString("artist");
        return "Remix of “" + p.optString("title", "a shared song") + "”" + (artist.length() > 0 ? " by " + artist : "");
    }

    // ---- starting a remix ----

    /** The lyrics as a Create form takes them: section tags kept, LRC times and metadata dropped. */
    static String formLyrics(HammerLyrics l) {
        if (l == null) return "";
        StringBuilder b = new StringBuilder();
        for (String raw : l.text.split("\r?\n")) {
            String line = raw.replaceAll("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]", "").trim();
            if (line.matches("\\[[a-z]+:.*\\]")) continue;   // [ar:...], [ti:...]
            b.append(line).append('\n');
        }
        return b.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Opens Create with the song's style and lyrics; the next song made remembers where it came from. */
    static void start(Context c, Hammer.Song original, Hammer.Card host, String artist, HammerLyrics lyrics) throws Exception {
        JSONObject r = new JSONObject();
        r.put("style", original.tags);
        r.put("lyrics", formLyrics(lyrics));
        r.put("_title", original.title + " (remix)");
        r.put("_remix_of", parent(original, host, artist));
        c.startActivity(new Intent(c, MainActivity.class).setAction(ACTION).putExtra("recipe", r.toString())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // ---- linking a shared remix ----

    private static SharedPreferences pending(Context c) {
        return c.getSharedPreferences("remix_links", 0);
    }

    /** Called when a Library song has just been shared publicly. Runs in the background. */
    static void shared(final Context c, final Hammer.Song mine, JSONObject meta) {
        final JSONObject p = meta == null ? null : meta.optJSONObject("_remix_of");
        if (p == null) return;
        try {
            JSONObject job = new JSONObject();
            job.put("parent", p);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            mine.write(new DataOutputStream(b));
            job.put("mine", Base64.getEncoder().encodeToString(b.toByteArray()));
            job.put("source_done", false);
            pending(c).edit().putString(mine.id, job.toString()).apply();
        } catch (Exception e) {
            return;
        }
        retry(c);
    }

    private static volatile boolean retrying;

    /** Sends the links not yet delivered (the original's phone may have been off). Safe to call often. */
    static void retry(final Context c) {
        if (retrying || pending(c).getAll().isEmpty()) return;
        retrying = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    for (Map.Entry<String, ?> e : new ArrayList<Map.Entry<String, ?>>(pending(c).getAll().entrySet())) {
                        try {
                            if (deliver(c, new JSONObject((String) e.getValue()), e.getKey())) pending(c).edit().remove(e.getKey()).apply();
                        } catch (Exception bad) {
                            EngineService.addLog("[Remix] Link for " + e.getKey().substring(0, Math.min(8, e.getKey().length())) + " not sent yet: " + bad.getMessage());
                        }
                    }
                } finally {
                    retrying = false;
                }
            }
        }, "remix-links").start();
    }

    /** True when both posts are in place (or can never be: the parent came from a private circle). */
    private static boolean deliver(Context c, JSONObject job, String id) throws Exception {
        JSONObject p = job.getJSONObject("parent");
        Hammer.Song original = original(p);
        Hammer.Song mine = Hammer.Song.read(new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(job.getString("mine")))));
        HammerStore store = HammerAndroid.store(c);
        String card = p.optString("card");
        String name = ownName(store);
        String self = Hammer.Card.p2p(HammerAndroid.network(c), HammerAndroid.pin(), "").encode();
        if (!job.optBoolean("source_done")) {
            // beside the remix, on this phone's host: where it came from
            HammerCommunity.Post source = new HammerCommunity.Post();
            source.target = mine.id;
            source.kind = "source";
            source.remix = original;
            source.name = name;
            source.body = "Remix of " + original.title;
            source.host = card.length() > 0 ? card : self;
            source.sign(HammerAndroid.identity());
            store.community.post(source, "");
            job.put("source_done", true);
            pending(c).edit().putString(id, job.toString()).apply();
        }
        if (card.length() == 0) return true;   // nowhere public to tell the original artist
        // beside the original, on its host: the remix, so its artist and listeners find it
        HammerCommunity.Post link = new HammerCommunity.Post();
        link.target = original.id;
        link.kind = "remix";
        link.remix = mine;
        link.name = name;
        link.body = "Remix: " + mine.title;
        link.host = self;
        link.sign(HammerAndroid.identity());
        Hammer.Card host = Hammer.Card.parse(card);
        HammerCommunity.post(host, link, new Hammer.Job());
        EngineService.addLog("[Remix] Linked " + mine.title + " to " + original.title);
        return true;
    }

    private static String ownName(HammerStore store) {
        try {
            String key = Hammer.publicKey(HammerAndroid.identity().getPublic());
            for (HammerProfile p : store.publicObjects.profiles())
                if (p.key.equals(key) && p.name.length() > 0) return p.name;
        } catch (Exception e) {
            // no profile yet
        }
        return "";
    }

    // ---- what others made from your songs ----

    static final class Child {
        Hammer.Song original, remix;
        String by, host;
    }

    /** Remixes linked beside the songs this phone hosts publicly. */
    static List<Child> ofMine(Context c) throws Exception {
        HammerStore store = HammerAndroid.store(c);
        String key = Hammer.publicKey(HammerAndroid.identity().getPublic());
        List<Child> out = new ArrayList<Child>();
        for (HammerStore.Hosted h : store.hosted()) {
            if (!h.publicSong || !h.song.key.equals(key)) continue;
            for (HammerCommunity.Post p : store.community.list("track", h.song.id, "")) {
                if (!"remix".equals(p.kind) || p.remix == null) continue;
                Child ch = new Child();
                ch.original = h.song;
                ch.remix = p.remix;
                ch.by = p.name;
                ch.host = p.host;
                out.add(ch);
            }
        }
        return out;
    }
}
