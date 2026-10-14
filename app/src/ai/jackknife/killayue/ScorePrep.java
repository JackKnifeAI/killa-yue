package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a song's ABC score ready for engraving with abcjs without changing a single note:
 * beams regrouped by beat (the song model writes no spaces, so abcjs would beam whole bars),
 * multi-bar rests spelled out when there are several staves (so all staves keep matching bar lines),
 * section comments turned into labels, bars reflowed into evenly filled lines, a title and readable
 * part names. It knows the slice of ABC the song model writes; a line with anything else in it is
 * left as written, and a score it cannot follow keeps its own line breaks.
 */
public class ScorePrep {
    static final int MAX_BARS = 4;                  // bars in one line at most
    static final int LINE_WIDTH = 700;              // the abcjs staff width lines are filled to
    static final int BAR_BASE = 36, PER_EVENT = 18; // estimated bar width: a base plus each note or rest

    private static final int NAME = 1, NOTE = 2, REST = 3, MULTI = 4, TIE = 5, BAR = 6, SPACE = 7;
    private static final Pattern TOKEN = Pattern.compile(
            "(\"[^\"]*\")"                                // 1 chord name or annotation
            + "|([\\^_=]*[A-Ga-g][,']*)(\\d*)(/*)(\\d*)"  // 2 note, 3-5 its length
            + "|([zx])(\\d*)(/*)(\\d*)"                   // 6 rest, 7-9 its length
            + "|Z(\\d*)"                                  // 10 bars of a multi-bar rest
            + "|(-)"                                      // 11 tie
            + "|(::|\\[\\||\\|\\]|:?\\|\\|?:?)"           // 12 bar line
            + "|( +)");                                   // 13 spaces

    private static class Tok {
        int kind;
        String text;
        long num = 0, den = 1; // length in units of L
        int bars;              // of a multi-bar rest
    }

    /** The line split into tokens, or null when it holds anything this class does not know. */
    private static ArrayList<Tok> tokens(String line) {
        ArrayList<Tok> out = new ArrayList<Tok>();
        Matcher m = TOKEN.matcher(line);
        int at = 0;
        while (at < line.length()) {
            m.region(at, line.length());
            if (!m.lookingAt()) return null;
            Tok t = new Tok();
            t.text = m.group();
            if (m.group(1) != null) {
                t.kind = NAME;
            } else if (m.group(2) != null) {
                t.kind = NOTE;
                length(t, m.group(3), m.group(4), m.group(5));
            } else if (m.group(6) != null) {
                t.kind = REST;
                length(t, m.group(7), m.group(8), m.group(9));
            } else if (m.group(10) != null) {
                t.kind = MULTI;
                t.bars = m.group(10).length() == 0 ? 1 : Integer.parseInt(m.group(10));
            } else if (m.group(11) != null) {
                t.kind = TIE;
            } else if (m.group(12) != null) {
                t.kind = BAR;
            } else {
                t.kind = SPACE;
            }
            out.add(t);
            at = m.end();
        }
        return out;
    }

    private static void length(Tok t, String num, String slashes, String den) {
        t.num = num.length() == 0 ? 1 : Long.parseLong(num);
        t.den = den.length() > 0 ? Long.parseLong(den) : 1L << slashes.length();
    }

    private static long gcd(long a, long b) {
        return b == 0 ? Math.abs(a) : gcd(b, a % b);
    }

    /** The beat as a fraction of the unit length L: {num, den}. Compound metres (6/8, 9/8, 12/8) beat in dotted quarters. */
    private static long[] beat(int meterNum, int meterDen, int unitNum, int unitDen) {
        boolean compound = meterDen == 8 && meterNum % 3 == 0 && meterNum > 3;
        long n = (compound ? 3L : 1L) * unitDen, d = (long) meterDen * unitNum;
        long g = gcd(n, d);
        return new long[] {n / g, d / g};
    }

    /** Spaces at the beats, so abcjs beams note groups the way printed music does. */
    static String rebeam(String music, int meterNum, int meterDen, int unitNum, int unitDen) {
        ArrayList<Tok> ts = tokens(music);
        if (ts == null) return music;
        long[] beat = beat(meterNum, meterDen, unitNum, unitDen);
        StringBuilder out = new StringBuilder();
        long pn = 0, pd = 1; // position in the bar, in units of L
        for (int i = 0; i < ts.size(); i++) {
            Tok t = ts.get(i);
            out.append(t.text);
            if (t.kind == BAR) {
                pn = 0;
                pd = 1;
                continue;
            }
            if (t.kind == NOTE || t.kind == REST) {
                pn = pn * t.den + t.num * pd;
                pd = pd * t.den;
                long g = gcd(pn, pd);
                pn /= g;
                pd /= g;
            }
            if ((t.kind != NOTE && t.kind != REST && t.kind != TIE) || pn == 0 || i + 1 >= ts.size()) continue;
            int next = ts.get(i + 1).kind;
            if (next != NOTE && next != REST && next != NAME) continue; // a tie, bar line or space comes first
            if ((pn * beat[1]) % (pd * beat[0]) == 0) out.append(' ');
        }
        return out.toString();
    }

