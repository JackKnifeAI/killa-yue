package ai.jackknife.killayue;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.TreeSet;

/**
 * Engraves a song's score as printable sheet music (PDF): one treble staff per voice of the
 * score, chord symbols above, section names at the bars where they start.
 *
 * A small engraver, drawn with plain shapes so it needs no music font: note heads, stems,
 * flags and beams within each beat, dots, ties, rests, ledger lines, key and time signatures.
 * Spacing follows the usual rule that a note takes room in proportion to the log of its length,
 * with every voice's notes lined up in shared columns. Tuplet brackets are not drawn.
 */
public class SheetMusic {
    private static final float PAGE_W = 595, PAGE_H = 842, MARGIN = 40, LABEL_W = 30; // A4 in points
    private static final float SP = 5.2f; // the space between two staff lines
    private static final float STAFF_GAP = 9 * SP, ABOVE = 7 * SP, BELOW = 5 * SP;

    // durations in ticks for whole (0) to 32nd (5)
    private static final int[] TYPE_TICKS = {Abc.PPQ * 4, Abc.PPQ * 2, Abc.PPQ, Abc.PPQ / 2, Abc.PPQ / 4, Abc.PPQ / 8};
    // key signature positions on a treble staff (0 is the bottom line)
    private static final int[] SHARP_STEPS = {8, 5, 9, 6, 3, 7, 4}, FLAT_STEPS = {4, 7, 3, 6, 2, 5, 1};

    private static class Glyph {
        int off;             // ticks from the start of the bar
        int type;            // 0 whole ... 5 32nd
        boolean dotted, rest, wholeBar, tieNext;
        int[] steps, shown;
        float x, y;          // where the head was drawn
        int stem;            // 1 up, -1 down
        boolean beamed;
    }

    private static class Bar {
        ArrayList<ArrayList<Glyph>> voices = new ArrayList<ArrayList<Glyph>>();
        TreeSet<Integer> cols = new TreeSet<Integer>();
        int len;
        float width;         // natural width, before the system is stretched
        float[] colX;        // natural x of each column, from the bar's left edge
        Integer[] colOff;
        String section, sectionAt;
        ArrayList<Abc.Mark> chords = new ArrayList<Abc.Mark>(); // tick is relative to the bar
    }

    private static class Strip {
        int from, to;        // bars [from, to)
        float scale;
        boolean first;
    }

    private final Abc.Score s;
    private final ArrayList<Abc.Voice> shownVoices = new ArrayList<Abc.Voice>();
    private final ArrayList<Bar> bars = new ArrayList<Bar>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean clefGlyph;

