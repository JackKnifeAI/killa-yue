package ai.jackknife.killayue.studio;

/**
 * Writes the project and its undo history a moment after the last change, never on every edit: a fader
 * drag of a hundred moves is one write, a second after the hand lets go. flush() writes at once (the screen
 * going away). A failed write keeps the state dirty and is tried again on the next tick. Thread-safe: the
 * UI thread calls changed() and flush(), a worker thread calls tick() a few times a second; the saver runs on
 * whichever thread writes. Plain Java (the clock is given, so it is tested without waiting).
 */
public final class Autosave {
    public interface Clock {
        long now();
    }

    public interface Saver {
        void save(String project, String history) throws Exception;
    }

    private final Saver saver;
    private final long quietMs;
    private final Clock clock;
    private String project, history;  // the latest state not yet written
    private long changedAt;
    private boolean dirty;
    private String error;

    public Autosave(Saver saver, long quietMs, Clock clock) {
        this.saver = saver;
        this.quietMs = quietMs;
        this.clock = clock;
    }

    /** The state after a change, as text (made on the UI thread, so the worker never reads a moving project). */
    public synchronized void changed(String projectText, String historyText) {
        project = projectText;
        history = historyText;
        changedAt = clock.now();
        dirty = true;
    }

    /** Writes if there is a change and it has been quiet for long enough. */
    public void tick() {
        String p, h;
        synchronized (this) {
            if (!dirty || clock.now() - changedAt < quietMs) return;
            p = project;
            h = history;
        }
        write(p, h);
    }

    /** Writes now if there is anything to write. */
    public void flush() {
        String p, h;
        synchronized (this) {
            if (!dirty) return;
            p = project;
            h = history;
        }
        write(p, h);
    }

    private void write(String p, String h) {
        try {
            saver.save(p, h);
            synchronized (this) {
                if (project == p) dirty = false;  // a change that came in during the write stays dirty
                error = null;
            }
        } catch (Exception e) {
            synchronized (this) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }
    }

    public synchronized boolean dirty() {
        return dirty;
    }

    /** What went wrong with the last write, or null once a write has worked. */
    public synchronized String lastError() {
        return error;
    }
}
