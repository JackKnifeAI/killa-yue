package ai.jackknife.killayue.studio;

/**
 * The mixer fader's travel: +6 dB at the top, 0 dB about two thirds up, silence (-60 dB) at the bottom, with
 * more room near 0 dB, where fine moves matter, than near silence.
 */
public final class FaderScale {
    private FaderScale() {}

    /** Position 0 (bottom) to 1 (top) for a level in dB. */
    public static float pos(float db) {
        db = Math.max(-60f, Math.min(6f, db));
        return (float) (1 - Math.sqrt((6 - db) / 66.0));
    }

    /** The level in dB at a position. */
    public static float db(float u) {
        u = Math.max(0f, Math.min(1f, u));
        return 6f - 66f * (1 - u) * (1 - u);
    }
}
