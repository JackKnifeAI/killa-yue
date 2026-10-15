package ai.jackknife.killayue.studio;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One change to a project that can be undone. Each factory returns an edit that records what it replaced the
 * first time it is applied, so revert restores exactly that, and a redo applies the very same change again
 * (the same IDs included). Every edit saves to JSON and loads back, so the undo tree survives closing the app;
 * edits find tracks by ID, never by object, so they work on a project that was loaded from disk.
 */
public abstract class Edit {
    abstract void apply(Project p);

    abstract void revert(Project p);

    public abstract String label();

    abstract LinkedHashMap<String, Object> toJson();

    static Track need(Project p, String id) {
        Track t = p.track(id);
        if (t == null) throw new IllegalArgumentException("There is no track " + id + ".");
        return t;
    }

    static float finiteLevel(float db) {
        if (Float.isNaN(db) || Float.isInfinite(db)) throw new IllegalArgumentException("A level must be a number of decibels.");
        return db;
    }

    // ---------- factories ----------

    public static Edit gain(String trackId, float db) {
        return new Setting("gain", trackId, Float.valueOf(finiteLevel(db)));
    }

    public static Edit pan(String trackId, float pan) {
        if (!(pan >= -1f && pan <= 1f)) throw new IllegalArgumentException("Pan must be between -1 and 1.");
        return new Setting("pan", trackId, Float.valueOf(pan));
    }

    public static Edit mute(String trackId, boolean on) {
        return new Setting("mute", trackId, Boolean.valueOf(on));
    }

    public static Edit solo(String trackId, boolean on) {
        return new Setting("solo", trackId, Boolean.valueOf(on));
    }

    public static Edit rename(String trackId, String name) {
        if (name == null) throw new IllegalArgumentException("A track needs a name.");
        return new Setting("rename", trackId, name);
    }

    public static Edit masterGain(float db) {
        return new Setting("master", null, Float.valueOf(finiteLevel(db)));
    }

    /** Removes a track; undo puts the same track back in its place. */
    public static Edit removeTrack(String trackId) {
        return new RemoveTrack(trackId);
    }

    /**
     * Adds a track holding one clip of a whole file at the start. The IDs are taken on the first apply and never
     * given back: a redo puts back the same track, and an ID is never handed out twice, so anything outside the
     * project that keeps an ID (the engine, a stems job) can trust it.
     */
    public static Edit addTrack(String name, String file, long length) {
        if (name == null || file == null) throw new IllegalArgumentException("A track needs a name and a file.");
        return new AddTrack(name, file, length);
    }

    // ---------- the edits ----------

    /** A change to one value of a track, or the master level, remembered on first apply. */
    static final class Setting extends Edit {
        final String kind, track;
        final Object value;
        Object old;
        boolean saved;

        Setting(String kind, String track, Object value) {
            this.kind = kind;
            this.track = track;
            this.value = value;
        }

        private Object get(Project p) {
            if (kind.equals("master")) return Float.valueOf(p.masterDb);
            Track t = need(p, track);
            if (kind.equals("gain")) return Float.valueOf(t.gainDb);
            if (kind.equals("pan")) return Float.valueOf(t.pan);
            if (kind.equals("mute")) return Boolean.valueOf(t.mute);
            if (kind.equals("solo")) return Boolean.valueOf(t.solo);
            return t.name;
        }

        private void set(Project p, Object v) {
            if (kind.equals("master")) {
                p.masterDb = ((Number) v).floatValue();
                return;
            }
            Track t = need(p, track);
            if (kind.equals("gain")) t.gainDb = ((Number) v).floatValue();
            else if (kind.equals("pan")) t.pan = ((Number) v).floatValue();
            else if (kind.equals("mute")) t.mute = (Boolean) v;
            else if (kind.equals("solo")) t.solo = (Boolean) v;
            else t.name = (String) v;
        }

