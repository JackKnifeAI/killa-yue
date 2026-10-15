package ai.jackknife.killayue;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/** Tests for SvgScore: abcjs's SVG read back into shapes and text, exactly. */
public class SvgScoreTest {
    private static int failed, passed;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    private static void ops(String name, float[] want, float[] got) {
        boolean ok = want.length == got.length;
        for (int i = 0; ok && i < want.length; i++) ok = Math.abs(want[i] - got[i]) < 1e-4;
        check(name, ok, "want " + Arrays.toString(want) + " got " + Arrays.toString(got));
    }

    private static final float M = SvgScore.MOVE, L = SvgScore.LINE, C = SvgScore.CUBIC, Q = SvgScore.QUAD, Z = SvgScore.CLOSE;

    public static void main(String[] a) throws Exception {
        ops("absolute move, line, close", new float[] {M, 10, 20, L, 30, 20, L, 30, 22, Z}, SvgScore.pathOps("M 10 20 L 30 20 L 30 22 z"));
        ops("relative curve from the current point", new float[] {M, 1, 2, C, 2, 3, 3, 4, 4, 5}, SvgScore.pathOps("M 1 2c 1 1 2 2 3 3"));
        ops("repeated curve arguments continue the command", new float[] {M, 0, 0, C, 1, 0, 1, 1, 0, 1, C, -1, 1, -1, 0, 0, 0},
                SvgScore.pathOps("M0 0c 1 0 1 1 0 1 -1 0 -1 -1 0 -1"));
        ops("relative lines and moves", new float[] {M, 5, 5, L, 6, 5, L, 6, 7, M, 8, 7, L, 9, 7}, SvgScore.pathOps("M 5 5 l 1 0 l 0 2 m 2 0 l 1 0"));
        ops("horizontal and vertical lines", new float[] {M, 0, 0, L, 4, 0, L, 4, 3, L, 1, 3, L, 1, 1, Z}, SvgScore.pathOps("M0,0H4V3h-3v-2Z"));
        ops("numbers written without spaces", new float[] {M, 1.5f, -2, L, 0.5f, 0.25f}, SvgScore.pathOps("M1.5-2L.5.25"));
        ops("smooth and quadratic curves", new float[] {M, 0, 0, C, 0, 1, 1, 1, 1, 0, C, 1, -1, 2, -1, 2, 0, Q, 3, 1, 4, 0},
                SvgScore.pathOps("M0 0C0 1 1 1 1 0S2 -1 2 0Q3 1 4 0"));

        String svg = "<svg width=\"800\" height=\"300\" fill=\"currentColor\" stroke=\"currentColor\"><style>.x{}</style><title>T</title>"
                + "<g class=\"abcjs-note abcjs-l2 abcjs-m5\" data-name=\"note\"><path data-name=\"e\" d=\"M 10 10 L 20 10 L 20 14 z\" class=\"abcjs-notehead\"></path>"
                + "<path d=\"M 19 0 L 20 0 L 20 12 L 19 12 z\" class=\"abcjs-stem\"></path></g>"
                + "<text font-size=\"16\" font-style=\"normal\" font-family=\"Helvetica\" font-weight=\"bold\" class=\"abcjs-chord abcjs-l0\" text-anchor=\"middle\" x=\"301.9\" y=\"80.69\" data-name=\"chord\"><tspan x=\"301.9\">E&amp;m</tspan></text>"
                + "<path transform=\"translate(100 50) scale(0.5,0.5) translate(-100 -50)\" d=\"M 100 50 L 110 50 L 110 60 z\" data-name=\"noteheads.quarter\"></path>"
                + "</svg>";
        SvgScore s = SvgScore.parse(svg);
        check("the page size", s.width == 800 && s.height == 300, s.width + "x" + s.height);
        check("every shape and text is read", s.items.size() == 4, "items " + s.items.size());
        SvgScore.Item head = s.items.get(0);
        check("a shape keeps its own and its group's classes", head.has("abcjs-notehead") && head.has("abcjs-note") && head.has("abcjs-m5"), head.cls);
        check("a shape knows its line from its group", head.line == 2, "line " + head.line);
        check("a shape keeps its own glyph name", "e".equals(head.name), head.name);
        check("a shape's box", Arrays.equals(new float[] {10, 10, 20, 14}, head.box), Arrays.toString(head.box));
        check("a shape without a name takes its group's", "note".equals(s.items.get(1).name), s.items.get(1).name);
        SvgScore.Item t = s.items.get(2);
        check("text is read with its style", t.text != null && t.text.equals("E&m") && t.size == 16 && t.bold && !t.italic
                && "Helvetica".equals(t.family) && t.anchor == 1 && Math.abs(t.x - 301.9f) < 1e-3 && Math.abs(t.y - 80.69f) < 1e-3 && t.line == 0,
                t.text + " " + t.size + " " + t.family + " " + t.anchor + " " + t.line);
        ops("a transform is applied to the points", new float[] {M, 100, 50, L, 105, 50, L, 105, 55, Z}, s.items.get(3).ops);
        check("a shape outside any line has line -1", s.items.get(3).line == -1, "line " + s.items.get(3).line);

        // the real thing: Cut the Line as abcjs drew it on the phone
        InputStream in = new GZIPInputStream(new FileInputStream("test/resources/cut-the-line.svg.gz"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        SvgScore cut = SvgScore.parse(buf.toString("UTF-8"));
        int heads = 0, texts = 0, maxLine = -1;
        for (SvgScore.Item i : cut.items) {
            if (i.has("abcjs-notehead")) heads++;
            if (i.text != null) texts++;
            maxLine = Math.max(maxLine, i.line);
        }
        check("Cut the Line: every notehead", heads == 375, "heads " + heads);
        check("Cut the Line: every text", texts == 108, "texts " + texts);
        check("Cut the Line: 18 lines of music", maxLine == 17, "last line " + maxLine);
        float[] l0 = cut.lineBox(0), l1 = cut.lineBox(1);
        check("lines are stacked top to bottom", l0 != null && l1 != null && l0[3] <= l1[1] + 1, Arrays.toString(l0) + " " + Arrays.toString(l1));

        // pages: lines packed in order, the top block (title, tempo) on the first page
        int[][] pages = SvgScore.paginate(new float[] {0, 40}, new float[][] {{50, 150}, {160, 260}, {270, 370}, {380, 480}}, 280);
        check("lines packed onto pages in order", Arrays.deepEquals(new int[][] {{0, 1}, {2, 3}}, pages), Arrays.deepToString(pages));
        int[][] tall = SvgScore.paginate(new float[] {0, 40}, new float[][] {{50, 400}}, 250);
        check("a line taller than a page still gets a page of its own", Arrays.deepEquals(new int[][] {{0}}, tall), Arrays.deepToString(tall));

        SvgScore tab = SvgScore.parse("<svg width=\"800\" height=\"100\"><g class=\"abcjs-staff abcjs-l1 abcjs-v1\">"
                + "<path d=\"M 77.56 346.55 L 775 346.55 L 775 347.25 L 77.56 347.25 z\"></path></g>"
                + "<g class=\"abcjs-tabNumber abcjs-note abcjs-l1 abcjs-m0 abcjs-v0\"><text font-size=\"15\" font-family=\"Arial\" "
                + "class=\"abcjs-tab-number\" text-anchor=\"middle\" x=\"207.81\" y=\"351.83\"><tspan x=\"207.81\">11</tspan></text></g>"
                + "<text font-size=\"21\" class=\"abcjs-title\" x=\"10\" y=\"20\"><tspan>Song</tspan></text></svg>");
        SvgScore.Item number = tab.items.get(1);
        check("a tab number is read with its class and line", number.has("abcjs-tab-number") && number.line == 1 && "11".equals(number.text),
                number.cls + " line " + number.line + " " + number.text);
        float[] gap = SvgScore.gap(number);
        check("a tab number clears its string line behind it",
                gap != null && gap[1] < 346.55f && gap[3] > 347.25f && gap[0] < 207.81f - 7 && gap[2] > 207.81f + 7 && gap[2] - gap[0] < 22,
                Arrays.toString(gap));
        check("other text clears nothing", SvgScore.gap(tab.items.get(2)) == null, "");

        float[] shift = SvgScore.spread(new float[][] {{0, 100}, {90, 200}, {220, 300}, {330, 400}}, 18);
        ops("a line that runs into the one above moves down to the usual gap, and the lines below move with it",
                new float[] {0, 28, 28, 28}, shift);
        ops("an empty line, which takes the span of the line above, moves with that line and pushes nothing",
                new float[] {0, 0, 0}, SvgScore.spread(new float[][] {{0, 100}, {0, 100}, {130, 200}}, 18));
        ops("lines already apart stay where abcjs put them", new float[] {0, 0}, SvgScore.spread(new float[][] {{0, 100}, {130, 200}}, 18));

        System.out.println("SvgScoreTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
