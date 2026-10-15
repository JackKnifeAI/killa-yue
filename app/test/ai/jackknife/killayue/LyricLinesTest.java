package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeMap;

/** Tests for LyricLines: each sung line printed under the line of music it is sung in. */
public class LyricLinesTest {
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

    /** The lyric text under each line of music, in order. */
    private static TreeMap<Integer, ArrayList<String>> under(SvgScore s) {
        TreeMap<Integer, ArrayList<String>> out = new TreeMap<Integer, ArrayList<String>>();
        for (SvgScore.Item i : s.items) {
            if (!i.has("killa-lyrics")) continue;
            if (!out.containsKey(i.line)) out.put(i.line, new ArrayList<String>());
            out.get(i.line).add(i.text);
        }
        return out;
    }

    public static void main(String[] a) {
        same("abcjs's start of each line of music, read from what the engraver hands back", "{1=0, 2=11429, 3=22857}",
                LyricLines.starts("{\"1\":0,\"2\":11429,\"3\":22857}"));

        String svg = "<svg width=\"760\" height=\"400\">" + staff(1, 80) + staff(2, 200) + staff(3, 320) + "</svg>";
        SvgScore s = SvgScore.parse(svg);
        int placed = LyricLines.add(s, LyricLines.starts("{\"1\":0,\"2\":11429,\"3\":22857}"),
                Arrays.asList("Gold in the wire", "a voice in the choir", "hold on", "let it ring"), new int[] {800, 9000, 12000, 30000});
        same("every line placed", 4, placed);
        same("each line under the line of music where it starts", "{1=[Gold in the wire, a voice in the choir], 2=[hold on], 3=[let it ring]}",
                under(s));

        float staffBottom = 0, lyricTop = Float.MAX_VALUE, x = 0;
        for (SvgScore.Item i : s.items) {
            if (i.line == 1 && i.has("abcjs-staff")) staffBottom = Math.max(staffBottom, i.box[3]);
            if (i.line == 1 && i.has("killa-lyrics")) {
                lyricTop = Math.min(lyricTop, i.y - i.size);
                x = i.x;
            }
        }
        same("the words sit below the staff", true, lyricTop > staffBottom);
        same("they start at the staff's left edge", 100.0, Math.round(x * 10) / 10.0);

        SvgScore early = SvgScore.parse(svg);
        LyricLines.add(early, LyricLines.starts("{\"1\":2000,\"2\":11429}"), Arrays.asList("a pickup"), new int[] {1500});
        same("a line sung just before the first line's first note goes with that line", "{1=[a pickup]}", under(early));

        SvgScore wrap = SvgScore.parse(svg);
        String longLine = "this line of words is so long that it cannot possibly fit across the width of one staff on the page at all";
        LyricLines.add(wrap, LyricLines.starts("{\"1\":0}"), Arrays.asList(longLine), new int[] {0});
        ArrayList<String> rows = under(wrap).get(1);
        same("a line too long for the staff wraps at a space, keeping every word", longLine, String.join(" ", rows));
        same("into more than one row", true, rows.size() > 1);

        System.out.println("LyricLinesTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
