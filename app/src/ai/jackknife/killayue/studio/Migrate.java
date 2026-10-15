package ai.jackknife.killayue.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings a saved project up to the current format before it is read, one step per format, so a project made
 * by an older version of the app always opens. Each step only adds what the newer format has, with defaults.
 */
final class Migrate {
    private Migrate() {}

    /** The project's JSON at the current format (from format 1 up). */
    static Map<String, Object> toCurrent(Map<?, ?> j, int version) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : j.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
        if (version < 2) {
            // format 2: a loop region and a colour for every track
            m.put("loopStart", Long.valueOf(0));
            m.put("loopEnd", Long.valueOf(0));
            m.put("loopOn", Boolean.FALSE);
            Object ts = m.get("tracks");
            if (ts instanceof List) {
                ArrayList<Object> out = new ArrayList<Object>();
                int i = 0;
                for (Object t : (List<?>) ts) {
                    if (t instanceof Map) {
                        LinkedHashMap<String, Object> k = new LinkedHashMap<String, Object>();
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) t).entrySet()) k.put(String.valueOf(e.getKey()), e.getValue());
                        k.put("color", Integer.valueOf(Track.PALETTE[i++ % Track.PALETTE.length]));
                        out.add(k);
                    } else {
                        out.add(t);
                    }
                }
                m.put("tracks", out);
            }
        }
        m.put("version", Integer.valueOf(Project.VERSION));
        return m;
    }
}
