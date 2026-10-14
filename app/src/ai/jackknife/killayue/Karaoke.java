package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out when each lyric line is sung, for the karaoke view.
 *
 * The model gives no word timings, so this is an estimate built from the score it wrote:
 * the score's tempo and section markers ("% verse", "% chorus") say when each section
 * starts, and its vocal notes say when singing happens inside a section. Lyric sections
 * are matched to score sections in order. Where a section has no vocal notes (rap, or
 * no score at all), its lines are spread across the section by syllable count.
 */
public class Karaoke {
    public static class Line {
        public String text;
        public int start, end; // milliseconds
        public int syllables;
    }

    private static class LyricSection {
        String type = "";
        ArrayList<Line> lines = new ArrayList<Line>();
    }

    private static class ScoreSection {
        String type = "";
        double start, end;                 // seconds
        ArrayList<Double> onsets = new ArrayList<Double>(); // seconds, one per sung note
        boolean used;
    }

    private static final Pattern TEMPO = Pattern.compile("Q:\\s*(\\d+)/(\\d+)\\s*=\\s*(\\d+)");
    private static final Pattern UNIT = Pattern.compile("L:\\s*(\\d+)/(\\d+)");
    private static final Pattern METER = Pattern.compile("(?m)^M:\\s*(\\d+)/(\\d+)");
    // a note or rest with its length: optional accidental, letter, octave marks, numerator, slashes, denominator
    private static final Pattern TOKEN = Pattern.compile("([_^=]*)([A-Ga-gzZx])([,']*)(\\d*)(/*)(\\d*)(-?)");

