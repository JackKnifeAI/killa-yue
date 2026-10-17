package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Studio project: tracks of clips on a timeline counted in frames at the project's rate. Every track and
 * clip has an ID that never changes ("t1", "c2", ...), so undo, redo and the engine can always find it.
 * Plain Java with no Android in it; saved as JSON by ProjectStore.
 */
public final class Project {
    public static final int VERSION = 5;

    public int version = VERSION;
    public String name;
    public int rate;
    public int nextId = 1;
    public float masterDb;
    public final ArrayList<Track> tracks = new ArrayList<Track>();
    public String sourceSong, sourceTitle;  // the library song it was made from (its meta file name), or null
    public long loopStart, loopEnd;         // the loop region in frames; loopOn plays it round
    public boolean loopOn;
    public Performance performance = new Performance();
    public Mixer mixer = new Mixer();

    public Project(String name, int rate) {
        this.name = name;
        this.rate = rate;
    }

    public String newId(String prefix) {
        return prefix + nextId++;
    }

    public Track addTrack(String trackName) {
        Track t = new Track();
        t.id = newId("t");
        t.name = trackName;
        t.color = Track.PALETTE[tracks.size() % Track.PALETTE.length];
        tracks.add(t);
        return t;
    }

    public Clip addClip(Track t, String file, long start, long offset, long length) {
        Clip c = new Clip();
        c.id = newId("c");
        c.file = file;
        c.start = start;
        c.offset = offset;
        c.length = length;
        t.clips.add(c);
        return c;
    }

    public Track track(String id) {
        for (Track t : tracks) if (t.id.equals(id)) return t;
        return null;
    }

    public Clip clip(String id) {
        for (Track t : tracks) for (Clip c : t.clips) if (c.id.equals(id)) return c;
        return null;
    }

    /** Group solo includes its tracks; track solo passes through its group. Either mute wins over solo. */
    public boolean audible(Track t) {
        Mixer.Strip strip = mixer.strips.get(t.id);
        Mixer.Strip group = strip == null ? null : mixer.strips.get(strip.route);
        if (t.mute || (group != null && group.mute)) return false;
        boolean solo = false;
        for (Track o : tracks) solo |= o.solo;
        for (String g : mixer.groups) solo |= mixer.get(g).solo;
        return !solo || t.solo || (group != null && group.solo);
    }

    /** Throws IllegalArgumentException, in words a reader can act on, for anything a project must never hold. */
    public void validate() {
        if (version != VERSION) throw new IllegalArgumentException("This project was made by a newer version of the app (format " + version + ").");
        if (loopStart < 0 || loopEnd < loopStart || (loopOn && loopEnd == loopStart))
            throw new IllegalArgumentException("The loop region " + loopStart + " to " + loopEnd + " is not possible.");
        if (rate < 8000 || rate > 384000) throw new IllegalArgumentException("The project's sample rate " + rate + " is not possible.");
        if (name == null) throw new IllegalArgumentException("The project has no name.");
        finite("master level", masterDb);
        performance.validate();
        mixer.validate(this);
        HashSet<String> ids = new HashSet<String>();
        for (Track t : tracks) {
            id(ids, t.id);
            if (t.name == null) throw new IllegalArgumentException("Track " + t.id + " has no name.");
            finite("track " + t.id + " level", t.gainDb);
            if (!(t.pan >= -1f && t.pan <= 1f)) throw new IllegalArgumentException("Track " + t.id + " is panned beyond the edge (" + t.pan + ").");
            for (Clip c : t.clips) {
                id(ids, c.id);
                finite("clip " + c.id + " level", c.gainDb);
                if (c.start < 0 || c.offset < 0 || c.length < 0) throw new IllegalArgumentException("Clip " + c.id + " has a negative position or length.");
                if (c.start > Long.MAX_VALUE - c.length || c.offset > Long.MAX_VALUE - c.length || c.fadeIn < 0 || c.fadeOut < 0
                    || c.fadeIn > c.length || c.fadeOut > c.length)
                    throw new IllegalArgumentException("Invalid clip range or fade.");
                if (!insideProject(c.file)) throw new IllegalArgumentException("Clip " + c.id + " points outside the project folder (" + c.file + ").");
            }
        }
        if (nextId < 1 || nextId > 999999999) throw new IllegalArgumentException("The project's next ID " + nextId + " is out of range.");
        for (String id : ids) {
            int n = Integer.parseInt(id.substring(1));
            if (n >= nextId) throw new IllegalArgumentException("The project's next ID " + nextId + " would repeat " + id + ".");
        }
    }

    /** A relative path that stays inside the project folder: no leading /, no backslash, no ".." segment. */
    static boolean insideProject(String path) {
        if (path == null || path.length() == 0 || path.startsWith("/") || path.contains("\\")) return false;
        for (String seg : path.split("/")) if (seg.equals("..")) return false;
        return true;
    }

