package ai.jackknife.killayue;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Draws a score laid out by abcjs (SvgScore) onto A4 pages. The plain sheet is ink on paper in a vector
 * PDF: every symbol, beam, tie and staff line is one of abcjs's filled shapes, drawn here as it is.
 */
class ScorePages {
    static final float PAGE_W = 595, PAGE_H = 842, MARGIN = 40, FOOT = 26; // A4 in points

    /** Writes the PDF and returns how many pages it has. */
    static int writePdf(SvgScore s, String footer, File out) throws Exception {
        float k = (PAGE_W - 2 * MARGIN) / drawnWidth(s);
        float[][] lines = lineSpans(s);
        float[] top = topSpan(s);
        int[][] pages = SvgScore.paginate(top, lines, (PAGE_H - 2 * MARGIN - FOOT) / k);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.BLACK);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.BLACK);
        fitTitles(s, text);
        PdfDocument doc = new PdfDocument();
        try {
            for (int p = 0; p < pages.length; p++) {
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) PAGE_W, (int) PAGE_H, p + 1).create());
                Canvas c = page.getCanvas();
                boolean[] on = new boolean[lines.length];
                for (int l : pages[p]) on[l] = true;
                float start = lines[pages[p][0]][0];
                if (p == 0 && top != null) start = Math.min(start, top[0]);
                c.save();
                c.translate(MARGIN, MARGIN);
                c.scale(k, k);
                c.translate(0, -start);
                for (SvgScore.Item i : s.items) {
                    boolean mine = i.line < 0 ? p == 0 : i.line < on.length && on[i.line];
                    if (mine) draw(c, i, fill, text);
                }
                c.restore();
                text.setTypeface(Typeface.SANS_SERIF);
                text.setTextSize(8);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(footer + "  ·  page " + (p + 1) + " of " + pages.length, PAGE_W / 2, PAGE_H - MARGIN / 2, text);
                doc.finishPage(page);
            }
            FileOutputStream f = new FileOutputStream(out);
            try {
                doc.writeTo(f);
            } finally {
                f.close();
            }
        } finally {
            doc.close();
        }
        return pages.length;
    }

    /** The whole score on one canvas, unpaginated, in SVG units: for checking the drawing against abcjs's own. */
    static void drawAll(Canvas c, SvgScore s) {
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.BLACK);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.BLACK);
        for (SvgScore.Item i : s.items) draw(c, i, fill, text);
    }

    static void draw(Canvas c, SvgScore.Item i, Paint fill, Paint text) {
        if (i.ops != null) {
            c.drawPath(path(i.ops), fill);
        } else if (i.text != null && i.text.length() > 0) {
            String fam = i.family.toLowerCase();
            Typeface base = fam.contains("times") || fam.contains("serif") && !fam.contains("sans") ? Typeface.SERIF : Typeface.SANS_SERIF;
            text.setTypeface(Typeface.create(base, (i.bold ? Typeface.BOLD : 0) | (i.italic ? Typeface.ITALIC : 0)));
            text.setTextSize(i.size);
            text.setTextAlign(i.anchor == 1 ? Paint.Align.CENTER : i.anchor == 2 ? Paint.Align.RIGHT : Paint.Align.LEFT);
            c.drawText(i.text, i.x, i.y, text);
            if (i.has("abcjs-part")) { // a section label sits on a small plaque, as in printed music
                float w = text.measureText(i.text), x0 = i.anchor == 1 ? i.x - w / 2 : i.anchor == 2 ? i.x - w : i.x, pad = i.size * 0.3f;
                Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
                box.setStyle(Paint.Style.STROKE);
                box.setStrokeWidth(i.size * 0.07f);
                box.setColor(text.getColor());
                c.drawRoundRect(x0 - pad, i.y - i.size * 0.82f, x0 + w + pad, i.y + i.size * 0.3f, pad * 0.6f, pad * 0.6f, box);
            }
        }
    }

    /** Title lines wider than the music (a long style in the subtitle) are made smaller to fit. */
    private static void fitTitles(SvgScore s, Paint text) {
        for (SvgScore.Item i : s.items) {
            if (i.line >= 0 || i.text == null || i.text.length() == 0) continue;
            text.setTypeface(Typeface.create(Typeface.SERIF, (i.bold ? Typeface.BOLD : 0) | (i.italic ? Typeface.ITALIC : 0)));
            text.setTextSize(i.size);
            float w = text.measureText(i.text);
            if (w > s.width * 0.98f) i.size *= s.width * 0.98f / w;
        }
    }

    static Path path(float[] ops) {
        Path p = new Path();
        for (int k = 0; k < ops.length; ) {
            float op = ops[k];
            if (op == SvgScore.MOVE) p.moveTo(ops[k + 1], ops[k + 2]);
            else if (op == SvgScore.LINE) p.lineTo(ops[k + 1], ops[k + 2]);
            else if (op == SvgScore.CUBIC) p.cubicTo(ops[k + 1], ops[k + 2], ops[k + 3], ops[k + 4], ops[k + 5], ops[k + 6]);
            else if (op == SvgScore.QUAD) p.quadTo(ops[k + 1], ops[k + 2], ops[k + 3], ops[k + 4]);
            else p.close();
            k += 1 + SvgScore.args(op);
        }
        return p;
    }

    /** The width of what is drawn: abcjs lets a line too dense for its staff width run past it. */
    static float drawnWidth(SvgScore s) {
        float w = s.width;
        for (SvgScore.Item i : s.items) if (i.box != null) w = Math.max(w, i.box[2]);
        return w;
    }

    /** Each line of music's top and bottom; a line abcjs left empty takes its neighbour's. */
    private static float[][] lineSpans(SvgScore s) {
        int n = Math.max(1, s.lines());
        float[][] out = new float[n][];
        for (int l = 0; l < n; l++) {
            float[] b = s.lineBox(l);
            out[l] = b != null ? new float[] {b[1], b[3]} : l > 0 ? out[l - 1].clone() : new float[] {0, 0};
        }
        return out;
    }

    /** The top and bottom of what belongs to no line (title, subtitle, tempo), or null. */
    private static float[] topSpan(SvgScore s) {
        float[] t = null;
        for (SvgScore.Item i : s.items) {
            if (i.line >= 0) continue;
            float lo = i.box != null ? i.box[1] : i.y - i.size, hi = i.box != null ? i.box[3] : i.y + i.size * 0.3f;
            if (t == null) t = new float[] {lo, hi};
            else {
                t[0] = Math.min(t[0], lo);
                t[1] = Math.max(t[1], hi);
            }
        }
        return t;
    }
}
