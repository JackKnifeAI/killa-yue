package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Chords to play along to the vocal line: under each line of music a tab with every chord's shape stacked
 * on the strings, under its chord name; and under the title a diagram of each chord, showing where the
 * fingers go and the fret it starts at. Shapes come from Chords and are drawn here, not by abcjs, so the
 * tab and the diagram always show the same shape. Everything is added to the score as items of its lines.
 */
class ChordSheet {
    static final float TAB_GAP = 12, STRING_GAP = 9, NUMBER_SIZE = 11; // in abcjs's units
    static final float DIAGRAM_STRING = 11, DIAGRAM_FRET = 13, DIAGRAM_FRETS = 5, DOT = 4.2f;

    /** Adds the tab and the diagrams. Returns the chords, each once, in order. Throws for a chord it cannot play. */
    static List<String> add(SvgScore s, Instruments.Instrument ins) {
        int[] strings = ins.chordStrings();
        LinkedHashMap<String, int[]> shapes = new LinkedHashMap<String, int[]>();
        ArrayList<SvgScore.Item> added = new ArrayList<SvgScore.Item>();
        for (int line = 0; line < s.lines(); line++) {
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
            ArrayList<SvgScore.Item> names = new ArrayList<SvgScore.Item>();
            ArrayList<Float> bars = new ArrayList<Float>();
            for (SvgScore.Item i : s.items) {
                if (i.line != line) continue;
                if (i.has("abcjs-staff") && i.box != null) {
                    x0 = Math.min(x0, i.box[0]);
                    x1 = Math.max(x1, i.box[2]);
                }
                if (i.has("abcjs-chord") && i.text != null && i.text.trim().length() > 0) names.add(i);
                if (i.has("abcjs-bar") && i.box != null) bars.add(i.box[0]);
            }
            if (x0 > x1) continue; // a line with no staff
            float[] box = s.lineBox(line);
            float top = box[3] + TAB_GAP, bottom = top + STRING_GAP * (strings.length - 1);
            for (int k = 0; k < strings.length; k++) {
                float y = top + STRING_GAP * k;
                added.add(path(line, "killa-chord-tab killa-tab-line", rect(x0, y - 0.3f, x1, y + 0.3f)));
            }
            for (float b : bars) added.add(path(line, "killa-chord-tab killa-tab-bar", rect(b, top, b + 0.6f, bottom)));
            added.add(path(line, "killa-chord-tab", rect(x0, top, x0 + 0.6f, bottom)));
            float letter = (bottom - top) / 3.4f;
            String[] tab = {"T", "A", "B"};
            for (int k = 0; k < 3; k++) {
                added.add(text(line, "killa-chord-tab killa-tab-clef", tab[k], x0 + 8, top + (bottom - top) * (k + 0.5f) / 3 + letter * 0.36f,
                        letter, 1, true));
            }
            for (SvgScore.Item n : names) {
                String name = n.text.trim();
                if (Chords.noChord(name)) continue;
                int[] f = shapes.get(name);
                if (f == null) {
                    f = Chords.shape(Chords.parse(name), ins);
                    shapes.put(name, f);
                }
                for (int k = 0; k < f.length; k++) {
                    if (f[k] < 0) continue;
                    float y = bottom - STRING_GAP * k; // the lowest string is the bottom line
                    added.add(text(line, "killa-chord-tab abcjs-tab-number", String.valueOf(f[k]), n.x, y + 0.36f * NUMBER_SIZE,
                            NUMBER_SIZE, 1, false));
                }
            }
        }
        s.items.addAll(added);
        diagrams(s, shapes, strings.length);
        return new ArrayList<String>(shapes.keySet());
    }