        void apply(Project p) {
            if (!saved) {
                old = get(p);
                saved = true;
            }
            set(p, value);
        }

        void revert(Project p) {
            set(p, old);
        }

        public String label() {
            if (kind.equals("gain")) return "Level";
            if (kind.equals("pan")) return "Pan";
            if (kind.equals("mute")) return (Boolean) value ? "Mute" : "Unmute";
            if (kind.equals("solo")) return (Boolean) value ? "Solo" : "Unsolo";
            if (kind.equals("rename")) return "Rename";
            return "Master level";
        }

        LinkedHashMap<String, Object> toJson() {
            LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
            j.put("kind", kind);
            if (track != null) j.put("track", track);
            j.put("value", value);
            if (saved) j.put("old", old);
            return j;
        }
    }

    static final class RemoveTrack extends Edit {
        final String track;
        Track removed;
        int index;

        RemoveTrack(String track) {
            this.track = track;
        }

        void apply(Project p) {
            Track t = need(p, track);
            index = p.tracks.indexOf(t);
            removed = t;
            p.tracks.remove(index);
        }

        void revert(Project p) {
            p.tracks.add(index, removed);
        }

        public String label() {
            return "Remove track";
        }

        LinkedHashMap<String, Object> toJson() {
            LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
            j.put("kind", "removeTrack");
            j.put("track", track);
            j.put("index", Integer.valueOf(index));
            if (removed != null) j.put("removed", Project.trackJson(removed));
            return j;
        }
    }

    static final class AddTrack extends Edit {
        final String name, file;
        final long length;
        Track added;

        AddTrack(String name, String file, long length) {
            this.name = name;
            this.file = file;
            this.length = length;
        }

        void apply(Project p) {
            if (added == null) {
                added = p.addTrack(name);
                p.addClip(added, file, 0, 0, length);
            } else {
                p.tracks.add(added);
            }
        }

        void revert(Project p) {
            Track t = need(p, added.id);  // by ID: after reopening, the project holds its own copy
            p.tracks.remove(t);
            added = t;
        }

        public String label() {
            return "Add track";
        }

        LinkedHashMap<String, Object> toJson() {
            LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
            j.put("kind", "addTrack");
            j.put("name", name);
            j.put("file", file);
            j.put("length", Long.valueOf(length));
            if (added != null) j.put("added", Project.trackJson(added));
            return j;
        }
    }

    /** An edit saved by toJson, ready to undo or redo again. */
    static Edit fromJson(Object o) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("The history is damaged: an edit is not an object.");
        Map<?, ?> j = (Map<?, ?>) o;
        Object k = j.get("kind");
        if ("removeTrack".equals(k)) {
            RemoveTrack r = new RemoveTrack((String) j.get("track"));
            r.index = ((Number) j.get("index")).intValue();
            if (j.get("removed") != null) r.removed = Project.trackFromJson(j.get("removed"));
            return r;
        }
        if ("addTrack".equals(k)) {
            AddTrack a = new AddTrack((String) j.get("name"), (String) j.get("file"), ((Number) j.get("length")).longValue());
            if (j.get("added") != null) a.added = Project.trackFromJson(j.get("added"));
            return a;
        }
        if ("gain".equals(k) || "pan".equals(k) || "master".equals(k) || "mute".equals(k) || "solo".equals(k) || "rename".equals(k)) {
            boolean number = !"mute".equals(k) && !"solo".equals(k) && !"rename".equals(k);
            Setting s = new Setting((String) k, (String) j.get("track"), number ? Float.valueOf(((Number) j.get("value")).floatValue()) : j.get("value"));
            if (j.containsKey("old")) {
                s.old = number ? Float.valueOf(((Number) j.get("old")).floatValue()) : j.get("old");
                s.saved = true;
            }
            return s;
        }
        throw new IllegalArgumentException("The history is damaged: an unknown edit \"" + k + "\".");
    }
}
