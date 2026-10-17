package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mixer-only state; source clips remain in Project. Groups cannot feed other groups or send returns. */
public final class Mixer {
    public static final int MAX_GROUPS = 8, MAX_SLOTS = 8, STRIDE = 32;
    public static final String MASTER = "master", REVERB = "reverb", DELAY = "delay";
    public int nextId = 1;
    public final ArrayList<String> groups = new ArrayList<String>();
    public final LinkedHashMap<String, Strip> strips = new LinkedHashMap<String, Strip>();

    public static final class Slot {
        public String id;
        public int kind; // 1 trim (dB), 2 stereo width (0..2), 3 polarity inversion
        public float value;
        public boolean bypass;
    }

    public static final class Strip {
        public String name = "", route = "";
        public float gainDb, pan, reverb, delay;
        public boolean mute, solo;
        public final ArrayList<Slot> chain = new ArrayList<Slot>();
    }

    public Mixer() {
        strips.put(MASTER, new Strip());
        strips.put(REVERB, new Strip());
        strips.put(DELAY, new Strip());
    }

    /** Reading a default must not silently edit a project or create an undo entry. */
    public Strip get(String id) {
        Strip s = strips.get(id);
        return s == null ? new Strip() : s;
    }

    public Strip edit(String id) {
        Strip s = strips.get(id);
        if (s == null) {
            s = new Strip();
            strips.put(id, s);
        }
        return s;
    }

    public String addGroup(String name) {
        if (groups.size() >= MAX_GROUPS) throw new IllegalArgumentException("The mixer has room for eight groups.");
        String id = "g" + nextId++;
        groups.add(id);
        edit(id).name = name;
        return id;
    }

    public void removeGroup(String id) {
        if (!groups.remove(id)) throw new IllegalArgumentException("That group is no longer here.");
        strips.remove(id);
        for (Strip s : strips.values()) if (id.equals(s.route)) s.route = "";
    }

    public Slot addSlot(String strip, int kind) {
        Strip s = edit(strip);
        if (s.chain.size() >= MAX_SLOTS) throw new IllegalArgumentException("A rack holds eight effects.");
        Slot f = new Slot();
        f.id = "e" + nextId++;
        f.kind = kind;
        f.value = kind == 2 ? 1 : 0;
        s.chain.add(f);
        return f;
    }

    public boolean isGroup(String id) { return groups.contains(id); }
    public boolean sends(String id) { return id.startsWith("t") || isGroup(id); }

    public void validate(Project p) {
        if (groups.size() > MAX_GROUPS || nextId < 1 || nextId > 999999999) bad("Invalid mixer size or next ID.");
        HashSet<String> allowed = new HashSet<String>(), ids = new HashSet<String>();
        allowed.add(MASTER); allowed.add(REVERB); allowed.add(DELAY);
        for (Track t : p.tracks) allowed.add(t.id);
        for (String g : groups) {
            identifier(g, "g", ids);
            if (!strips.containsKey(g)) bad("A mixer group has no strip.");
            allowed.add(g);
        }
        if (strips.size() > allowed.size()) bad("The mixer has strips for missing tracks.");
        for (Map.Entry<String, Strip> entry : strips.entrySet()) {
            String id = entry.getKey();
            Strip s = entry.getValue();
            if (!allowed.contains(id) || s == null) bad("The mixer refers to a missing strip.");
            if (s.name == null || s.route == null) bad("A mixer strip has no name or route.");
            range(s.gainDb, -60, 12, "strip level");
            range(s.pan, -1, 1, "strip pan");
            range(s.reverb, 0, 1, "reverb send");
            range(s.delay, 0, 1, "delay send");
            if (!s.route.isEmpty() && (!id.startsWith("t") || !groups.contains(s.route))) bad("Only tracks can feed a group.");
            if (!sends(id) && (s.reverb != 0 || s.delay != 0)) bad("Returns cannot feed themselves.");
            if (s.chain.size() > MAX_SLOTS) bad("A rack holds eight effects.");
            for (Slot f : s.chain) {
                if (f == null) bad("An effect slot is missing.");
                identifier(f.id, "e", ids);
                if (f.kind == 1) range(f.value, -24, 24, "trim");
                else if (f.kind == 2) range(f.value, 0, 2, "stereo width");
                else if (f.kind == 3) range(f.value, 0, 0, "polarity");
                else bad("Unknown mixer effect.");
            }
        }
        for (String required : new String[] {MASTER, REVERB, DELAY})
            if (!strips.containsKey(required)) bad("A mixer return or master is missing.");
    }

    private void identifier(String id, String prefix, HashSet<String> ids) {
        if (id == null || !id.matches(prefix + "[1-9][0-9]{0,8}") || !ids.add(id)) bad("Invalid or repeated mixer ID.");
        if (Integer.parseInt(id.substring(1)) >= nextId) bad("A mixer ID would be reused.");
    }

    static void range(float v, float lo, float hi, String what) {
        if (!(v >= lo && v <= hi)) bad("The " + what + " is outside its range.");
    }
    static void bad(String why) { throw new IllegalArgumentException(why); }

