package ai.jackknife.killayue.studio;

import java.util.ArrayList;

/** The project's undo and redo: every change goes through run(), and changes() counts steps for autosave. */
public final class History {
    private final Project project;
    private final ArrayList<Edit> undo = new ArrayList<Edit>(), redo = new ArrayList<Edit>();
    private int changes;

    public History(Project p) {
        project = p;
    }

    public Project project() {
        return project;
    }

    public void run(Edit e) {
        e.apply(project);
        undo.add(e);
        redo.clear();
        changes++;
    }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        Edit e = undo.remove(undo.size() - 1);
        e.revert(project);
        redo.add(e);
        changes++;
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) return false;
        Edit e = redo.remove(redo.size() - 1);
        e.apply(project);
        undo.add(e);
        changes++;
        return true;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** What Undo would undo ("Pan"), or null. */
    public String undoLabel() {
        return undo.isEmpty() ? null : undo.get(undo.size() - 1).label();
    }

    public int changes() {
        return changes;
    }
}