    static int syllables(String text) {
        int n = 0;
        String[] words = text.toLowerCase().split("[^a-z0-9']+");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.length() == 0) continue;
            int c = 0;
            boolean inVowel = false;
            for (int j = 0; j < w.length(); j++) {
                boolean v = "aeiouy".indexOf(w.charAt(j)) >= 0;
                if (v && !inVowel) c++;
                inVowel = v;
            }
            if (w.endsWith("e") && !w.endsWith("le") && c > 1) c--; // silent e
            n += Math.max(1, c);
        }
        return Math.max(1, n);
    }

    /** verse, chorus, bridge and so on, from "[Verse 1]" or "% verse". */
    static String kind(String label) {
        String s = label.toLowerCase().replaceAll("[^a-z]", "");
        if (s.startsWith("prechorus")) return "prechorus";
        String[] kinds = {"intro", "verse", "chorus", "bridge", "outro", "interlude", "hook", "refrain", "solo", "break"};
        for (int i = 0; i < kinds.length; i++) if (s.startsWith(kinds[i])) return kinds[i];
        return s;
    }

    private static ArrayList<LyricSection> lyricSections(String lyrics) {
        ArrayList<LyricSection> out = new ArrayList<LyricSection>();
        LyricSection cur = null;
        String[] rows = lyrics.replace("\u200b", "").replace("\ufeff", "").split("\n");
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i].trim();
            if (r.length() == 0) continue;
            if (r.startsWith("[")) {
                cur = new LyricSection();
                cur.type = kind(r);
                out.add(cur);
                continue;
            }
            if (r.startsWith("(") && r.endsWith(")")) continue; // a stage direction, not sung
            if (cur == null) {
                cur = new LyricSection();
                out.add(cur);
            }
            Line l = new Line();
            l.text = r;
            l.syllables = syllables(r);
            cur.lines.add(l);
        }
        return out;
    }

    private static ArrayList<ScoreSection> scoreSections(String abc) {
        ArrayList<ScoreSection> out = new ArrayList<ScoreSection>();
        if (abc == null || abc.trim().length() == 0) return out;
        double quarter = 0.5; // seconds
        Matcher m = TEMPO.matcher(abc);
        if (m.find()) {
            double beat = Integer.parseInt(m.group(1)) / (double) Integer.parseInt(m.group(2)); // in whole notes
            quarter = 60.0 / Integer.parseInt(m.group(3)) * (0.25 / beat);
        }
        double unit = quarter * 4 / 8; // default L:1/8
        m = UNIT.matcher(abc);
        if (m.find()) unit = quarter * 4 * Integer.parseInt(m.group(1)) / Integer.parseInt(m.group(2));
        double bar = quarter * 4; // default M:4/4
        m = METER.matcher(abc);
        if (m.find()) bar = quarter * 4 * Integer.parseInt(m.group(1)) / Integer.parseInt(m.group(2)); // M:C and C| are 4/4 long too

        double t = 0;
        boolean vocal = false, tied = false;
        ScoreSection cur = null;
        String[] rows = abc.split("\n");
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i].trim();
            if (r.startsWith("%")) {
                if (cur != null) cur.end = t;
                cur = new ScoreSection();
                cur.type = kind(r);
                cur.start = t;
                out.add(cur);
                continue;
            }
            if (r.startsWith("V:")) {
                if (r.indexOf('=') < 0) vocal = r.substring(2).trim().toLowerCase().startsWith("vocal");
                continue;
            }
            if (!vocal || r.length() < 2 || (Character.isLetter(r.charAt(0)) && r.charAt(1) == ':')) continue;
            String notes = r.replaceAll("\"[^\"]*\"", "").replaceAll("![^!]*!", "");
            Matcher k = TOKEN.matcher(notes);
            while (k.find()) {
                char c = k.group(2).charAt(0);
                double len = k.group(4).length() > 0 ? Integer.parseInt(k.group(4)) : 1;
                int slashes = k.group(5).length();
                if (slashes > 0) len /= k.group(6).length() > 0 ? Integer.parseInt(k.group(6)) : Math.pow(2, slashes);
                if (c == 'Z') { // whole-bar rests: Z is one bar, Z4 is four; the vocal often opens with them
                    t += (k.group(4).length() > 0 ? Integer.parseInt(k.group(4)) : 1) * bar;
                    tied = false;
                    continue;
                }
                boolean rest = c == 'z' || c == 'x';
                if (!rest && !tied && cur != null) cur.onsets.add(Double.valueOf(t));
                tied = !rest && k.group(7).length() > 0;
                t += len * unit;
            }
        }
        if (cur != null) cur.end = t;
        return out;
    }

    private static void spread(ArrayList<Line> lines, double from, double to) {
        int total = 0;
        for (int i = 0; i < lines.size(); i++) total += lines.get(i).syllables;
        int done = 0;
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).start = (int) (1000 * (from + (to - from) * done / (double) Math.max(1, total)));
            done += lines.get(i).syllables;
        }
    }

    /** The sung lines in order, without times. */
    public static ArrayList<Line> lines(String lyrics) {
        ArrayList<Line> all = new ArrayList<Line>();
        ArrayList<LyricSection> ls = lyricSections(lyrics);
        for (int i = 0; i < ls.size(); i++) all.addAll(ls.get(i).lines);
        return all;
    }

    /** Lines with start times that were set by hand (one per line), and ends worked out from them. */
    public static ArrayList<Line> timed(String lyrics, int[] starts, int durationMs) {
        ArrayList<Line> all = lines(lyrics);
        for (int i = 0; i < all.size() && i < starts.length; i++) all.get(i).start = starts[i];
        finish(all, durationMs);
        return all;
    }

    private static void finish(ArrayList<Line> all, int durationMs) {
        for (int i = 0; i < all.size(); i++) {
            Line l = all.get(i);
            if (i > 0 && l.start < all.get(i - 1).start) l.start = all.get(i - 1).start; // keep order
            l.start = Math.max(0, Math.min(durationMs, l.start));
        }
        for (int i = 0; i < all.size(); i++) {
            Line l = all.get(i);
            int until = i + 1 < all.size() ? all.get(i + 1).start : durationMs;
            // a line is not held longer than it could take to sing; the gap after it is a rest
            int sung = 600 + l.syllables * 330;
            l.end = Math.max(l.start + 300, Math.min(until, l.start + sung));
        }
    }

    /** Lines with estimated start and end times. durationMs is the length of the audio. */
    public static ArrayList<Line> build(String lyrics, String abc, int durationMs) {
        ArrayList<LyricSection> ls = lyricSections(lyrics);
        ArrayList<ScoreSection> ss = scoreSections(abc);
        ArrayList<Line> all = new ArrayList<Line>();
        double dur = durationMs / 1000.0;

        if (ss.size() == 0) {
            // no score: everything spread over the song, leaving room for an intro and an ending
            for (int i = 0; i < ls.size(); i++) all.addAll(ls.get(i).lines);
            spread(all, dur * 0.08, dur * 0.94);
        } else {
            int next = 0;
            double lastEnd = 0;
            for (int i = 0; i < ls.size(); i++) {
                LyricSection l = ls.get(i);
                if (l.lines.size() == 0) continue;
                ScoreSection match = null;
                for (int j = next; j < ss.size(); j++) {
                    if (ss.get(j).type.equals(l.type)) {
                        match = ss.get(j);
                        next = j + 1;
                        break;
                    }
                }
                if (match == null && next < ss.size()) match = ss.get(next++); // bridge sung over an "interlude", say
                if (match == null) {
                    spread(l.lines, lastEnd, Math.max(lastEnd + 1, dur * 0.97));
                } else {
                    int total = 0;
                    for (int k = 0; k < l.lines.size(); k++) total += l.lines.get(k).syllables;
                    int n = match.onsets.size();
                    if (n >= Math.max(3, total / 3)) {
                        // sung section: each line starts on the note its first syllable lands on
                        int done = 0;
                        for (int k = 0; k < l.lines.size(); k++) {
                            int at = Math.min(n - 1, (int) Math.floor(done * (double) n / total));
                            l.lines.get(k).start = (int) (1000 * match.onsets.get(at).doubleValue());
                            done += l.lines.get(k).syllables;
                        }
                    } else {
                        spread(l.lines, match.start, match.end);
                    }
                    lastEnd = match.end;
                }
                all.addAll(l.lines);
            }
        }

        finish(all, durationMs);
        return all;
    }
}
