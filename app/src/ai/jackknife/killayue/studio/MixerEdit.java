package ai.jackknife.killayue.studio;

import java.util.LinkedHashMap;
import java.util.Map;

/** A mixer transaction; a drag keeps its original before-state and replaces only its final after-state. */
public final class MixerEdit extends Edit {
    private final String title, key, before;
    private String after;

    public MixerEdit(Project p, Mixer next, String title, String key) {
        next.validate(p);
        this.title = title; this.key = key;
        before = Json.write(p.mixer.json()); after = Json.write(next.json());
    }

    private MixerEdit(String title, String key, String before, String after) {
        this.title = title; this.key = key; this.before = before; this.after = after;
    }

    private void restore(Project p, String text) {
        Mixer next = Mixer.read(Json.parse(text));
        next.nextId = Math.max(p.mixer.nextId, next.nextId);
        next.validate(p);
        p.mixer = next;
    }
    void apply(Project p) { restore(p, after); }
    void revert(Project p) { restore(p, before); }
    public String label() { return title; }
    boolean merge(Edit next, Project p) {
        if (!(next instanceof MixerEdit) || key == null || !key.equals(((MixerEdit) next).key)) return false;
        after = ((MixerEdit) next).after; restore(p, after); return true;
    }
    LinkedHashMap<String, Object> toJson() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("kind", "mixer"); out.put("label", title); out.put("key", key); out.put("before", before); out.put("after", after);
        return out;
    }
    static MixerEdit read(Map<?, ?> j) {
        return new MixerEdit((String) j.get("label"), (String) j.get("key"), (String) j.get("before"), (String) j.get("after"));
    }
}