    /** A diagram per chord in rows under the title, and the music moved down to clear them. */
    private static void diagrams(SvgScore s, LinkedHashMap<String, int[]> shapes, int n) {
        if (shapes.isEmpty()) return;
        float headBottom = 0;
        float musicTop = Float.MAX_VALUE;
        for (SvgScore.Item i : s.items) {
            float[] b = bounds(i);
            if (i.line < 0) headBottom = Math.max(headBottom, b[3]);
            else musicTop = Math.min(musicTop, b[1]);
        }
        float gridW = DIAGRAM_STRING * (n - 1), cell = gridW + 44, rowH = 34 + DIAGRAM_FRET * DIAGRAM_FRETS + 16;
        int perRow = Math.max(1, (int) (s.width / cell));
        int rows = (shapes.size() + perRow - 1) / perRow;
        float y = headBottom + 12;
        ArrayList<SvgScore.Item> out = new ArrayList<SvgScore.Item>();
        int k = 0;
        for (String name : shapes.keySet()) {
            int row = k / perRow, col = k % perRow;
            int inRow = Math.min(perRow, shapes.size() - row * perRow);
            float left = (s.width - inRow * cell) / 2 + col * cell + (cell - gridW) / 2;
            diagram(out, name, shapes.get(name), left, y + row * rowH);
            k++;
        }
        s.items.addAll(out);
        float blockBottom = y + rows * rowH;
        float dy = blockBottom + 8 - musicTop;
        if (dy > 0) for (SvgScore.Item i : s.items) if (i.line >= 0) move(i, dy);
    }

    /** One chord diagram: name, open and unplayed marks, nut or starting fret, strings, frets, dots and barre. */
    private static void diagram(ArrayList<SvgScore.Item> out, String name, int[] f, float left, float top) {
        int n = f.length, max = 0, min = 99;
        for (int x : f) if (x > 0) {
            max = Math.max(max, x);
            min = Math.min(min, x);
        }
        int start = max <= DIAGRAM_FRETS ? 1 : min; // the first fret the diagram shows
        float gridW = DIAGRAM_STRING * (n - 1), gridTop = top + 32, gridBottom = gridTop + DIAGRAM_FRET * DIAGRAM_FRETS;
        out.add(text(-1, "killa-diagram killa-diagram-name", name, left + gridW / 2, top + 15, 16, 1, true));
        for (int k = 0; k < n; k++) {
            float x = left + DIAGRAM_STRING * k;
            out.add(path(-1, "killa-diagram", rect(x - 0.3f, gridTop, x + 0.3f, gridBottom)));
            if (f[k] < 0) out.add(text(-1, "killa-diagram killa-diagram-unplayed", "\u00d7", x, gridTop - 4, 11, 1, false));
            else if (f[k] == 0) out.add(path(-1, "killa-diagram killa-diagram-open", ring(x, gridTop - 7.5f, 3.2f, 0.8f)));
        }
        for (int r = 0; r <= DIAGRAM_FRETS; r++) {
            float y = gridTop + DIAGRAM_FRET * r, w = r == 0 && start == 1 ? 2.0f : 0.35f; // the nut is thick
            out.add(path(-1, "killa-diagram", rect(left - 0.3f, y - w, left + gridW + 0.3f, y + w)));
        }
        if (start > 1) out.add(text(-1, "killa-diagram killa-diagram-fret", start + "fr", left - 6, gridTop + DIAGRAM_FRET * 0.5f + 3.5f, 10, 2, false));
        int base = 99;
        for (int x : f) if (x > 0) base = Math.min(base, x);
        boolean barre = base < 99 && Chords.barred(f, base);
        if (barre) {
            int from = -1;
            for (int k = 0; k < n; k++) if (f[k] == base) {
                from = k;
                break;
            }
            float y = gridTop + DIAGRAM_FRET * (base - start + 0.5f);
            out.add(path(-1, "killa-diagram killa-diagram-barre", capsule(left + DIAGRAM_STRING * from, left + DIAGRAM_STRING * (n - 1), y, DOT)));
        }
        for (int k = 0; k < n; k++) {
            if (f[k] <= 0 || barre && f[k] == base) continue;
            float y = gridTop + DIAGRAM_FRET * (f[k] - start + 0.5f);
            out.add(path(-1, "killa-diagram killa-diagram-dot", disc(left + DIAGRAM_STRING * k, y, DOT)));
        }
    }

