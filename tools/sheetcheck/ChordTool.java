package ai.jackknife.killayue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * For check_chords.py. "prep OUTDIR SCORE.abc...": each song's vocal line prepared as the app prepares its
 * chord sheet (OUTDIR/SONG.abc). "add INSTRUMENT SONG.svg...": the chord tab and diagrams the app adds to
 * each laid-out vocal line, one JSON line per song with every added or chord-name item.
 */
public class ChordTool {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("prep")) {
            Path out = Paths.get(a[1]);
            Files.createDirectories(out);
            Instruments.Instrument voice = new Instruments.Instrument("Voices", "vocal", "Vocal", "Voc.", "", 0, 0, "auto", 0, 127);
            for (int i = 2; i < a.length; i++) {
                Path in = Paths.get(a[i]);
                Transpose.Part p = Transpose.part(new String(Files.readAllBytes(in), StandardCharsets.UTF_8), "Vocal", voice);
                Files.write(out.resolve(in.getFileName()), ScorePrep.prepare(p.abc, "Test", "chords").getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        Instruments.Instrument ins = Instruments.find(a[1]);
        for (int i = 2; i < a.length; i++) {
            StringBuilder j = new StringBuilder("{\"song\":\"").append(Paths.get(a[i]).getFileName()).append("\",");
            try {
                SvgScore s = SvgScore.parse(new String(Files.readAllBytes(Paths.get(a[i])), StandardCharsets.UTF_8));
                ChordSheet.add(s, ins);
                j.append("\"items\":[");
                boolean first = true;
                for (SvgScore.Item it : s.items) {
                    if (!it.has("abcjs-chord") && !it.cls.contains("killa-")) continue;
                    j.append(first ? "" : ",").append("{\"cls\":\"").append(it.cls).append("\",\"line\":").append(it.line)
                            .append(",\"x\":").append(it.x).append(",\"y\":").append(it.y);
                    if (it.text != null) j.append(",\"text\":\"").append(it.text.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                    if (it.box != null) j.append(",\"box\":[").append(it.box[0]).append(',').append(it.box[1]).append(',').append(it.box[2]).append(',').append(it.box[3]).append(']');
                    j.append('}');
                    first = false;
                }
                j.append("]}");
            } catch (IllegalArgumentException e) {
                j.append("\"error\":\"").append(String.valueOf(e.getMessage()).replace("\"", "'")).append("\"}");
            }
            System.out.println(j);
        }
    }
}