    public LinkedHashMap<String, Object> json() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("nextId", nextId);
        out.put("groups", new ArrayList<String>(groups));
        LinkedHashMap<String, Object> all = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Strip> e : strips.entrySet()) all.put(e.getKey(), stripJson(e.getValue()));
        out.put("strips", all);
        return out;
    }

    static LinkedHashMap<String, Object> stripJson(Strip s) {
        LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
        j.put("name", s.name); j.put("route", s.route);
        j.put("gainDb", s.gainDb); j.put("pan", s.pan);
        j.put("mute", s.mute); j.put("solo", s.solo);
        j.put("reverb", s.reverb); j.put("delay", s.delay);
        ArrayList<Object> chain = new ArrayList<Object>();
        for (Slot f : s.chain) {
            LinkedHashMap<String, Object> k = new LinkedHashMap<String, Object>();
            k.put("id", f.id); k.put("kind", f.kind); k.put("value", f.value); k.put("bypass", f.bypass);
            chain.add(k);
        }
        j.put("chain", chain);
        return j;
    }

    public static Mixer read(Object value) {
        if (value == null) bad("The mixer settings are missing.");
        Map<?, ?> j = map(value);
        Mixer m = new Mixer();
        m.nextId = integer(j.get("nextId"));
        for (Object id : list(j.get("groups"))) m.groups.add(string(id));
        m.strips.clear();
        for (Map.Entry<?, ?> e : map(j.get("strips")).entrySet()) m.strips.put(string(e.getKey()), readStrip(e.getValue()));
        return m;
    }

    static Strip readStrip(Object value) {
        Map<?, ?> j = map(value); Strip s = new Strip();
        s.name = string(j.get("name")); s.route = string(j.get("route"));
        s.gainDb = number(j.get("gainDb")); s.pan = number(j.get("pan"));
        s.mute = bool(j.get("mute")); s.solo = bool(j.get("solo"));
        s.reverb = number(j.get("reverb")); s.delay = number(j.get("delay"));
        for (Object o : list(j.get("chain"))) {
            Map<?, ?> k = map(o); Slot f = new Slot();
            f.id = string(k.get("id")); f.kind = integer(k.get("kind"));
            f.value = number(k.get("value")); f.bypass = bool(k.get("bypass")); s.chain.add(f);
        }
        return s;
    }

    public Mixer copy() { return read(Json.parse(Json.write(json()))); }

    /** Fixed layout shared with native MixerGraph; all limits are checked before JNI sees it. */
    public float[] nativeData(Project p) {
        validate(p);
        if (p.tracks.size() > 64) bad("The Studio plays up to 64 tracks.");
        float[] out = new float[2 + 75 * STRIDE];
        out[0] = p.tracks.size(); out[1] = groups.size();
        for (int i = 0; i < 75; i++) out[2 + i * STRIDE + 6] = -1;
        for (int i = 0; i < p.tracks.size(); i++) pack(out, i, get(p.tracks.get(i).id));
        for (int i = 0; i < groups.size(); i++) pack(out, 64 + i, get(groups.get(i)));
        pack(out, 72, get(REVERB)); pack(out, 73, get(DELAY)); pack(out, 74, get(MASTER));
        return out;
    }

    private void pack(float[] out, int i, Strip s) {
        int n = 2 + i * STRIDE;
        out[n] = s.gainDb; out[n + 1] = s.pan; out[n + 2] = s.mute ? 1 : 0; out[n + 3] = s.solo ? 1 : 0;
        out[n + 4] = s.reverb; out[n + 5] = s.delay; out[n + 6] = groups.indexOf(s.route); out[n + 7] = s.chain.size();
        for (int k = 0; k < s.chain.size(); k++) {
            Slot f = s.chain.get(k);
            out[n + 8 + k * 3] = f.kind; out[n + 9 + k * 3] = f.value; out[n + 10 + k * 3] = f.bypass ? 1 : 0;
        }
    }

    /** Isolated stems retain track inserts but omit groups, sends and master processing. */
    public Mixer stem(String track) {
        Mixer out = new Mixer();
        out.nextId = nextId;
        if (track != null && strips.containsKey(track)) {
            Strip s = readStrip(stripJson(get(track)));
            s.route = "";
            s.reverb = s.delay = 0;
            out.strips.put(track, s);
        }
        return out;
    }

    public long tailFrames(int rate) {
        for (Strip s : strips.values()) if (s.reverb > 0 || s.delay > 0) return rate * 4L;
        return 0;
    }

    private static Map<?, ?> map(Object o) { if (!(o instanceof Map)) bad("Damaged mixer object."); return (Map<?, ?>) o; }
    private static List<?> list(Object o) { if (!(o instanceof List)) bad("Damaged mixer list."); return (List<?>) o; }
    private static String string(Object o) { if (!(o instanceof String)) bad("Damaged mixer text."); return (String) o; }
    private static boolean bool(Object o) { if (!(o instanceof Boolean)) bad("Damaged mixer switch."); return (Boolean) o; }
    private static float number(Object o) { if (!(o instanceof Number)) bad("Damaged mixer number."); return ((Number) o).floatValue(); }
    private static int integer(Object o) {
        if (!(o instanceof Number)) bad("Damaged mixer integer.");
        double d = ((Number) o).doubleValue();
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) bad("Damaged mixer integer.");
        return (int) d;
    }
}
