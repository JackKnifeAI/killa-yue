package ai.jackknife.killayue.studio;

import java.util.HashMap;

/**
 * Keeps the engine in step with the project after any change (an edit, undo, redo or a jump in the undo
 * tree): a change to the tracks or clips reloads the session (gapless), anything else is sent as just the
 * messages for what changed, so a fader never reloads anything. It remembers what it last sent.
 */
public final class SessionDiff {
    /** Where the changes go: the engine (NativeEngine) in the app, a recorder in tests. */
    public interface Target {
        void reload(Project p); // the whole project: tracks, clips, levels, master and loop

        void gain(String id, float db);

        void pan(String id, float pan);

        void mute(String id, boolean on);

        void solo(String id, boolean on);

        void master(float db);

        void loop(long start, long end, boolean on);
    }

    private String structure; // null: nothing sent yet (or forgotten)
    private final HashMap<String, float[]> sent = new HashMap<String, float[]>(); // id -> gain, pan, mute, solo
    private float master;
    private long loopStart, loopEnd;
    private boolean loopOn;

    public void sync(Project p, Target t) {
        String s = structure(p);
        if (!s.equals(structure)) {
            t.reload(p);
            remember(p, s);
            return;
        }
        for (Track tr : p.tracks) {
            float[] was = sent.get(tr.id);
            if (was[0] != tr.gainDb)
                t.gain(tr.id, tr.gainDb);
            if (was[1] != tr.pan)
                t.pan(tr.id, tr.pan);
            if ((was[2] != 0) != tr.mute)
                t.mute(tr.id, tr.mute);
            if ((was[3] != 0) != tr.solo)
                t.solo(tr.id, tr.solo);
        }
        if (master != p.masterDb)
            t.master(p.masterDb);
        if (loopStart != p.loopStart || loopEnd != p.loopEnd || loopOn != p.loopOn)
            t.loop(p.loopStart, p.loopEnd, p.loopOn);
        remember(p, s);
    }

    /** The engine was rebuilt (a device change, a new engine): send everything next time. */
    public void forget() {
        structure = null;
        sent.clear();
    }

    private void remember(Project p, String s) {
        structure = s;
        sent.clear();
        for (Track tr : p.tracks) sent.put(tr.id, new float[] {tr.gainDb, tr.pan, tr.mute ? 1 : 0, tr.solo ? 1 : 0});
        master = p.masterDb;
        loopStart = p.loopStart;
        loopEnd = p.loopEnd;
        loopOn = p.loopOn;
    }

    /** Everything that makes a different session: the tracks in order and every clip. */
    static String structure(Project p) {
        StringBuilder b = new StringBuilder();
        b.append(p.rate);
        for (Track t : p.tracks) {
            b.append('|').append(t.id).append(Json.write(t.notes.json()));
            if (!t.notes.notes.isEmpty()) {
                b.append(p.performance.tempo);
                for (java.util.Map.Entry<Long,Double> point : p.performance.tempos.entrySet()) b.append(":").append(point.getKey()).append(":").append(point.getValue());
            }
            for (Clip c : t.clips)
                b.append(';')
                    .append(c.id)
                    .append(',')
                    .append(c.file)
                    .append(',')
                    .append(c.start)
                    .append(',')
                    .append(c.offset)
                    .append(',')
                    .append(c.length)
                    .append(',')
                    .append(Float.floatToIntBits(c.gainDb))
                    .append(',')
                    .append(c.fadeIn)
                    .append(',')
                    .append(c.fadeOut);
        }
        return b.toString();
    }
}
