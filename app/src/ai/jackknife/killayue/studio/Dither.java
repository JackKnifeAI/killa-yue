package ai.jackknife.killayue.studio;

/**
 * Float audio to 16-bit for export, with TPDF dither (the sum of two uniform noises a step wide), so quiet
 * passages fade into fine noise instead of buzzing distortion. Full scale and beyond are held at the top,
 * never wrapped round; NaN is silence. Seeded, so the same export gives the same file.
 */
public final class Dither {
    private long s;

    public Dither(long seed) {
        s = seed * 0x9E3779B97F4A7C15L + 1;
        if (s == 0) s = 1;
    }

    private double uniform() {  // xorshift64*, [0, 1)
        s ^= s >>> 12;
        s ^= s << 25;
        s ^= s >>> 27;
        return ((s * 0x2545F4914F6CDD1DL) >>> 11) * 0x1.0p-53;
    }

    public short quantise(float x) {
        if (x != x) x = 0f;
        if (x >= 1f) return 32767;
        if (x <= -1f) return -32767;
        double v = x * 32767.0 + (uniform() - uniform());
        long q = Math.round(v);
        return (short) Math.max(-32768, Math.min(32767, q));
    }
}
