package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Tests for ChordSheet: chord tab under each line of the vocal, and chord diagrams under the title. */
public class ChordSheetTest {
    private static int failed, passed;

    private static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    private static String staff(int line, float top) {
        StringBuilder b = new StringBuilder("<g class=\"abcjs-staff abcjs-l" + line + " abcjs-v0\">");
        for (int k = 0; k < 5; k++) {
            float y = top + 7.75f * k;
            b.append("<path d=\"M 100 ").append(y).append(" L 700 ").append(y).append(" L 700 ").append(y + 0.7f)
                    .append(" L 100 ").append(y + 0.7f).append(" z\"></path>");
        }
        return b.append("</g>").toString();
    }

    private static String chord(int line, float x, float y, String name) {
        return "<text class=\"abcjs-chord abcjs-l" + line + " abcjs-v0\" text-anchor=\"middle\" font-size=\"12\" x=\"" + x + "\" y=\"" + y
                + "\"><tspan>" + name + "</tspan></text>";
    }

    public static void main(String[] a) {
        String svg = "<svg width=\"760\" height=\"400\">"
                + "<text class=\"abcjs-title\" font-size=\"20\" text-anchor=\"middle\" x=\"380\" y=\"30\"><tspan>Song</tspan></text>"
                + staff(0, 80) + chord(0, 200, 70, "C") + chord(0, 450, 70, "G")
                + "<g class=\"abcjs-bar abcjs-l0 abcjs-v0\"><path d=\"M 400 80 L 400 111 L 400.6 111 L 400.6 80 z\"></path></g>"
                + staff(1, 200) + chord(1, 300, 190, "Am")
                + "</svg>";
        SvgScore s = SvgScore.parse(svg);
        List<String> chords = ChordSheet.add(s, Instruments.find("guitar"));
        same("the chords, each once, in the order they come", "[C, G, Am]", chords);

        // the tab under line 0: six string lines below everything on the line
        ArrayList<Float> lines0 = new ArrayList<Float>();
        float staffBottom0 = 0, staffTop0 = Float.MAX_VALUE;
        for (SvgScore.Item i : s.items) {
            if (i.line == 0 && i.has("killa-tab-line")) lines0.add(i.box[1]);
            if (i.line == 0 && i.has("abcjs-staff")) {
                staffBottom0 = Math.max(staffBottom0, i.box[3]);
                staffTop0 = Math.min(staffTop0, i.box[1]);
            }
        }
        same("a guitar tab has six lines", 6, lines0.size());
        same("the tab sits under the staff", true, lines0.get(0) > staffBottom0);

        // the C column: x32010 read off the lines, low string at the bottom
        TreeMap<Float, String> c = new TreeMap<Float, String>();
        for (SvgScore.Item i : s.items) {
            if (i.line == 0 && i.has("killa-chord-tab") && i.text != null && Math.abs(i.x - 200) < 0.01f) c.put(-i.y, i.text);
        }
        same("under C, its shape from the low string up (x32010; the unplayed string blank)", "[3, 2, 0, 1, 0]", new ArrayList<String>(c.values()));
        boolean gaps = true;
        for (SvgScore.Item i : s.items) if (i.has("killa-chord-tab") && i.text != null && i.text.matches("\\d+") && SvgScore.gap(i) == null) gaps = false;
        same("every number clears its string line", true, gaps);

        // diagrams under the title, one per chord, the music moved down below them
        ArrayList<String> names = new ArrayList<String>();
        float diagramsBottom = 0;
        for (SvgScore.Item i : s.items) {
            if (!i.has("killa-diagram")) continue;
            same("a diagram belongs to the top of page one", -1, i.line);
            if (i.has("killa-diagram-name")) names.add(i.text);
            diagramsBottom = Math.max(diagramsBottom, i.box != null ? i.box[3] : i.y);
        }
        same("a diagram named for each chord", "[C, G, Am]", names);
        same("the music starts below the diagrams", true, staffTop0 > diagramsBottom);
        float title = 0;
        for (SvgScore.Item i : s.items) if (i.has("abcjs-title")) title = i.y;
        same("the diagrams start below the title", true, diagramsBottom > title);

        boolean refused = false;
        try {
            ChordSheet.add(SvgScore.parse("<svg width=\"760\" height=\"200\">" + staff(0, 80) + chord(0, 200, 70, "Cblargh") + "</svg>"),
                    Instruments.find("guitar"));
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        same("a chord it cannot play stops the sheet, with the chord's name", true, refused);

        SvgScore uke = SvgScore.parse(svg);
        ChordSheet.add(uke, Instruments.find("ukulele"));
        int ukeLines = 0;
        for (SvgScore.Item i : uke.items) if (i.line == 0 && i.has("killa-tab-line")) ukeLines++;
        same("a ukulele tab has four lines", 4, ukeLines);

        System.out.println("ChordSheetTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