    // ---------- shapes as items ----------

    private static SvgScore.Item path(int line, String cls, float[] ops) {
        SvgScore.Item i = new SvgScore.Item();
        i.line = line;
        i.cls = cls + (line >= 0 ? " abcjs-l" + line : "");
        i.ops = ops;
        i.box = SvgScore.box(ops);
        return i;
    }

    private static SvgScore.Item text(int line, String cls, String t, float x, float y, float size, int anchor, boolean bold) {
        SvgScore.Item i = new SvgScore.Item();
        i.line = line;
        i.cls = cls + (line >= 0 ? " abcjs-l" + line : "");
        i.text = t;
        i.x = x;
        i.y = y;
        i.size = size;
        i.anchor = anchor;
        i.bold = bold;
        i.family = bold ? "Times New Roman" : "Arial";
        return i;
    }

    private static float[] rect(float x0, float y0, float x1, float y1) {
        float M = SvgScore.MOVE, L = SvgScore.LINE, Z = SvgScore.CLOSE;
        return new float[] {M, x0, y0, L, x1, y0, L, x1, y1, L, x0, y1, Z};
    }

    /** A bar with round ends from x0 to x1, as a barre is drawn: a rectangle and a disc at each end. */
    private static float[] capsule(float x0, float x1, float y, float r) {
        float[] a = rect(x0, y - r, x1, y + r), b = disc(x0, y, r), c = disc(x1, y, r);
        float[] out = new float[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    /** A filled circle as four cubic curves. */
    private static float[] disc(float cx, float cy, float r) {
        float k = 0.5523f * r, M = SvgScore.MOVE, C = SvgScore.CUBIC, Z = SvgScore.CLOSE;
        return new float[] {M, cx + r, cy,
            C, cx + r, cy + k, cx + k, cy + r, cx, cy + r,
            C, cx - k, cy + r, cx - r, cy + k, cx - r, cy,
            C, cx - r, cy - k, cx - k, cy - r, cx, cy - r,
            C, cx + k, cy - r, cx + r, cy - k, cx + r, cy, Z};
    }

    /** A ring: an outer circle and an inner one drawn the other way round, so the middle stays empty. */
    private static float[] ring(float cx, float cy, float r, float w) {
        float[] out = disc(cx, cy, r);
        float ri = r - w, k = 0.5523f * ri, M = SvgScore.MOVE, C = SvgScore.CUBIC, Z = SvgScore.CLOSE;
        float[] in = {M, cx + ri, cy,
            C, cx + ri, cy - k, cx + k, cy - ri, cx, cy - ri,
            C, cx - k, cy - ri, cx - ri, cy - k, cx - ri, cy,
            C, cx - ri, cy + k, cx - k, cy + ri, cx, cy + ri,
            C, cx + k, cy + ri, cx + ri, cy + k, cx + ri, cy, Z};
        float[] both = new float[out.length + in.length];
        System.arraycopy(out, 0, both, 0, out.length);
        System.arraycopy(in, 0, both, out.length, in.length);
        return both;
    }

    private static float[] bounds(SvgScore.Item i) {
        if (i.box != null) return i.box;
        return new float[] {i.x, i.y - i.size, i.x, i.y + i.size * 0.3f};
    }

    /** Moves an item down by dy. */
    static void move(SvgScore.Item i, float dy) {
        if (i.ops != null) {
            for (int k = 0; k < i.ops.length; ) {
                int args = SvgScore.args(i.ops[k]);
                for (int a = 2; a <= args; a += 2) i.ops[k + a] += dy;
                k += 1 + args;
            }
        }
        if (i.box != null) {
            i.box[1] += dy;
            i.box[3] += dy;
        }
        i.y += dy;
    }
}
