package ai.jackknife.killayue.studio;

/** FaderScale: the mixer's fader travel, more room near 0 dB than near silence. */
public class FaderScaleTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        same("the top is +6 dB", 6f, FaderScale.db(1f));
        same("the bottom is silence (-60 dB)", -60f, FaderScale.db(0f));
        same("0 dB sits about two thirds up", true, Math.abs(FaderScale.pos(0f) - 0.698f) < 0.001f);
        boolean round = true, rising = true;
        float last = -1000;
        for (float u = 0; u <= 1.0001f; u += 0.01f) {
            float d = FaderScale.db(u);
            if (Math.abs(FaderScale.pos(d) - u) > 1e-4f) round = false;
            if (d <= last) rising = false;
            last = d;
        }
        same("position to dB and back is the same place", true, round);
        same("higher is always louder", true, rising);
        same("beyond the ends stays at the ends", "6.0 -60.0", FaderScale.db(2f) + " " + FaderScale.db(-1f));
        same("the top half of the travel covers -10 to +6 dB: room where it matters", true, FaderScale.db(0.5f) < -10 && FaderScale.db(0.5f) > -20);
        System.out.println("FaderScaleTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