    private static class Voice {
        final String id;
        final StringBuilder music = new StringBuilder();
        ArrayList<String> bars;
        ArrayList<Integer> events;

        Voice(String id) {
            this.id = id;
        }
    }

    /** The score ready for abcjs: titled, part names readable, rests spelled out, sections labelled, beams regrouped, bars reflowed. */
    static String prepare(String abc, String title, String subtitle) {
        String[] rows = abc.replace("\r", "").split("\n");
        ArrayList<String> head = new ArrayList<String>();
        LinkedHashMap<String, Voice> voices = new LinkedHashMap<String, Voice>();
        int meterNum = 4, meterDen = 4, unitNum = 1, unitDen = 8;
        boolean titled = false, body = false, plain = true;
        Voice cur = null;
        String pending = null;
        LinkedHashMap<Integer, String> labels = new LinkedHashMap<Integer, String>();
        Pattern voiceLine = Pattern.compile("^V:\\s*(\\S+)(.*)$");
        for (String row : rows) {
            if (!body) {
                if (row.startsWith("T:") && !titled) {
                    head.add("T:" + title);
                    if (subtitle.length() > 0) head.add("T:" + subtitle);
                    titled = true;
                } else if (row.startsWith("T:")) {
                    // the song model writes one empty title; a second one is not ours to keep
                } else {
                    Matcher v = voiceLine.matcher(row);
                    if (v.matches()) {
                        if (!voices.containsKey(v.group(1))) voices.put(v.group(1), new Voice(v.group(1)));
                        head.add("V: " + v.group(1) + names(v.group(2)));
                    } else {
                        head.add(row);
                    }
                    if (row.startsWith("M:")) {
                        int[] m = meter(row.substring(2).trim());
                        meterNum = m[0];
                        meterDen = m[1];
                    } else if (row.startsWith("L:")) {
                        String[] l = row.substring(2).trim().split("/");
                        unitNum = Integer.parseInt(l[0].trim());
                        unitDen = l.length > 1 ? Integer.parseInt(l[1].trim()) : 1;
                    } else if (row.startsWith("K:")) {
                        body = true;
                        if (!titled) { // no title line at all: one goes before the key, where ABC allows it
                            head.add(head.size() - 1, "T:" + title);
                            if (subtitle.length() > 0) head.add(head.size() - 1, "T:" + subtitle);
                            titled = true;
                        }
                    }
                }
                continue;
            }
            String r = row.trim();
            Matcher v = voiceLine.matcher(r);
            if (r.length() == 0) continue;
            if (r.startsWith("%")) {
                String s = r.substring(1).trim();
                if (s.length() > 0) pending = Character.toUpperCase(s.charAt(0)) + s.substring(1);
            } else if (v.matches() && v.group(2).trim().length() == 0) {
                cur = voices.get(v.group(1));
                if (cur == null) {
                    cur = new Voice(v.group(1));
                    voices.put(cur.id, cur);
                }
            } else if (cur == null || r.matches("^[A-Za-z]:.*")) {
                plain = false; // fields inside the music: keep the score's own line breaks
                break;
            } else {
                if (pending != null && cur == voices.values().iterator().next()) {
                    labels.put(Integer.valueOf(barCount(cur.music.toString(), voices.size() > 1)), pending);
                    pending = null;
                }
                cur.music.append(r);
            }
        }
        if (!plain || voices.size() == 0) return lineByLine(rows, title, subtitle, meterNum, meterDen, unitNum, unitDen);

        long barNum = (long) meterNum * unitDen, barDen = (long) meterDen * unitNum, g = gcd(barNum, barDen);
        String fullBar = "z" + lengthText(barNum / g, barDen / g);
        int count = -1;
        for (Voice voice : voices.values()) {
            ArrayList<Tok> ts = tokens(voice.music.toString());
            if (ts == null) return lineByLine(rows, title, subtitle, meterNum, meterDen, unitNum, unitDen);
            voice.bars = new ArrayList<String>();
            voice.events = new ArrayList<Integer>();
            StringBuilder bar = new StringBuilder();
            int events = 0;
            for (Tok t : ts) {
                if (t.kind == MULTI && voices.size() > 1) { // one full-bar rest per bar, anything before it stays in the first
                    bar.append(fullBar);
                    events++;
                    for (int k = 1; k < t.bars; k++) {
                        bar.append('|');
                        voice.bars.add(rebeam(bar.toString(), meterNum, meterDen, unitNum, unitDen));
                        voice.events.add(Integer.valueOf(events));
                        bar.setLength(0);
                        bar.append(fullBar);
                        events = 1;
                    }
                    continue;
                }
                bar.append(t.text);
                if (t.kind == NOTE || t.kind == REST || t.kind == MULTI) events++;
                if (t.kind == BAR) {
                    voice.bars.add(rebeam(bar.toString(), meterNum, meterDen, unitNum, unitDen));
                    voice.events.add(Integer.valueOf(events));
                    bar.setLength(0);
                    events = 0;
                }
            }
            if (bar.length() > 0) {
                voice.bars.add(rebeam(bar.toString(), meterNum, meterDen, unitNum, unitDen));
                voice.events.add(Integer.valueOf(events));
            }
            if (count >= 0 && count != voice.bars.size()) return lineByLine(rows, title, subtitle, meterNum, meterDen, unitNum, unitDen);
            count = voice.bars.size();
        }

        Voice top = voices.values().iterator().next();
        for (Integer at : labels.keySet()) {
            int b = at.intValue();
            if (b < top.bars.size()) top.bars.set(b, "\"^" + labels.get(at) + "\"" + top.bars.get(b));
        }

        StringBuilder out = new StringBuilder();
        for (String h : head) out.append(h).append('\n');
        int b = 0;
        while (b < count) {
            int end = b, width = 0;
            while (end < count && end - b < MAX_BARS) {
                int most = 0;
                for (Voice voice : voices.values()) most = Math.max(most, voice.events.get(end).intValue());
                int w = BAR_BASE + PER_EVENT * most;
                if (end > b && width + w > LINE_WIDTH) break;
                width += w;
                end++;
            }
            for (Voice voice : voices.values()) {
                out.append("V: ").append(voice.id).append('\n');
                for (int k = b; k < end; k++) out.append(voice.bars.get(k));
                out.append('\n');
            }
            b = end;
        }
        return out.toString();
    }