    private SheetMusic(Abc.Score s) {
        this.s = s;
        for (int i = 0; i < s.voices.size(); i++) if (!s.voices.get(i).silent()) shownVoices.add(s.voices.get(i));
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0xFF000000);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF000000);
        text.setColor(0xFF000000);
        clefGlyph = text.hasGlyph("\ud834\udd1e");
    }

    /** Writes the sheet music for the score to out. Throws if the score has no notes. */
    static void write(Abc.Score s, String title, String subtitle, File out) throws Exception {
        SheetMusic m = new SheetMusic(s);
        if (m.shownVoices.size() == 0) throw new Exception("The score has no notes in it");
        m.layoutBars();
        ArrayList<Strip> systems = m.layoutSystems();
        float sysH = ABOVE + m.shownVoices.size() * 4 * SP + (m.shownVoices.size() - 1) * STAFF_GAP + BELOW;
        float headH = 70;
        // pages: as many systems as fit, the first page has the title
        ArrayList<int[]> pages = new ArrayList<int[]>();
        int at = 0;
        while (at < systems.size()) {
            float room = PAGE_H - 2 * MARGIN - 16 - (pages.size() == 0 ? headH : 0);
            int n = Math.max(1, (int) (room / sysH));
            pages.add(new int[] {at, Math.min(systems.size(), at + n)});
            at += n;
        }
        PdfDocument doc = new PdfDocument();
        try {
            for (int p = 0; p < pages.size(); p++) {
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder((int) PAGE_W, (int) PAGE_H, p + 1).create());
                Canvas c = page.getCanvas();
                c.drawColor(0xFFFFFFFF);
                float y = MARGIN;
                if (p == 0) {
                    m.text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                    m.text.setTextSize(20);
                    m.text.setTextAlign(Paint.Align.CENTER);
                    c.drawText(fit(m.text, title, PAGE_W - 2 * MARGIN), PAGE_W / 2, y + 20, m.text);
                    m.text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
                    m.text.setTextSize(10);
                    c.drawText(fit(m.text, subtitle, PAGE_W - 2 * MARGIN), PAGE_W / 2, y + 38, m.text);
                    y += headH;
                }
                for (int k = pages.get(p)[0]; k < pages.get(p)[1]; k++) {
                    m.drawSystem(c, systems.get(k), y);
                    y += sysH;
                }
                m.text.setTypeface(Typeface.DEFAULT);
                m.text.setTextSize(8);
                m.text.setTextAlign(Paint.Align.CENTER);
                c.drawText("Killa YuE  \u00b7  page " + (p + 1) + " of " + pages.size(), PAGE_W / 2, PAGE_H - MARGIN / 2, m.text);
                doc.finishPage(page);
            }
            FileOutputStream o = new FileOutputStream(out);
            try {
                doc.writeTo(o);
            } finally {
                o.close();
            }
        } finally {
            doc.close();
        }
    }

    private static String fit(Paint p, String s, float w) {
        if (p.measureText(s) <= w) return s;
        while (s.length() > 1 && p.measureText(s + "\u2026") > w) s = s.substring(0, s.length() - 1);
        return s + "\u2026";
    }

    // ---------- layout ----------

    /** How much room a column takes before the next, for a gap of the given ticks. */
    private static float gap(int ticks) {
        return SP * (1.4f + 1.5f * (float) (Math.log(1 + ticks / (double) (Abc.PPQ / 4)) / Math.log(2)));
    }

    private void layoutBars() {
        int n = 0;
        for (int v = 0; v < shownVoices.size(); v++) n = Math.max(n, shownVoices.get(v).bars.size());
        int nominal = s.barTicks();
        for (int b = 0; b < n; b++) {
            Bar bar = new Bar();
            bar.len = 0;
            for (int v = 0; v < shownVoices.size(); v++) {
                Abc.Voice voice = shownVoices.get(v);
                int start = b < voice.bars.size() ? voice.bars.get(b).intValue() : -1;
                int end = b + 1 < voice.bars.size() ? voice.bars.get(b + 1).intValue() : voice.end;
                ArrayList<Glyph> gl = new ArrayList<Glyph>();
                if (start >= 0) {
                    bar.len = Math.max(bar.len, end - start);
                    glyphs(voice, start, end, gl);
                    if (v == 0) {
                        for (int k = 0; k < voice.chords.size(); k++) {
                            Abc.Mark c = voice.chords.get(k);
                            if (c.tick >= start && c.tick < end) bar.chords.add(new Abc.Mark(c.tick - start, c.text));
                        }
                        for (int k = 0; k < s.sections.size(); k++) {
                            int t = s.sections.get(k).tick;
                            if (t >= start && t < end) bar.section = s.sections.get(k).text;
                        }
                    }
                }
                if (gl.size() == 0) { // nothing written for this voice here: a bar's rest
                    Glyph g = new Glyph();
                    g.rest = g.wholeBar = true;
                    gl.add(g);
                }
                bar.voices.add(gl);
            }
            if (bar.len <= 0) bar.len = nominal;
            boolean accidentalFirst = false;
            for (int v = 0; v < bar.voices.size(); v++) {
                for (int k = 0; k < bar.voices.get(v).size(); k++) {
                    Glyph g = bar.voices.get(v).get(k);
                    if (g.wholeBar) continue;
                    bar.cols.add(Integer.valueOf(g.off));
                    if (g.off == 0 && g.shown != null) for (int a = 0; a < g.shown.length; a++) if (g.shown[a] != Abc.NONE) accidentalFirst = true;
                }
            }
            for (int k = 0; k < bar.chords.size(); k++) bar.cols.add(Integer.valueOf(bar.chords.get(k).tick));
            bar.colOff = bar.cols.toArray(new Integer[0]);
            bar.colX = new float[bar.colOff.length];
            float x = SP * (accidentalFirst ? 2.6f : 1.6f);
            for (int k = 0; k < bar.colOff.length; k++) {
                bar.colX[k] = x;
                int next = k + 1 < bar.colOff.length ? bar.colOff[k + 1].intValue() : bar.len;
                x += gap(Math.max(Abc.PPQ / 8, next - bar.colOff[k].intValue()));
            }
            bar.width = Math.max(x, bar.colOff.length == 0 ? SP * 9 : x);
            bars.add(bar);
        }
    }

    /** Splits a voice's events in one bar into drawable notes and rests. */
    private void glyphs(Abc.Voice voice, int start, int end, ArrayList<Glyph> out) {
        int len = end - start;
        for (int i = 0; i < voice.events.size(); i++) {
            Abc.Event e = voice.events.get(i);
            if (e.tick < start || e.tick >= end) continue;
            if (e.rest && (e.bars > 0 || (e.tick == start && e.dur >= len))) {
                Glyph g = new Glyph();
                g.rest = g.wholeBar = true;
                out.add(g);
                continue;
            }
            int dur = Math.min(e.dur, end - e.tick);
            ArrayList<int[]> parts = split(dur);
            int off = e.tick - start;
            for (int p = 0; p < parts.size(); p++) {
                Glyph g = new Glyph();
                g.off = off;
                g.type = parts.get(p)[0];
                g.dotted = parts.get(p)[1] == 1;
                g.rest = e.rest;
                if (!e.rest) {
                    g.steps = e.step;
                    g.shown = p == 0 ? e.shown : null;
                    g.tieNext = p + 1 < parts.size() || e.tie;
                }
                out.add(g);
                off += parts.get(p)[2];
            }
        }
    }

    /** A length in ticks as notes that can be written: {type, dotted, ticks}, tied together when more than one. */
    static ArrayList<int[]> split(int dur) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        for (int t = 0; t < TYPE_TICKS.length; t++) {
            if (dur == TYPE_TICKS[t]) {
                out.add(new int[] {t, 0, dur});
                return out;
            }
            if (dur == TYPE_TICKS[t] * 3 / 2) {
                out.add(new int[] {t, 1, dur});
                return out;
            }
            if (dur * 3 == TYPE_TICKS[t] * 2) { // a note of a triplet
                out.add(new int[] {t, 0, dur});
                return out;
            }
        }
        int rem = dur;
        while (rem >= TYPE_TICKS[TYPE_TICKS.length - 1]) {
            for (int t = 0; t < TYPE_TICKS.length; t++) {
                if (t > 0 && TYPE_TICKS[t] * 3 / 2 <= rem && TYPE_TICKS[t] * 3 / 2 < TYPE_TICKS[t - 1]) {
                    out.add(new int[] {t, 1, TYPE_TICKS[t] * 3 / 2});
                    rem -= TYPE_TICKS[t] * 3 / 2;
                    break;
                }
                if (TYPE_TICKS[t] <= rem) {
                    out.add(new int[] {t, 0, TYPE_TICKS[t]});
                    rem -= TYPE_TICKS[t];
                    break;
                }
            }
        }
        if (out.size() == 0) out.add(new int[] {TYPE_TICKS.length - 1, 0, dur});
        return out;
    }

    private float headerWidth(boolean first) {
        float w = SP * 4.2f + Math.abs(s.fifths) * SP * 1.1f + SP;
        return first ? w + SP * 3f : w;
    }

    private ArrayList<Strip> layoutSystems() {
        ArrayList<Strip> out = new ArrayList<Strip>();
        float content = PAGE_W - 2 * MARGIN - LABEL_W;
        int b = 0;
        while (b < bars.size()) {
            Strip sy = new Strip();
            sy.first = out.size() == 0;
            sy.from = b;
            float room = content - headerWidth(sy.first), used = 0;
            while (b < bars.size() && (b == sy.from || used + bars.get(b).width <= room) && b - sy.from < 6) used += bars.get(b++).width;
            sy.to = b;
            sy.scale = room / used;
            if (b >= bars.size() && sy.scale > 1.5f) sy.scale = 1; // the last line is left short rather than spread out
            out.add(sy);
        }
        return out;
    }

    // ---------- drawing ----------

    private static float stepY(float bottom, float step) {
        return bottom - step * SP / 2;
    }

    private void drawSystem(Canvas c, Strip sy, float top) {
        float left = MARGIN + LABEL_W;
        float right = PAGE_W - MARGIN;
        int nv = shownVoices.size();
        float[] bottoms = new float[nv];
        for (int v = 0; v < nv; v++) bottoms[v] = top + ABOVE + v * (4 * SP + STAFF_GAP) + 4 * SP;

        // staff lines, voice names, the line joining the staves
        line.setStrokeWidth(SP * 0.11f);
        float barsWidth = 0;
        for (int b = sy.from; b < sy.to; b++) barsWidth += bars.get(b).width * sy.scale;
        float end = Math.min(right, left + headerWidth(sy.first) + barsWidth);
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        text.setTextSize(SP * 1.7f);
        text.setTextAlign(Paint.Align.RIGHT);
        for (int v = 0; v < nv; v++) {
            for (int l = 0; l < 5; l++) c.drawLine(left, bottoms[v] - l * SP, end, bottoms[v] - l * SP, line);
            c.drawText(fit(text, shownVoices.get(v).name, LABEL_W - 4), left - 4, bottoms[v] - 1.5f * SP, text);
        }
        line.setStrokeWidth(SP * 0.16f);
        c.drawLine(left, bottoms[0] - 4 * SP, left, bottoms[nv - 1], line);
        if (nv > 1) { // a bracket for the staves sounding together
            fill.setStyle(Paint.Style.FILL);
            c.drawRect(left - SP * 0.9f, bottoms[0] - 4 * SP - SP * 0.3f, left - SP * 0.45f, bottoms[nv - 1] + SP * 0.3f, fill);
        }

        // clef, key and time
        float x = left + SP * 0.6f;
        for (int v = 0; v < nv; v++) {
            clef(c, x, bottoms[v]);
            float kx = x + SP * 4.0f;
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(SP * 2.6f);
            text.setTextAlign(Paint.Align.CENTER);
            int count = Math.abs(s.fifths);
            for (int k = 0; k < count; k++) {
                int st = s.fifths > 0 ? SHARP_STEPS[k] : FLAT_STEPS[k];
                accidental(c, s.fifths > 0 ? 1 : -1, kx + k * SP * 1.1f, stepY(bottoms[v], st));
            }
            if (sy.first) {
                float tx = kx + count * SP * 1.1f + SP * 1.2f;
                text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
                text.setTextSize(SP * 2.5f);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(String.valueOf(s.meterNum), tx, bottoms[v] - 2 * SP - SP * 0.15f, text);
                c.drawText(String.valueOf(s.meterDen), tx, bottoms[v] - SP * 0.15f, text);
            }
        }

        // the bars
        float bx = left + headerWidth(sy.first);
        ArrayList<ArrayList<Glyph>> row = new ArrayList<ArrayList<Glyph>>();
        for (int v = 0; v < nv; v++) row.add(new ArrayList<Glyph>());
        for (int b = sy.from; b < sy.to; b++) {
            Bar bar = bars.get(b);
            float w = bar.width * sy.scale;
            if (bar.section != null) {
                text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
                text.setTextSize(SP * 1.9f);
                text.setTextAlign(Paint.Align.LEFT);
                String label = bar.section.substring(0, 1).toUpperCase() + bar.section.substring(1);
                float ty = bottoms[0] - 4 * SP - SP * 5f;
                RectF box = new RectF(bx, ty - SP * 1.7f, bx + text.measureText(label) + SP * 1.2f, ty + SP * 0.6f);
                line.setStrokeWidth(SP * 0.1f);
                c.drawRect(box, line);
                c.drawText(label, bx + SP * 0.6f, ty, text);
            }
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(SP * 1.8f);
            text.setTextAlign(Paint.Align.LEFT);
            for (int k = 0; k < bar.chords.size(); k++) {
                Abc.Mark ch = bar.chords.get(k);
                float cx = bx + colX(bar, ch.tick) * sy.scale - SP * 0.6f;
                c.drawText(ch.text.replace("b", "\u266d").replace("#", "\u266f"),
                        cx, bottoms[0] - 4 * SP - SP * 2.2f, text);
            }
            for (int v = 0; v < nv; v++) {
                ArrayList<Glyph> gl = bar.voices.get(v);
                for (int k = 0; k < gl.size(); k++) {
                    Glyph g = gl.get(k);
                    g.x = g.wholeBar ? bx + w / 2 : bx + colX(bar, g.off) * sy.scale;
                }
                beams(gl);
                for (int k = 0; k < gl.size(); k++) drawGlyph(c, gl.get(k), bottoms[v]);
                drawBeams(c, gl, bottoms[v]);
                row.get(v).addAll(gl);
            }
            bx += w;
            line.setStrokeWidth(SP * 0.12f);
            boolean last = b == bars.size() - 1;
            for (int v = 0; v < nv; v++) {
                if (last) {
                    c.drawLine(bx - SP * 0.8f, bottoms[v] - 4 * SP, bx - SP * 0.8f, bottoms[v], line);
                    fill.setStyle(Paint.Style.FILL);
                    c.drawRect(bx - SP * 0.45f, bottoms[v] - 4 * SP, bx, bottoms[v], fill);
                } else {
                    c.drawLine(bx, bottoms[v] - 4 * SP, bx, bottoms[v], line);
                }
            }
        }
        // ties, now that every head on the line has its place
        for (int v = 0; v < nv; v++) {
            ArrayList<Glyph> gl = row.get(v);
            for (int k = 0; k < gl.size(); k++) {
                Glyph g = gl.get(k);
                if (g.rest || !g.tieNext) continue;
                Glyph n = null;
                for (int j = k + 1; j < gl.size(); j++) {
                    if (!gl.get(j).rest) {
                        n = gl.get(j);
                        break;
                    }
                }
                float x2 = n != null ? n.x - SP * 0.7f : end - SP * 0.3f;
                tie(c, g, x2);
            }
        }
    }

    private static float colX(Bar bar, int off) {
        for (int k = 0; k < bar.colOff.length; k++) if (bar.colOff[k].intValue() == off) return bar.colX[k];
        return bar.colX.length > 0 ? bar.colX[0] : SP * 1.6f;
    }

    /** Joins the eighths and shorter within each beat, and sets every stem's direction. */
    private void beams(ArrayList<Glyph> gl) {
        int beat = s.meterDen == 8 && s.meterNum % 3 == 0 ? Abc.PPQ * 3 / 2 : Abc.PPQ * 4 / Math.max(1, s.meterDen);
        for (int k = 0; k < gl.size(); k++) {
            Glyph g = gl.get(k);
            g.beamed = false;
            if (!g.rest) g.stem = avg(g.steps) < 4 ? 1 : -1;
        }
        int k = 0;
        while (k < gl.size()) {
            int j = k;
            while (j < gl.size() && beamable(gl.get(j)) && gl.get(j).off / beat == gl.get(k).off / beat) j++;
            if (j - k >= 2) {
                float sum = 0;
                int n = 0;
                for (int i = k; i < j; i++) {
                    sum += avg(gl.get(i).steps);
                    n++;
                }
                int dir = sum / n < 4 ? 1 : -1;
                for (int i = k; i < j; i++) {
                    gl.get(i).beamed = true;
                    gl.get(i).stem = dir;
                }
            }
            k = Math.max(j, k + 1);
        }
    }

    private static boolean beamable(Glyph g) {
        return !g.rest && !g.wholeBar && g.type >= 3;
    }

    private static float avg(int[] a) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i];
        return s / Math.max(1, a.length);
    }

    private static int min(int[] a) {
        int m = Integer.MAX_VALUE;
        for (int i = 0; i < a.length; i++) m = Math.min(m, a[i]);
        return m;
    }

    private static int max(int[] a) {
        int m = Integer.MIN_VALUE;
        for (int i = 0; i < a.length; i++) m = Math.max(m, a[i]);
        return m;
    }

    private void drawGlyph(Canvas c, Glyph g, float bottom) {
        if (g.rest) {
            rest(c, g, bottom);
            return;
        }
        float hw = SP * 0.66f;
        // ledger lines
        line.setStrokeWidth(SP * 0.11f);
        for (int st = -2; st >= min(g.steps); st -= 2) c.drawLine(g.x - hw * 1.6f, stepY(bottom, st), g.x + hw * 1.6f, stepY(bottom, st), line);
        for (int st = 10; st <= max(g.steps); st += 2) c.drawLine(g.x - hw * 1.6f, stepY(bottom, st), g.x + hw * 1.6f, stepY(bottom, st), line);
        for (int i = 0; i < g.steps.length; i++) {
            float y = stepY(bottom, g.steps[i]);
            head(c, g.x, y, g.type >= 2);
            if (g.shown != null && g.shown[i] != Abc.NONE) accidental(c, g.shown[i], g.x - hw - SP * 0.9f, y);
            if (g.dotted) {
                float dy = g.steps[i] % 2 == 0 ? y - SP / 2 : y;
                fill.setStyle(Paint.Style.FILL);
                c.drawCircle(g.x + hw + SP * 0.5f, dy, SP * 0.2f, fill);
            }
        }
        g.y = stepY(bottom, g.stem > 0 ? min(g.steps) : max(g.steps));
        if (g.type == 0) return; // a whole note has no stem
        line.setStrokeWidth(SP * 0.12f);
        float sx = g.stem > 0 ? g.x + hw - SP * 0.06f : g.x - hw + SP * 0.06f;
        float from = stepY(bottom, g.stem > 0 ? min(g.steps) : max(g.steps));
        float tip = stemTip(g, bottom);
        if (!g.beamed) c.drawLine(sx, from, sx, tip, line);
        if (!g.beamed && g.type >= 3) flags(c, sx, tip, g.stem, g.type - 2);
    }

    private float stemTip(Glyph g, float bottom) {
        float len = SP * (3.5f + Math.max(0, g.type - 3) * 0.6f);
        float tip = g.stem > 0 ? stepY(bottom, max(g.steps)) - len : stepY(bottom, min(g.steps)) + len;
        // notes far off the staff keep their stems reaching the middle line
        float mid = stepY(bottom, 4);
        return g.stem > 0 ? Math.min(tip, mid) : Math.max(tip, mid);
    }

    private void drawBeams(Canvas c, ArrayList<Glyph> gl, float bottom) {
        float hw = SP * 0.66f;
        int k = 0;
        while (k < gl.size()) {
            if (!gl.get(k).beamed) {
                k++;
                continue;
            }
            int j = k;
            int beat = s.meterDen == 8 && s.meterNum % 3 == 0 ? Abc.PPQ * 3 / 2 : Abc.PPQ * 4 / Math.max(1, s.meterDen);
            while (j < gl.size() && gl.get(j).beamed && gl.get(j).off / beat == gl.get(k).off / beat) j++;
            int dir = gl.get(k).stem;
            float y = dir > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
            for (int i = k; i < j; i++) {
                float t = stemTip(gl.get(i), bottom);
                y = dir > 0 ? Math.min(y, t) : Math.max(y, t);
            }
            float th = SP * 0.5f;
            line.setStrokeWidth(SP * 0.12f);
            fill.setStyle(Paint.Style.FILL);
            float[] sx = new float[j - k];
            for (int i = k; i < j; i++) {
                Glyph g = gl.get(i);
                sx[i - k] = dir > 0 ? g.x + hw - SP * 0.06f : g.x - hw + SP * 0.06f;
                c.drawLine(sx[i - k], stepY(bottom, dir > 0 ? min(g.steps) : max(g.steps)), sx[i - k], y, line);
            }
            for (int level = 0; level < 3; level++) {
                float by = y + (dir > 0 ? 1 : -1) * level * SP * 0.8f;
                for (int i = k; i < j; i++) {
                    if (gl.get(i).type < 3 + level) continue;
                    boolean prev = i > k && gl.get(i - 1).type >= 3 + level, next = i + 1 < j && gl.get(i + 1).type >= 3 + level;
                    float x1, x2;
                    if (next) {
                        x1 = sx[i - k];
                        x2 = sx[i + 1 - k];
                    } else if (!prev && level > 0) { // a lone shorter note: a short stub toward its neighbour
                        float stub = SP * 1.2f;
                        boolean toLeft = i == j - 1;
                        x1 = toLeft ? sx[i - k] - stub : sx[i - k];
                        x2 = toLeft ? sx[i - k] : sx[i - k] + stub;
                    } else {
                        continue;
                    }
                    c.drawRect(Math.min(x1, x2), dir > 0 ? by : by - th, Math.max(x1, x2), dir > 0 ? by + th : by, fill);
                }
            }
            k = j;
        }
    }

    private void head(Canvas c, float x, float y, boolean filled) {
        c.save();
        c.rotate(-20, x, y);
        RectF r = new RectF(x - SP * 0.66f, y - SP * 0.46f, x + SP * 0.66f, y + SP * 0.46f);
        if (filled) {
            fill.setStyle(Paint.Style.FILL);
            c.drawOval(r, fill);
        } else {
            line.setStrokeWidth(SP * 0.18f);
            c.drawOval(new RectF(r.left + SP * 0.08f, r.top + SP * 0.08f, r.right - SP * 0.08f, r.bottom - SP * 0.08f), line);
        }
        c.restore();
    }

    private void flags(Canvas c, float sx, float tip, int dir, int count) {
        line.setStrokeWidth(SP * 0.3f);
        for (int i = 0; i < count; i++) {
            float y0 = tip + dir * i * SP * 0.8f;
            Path p = new Path();
            p.moveTo(sx, y0);
            p.cubicTo(sx + SP * 0.2f, y0 + dir * SP * 1.0f, sx + SP * 1.4f, y0 + dir * SP * 1.3f, sx + SP * 0.9f, y0 + dir * SP * 2.6f);
            c.drawPath(p, line);
        }
    }

    private void tie(Canvas c, Glyph g, float x2) {
        float x1 = g.x + SP * 0.7f;
        if (x2 <= x1) return;
        float dir = g.stem > 0 ? 1 : -1; // below the heads when the stems go up
        float y = g.y + dir * SP * 0.6f;
        Path p = new Path();
        p.moveTo(x1, y);
        p.quadTo((x1 + x2) / 2, y + dir * SP * 1.0f, x2, y);
        line.setStrokeWidth(SP * 0.14f);
        c.drawPath(p, line);
    }

    private void accidental(Canvas c, int alter, float x, float y) {
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(SP * 2.6f);
        text.setTextAlign(Paint.Align.CENTER);
        String a = alter == 0 ? "\u266e" : alter > 0 ? (alter > 1 ? "\u266f\u266f" : "\u266f") : (alter < -1 ? "\u266d\u266d" : "\u266d");
        // a flat's bowl sits on its line or space; a sharp and a natural are centred on it
        c.drawText(a, x, y + (alter < 0 ? SP * 0.35f : SP * 0.9f), text);
    }

    private void clef(Canvas c, float x, float bottom) {
        text.setTextAlign(Paint.Align.LEFT);
        if (clefGlyph) {
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(SP * 7.6f);
            c.drawText("\ud834\udd1e", x, bottom + SP * 1.15f, text);
            return;
        }
        // no music font on this phone: a drawn G clef, curling round the G line
        line.setStrokeWidth(SP * 0.32f);
        float g = stepY(bottom, 2), cx = x + SP * 1.6f;
        Path p = new Path();
        p.moveTo(cx + SP * 0.3f, bottom + SP * 1.6f);
        p.cubicTo(cx - SP * 0.6f, bottom + SP * 2.2f, cx - SP * 0.9f, bottom + SP * 0.9f, cx, bottom + SP * 0.9f);
        p.lineTo(cx + SP * 0.4f, bottom - SP * 5.0f);
        p.cubicTo(cx + SP * 0.6f, bottom - SP * 6.2f, cx + SP * 1.5f, bottom - SP * 5.4f, cx + SP * 0.6f, bottom - SP * 4.2f);
        p.cubicTo(cx - SP * 0.8f, bottom - SP * 3.0f, cx - SP * 1.4f, g, cx - SP * 0.2f, g + SP * 0.9f);
        p.cubicTo(cx + SP * 1.0f, g + SP * 1.4f, cx + SP * 1.4f, g - SP * 0.8f, cx + SP * 0.1f, g - SP * 0.9f);
        p.cubicTo(cx - SP * 0.6f, g - SP * 0.9f, cx - SP * 0.7f, g, cx - SP * 0.3f, g + SP * 0.3f);
        c.drawPath(p, line);
    }

    private void rest(Canvas c, Glyph g, float bottom) {
        fill.setStyle(Paint.Style.FILL);
        float x = g.x;
        int type = g.wholeBar ? 0 : g.type;
        if (type == 0) {
            c.drawRect(x - SP * 0.6f, stepY(bottom, 6), x + SP * 0.6f, stepY(bottom, 6) + SP * 0.5f, fill);
        } else if (type == 1) {
            c.drawRect(x - SP * 0.6f, stepY(bottom, 4) - SP * 0.5f, x + SP * 0.6f, stepY(bottom, 4), fill);
        } else if (type == 2) {
            line.setStrokeWidth(SP * 0.3f);
            Path p = new Path();
            float y = stepY(bottom, 4);
            p.moveTo(x - SP * 0.4f, y - SP * 1.5f);
            p.lineTo(x + SP * 0.35f, y - SP * 0.7f);
            p.lineTo(x - SP * 0.3f, y + SP * 0.05f);
            p.lineTo(x + SP * 0.35f, y + SP * 0.75f);
            p.quadTo(x - SP * 0.6f, y + SP * 0.5f, x - SP * 0.1f, y + SP * 1.4f);
            c.drawPath(p, line);
        } else {
            int n = type - 2; // hooks: one for an eighth, two for a sixteenth
            line.setStrokeWidth(SP * 0.16f);
            float top = stepY(bottom, 5.5f);
            c.drawLine(x + SP * 0.5f, top - SP * 0.2f, x - SP * 0.2f + 0 * SP, top + SP * (1.3f + n * 0.9f), line);
            for (int i = 0; i < n; i++) {
                float y = top + i * SP * 0.9f;
                c.drawCircle(x - SP * 0.35f + i * -SP * 0.15f, y, SP * 0.3f, fill);
                c.drawLine(x - SP * 0.35f, y + SP * 0.15f, x + SP * 0.5f - i * SP * 0.15f, y - SP * 0.2f, line);
            }
        }
        if (g.dotted && !g.wholeBar) c.drawCircle(x + SP * 1.0f, stepY(bottom, 5), SP * 0.2f, fill);
    }
}
