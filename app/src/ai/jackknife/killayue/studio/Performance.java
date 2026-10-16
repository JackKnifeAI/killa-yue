package ai.jackknife.killayue.studio;
import java.util.*;
/** Saved recording and sampler controls. Runtime voices and armed inputs deliberately live outside the project. */
public final class Performance {
    public double tempo = 120, swing = 0;
    public int beats = 4, beatUnit = 4, countIn = 0, patternLength = 16;
    public boolean click, punch, pattern;
    public long punchStart, punchEnd;
    public final Pad[] pads = new Pad[16];
    public final float[][] steps = new float[16][32];
    public final TreeMap<Long, Double> tempos = new TreeMap<Long, Double>();
    public final TreeMap<Long, String> markers = new TreeMap<Long, String>();
    public Performance() {
        for (int i = 0; i < 16; i++)
            pads[i] = new Pad();
    }
    public static final class Pad {
        public String file = "", name = "";
        public long offset, length;
        public int mode, choke;
        public float tune, attack = 0.002f, decay = 0.08f, sustain = 1, release = 0.08f, cutoff = 20000;
        public boolean reverse;
        public Map<String, Object> json() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("file", file);
            m.put("name", name);
            m.put("offset", offset);
            m.put("length", length);
            m.put("mode", mode);
            m.put("choke", choke);
            m.put("tune", tune);
            m.put("attack", attack);
            m.put("decay", decay);
            m.put("sustain", sustain);
            m.put("release", release);
            m.put("cutoff", cutoff);
            m.put("reverse", reverse);
            return m;
        }
    }
    static double number(Map<?, ?> m, String k, double d) {
        Object o = m.get(k);
        return o == null ? d : ((Number)o).doubleValue();
    }
    static boolean flag(Map<?, ?> m, String k) { return Boolean.TRUE.equals(m.get(k)); }
    public Map<String, Object> json() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("tempo", tempo);
        m.put("beats", beats);
        m.put("beatUnit", beatUnit);
        m.put("swing", swing);
        m.put("click", click);
        m.put("countIn", countIn);
        m.put("punch", punch);
        m.put("punchStart", punchStart);
        m.put("punchEnd", punchEnd);
        m.put("pattern", pattern);
        m.put("patternLength", patternLength);
        ArrayList<Object> ps = new ArrayList<Object>(), ss = new ArrayList<Object>(), ms = new ArrayList<Object>();
        for (int i = 0; i < 16; i++) {
            ps.add(pads[i].json());
            ArrayList<Object> row = new ArrayList<Object>();
            for (float v : steps[i])
                row.add(v);
            ss.add(row);
        }
        for (Map.Entry<Long, String> e : markers.entrySet())
            ms.add(Arrays.asList(e.getKey(), e.getValue()));
        ArrayList<Object> tm = new ArrayList<Object>();
        for (Map.Entry<Long, Double> e : tempos.entrySet())
            tm.add(Arrays.asList(e.getKey(), e.getValue()));
        m.put("tempos", tm);
        m.put("pads", ps);
        m.put("steps", ss);
        m.put("markers", ms);
        return m;
    }
    public static Performance read(Object o) {
        Performance p = new Performance();
        if (o == null)
            return p;Map<?,?> m=(Map<?,?>)o;
        p.tempo = number(m, "tempo", 120);
        p.beats = (int)number(m, "beats", 4);
        p.beatUnit = (int)number(m, "beatUnit", 4);
        p.swing = number(m, "swing", 0);
        p.countIn = (int)number(m, "countIn", 0);
        p.click = flag(m, "click");
        p.punch = flag(m, "punch");
        p.punchStart = (long)number(m, "punchStart", 0);
        p.punchEnd = (long)number(m, "punchEnd", 0);
        p.pattern = flag(m, "pattern");
        p.patternLength = (int)number(m, "patternLength", 16);
        if (m.get("pads") != null) {
            List<?> ps = (List<?>)m.get("pads");
            if (ps.size() != 16)
                throw new IllegalArgumentException("A bank needs sixteen pads.");
            for (int i = 0; i < 16; i++) {Map<?,?> q=(Map<?,?>)ps.get(i);
                Pad d = p.pads[i];
                d.file = (String)q.get("file");
                d.name = (String)q.get("name");
                d.offset = (long)number(q, "offset", 0);
                d.length = (long)number(q, "length", 0);
                d.mode = (int)number(q, "mode", 0);
                d.choke = (int)number(q, "choke", 0);
                d.tune = (float)number(q, "tune", 0);
                d.attack = (float)number(q, "attack", .002);
                d.decay = (float)number(q, "decay", .08);
                d.sustain = (float)number(q, "sustain", 1);
                d.release = (float)number(q, "release", .08);
                d.cutoff = (float)number(q, "cutoff", 20000);
                d.reverse = flag(q, "reverse");
            }
        }
        if (m.get("steps") != null) {
            List<?> ss = (List<?>)m.get("steps");
            if (ss.size() != 16)
                throw new IllegalArgumentException("Invalid pattern.");
            for (int i = 0; i < 16; i++) {
                List<?> row = (List<?>)ss.get(i);
                if (row.size() != 32)
                    throw new IllegalArgumentException("Invalid steps.");
                for (int j = 0; j < 32; j++)
                    p.steps[i][j] = ((Number)row.get(j)).floatValue();
            }
        }
        if (m.get("markers") != null)
            for (Object v : (List<?>)m.get("markers")) {
                List<?> q = (List<?>)v;
                p.markers.put(((Number)q.get(0)).longValue(), (String)q.get(1));
            }
        if (m.get("tempos") != null)
            for (Object v : (List<?>)m.get("tempos")) {
                List<?> q = (List<?>)v;
                p.tempos.put(((Number)q.get(0)).longValue(), ((Number)q.get(1)).doubleValue());
            }
        p.validate();
        return p;
    }
    public void validate() {
        if (!(tempo >= 20 && tempo <= 400) || beats < 1 || beats > 16 ||
            (beatUnit != 2 && beatUnit != 4 && beatUnit != 8 && beatUnit != 16) || !(swing >= 0 && swing <= .75) || countIn < 0 ||
            countIn > 4 || (patternLength != 16 && patternLength != 32))
            throw new IllegalArgumentException("Invalid tempo, metre or pattern.");
        if (punchStart < 0 || punchEnd < punchStart || (punch && punchEnd == punchStart))
            throw new IllegalArgumentException("Mark a punch range first.");
        if (tempos.size() > 63)
            throw new IllegalArgumentException("A project supports up to 63 tempo changes.");
        for (Map.Entry<Long, Double> e : tempos.entrySet())
            if (e.getKey() < 0 || !(e.getValue() >= 20 && e.getValue() <= 400))
                throw new IllegalArgumentException("Invalid tempo change.");
        for (Pad d : pads)
            if (d.file == null || d.name == null || (!d.file.isEmpty() && !Project.insideProject(d.file)) || d.offset < 0 || d.length < 0 ||
                d.offset > Long.MAX_VALUE - d.length || d.mode < 0 || d.mode > 2 || d.choke < 0 || d.choke > 16 ||
                !(d.tune >= -24 && d.tune <= 24) || !(d.attack >= 0 && d.attack <= 10) || !(d.decay >= 0 && d.decay <= 10) ||
                !(d.sustain >= 0 && d.sustain <= 1) || !(d.release >= .002 && d.release <= 10) || !(d.cutoff >= 20 && d.cutoff <= 24000))
                throw new IllegalArgumentException("Invalid pad settings.");
        for (float[] row : steps)
            for (float v : row)
                if (!(v >= 0 && v <= 1))
                    throw new IllegalArgumentException("Step velocity must be 0 to 1.");
        for (Map.Entry<Long, String> e : markers.entrySet())
            if (e.getKey() < 0 || e.getValue() == null)
                throw new IllegalArgumentException("Invalid marker.");
    }
}
