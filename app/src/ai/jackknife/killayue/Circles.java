package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.security.KeyPair;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The circles this phone is in, and the one it is in the room of now. A circle's key, and this phone's own key for
 * it, stay in this app's private storage. This phone carries the circles it started and none other.
 */
final class Circles {
    private Circles() {}
    private static final String PREFS = "circles";

    /** A circle this phone is in. */
    static final class Joined {
        final HammerCircle circle;
        final KeyPair me;
        final boolean started;
        Joined(HammerCircle circle, KeyPair me, boolean started) {
            this.circle = circle;
            this.me = me;
            this.started = started;
        }
    }

    static synchronized List<Joined> joined(Context c) {
        List<Joined> out = new ArrayList<Joined>();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences(PREFS, 0).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++)
                try {
                    JSONObject o = a.getJSONObject(i);
                    HammerCircle circle = HammerCircle.read(new DataInputStream(new ByteArrayInputStream(
                        android.util.Base64.decode(o.getString("record"), 0))), o.getString("secret"));
                    out.add(new Joined(circle, HammerCircle.loadKey(o.getString("me")), o.optBoolean("started")));
                } catch (Exception unreadable) {
                }
        } catch (Exception none) {
        }
        return out;
    }
    static Joined get(Context c, String id) {
        for (Joined j : joined(c))
            if (j.circle.id.equals(id))
                return j;
        return null;
    }
    /** Keeps a circle, or its newer record; the first record seen of it is the one whose starter is trusted. */
    static synchronized Joined save(Context c, HammerCircle circle, KeyPair me, boolean started) throws Exception {
        JSONArray old = new JSONArray(c.getSharedPreferences(PREFS, 0).getString("list", "[]")), next = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.getJSONObject(i);
            if (!o.optString("id").equals(circle.id))
                next.put(o);
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        circle.write(new DataOutputStream(b));
        JSONObject o = new JSONObject();
        o.put("id", circle.id).put("record", android.util.Base64.encodeToString(b.toByteArray(), 2))
            .put("secret", circle.secret).put("me", HammerCircle.saveKey(me)).put("started", started);
        next.put(o);
        c.getSharedPreferences(PREFS, 0).edit().putString("list", next.toString()).apply();
        carryMine(c);
        return new Joined(circle, me, started);
    }
    static synchronized void leave(Context c, String id) throws Exception {
        JSONArray old = new JSONArray(c.getSharedPreferences(PREFS, 0).getString("list", "[]")), next = new JSONArray();
        for (int i = 0; i < old.length(); i++)
            if (!old.getJSONObject(i).optString("id").equals(id))
                next.put(old.getJSONObject(i));
        c.getSharedPreferences(PREFS, 0).edit().putString("list", next.toString()).apply();
        HammerAndroid.store(c).circles.forget(id);
        carryMine(c);
    }
    /** This phone carries exactly the circles it started. */
    static void carryMine(Context c) {
        try {
            Set<String> mine = new HashSet<String>();
            for (Joined j : joined(c))
                if (j.started)
                    mine.add(j.circle.id);
            HammerAndroid.store(c).circles.only = mine;
        } catch (Exception noStore) {
        }
    }
    static String name(Context c) {
        String n = c.getSharedPreferences(PREFS, 0).getString("name", "").trim();
        return n.length() > 40 ? n.substring(0, 40) : n;
    }
    static void name(Context c, String n) { c.getSharedPreferences(PREFS, 0).edit().putString("name", n).apply(); }
    static boolean muted(Context c, String key) {
        return c.getSharedPreferences(PREFS, 0).getStringSet("muted", new HashSet<String>()).contains(key);
    }
    static void mute(Context c, String key, boolean on) {
        Set<String> m = new HashSet<String>(c.getSharedPreferences(PREFS, 0).getStringSet("muted", new HashSet<String>()));
        if (on)
            m.add(key);
        else
            m.remove(key);
        c.getSharedPreferences(PREFS, 0).edit().putStringSet("muted", m).apply();
    }

    /**
     * Starts a circle carried by this phone and up to three of the given hosts that agree to: first signed naming
     * only this phone, handed to the hosts, then signed again naming those that took it.
     */
    static Joined start(Context c, String title, String about, boolean open, List<Hammer.Card> candidates, Hammer.Job job)
        throws Exception {
        Hammer.require(!title.trim().isEmpty(), "Give the circle a name");
        Hammer.require(title.length() <= HammerCircle.TITLE && about.length() <= HammerCircle.ABOUT,
                       "Keep the name to 80 letters and the words to 280");
        HammerStore store = HammerAndroid.store(c);
        Hammer.Card own = Hammer.Card.p2p(HammerAndroid.network(c), HammerAndroid.pin(), "");
        KeyPair me = HammerCircle.memberKey();
        HammerCircle circle = HammerCircle.start(title.trim(), about.trim(), open);
        circle.hosts.add(own.encode());
        circle.sign(me);
        save(c, circle, me, true);
        store.circles.carry(circle);
        List<Hammer.Card> took = new ArrayList<Hammer.Card>();
        for (Hammer.Card h : candidates) {
            if (took.size() >= HammerCircle.HOSTS - 1 || job.cancelled)
                break;
            if (h.isPrivate() || h.pin.equals(own.pin))
                continue;
            try {
                if (Hammer.carryCircle(h, circle, job))
                    took.add(h);
            } catch (Exception refusedOrOld) {
            }
        }
        if (!took.isEmpty()) {
            for (Hammer.Card h : took)
                circle.hosts.add(h.encode());
            circle.revision = Math.max(circle.revision + 1, System.currentTimeMillis());
            circle.sign(me);
            store.circles.carry(circle);
            for (Hammer.Card h : took)
                try {
                    Hammer.carryCircle(h, circle, job);
                } catch (Exception gone) {
                }
            save(c, circle, me, true);
        }
        return get(c, circle.id);
    }

    /** Joins from an open record or an invitation, checking the record against the key it came with. */
    static Joined join(Context c, HammerCircle circle) throws Exception {
        Joined old = get(c, circle.id);
        if (old != null)
            return old;
        circle.validate();
        Hammer.require(Hammer.digest(circle.secret) && HammerCircle.idOf(circle.secret).equals(circle.id),
                       "This invitation's key doesn't match the circle");
        return save(c, circle, HammerCircle.memberKey(), false);
    }
}
