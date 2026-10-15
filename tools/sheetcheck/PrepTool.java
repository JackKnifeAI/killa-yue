package ai.jackknife.killayue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** java ai.jackknife.killayue.PrepTool OUTDIR SCORE.abc... : runs the app's ScorePrep on each score, for the checks in check.py. */
public class PrepTool {
    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]);
        Files.createDirectories(out);
        for (int i = 1; i < a.length; i++) {
            Path in = Paths.get(a[i]);
            String abc = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);
            Files.write(out.resolve(in.getFileName()), ScorePrep.prepare(abc, "Test", "Killa YuE").getBytes(StandardCharsets.UTF_8));
        }
    }
}
