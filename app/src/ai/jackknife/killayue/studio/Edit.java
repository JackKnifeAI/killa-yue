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

    /** Takes in the next move of a drag (same control): applies its value, keeping what this edit replaced. */
    boolean merge(Edit next, Project p) {
        return false;
    }

    abstract LinkedHashMap<String, Object> toJson();

    static Track need(Project p, String id) {
        Track t = p.track(id);
        if (t == null)
            throw new IllegalArgumentException("There is no track " + id + ".");
        return t;
    }

    static float finiteLevel(float db) {
        if (Float.isNaN(db) || Float.isInfinite(db))
            throw new IllegalArgumentException("A level must be a number of decibels.");
        return db;
    }

    // ---------- factories ----------

    public static Edit gain(String trackId, float db) {
        return new Setting("gain", trackId, Float.valueOf(finiteLevel(db)));
    }

    public static Edit pan(String trackId, float pan) {
        if (!(pan >= -1f && pan <= 1f))
            throw new IllegalArgumentException("Pan must be between -1 and 1.");
        return new Setting("pan", trackId, Float.valueOf(pan));
    }

    public static Edit mute(String trackId, boolean on) {
        return new Setting("mute", trackId, Boolean.valueOf(on));
    }

    public static Edit solo(String trackId, boolean on) {
        return new Setting("solo", trackId, Boolean.valueOf(on));
    }

    public static Edit rename(String trackId, String name) {
        if (name == null)
            throw new IllegalArgumentException("A track needs a name.");
        return new Setting("rename", trackId, name);
    }

    public static Edit setColor(String trackId, int argb) {
        return new Setting("color", trackId, Integer.valueOf(argb));
    }

    /** The loop region and whether it plays round. */
    public static Edit setLoop(long start, long end, boolean on) {
        if (start < 0 || end < start || (on && end == start))
            throw new IllegalArgumentException("A loop needs a start before its end.");
        return new Loop(start, end, on);
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
        return addTrack(name, file, length, null);
    }

    /** The same, with what the track is ("stem:vocals", "take", ...). */
    public static Edit addTrack(String name, String file, long length, String role) {
        if (name == null || file == null)
            throw new IllegalArgumentException("A track needs a name and a file.");
        AddTrack a = new AddTrack(name, file, length);
        a.role = role;
        return a;
    }

    /** Several edits as one step: applied in order, undone in reverse. */
    public static Edit compound(String label, Edit... edits) {
        if (label == null || edits.length == 0)
            throw new IllegalArgumentException("A compound edit needs a label and edits.");
        return new Compound(label, edits);
    }

    // ---------- the edits ----------

    /** A change to one value of a track, or the master level, remembered on first apply. */
    static final class Setting extends Edit {
        final String kind, track;
        Object value; // a drag moves it (merge)
        Object old;
        boolean saved;

        Setting(String kind, String track, Object value) {
            this.kind = kind;
            this.track = track;
            this.value = value;
        }

        private Object get(Project p) {
            if (kind.equals("master"))
                return Float.valueOf(p.masterDb);
            Track t = need(p, track);
            if (kind.equals("gain"))
                return Float.valueOf(t.gainDb);
            if (kind.equals("pan"))
                return Float.valueOf(t.pan);
            if (kind.equals("mute"))
                return Boolean.valueOf(t.mute);
            if (kind.equals("solo"))
                return Boolean.valueOf(t.solo);
            if (kind.equals("color"))
                return Integer.valueOf(t.color);
            return t.name;
        }

        private void set(Project p, Object v) {
            if (kind.equals("master")) {
                p.masterDb = ((Number) v).floatValue();
                return;
            }
            Track t = need(p, track);
            if (kind.equals("gain"))
                t.gainDb = ((Number) v).floatValue();
            else if (kind.equals("pan"))
                t.pan = ((Number) v).floatValue();
            else if (kind.equals("mute"))
                t.mute = (Boolean) v;
            else if (kind.equals("solo"))
                t.solo = (Boolean) v;
            else if (kind.equals("color"))
                t.color = ((Number) v).intValue();
            else
                t.name = (String) v;
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

        boolean merge(Edit next, Project p) {
            if (!(next instanceof Setting))
                return false;
            Setting n = (Setting) next;
            if (!kind.equals(n.kind) || (track == null ? n.track != null : !track.equals(n.track)))
                return false;
            if (kind.equals("mute") || kind.equals("solo"))
                return false; // a switch is never dragged
            value = n.value;
            set(p, value);
            return true;
        }

        public String label() {
            if (kind.equals("gain"))
                return "Level";
            if (kind.equals("pan"))
                return "Pan";
            if (kind.equals("mute"))
                return (Boolean) value ? "Mute" : "Unmute";
            if (kind.equals("solo"))
                return (Boolean) value ? "Solo" : "Unsolo";
            if (kind.equals("rename"))
                return "Rename";
            if (kind.equals("color"))
                return "Colour";
            return "Master level";
        }

        LinkedHashMap<String, Object> toJson() {
            LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
            j.put("kind", kind);
            if (track != null)
                j.put("track", track);
            j.put("value", value);
            if (saved)
                j.put("old", old);
            return j;
        }
    }

    static final class RemoveTrack extends Edit {
        final String track;
        Track removed;
        Mixer.Strip removedMixer;
        Automation removedAutomation = new Automation();
        int index;

        RemoveTrack(String track) {
            this.track = track;
        }

        void apply(Project p) {
            Track t = need(p, track);
            index = p.tracks.indexOf(t);
            removed = t;
            removedMixer = p.mixer.strips.remove(track);
            removedAutomation = Automation.read(p.mixer.automation.json());
            p.mixer.automation.removeStrip(track);
            p.tracks.remove(index);
        }

        void revert(Project p) {
            p.tracks.add(index, removed);
            if (removedMixer != null) p.mixer.strips.put(track, removedMixer);
            p.mixer.automation = Automation.read(removedAutomation.json());
        }

        public String label() {
            return "Remove track";
        }

        LinkedHashMap<String, Object> toJson() {
            LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
            j.put("kind", "removeTrack");
            if (removedMixer != null) j.put("removedMixer", Mixer.stripJson(removedMixer));
            j.put("removedAutomation", removedAutomation.json());
            j.put("track", track);
            j.put("index", Integer.valueOf(index));
            if (removed != null)
                j.put("removed", Project.trackJson(removed));
            return j;
        }
    }

    static final class AddTrack extends Edit {
        final String name, file;
        final long length;
        String role;
        Track added;

        AddTrack(String name, String file, long length) {
            this.name = name;
            this.file = file;
            this.length = length;
        }

        void apply(Project p) {
            if (added == null) {
                added = p.addTrack(name);
                added.role = role;
                p.addClip(added, file, 0, 0, length);
            } else {
                p.tracks.add(added);
            }
        }

        void revert(Project p) {
            Track t = need(p, added.id); // by ID: after reopening, the project holds its own copy
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
            if (role != null)
                j.put("role", role);
            if (added != null)
                j.put("added", Project.trackJson(added));
            return j;
        }
    }

    static final class Loop extends Edit {
        final long start, end;
        final boolean on;
        long oldStart, oldEnd;
        boolean oldOn, saved;

        Loop(long start, long end, boolean on) {
            this.start = start;
            this.end = end;
            this.on = on;
        }

        void apply(Project p) {
            if (!saved) {
                oldStart = p.loopStart;
                oldEnd = p.loopEnd;
                oldOn = p.loopOn;
                saved = true;
            }
            p.loopStart = start;
            p.loopEnd = end;
            p.loopOn = on;
        }

        void revert(Project p) {
            p.loopStart = oldStart;
            p.loopEnd = oldEnd;
            p.loopOn = oldOn;
        }

        boolean merge(Edit next, Project p) { // dragging a loop handle
            if (!(next instanceof Loop))
                return false;
            Loop n = (Loop) next;
            p.loopStart = n.start;
            p.loopEnd = n.end;
            p.loopOn = n.on;
            return true;
        }

        public String label() {
            return "Loop";
        }

        LinkedHashMap<String, Object> toJson() {
            LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
            j.put("kind", "loop");
            j.put("start", Long.valueOf(start));
            j.put("end", Long.valueOf(end));
            j.put("on", Boolean.valueOf(on));
            if (saved) {
                j.put("oldStart", Long.valueOf(oldStart));
                j.put("oldEnd", Long.valueOf(oldEnd));
                j.put("oldOn", Boolean.valueOf(oldOn));
            }
            return j;
        }
    }

    static final class Compound extends Edit {
        final String label;
        final Edit[] parts;

        Compound(String label, Edit[] parts) {
            this.label = label;
            this.parts = parts;
        }

        void apply(Project p) {
            for (Edit e : parts) e.apply(p);
        }

        void revert(Project p) {
            for (int i = parts.length - 1; i >= 0; i--) parts[i].revert(p);
        }

        public String label() {
            return label;
        }

        LinkedHashMap<String, Object> toJson() {
            LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
            j.put("kind", "compound");
            j.put("label", label);
            java.util.ArrayList<Object> list = new java.util.ArrayList<Object>();
            for (Edit e : parts) list.add(e.toJson());
            j.put("edits", list);
            return j;
        }
    }

    static final class Snapshot extends Edit {
        final String title, before, after;
        Snapshot(String title, String before, String after) {
            this.title = title;
            this.before = before;
            this.after = after;
        }
        void restore(Project p, String text) {
            Project n = Project.fromText(text);
            p.name = n.name;
            p.rate = n.rate;
            p.version = n.version;
            p.nextId = Math.max(p.nextId, n.nextId);
            p.masterDb = n.masterDb;
            p.sourceSong = n.sourceSong;
            p.sourceTitle = n.sourceTitle;
            p.loopStart = n.loopStart;
            p.loopEnd = n.loopEnd;
            p.loopOn = n.loopOn;
            p.performance = n.performance;
            n.mixer.nextId = Math.max(p.mixer.nextId, n.mixer.nextId);
            p.mixer = n.mixer;
            p.tracks.clear();
            p.tracks.addAll(n.tracks);
        }
        void apply(Project p) {
            restore(p, after);
        }
        void revert(Project p) {
            restore(p, before);
        }
        public String label() {
            return title;
        }
        LinkedHashMap<String, Object> toJson() {
            LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
            j.put("kind", "snapshot");
            j.put("label", title);
            j.put("before", before);
            j.put("after", after);
            return j;
        }
    }

    /** An edit saved by toJson, ready to undo or redo again. */
    static Edit fromJson(Object o) {
        if (!(o instanceof Map))
            throw new IllegalArgumentException("The history is damaged: an edit is not an object.");
        Map<?, ?> j = (Map<?, ?>) o;
        Object k = j.get("kind");
        if ("mixer".equals(k)) return MixerEdit.read(j);
        if ("snapshot".equals(k))
            return new Snapshot((String) j.get("label"), (String) j.get("before"), (String) j.get("after"));
        if ("loop".equals(k)) {
            Loop l = new Loop(((Number) j.get("start")).longValue(), ((Number) j.get("end")).longValue(), Boolean.TRUE.equals(j.get("on")));
            if (j.containsKey("oldStart")) {
                l.oldStart = ((Number) j.get("oldStart")).longValue();
                l.oldEnd = ((Number) j.get("oldEnd")).longValue();
                l.oldOn = Boolean.TRUE.equals(j.get("oldOn"));
                l.saved = true;
            }
            return l;
        }
        if ("compound".equals(k)) {
            Object list = j.get("edits");
            if (!(list instanceof java.util.List) || ((java.util.List<?>) list).isEmpty() || !(j.get("label") instanceof String))
                throw new IllegalArgumentException("The history is damaged: a compound edit without its parts.");
            java.util.List<?> l = (java.util.List<?>) list;
            Edit[] parts = new Edit[l.size()];
            for (int i = 0; i < parts.length; i++) parts[i] = fromJson(l.get(i));
            return new Compound((String) j.get("label"), parts);
        }
        if ("removeTrack".equals(k)) {
            RemoveTrack r = new RemoveTrack((String) j.get("track"));
            r.index = ((Number) j.get("index")).intValue();
            if (j.get("removedAutomation") != null) r.removedAutomation = Automation.read(j.get("removedAutomation"));
            if (j.get("removedMixer") != null) r.removedMixer = Mixer.readStrip(j.get("removedMixer"));
            if (j.get("removed") != null)
                r.removed = Project.trackFromJson(j.get("removed"));
            return r;
        }
        if ("addTrack".equals(k)) {
            AddTrack a = new AddTrack((String) j.get("name"), (String) j.get("file"), ((Number) j.get("length")).longValue());
            if (j.get("role") instanceof String)
                a.role = (String) j.get("role");
            if (j.get("added") != null)
                a.added = Project.trackFromJson(j.get("added"));
            return a;
        }
        if ("color".equals(k)) {
            Setting s = new Setting("color", (String) j.get("track"), Integer.valueOf(((Number) j.get("value")).intValue()));
            if (j.containsKey("old")) {
                s.old = Integer.valueOf(((Number) j.get("old")).intValue());
                s.saved = true;
            }
            return s;
        }
        if ("gain".equals(k) || "pan".equals(k) || "master".equals(k) || "mute".equals(k) || "solo".equals(k) || "rename".equals(k)) {
            boolean number = !"mute".equals(k) && !"solo".equals(k) && !"rename".equals(k);
            Setting s = new Setting(
                (String) k, (String) j.get("track"), number ? Float.valueOf(((Number) j.get("value")).floatValue()) : j.get("value"));
            if (j.containsKey("old")) {
                s.old = number ? Float.valueOf(((Number) j.get("old")).floatValue()) : j.get("old");
                s.saved = true;
            }
            return s;
        }
        throw new IllegalArgumentException("The history is damaged: an unknown edit \"" + k + "\".");
    }
}
