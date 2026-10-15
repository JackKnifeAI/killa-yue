package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * abcjs's SVG read back into shapes and text, so the app draws the music itself: a vector PDF for the
 * plain sheet and, for the Showpiece, the same shapes in the skin's material. abcjs draws every symbol,
 * beam, tie and staff line as a filled path; each item keeps its abcjs classes (abcjs-notehead,
 * abcjs-beam-elem, abcjs-tie, ...), its glyph name (noteheads.quarter, clefs.G, ...) and the line of
 * music it belongs to. Plain Java, so it is tested on any machine (app/test).
 */
public class SvgScore {
    static final float MOVE = 1, LINE = 2, CUBIC = 3, QUAD = 4, CLOSE = 5;

    static class Item {
        String cls = "", name;
        int line = -1;           // the line of music (abcjs-lN), -1 for the title, tempo and the like
        float[] ops;             // a shape: MOVE x y, LINE x y, CUBIC x1 y1 x2 y2 x y, QUAD x1 y1 x y, CLOSE
        float[] box;             // minX, minY, maxX, maxY
        String text, family;     // a text
        float x, y, size;
        int anchor;              // 0 start, 1 middle, 2 end
        boolean bold, italic;

        boolean has(String c) {
            return (" " + cls + " ").contains(" " + c + " ");
        }
    }

