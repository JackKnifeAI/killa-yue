package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The project's undo history as a tree, so nothing is ever thrown away. Undo goes back a step; an edit made
 * after undoing starts a new branch beside the old one; redo follows the branch last visited, or any branch by
 * choice; jumpTo walks to any step at all. Every change goes through run(); changes() counts steps for autosave.
 */
public final class History {
    /** One state of the project: the edit that led to it from its parent. */
    public static final class Step {
        public final int id, parent;  // the start has id 0 and parent -1
        public final String label;
        public final long when;       // milliseconds since 1970
        final Edit edit;
        final ArrayList<Step> children = new ArrayList<Step>();
        Step lastChild;               // where redo goes

        Step(int id, int parent, Edit edit, long when) {
            this.id = id;
            this.parent = parent;
            this.edit = edit;
            this.label = edit == null ? "Start" : edit.label();
            this.when = when;
        }
    }

    private final Project project;
    private final ArrayList<Step> steps = new ArrayList<Step>();  // by id
    private Step current;
    private int changes;
    private Object gesture;  // the drag the current step came from, while it lasts

    public History(Project p) {
        project = p;
        current = new Step(0, -1, null, System.currentTimeMillis());
        steps.add(current);
    }

    public Project project() {
        return project;
    }

    public void run(Edit e) {
        gesture = null;
        e.apply(project);
        Step s = new Step(steps.size(), current.id, e, System.currentTimeMillis());
        steps.add(s);
        current.children.add(s);
        current.lastChild = s;
        current = s;
        changes++;
    }

    /**
     * A continuous gesture, such as a fader drag: its first move is a step, and every later move of the same
     * gesture changes that step in place, so the whole drag is one undo step. A new gesture, another control,
     * a step that already has branches, or any other change in between starts a new step.
     */
    public void drag(Edit e, Object gesture) {
        if (gesture != null && gesture == this.gesture && current.edit != null && current.children.isEmpty()
                && current.edit.merge(e, project)) {
            changes++;
            return;
        }
        run(e);
        this.gesture = gesture;
    }

    public boolean undo() {
        gesture = null;
        if (current.parent < 0) return false;
        current.edit.revert(project);
        Step back = steps.get(current.parent);
        back.lastChild = current;
        current = back;
        changes++;
        return true;
    }

    /** Redo along the branch last visited. */
    public boolean redo() {
        if (current.lastChild == null) return false;
        return enter(current.lastChild);
    }

    /** Redo into one of the branches listed by redoChoices(). */
    public boolean redo(int choice) {
        if (choice < 0 || choice >= current.children.size()) return false;
        return enter(current.children.get(choice));
    }

    private boolean enter(Step s) {
        gesture = null;
        s.edit.apply(project);
        current.lastChild = s;
        current = s;
        changes++;
        return true;
    }

    /** What each branch from here would redo, oldest first. */
    public List<String> redoChoices() {
        ArrayList<String> out = new ArrayList<String>();
        for (Step s : current.children) out.add(s.label);
        return out;
    }

    /** Walks to any step: back up to where the branches meet, then down to it. */
    public boolean jumpTo(int stepId) {
        if (stepId < 0 || stepId >= steps.size()) return false;
        Step target = steps.get(stepId);
        ArrayList<Step> down = new ArrayList<Step>();
        for (Step s = target; s != null; s = s.parent < 0 ? null : steps.get(s.parent)) down.add(0, s);
        while (!down.contains(current)) undo();
        for (int i = down.indexOf(current) + 1; i < down.size(); i++) enter(down.get(i));
        return true;
    }

    public boolean canUndo() {
        return current.parent >= 0;
    }

    public boolean canRedo() {
        return current.lastChild != null;
    }

    /** What Undo would undo ("Pan"), or null. */
    public String undoLabel() {
        return current.edit == null ? null : current.label;
    }

    public int currentStep() {
        return current.id;
    }

    /** Every step, in the order they were made (the start first). */
    public List<Step> steps() {
        return new ArrayList<Step>(steps);
    }

    public int changes() {
        return changes;
    }

    // ---------- saving ----------

    LinkedHashMap<String, Object> toJson(String fingerprint) {
        LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
        j.put("version", Integer.valueOf(1));
        j.put("fingerprint", fingerprint);
        j.put("current", Integer.valueOf(current.id));
        ArrayList<Object> list = new ArrayList<Object>();
        for (Step s : steps) {
            LinkedHashMap<String, Object> k = new LinkedHashMap<String, Object>();
            k.put("id", Integer.valueOf(s.id));
            k.put("parent", Integer.valueOf(s.parent));
            k.put("when", Long.valueOf(s.when));
            k.put("redo", s.lastChild == null ? null : Integer.valueOf(s.lastChild.id));
            if (s.edit != null) k.put("edit", s.edit.toJson());
            list.add(k);
        }
        j.put("steps", list);
        return j;
    }

    /** A saved history for project p, which must be the project as it stands at the saved current step. */
    static History fromJson(Object o, Project p) {
        if (!(o instanceof Map)) throw new IllegalArgumentException("The history is damaged.");
        Map<?, ?> j = (Map<?, ?>) o;
        History h = new History(p);
        h.steps.clear();
        List<?> list = (List<?>) j.get("steps");
        for (Object so : list) {
            Map<?, ?> k = (Map<?, ?>) so;
            int id = ((Number) k.get("id")).intValue(), parent = ((Number) k.get("parent")).intValue();
            if (id != h.steps.size() || parent >= id || (id == 0) != (parent < 0)) throw new IllegalArgumentException("The history is damaged: steps out of order.");
            Step s = new Step(id, parent, id == 0 ? null : Edit.fromJson(k.get("edit")), ((Number) k.get("when")).longValue());
            h.steps.add(s);
            if (parent >= 0) h.steps.get(parent).children.add(s);
        }
        for (Object so : list) {
            Map<?, ?> k = (Map<?, ?>) so;
            Object r = k.get("redo");
            if (r != null) h.steps.get(((Number) k.get("id")).intValue()).lastChild = h.steps.get(((Number) r).intValue());
        }
        h.current = h.steps.get(((Number) j.get("current")).intValue());
        return h;
    }
}
