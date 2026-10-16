package ai.jackknife.killayue.studio;
/** All positions derive from absolute beat numbers: rounding never accumulates across a song. */
public final class BeatGrid {
    private BeatGrid() {}
    public static long snap(long frame, int rate, double bpm, int subdivisions) {
        if (rate <= 0 || !(bpm >= 20 && bpm <= 400) || subdivisions < 1)
            throw new IllegalArgumentException("Invalid grid.");
        double step = rate * 60.0 / bpm / subdivisions;
        return Math.max(0, Math.round(Math.rint(frame / step) * step));
    }
    public static long stepFrame(long step, int rate, double bpm, double swing) {
        if (step < 0 || rate <= 0 || !(bpm >= 20 && bpm <= 400) || !(swing >= 0 && swing <= .75))
            throw new IllegalArgumentException("Invalid step.");
        return Math.round((step + ((step & 1) == 1 ? swing : 0)) * rate * 15.0 / bpm);
    }
}
