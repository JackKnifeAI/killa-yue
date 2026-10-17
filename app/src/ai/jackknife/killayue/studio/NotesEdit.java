package ai.jackknife.killayue.studio;
import java.util.LinkedHashMap;
import java.util.Map;
/** One completed piano-roll gesture, including saved undo across restarts. */
public final class NotesEdit extends Edit {
    private final String track, title, before, after;
    public NotesEdit(Project p, String track, MidiNotes next, String title) {
        next.validate();
        this.track = track;
        this.title = title;
        before = Json.write(need(p, track).notes.json());
        after = Json.write(next.json());
    }
    private NotesEdit(String track, String title, String before, String after) {
        this.track = track;
        this.title = title;
        this.before = before;
        this.after = after;
    }
    private void restore(Project p, String json) {
        Track t = need(p, track);
        MidiNotes notes = MidiNotes.read(Json.parse(json));
        notes.nextId = Math.max(t.notes.nextId, notes.nextId);
        t.notes = notes;
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
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("kind", "notes");
        out.put("track", track);
        out.put("label", title);
        out.put("before", before);
        out.put("after", after);
        return out;
    }
    static NotesEdit read(Map<?, ?> m) {
        return new NotesEdit(
            (String) m.get("track"), (String) m.get("label"), (String) m.get("before"), (String) m.get("after"));
    }
}
