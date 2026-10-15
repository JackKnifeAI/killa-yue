package ai.jackknife.killayue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * java ai.jackknife.killayue.LyricTool SVG STARTS_JSON LYRICS_FILE TIMES : for check_lyrics.py, the lyric lines
 * the app prints under the laid-out score. TIMES is the song's _times, comma separated. Prints one line per
 * placed row: the line of music, then the text.
 */
public class LyricTool {
    public static void main(String[] a) throws Exception {
        SvgScore s = SvgScore.parse(new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8));
        String lyrics = new String(Files.readAllBytes(Paths.get(a[2])), StandardCharsets.UTF_8);
        ArrayList<Karaoke.Line> lines = Karaoke.lines(lyrics);
        String[] t = a[3].split(",");
        if (t.length != lines.size()) throw new IllegalStateException(t.length + " times for " + lines.size() + " lines");
        ArrayList<String> texts = new ArrayList<String>();
        int[] times = new int[t.length];
        for (int i = 0; i < t.length; i++) {
            texts.add(lines.get(i).text);
            times[i] = Integer.parseInt(t[i].trim());
        }
        LyricLines.add(s, LyricLines.starts(new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8)), texts, times);
        for (int i = 0; i < texts.size(); i++) System.out.println("LYRIC\t" + i + "\t" + times[i] + "\t" + texts.get(i));
        for (SvgScore.Item it : s.items) if (it.has("killa-lyrics")) System.out.println("ROW\t" + it.line + "\t" + it.text);
    }
}