    private static void finite(String what, float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) throw new IllegalArgumentException("The " + what + " is not a number.");
    }

    private static void id(HashSet<String> ids, String id) {
        if (id == null || !id.matches("[tc][1-9][0-9]{0,8}")) throw new IllegalArgumentException("\"" + id + "\" is not a track or clip ID.");
        if (!ids.add(id)) throw new IllegalArgumentException("Two things share the ID " + id + ".");
    }

    // ---------- JSON ----------

    public LinkedHashMap<String, Object> toJson() {
        LinkedHashMap<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("version", Integer.valueOf(version));
        p.put("name", name);
        p.put("rate", Integer.valueOf(rate));
        p.put("nextId", Integer.valueOf(nextId));
        p.put("masterDb", Float.valueOf(masterDb));
        if (sourceSong != null) p.put("sourceSong", sourceSong);
        if (sourceTitle != null) p.put("sourceTitle", sourceTitle);
        p.put("loopStart", Long.valueOf(loopStart));
        p.put("loopEnd", Long.valueOf(loopEnd));
        p.put("loopOn", Boolean.valueOf(loopOn));
        ArrayList<Object> ts = new ArrayList<Object>();
        for (Track t : tracks) ts.add(trackJson(t));
        p.put("tracks", ts);
        p.put("performance", performance.json());
        p.put("mixer", mixer.json());
        return p;
    }

    public static Project fromJson(Object json) {
        Map<?, ?> p = map(json, "the project");
        int v = (int) num(p, "version", false);
        if (v > VERSION) throw new IllegalArgumentException("This project was made by a newer version of the app (format " + v + ").");
        if (v < 1) throw new IllegalArgumentException("The project file is damaged: format " + v + ".");
        if (v < VERSION) p = Migrate.toCurrent(p, v);
        Project out = new Project(str(p, "name"), (int) num(p, "rate", false));
        out.version = (int) num(p, "version", false);
        out.nextId = (int) num(p, "nextId", false);
        out.masterDb = (float) num(p, "masterDb", true);
        if (p.get("sourceSong") != null) out.sourceSong = str(p, "sourceSong");
        if (p.get("sourceTitle") != null) out.sourceTitle = str(p, "sourceTitle");
        out.loopStart = (long) num(p, "loopStart", false);
        out.loopEnd = (long) num(p, "loopEnd", false);
        out.loopOn = bool(p, "loopOn");
        out.performance = Performance.read(p.get("performance"));
        for (Object to : list(p, "tracks")) out.tracks.add(trackFromJson(to));
        out.mixer = Mixer.read(p.get("mixer"));
        out.validate();
        return out;
    }

    /** A track and its clips as JSON (also used by the undo history). */
    static LinkedHashMap<String, Object> trackJson(Track t) {
        LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
        j.put("id", t.id);
        j.put("name", t.name);
        j.put("gainDb", Float.valueOf(t.gainDb));
        j.put("pan", Float.valueOf(t.pan));
        j.put("mute", Boolean.valueOf(t.mute));
        j.put("solo", Boolean.valueOf(t.solo));
        j.put("color", Integer.valueOf(t.color));
        if (t.role != null) j.put("role", t.role);
        ArrayList<Object> cs = new ArrayList<Object>();
        for (Clip c : t.clips) {
            LinkedHashMap<String, Object> k = new LinkedHashMap<String, Object>();
            k.put("id", c.id);
            k.put("file", c.file);
            k.put("start", Long.valueOf(c.start));
            k.put("offset", Long.valueOf(c.offset));
            k.put("length", Long.valueOf(c.length));
            k.put("gainDb", Float.valueOf(c.gainDb));
            k.put("fadeIn", c.fadeIn);
            k.put("fadeOut", c.fadeOut);
            cs.add(k);
        }
        j.put("clips", cs);
        return j;
    }

    static Track trackFromJson(Object to) {
        Map<?, ?> tj = map(to, "a track");
        Track t = new Track();
        t.id = str(tj, "id");
        t.name = str(tj, "name");
        t.gainDb = (float) num(tj, "gainDb", true);
        t.pan = (float) num(tj, "pan", true);
        t.mute = bool(tj, "mute");
        t.solo = bool(tj, "solo");
        // older saves (format 1, and tracks kept in an old undo history) have no colour or role
        if (tj.get("color") != null) t.color = (int) num(tj, "color", false);
        if (tj.get("role") != null) t.role = str(tj, "role");
        for (Object co : list(tj, "clips")) {
            Map<?, ?> cj = map(co, "a clip");
            Clip c = new Clip();
            c.id = str(cj, "id");
            c.file = str(cj, "file");
            c.start = (long) num(cj, "start", false);
            c.offset = (long) num(cj, "offset", false);
            c.length = (long) num(cj, "length", false);
            c.gainDb = (float) num(cj, "gainDb", true);
            c.fadeIn = cj.get("fadeIn") == null ? 0 : (long) num(cj, "fadeIn", false);
            c.fadeOut = cj.get("fadeOut") == null ? 0 : (long) num(cj, "fadeOut", false);
            t.clips.add(c);
        }
        return t;
    }

    public String toText() {
        return Json.write(toJson());
    }

    public static Project fromText(String text) {
        return fromJson(Json.parse(text));
    }

    public Project copy() {
        return fromText(toText());
    }

    private static Map<?, ?> map(Object o, String what) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("The project file is damaged: " + what + " is not an object.");
        return (Map<?, ?>) o;
    }

    private static List<?> list(Map<?, ?> m, String key) {
        Object o = m.get(key);
        if (!(o instanceof List)) throw new IllegalArgumentException("The project file is damaged: \"" + key + "\" is not a list.");
        return (List<?>) o;
    }

    private static String str(Map<?, ?> m, String key) {
        Object o = m.get(key);
        if (!(o instanceof String)) throw new IllegalArgumentException("The project file is damaged: \"" + key + "\" is not text.");
        return (String) o;
    }

    private static boolean bool(Map<?, ?> m, String key) {
        Object o = m.get(key);
        if (!(o instanceof Boolean)) throw new IllegalArgumentException("The project file is damaged: \"" + key + "\" is not true or false.");
        return ((Boolean) o).booleanValue();
    }

    private static double num(Map<?, ?> m, String key, boolean fractional) {
        Object o = m.get(key);
        if (!(o instanceof Number)) throw new IllegalArgumentException("The project file is damaged: \"" + key + "\" is not a number.");
        double d = ((Number) o).doubleValue();
        if (!fractional && d != Math.rint(d)) throw new IllegalArgumentException("The project file is damaged: \"" + key + "\" is not a whole number.");
        return d;
    }
}