    /** A score this class cannot reflow: titled, part names readable, and each music line's beams regrouped. */
    private static String lineByLine(String[] rows, String title, String subtitle, int meterNum, int meterDen, int unitNum, int unitDen) {
        StringBuilder out = new StringBuilder();
        boolean titled = false, body = false;
        Pattern voiceLine = Pattern.compile("^V:\\s*(\\S+)(.*)$");
        for (String row : rows) {
            if (!body && row.startsWith("T:")) {
                if (!titled) {
                    out.append("T:").append(title).append('\n');
                    if (subtitle.length() > 0) out.append("T:").append(subtitle).append('\n');
                    titled = true;
                }
                continue;
            }
            Matcher v = voiceLine.matcher(row);
            if (!body && v.matches()) {
                out.append("V: ").append(v.group(1)).append(names(v.group(2))).append('\n');
                continue;
            }
            if (row.startsWith("K:")) body = true;
            boolean music = body && !row.startsWith("%") && !row.matches("^[A-Za-z]:.*") && row.trim().length() > 0;
            out.append(music ? rebeam(row, meterNum, meterDen, unitNum, unitDen) : row).append('\n');
        }
        return out.toString();
    }

    /** Part names a reader understands: "Vocal Melody" becomes Vocal, "Ins Melody" becomes Instrument. */
    private static String names(String settings) {
        Matcher m = Pattern.compile("name=\"([^\"]*)\"").matcher(settings);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String n = m.group(1).replaceAll("\\s+Melody$", "");
            if (n.equals("Ins")) n = "Instrument";
            m.appendReplacement(out, Matcher.quoteReplacement("name=\"" + n + "\""));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int[] meter(String m) {
        if (m.equals("C")) return new int[] {4, 4};
        if (m.equals("C|")) return new int[] {2, 2};
        String[] p = m.split("/");
        try {
            return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (Exception e) {
            return new int[] {4, 4};
        }
    }

    /** Bars in the music so far; a multi-bar rest counts as all its bars when it will be spelled out. */
    private static int barCount(String music, boolean spelledOut) {
        ArrayList<Tok> ts = tokens(music);
        if (ts == null) return 0;
        int n = 0;
        for (Tok t : ts) {
            if (t.kind == BAR) n++;
            else if (t.kind == MULTI && spelledOut) n += t.bars - 1; // its own bar line is counted as it comes
        }
        return n;
    }

    private static String lengthText(long num, long den) {
        return (num == 1 && den != 1 ? "" : String.valueOf(num)) + (den == 1 ? "" : "/" + den);
    }
}
