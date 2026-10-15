package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words to sing, printed under the line of music each is sung in. A lyric line's time comes from the
 * song's lyric sync (or tap-to-time); a line of music's time span comes from abcjs's own playback timing of
 * the laid-out score. A lyric line goes under the line of music playing when it starts; one starting before
 * the first line's first note goes with the first line. Words are placed by line, not under single notes.
 */
class LyricLines {
    static final float SIZE = 13, GAP = 14, ROW = 17;

    /** abcjs's start time (ms) of each line of music, from the engraver's {"line": ms} answer. */
    static TreeMap<Integer, Long> starts(String json) {
        TreeMap<Integer, Long> out = new TreeMap<Integer, Long>();
        Matcher m = Pattern.compile("\"(\\d+)\"\\s*:\\s*(\\d+)").matcher(json == null ? "" : json);
        while (m.find()) out.put(Integer.valueOf(m.group(1)), Long.valueOf(m.group(2)));
        return out;
    }

    /** Adds the lyric lines (each with its start time in ms) under the lines of music. Returns how many were placed. */
    static int add(SvgScore s, TreeMap<Integer, Long> starts, List<String> lyrics, int[] times) {
        if (starts.isEmpty()) return 0;
        // lines of music in time order, with the lyric lines that start in each
        ArrayList<Map.Entry<Integer, Long>> order = new ArrayList<Map.Entry<Integer, Long>>(starts.entrySet());
        java.util.Collections.sort(order, new java.util.Comparator<Map.Entry<Integer, Long>>() {
            public int compare(Map.Entry<Integer, Long> a, Map.Entry<Integer, Long> b) {
                return Long.compare(a.getValue(), b.getValue());
            }
        });
        TreeMap<Integer, ArrayList<String>> under = new TreeMap<Integer, ArrayList<String>>();
        int placed = 0;
        for (int i = 0; i < lyrics.size() && i < times.length; i++) {
            String text = lyrics.get(i).trim();
            if (text.length() == 0) continue;
            int line = order.get(0).getKey();
            for (Map.Entry<Integer, Long> e : order) if (e.getValue() <= times[i]) line = e.getKey();
            if (!under.containsKey(line)) under.put(line, new ArrayList<String>());
            under.get(line).add(text);
            placed++;
        }
        ArrayList<SvgScore.Item> added = new ArrayList<SvgScore.Item>();
        for (Map.Entry<Integer, ArrayList<String>> e : under.entrySet()) {
            int line = e.getKey();
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
            for (SvgScore.Item i : s.items) if (i.line == line && i.has("abcjs-staff") && i.box != null) {
                x0 = Math.min(x0, i.box[0]);
                x1 = Math.max(x1, i.box[2]);
            }
            float[] box = s.lineBox(line);
            if (box == null || x0 > x1) continue;
            float y = box[3] + GAP + SIZE;
            for (String text : e.getValue()) {
                for (String row : wrap(text, x1 - x0)) {
                    SvgScore.Item t = new SvgScore.Item();
                    t.line = line;
                    t.cls = "killa-lyrics abcjs-l" + line;
                    t.text = row;
                    t.x = x0;
                    t.y = y;
                    t.size = SIZE;
                    t.italic = true;
                    t.family = "Times New Roman";
                    added.add(t);
                    y += ROW;
                }
            }
        }
        s.items.addAll(added);
        return placed;
    }

    /** Rows that fit the width, broken at spaces (a serif italic averages about half its size per letter). */
    static ArrayList<String> wrap(String text, float width) {
        int max = Math.max(10, (int) (width / (SIZE * 0.5f)));
        ArrayList<String> rows = new ArrayList<String>();
        StringBuilder row = new StringBuilder();
        for (String w : text.split("\\s+")) {
            if (row.length() > 0 && row.length() + 1 + w.length() > max) {
                rows.add(row.toString());
                row.setLength(0);
            }
            if (row.length() > 0) row.append(' ');
            row.append(w);
        }
        if (row.length() > 0) rows.add(row.toString());
        return rows;
    }
}