    float width, height;
    final ArrayList<Item> items = new ArrayList<Item>();

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z]+)((?:[^>\"]|\"[^\"]*\")*?)(/?)>|([^<]+)");
    private static final Pattern ATTR = Pattern.compile("([\\w:-]+)=\"([^\"]*)\"");
    private static final Pattern LINE_CLASS = Pattern.compile("\\babcjs-l(\\d+)\\b");

    private static class Scope {
        String cls = "", name;
        float[] m = {1, 0, 0, 1, 0, 0}; // a b c d e f: x' = a x + c y + e, y' = b x + d y + f
    }

    static SvgScore parse(String svg) {
        SvgScore s = new SvgScore();
        ArrayList<Scope> stack = new ArrayList<Scope>();
        stack.add(new Scope());
        Item text = null;
        StringBuilder words = null;
        boolean skip = false; // inside <style> or <title>
        Matcher t = TAG.matcher(svg);
        while (t.find()) {
            if (t.group(5) != null) {
                if (words != null && !skip) words.append(unescape(t.group(5)));
                continue;
            }
            boolean close = t.group(1).length() > 0, empty = t.group(4).length() > 0;
            String tag = t.group(2);
            if (close) {
                if (tag.equals("g") || tag.equals("svg")) {
                    if (stack.size() > 1) stack.remove(stack.size() - 1);
                } else if (tag.equals("text") && text != null) {
                    text.text = words.toString();
                    s.items.add(text);
                    text = null;
                    words = null;
                } else if (tag.equals("style") || tag.equals("title")) {
                    skip = false;
                }
                continue;
            }
            if (tag.equals("style") || tag.equals("title")) {
                skip = !empty;
                continue;
            }
            HashMap<String, String> a = attrs(t.group(3));
            Scope up = stack.get(stack.size() - 1), here = new Scope();
            here.cls = (up.cls + " " + get(a, "class", "")).trim();
            here.name = a.containsKey("data-name") ? a.get("data-name") : up.name;
            here.m = a.containsKey("transform") ? times(up.m, transform(a.get("transform"))) : up.m;
            if (tag.equals("svg")) {
                s.width = num(get(a, "width", "0"));
                s.height = num(get(a, "height", "0"));
            }
            if ((tag.equals("g") || tag.equals("svg")) && !empty) {
                stack.add(here);
            } else if (tag.equals("path") && a.containsKey("d")) {
                Item i = item(here);
                i.ops = apply(here.m, pathOps(a.get("d")));
                i.box = box(i.ops);
                s.items.add(i);
            } else if (tag.equals("text")) {
                text = item(here);
                text.x = num(get(a, "x", "0"));
                text.y = num(get(a, "y", "0"));
                float[] p = apply(here.m, new float[] {MOVE, text.x, text.y});
                text.x = p[1];
                text.y = p[2];
                text.size = num(get(a, "font-size", "12")) * (float) Math.sqrt(Math.abs(here.m[0] * here.m[3] - here.m[1] * here.m[2]));
                text.family = get(a, "font-family", "serif");
                text.bold = get(a, "font-weight", "").equals("bold");
                text.italic = get(a, "font-style", "").equals("italic");
                String an = get(a, "text-anchor", "start");
                text.anchor = an.equals("middle") ? 1 : an.equals("end") ? 2 : 0;
                words = new StringBuilder();
                if (empty) {
                    text.text = "";
                    text = null;
                    words = null;
                }
            }
        }
        return s;
    }

    private static Item item(Scope sc) {
        Item i = new Item();
        i.cls = sc.cls;
        i.name = sc.name;
        Matcher l = LINE_CLASS.matcher(sc.cls);
        if (l.find()) i.line = Integer.parseInt(l.group(1));
        return i;
    }

    private static HashMap<String, String> attrs(String s) {
        HashMap<String, String> a = new HashMap<String, String>();
        Matcher m = ATTR.matcher(s);
        while (m.find()) a.put(m.group(1), unescape(m.group(2)));
        return a;
    }

    private static String get(HashMap<String, String> a, String k, String dflt) {
        String v = a.get(k);
        return v == null ? dflt : v;
    }

    private static float num(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            if (e.startsWith("#x")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            else r = e.equals("amp") ? "&" : e.equals("lt") ? "<" : e.equals("gt") ? ">" : e.equals("quot") ? "\"" : "'";
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The gap a tab number clears in its string line, as printed tab has it (abcjs draws the number over the
     * line): minX, minY, maxX, maxY. Null for anything else.
     */
    static float[] gap(Item i) {
        if (i.text == null || !i.has("abcjs-tab-number")) return null;
        float w = i.text.length() * 0.556f * i.size, pad = 0.12f * i.size; // Arial's figures are 0.556 em wide
        float x0 = i.anchor == 1 ? i.x - w / 2 : i.anchor == 2 ? i.x - w : i.x;
        return new float[] {x0 - pad, i.y - 0.8f * i.size, x0 + w + pad, i.y + 0.1f * i.size};
    }

    // ---------- path data ----------

    private static final Pattern PATH_TOKEN = Pattern.compile("[MmLlHhVvCcSsQqTtZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    /** SVG path data as absolute operations: MOVE x y, LINE x y, CUBIC x1 y1 x2 y2 x y, QUAD x1 y1 x y, CLOSE. */
    static float[] pathOps(String d) {
        ArrayList<String> tk = new ArrayList<String>();
        Matcher m = PATH_TOKEN.matcher(d);
        while (m.find()) tk.add(m.group());
        Floats out = new Floats();
        float cx = 0, cy = 0, sx = 0, sy = 0; // current point, start of the subpath
        float lcx = 0, lcy = 0;                // last control point, for S and T
        char cmd = 0, last = 0;
        int i = 0;
        while (i < tk.size()) {
            String s = tk.get(i);
            if (Character.isLetter(s.charAt(0))) {
                cmd = s.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    out.add(CLOSE);
                    cx = sx;
                    cy = sy;
                    last = cmd;
                    continue;
                }
            }
            if (cmd == 0) break;
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    cx = ox + f(tk, i++);
                    cy = oy + f(tk, i++);
                    sx = cx;
                    sy = cy;
                    out.add(MOVE, cx, cy);
                    cmd = rel ? 'l' : 'L'; // more pairs after a move are lines
                    break;
                }
                case 'L':
                    cx = ox + f(tk, i++);
                    cy = oy + f(tk, i++);
                    out.add(LINE, cx, cy);
                    break;
                case 'H':
                    cx = ox + f(tk, i++);
                    out.add(LINE, cx, cy);
                    break;
                case 'V':
                    cy = oy + f(tk, i++);
                    out.add(LINE, cx, cy);
                    break;
                case 'C': {
                    float x1 = ox + f(tk, i++), y1 = oy + f(tk, i++), x2 = ox + f(tk, i++), y2 = oy + f(tk, i++);
                    cx = ox + f(tk, i++);
                    cy = oy + f(tk, i++);
                    out.add(CUBIC, x1, y1, x2, y2, cx, cy);
                    lcx = x2;
                    lcy = y2;
                    break;
                }
                case 'S': {
                    boolean after = "CcSs".indexOf(last) >= 0;
                    float x1 = after ? 2 * cx - lcx : cx, y1 = after ? 2 * cy - lcy : cy;
                    float x2 = ox + f(tk, i++), y2 = oy + f(tk, i++);
                    cx = ox + f(tk, i++);
                    cy = oy + f(tk, i++);
                    out.add(CUBIC, x1, y1, x2, y2, cx, cy);
                    lcx = x2;
                    lcy = y2;
                    break;
                }
                case 'Q': {
                    float x1 = ox + f(tk, i++), y1 = oy + f(tk, i++);
                    cx = ox + f(tk, i++);
                    cy = oy + f(tk, i++);
                    out.add(QUAD, x1, y1, cx, cy);
                    lcx = x1;
                    lcy = y1;
                    break;
                }
                case 'T': {
                    boolean after = "QqTt".indexOf(last) >= 0;
                    float x1 = after ? 2 * cx - lcx : cx, y1 = after ? 2 * cy - lcy : cy;
                    cx = ox + f(tk, i++);
                    cy = oy + f(tk, i++);
                    out.add(QUAD, x1, y1, cx, cy);
                    lcx = x1;
                    lcy = y1;
                    break;
                }
                default:
                    i++; // arcs are not used by abcjs
            }
            last = cmd;
        }
        return out.array();
    }

    private static float f(ArrayList<String> tk, int i) {
        return i < tk.size() ? Float.parseFloat(tk.get(i)) : 0;
    }

    private static class Floats {
        float[] a = new float[64];
        int n;

        void add(float... v) {
            if (n + v.length > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, n + v.length));
            System.arraycopy(v, 0, a, n, v.length);
            n += v.length;
        }

        float[] array() {
            return java.util.Arrays.copyOf(a, n);
        }
    }

    /** How many numbers follow each operation code. */
    static int args(float op) {
        return op == MOVE || op == LINE ? 2 : op == CUBIC ? 6 : op == QUAD ? 4 : 0;
    }

    private static float[] apply(float[] m, float[] ops) {
        if (m[0] == 1 && m[1] == 0 && m[2] == 0 && m[3] == 1 && m[4] == 0 && m[5] == 0) return ops;
        float[] out = ops.clone();
        for (int i = 0; i < out.length; ) {
            int n = args(out[i]);
            for (int k = i + 1; k < i + 1 + n; k += 2) {
                float x = out[k], y = out[k + 1];
                out[k] = m[0] * x + m[2] * y + m[4];
                out[k + 1] = m[1] * x + m[3] * y + m[5];
            }
            i += 1 + n;
        }
        return out;
    }

    private static float[] box(float[] ops) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < ops.length; ) {
            int n = args(ops[i]);
            for (int k = i + 1; k < i + 1 + n; k += 2) {
                b[0] = Math.min(b[0], ops[k]);
                b[1] = Math.min(b[1], ops[k + 1]);
                b[2] = Math.max(b[2], ops[k]);
                b[3] = Math.max(b[3], ops[k + 1]);
            }
            i += 1 + n;
        }
        return b;
    }

    // ---------- transforms ----------

    private static float[] times(float[] p, float[] q) { // p then q, as nested SVG transforms compose
        return new float[] {p[0] * q[0] + p[2] * q[1], p[1] * q[0] + p[3] * q[1], p[0] * q[2] + p[2] * q[3], p[1] * q[2] + p[3] * q[3],
                p[0] * q[4] + p[2] * q[5] + p[4], p[1] * q[4] + p[3] * q[5] + p[5]};
    }

    private static float[] transform(String s) {
        float[] m = {1, 0, 0, 1, 0, 0};
        Matcher t = Pattern.compile("(translate|scale|matrix)\\s*\\(([^)]*)\\)").matcher(s);
        while (t.find()) {
            String[] p = t.group(2).trim().split("[\\s,]+");
            float[] v = new float[p.length];
            for (int i = 0; i < p.length; i++) v[i] = num(p[i]);
            float[] q;
            if (t.group(1).equals("translate")) q = new float[] {1, 0, 0, 1, v[0], v.length > 1 ? v[1] : 0};
            else if (t.group(1).equals("scale")) q = new float[] {v[0], 0, 0, v.length > 1 ? v[1] : v[0], 0, 0};
            else q = v.length == 6 ? v : new float[] {1, 0, 0, 1, 0, 0};
            m = times(m, q);
        }
        return m;
    }

    // ---------- lines and pages ----------

    /** The box around one line of music (minX, minY, maxX, maxY), or null when there is no such line. */
    float[] lineBox(int line) {
        float[] b = null;
        for (Item i : items) {
            if (i.line != line) continue;
            float[] ib = i.box != null ? i.box : new float[] {i.x, i.y - i.size, i.x, i.y + i.size * 0.3f};
            if (b == null) b = ib.clone();
            else {
                b[0] = Math.min(b[0], ib[0]);
                b[1] = Math.min(b[1], ib[1]);
                b[2] = Math.max(b[2], ib[2]);
                b[3] = Math.max(b[3], ib[3]);
            }
        }
        return b;
    }

    /** How many lines of music there are. */
    int lines() {
        int n = 0;
        for (Item i : items) n = Math.max(n, i.line + 1);
        return n;
    }

    /**
     * How far down each line of music must move so it starts at least gap below the line above it. abcjs
     * spaces lines by their staves, so a line with notes high above its staff can run into the tab or low
     * notes of the line above. Lines move down only, and every line below moves with them.
     */
    static float[] spread(float[][] lines, float gap) {
        float[] shift = new float[lines.length];
        float moved = 0;
        for (int i = 1; i < lines.length; i++) {
            boolean empty = lines[i][0] == lines[i - 1][0] && lines[i][1] == lines[i - 1][1]; // ScorePages gives it the line above's span
            float need = empty ? 0 : lines[i - 1][1] + shift[i - 1] + gap - (lines[i][0] + moved);
            if (need > 0) moved += need;
            shift[i] = moved;
        }
        return shift;
    }

    /**
     * Lines of music packed onto pages in order: each page holds the lines that fit in pageHeight from
     * the top of its first line (the first page from the top of the title block). A line taller than a
     * page gets a page to itself.
     */
    static int[][] paginate(float[] top, float[][] lines, float pageHeight) {
        ArrayList<int[]> pages = new ArrayList<int[]>();
        ArrayList<Integer> cur = new ArrayList<Integer>();
        float start = top != null ? Math.min(top[0], lines.length > 0 ? lines[0][0] : top[0]) : (lines.length > 0 ? lines[0][0] : 0);
        for (int i = 0; i < lines.length; i++) {
            if (cur.size() > 0 && lines[i][1] - start > pageHeight) {
                pages.add(ints(cur));
                cur.clear();
                start = lines[i][0];
            }
            cur.add(Integer.valueOf(i));
        }
        if (cur.size() > 0) pages.add(ints(cur));
        return pages.toArray(new int[0][]);
    }

    private static int[] ints(ArrayList<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i).intValue();
        return a;
    }
}
