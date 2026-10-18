package ai.jackknife.killayue.studio;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/** Four pad patterns, song order and live playing controls. Pattern A remains the legacy step array. */
public final class Chamber {
    public static final int MAX_CHAIN = 128, DATA = 5 + 32 + 1 + MAX_CHAIN + 4 * 16 * 32;
    public int active, repeat, levelPad;
    public boolean song, levels;
    public final boolean[] mute = new boolean[16], solo = new boolean[16];
    public final float[][][] patterns = new float[4][16][32];
    public final ArrayList<Integer> chain = new ArrayList<Integer>();
    public Chamber(float[][] a) {
        patterns[0] = a;
        chain.add(0);
    }
    public float[][] rows() {
        return patterns[active];
    }
    public int bankAt(long step, int length) {
        if (step < 0)
            return -1;
        if (!song)
            return active;
        long index = step / length;
        return index >= chain.size() ? -1 : chain.get((int) index);
    }
    public boolean audible(int pad) {
        if (mute[pad])
            return false;
        for (boolean s : solo)
            if (s)
                return solo[pad];
        return true;
    }
    public double beats(int length) {
        return (song ? chain.size() : 1) * length / 4.0;
    }
    public void validate() {
        if (active < 0 || active > 3 || repeat < 0 || repeat > 3 || levelPad < 0 || levelPad > 15 || chain.isEmpty()
            || chain.size() > MAX_CHAIN)
            throw new IllegalArgumentException("Choose a valid Chamber pattern, repeat rate and song order.");
        for (Integer bank : chain)
            if (bank == null || bank < 0 || bank > 3)
                throw new IllegalArgumentException("A song order uses patterns A, B, C and D.");
        for (float[][] bank : patterns)
            for (float[] row : bank)
                for (float value : row)
                    if (!(value >= 0 && value <= 1))
                        throw new IllegalArgumentException("Pattern velocity must be 0 to 1.");
    }
    public Map<String, Object> json() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("active", active);
        out.put("repeat", repeat);
        out.put("levelPad", levelPad);
        out.put("song", song);
        out.put("levels", levels);
        out.put("chain", new ArrayList<Integer>(chain));
        ArrayList<Object> m = new ArrayList<Object>(), s = new ArrayList<Object>(), banks = new ArrayList<Object>();
        for (int i = 0; i < 16; i++) {
            m.add(mute[i]);
            s.add(solo[i]);
        }
        for (int bank = 1; bank < 4; bank++) {
            ArrayList<Object> rows = new ArrayList<Object>();
            for (float[] row : patterns[bank]) {
                ArrayList<Object> values = new ArrayList<Object>();
                for (float value : row) values.add(value);
                rows.add(values);
            }
            banks.add(rows);
        }
        out.put("mute", m);
        out.put("solo", s);
        out.put("banks", banks);
        return out;
    }
    private static int integer(Object value) {
        if (!(value instanceof Number))
            throw new IllegalArgumentException("Damaged Chamber value.");
        double n = ((Number) value).doubleValue();
        if (n != Math.rint(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Damaged Chamber number.");
        return (int) n;
    }
    private static List<?> list(Object value, int size) {
        if (!(value instanceof List) || (size >= 0 && ((List<?>) value).size() != size))
            throw new IllegalArgumentException("Damaged Chamber pattern data.");
        return (List<?>) value;
    }
    private static boolean flag(Object value) {
        if (!(value instanceof Boolean))
            throw new IllegalArgumentException("Damaged Chamber switch.");
        return (Boolean) value;
    }
    public static Chamber read(Object value, float[][] a) {
        if (!(value instanceof Map))
            throw new IllegalArgumentException("Damaged Chamber settings.");Map<?,?> m=(Map<?,?>)value;
        Chamber out = new Chamber(a);
        out.active = integer(m.get("active"));
        out.repeat = integer(m.get("repeat"));
        out.levelPad = integer(m.get("levelPad"));
        out.song = flag(m.get("song"));
        out.levels = flag(m.get("levels"));
        out.chain.clear();
        for (Object item : list(m.get("chain"), -1)) out.chain.add(integer(item));
        List<?> mute = list(m.get("mute"), 16), solo = list(m.get("solo"), 16), banks = list(m.get("banks"), 3);
        for (int i = 0; i < 16; i++) {
            out.mute[i] = flag(mute.get(i));
            out.solo[i] = flag(solo.get(i));
        }
        for (int bank = 1; bank < 4; bank++) {
            List<?> rows = list(banks.get(bank - 1), 16);
            for (int pad = 0; pad < 16; pad++) {
                List<?> row = list(rows.get(pad), 32);
                for (int step = 0; step < 32; step++) {
                    if (!(row.get(step) instanceof Number))
                        throw new IllegalArgumentException("Damaged step velocity.");
                    out.patterns[bank][pad][step] = ((Number) row.get(step)).floatValue();
                }
            }
        }
        out.validate();
        return out;
    }
    public float[] nativeData() {
        validate();
        float[] out = new float[DATA];
        out[0] = active;
        out[1] = song ? 1 : 0;
        out[2] = levels ? 1 : 0;
        out[3] = levelPad;
        out[4] = repeat;
        for (int i = 0; i < 16; i++) {
            out[5 + i] = mute[i] ? 1 : 0;
            out[21 + i] = solo[i] ? 1 : 0;
        }
        out[37] = chain.size();
        for (int i = 0; i < chain.size(); i++) out[38 + i] = chain.get(i);
        int at = 38 + MAX_CHAIN;
        for (float[][] bank : patterns)
            for (float[] row : bank)
                for (float v : row) out[at++] = v;
        return out;
    }
}
