package ai.jackknife.killayue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * java ai.jackknife.killayue.PartTool OUTDIR SCORE.abc... : every voice of every score written out for
 * every instrument, prepared as the app prepares it, for the checks in check_parts.py. Writes
 * SONG__VOICE__INSTRUMENT.abc and a line per part to OUTDIR/parts.tsv:
 * file, song, voice, instrument, semitones, octaves, key, notes, out of range, below the lowest string.
 */
public class PartTool {
    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]);
        Files.createDirectories(out);
        StringBuilder tsv = new StringBuilder();
        for (int i = 1; i < a.length; i++) {
            Path in = Paths.get(a[i]);
            String song = in.getFileName().toString().replace(".abc", "");
            String abc = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);
            for (String voice : new String[] {"Vocal", "Ins"}) {
                for (Instruments.Instrument ins : Instruments.ALL) {
                    Transpose.Part p = Transpose.part(abc, voice, ins);
                    String name = song + "__" + voice + "__" + ins.id + ".abc";
                    Files.write(out.resolve(name), ScorePrep.prepare(p.abc, "Test", ins.title()).getBytes(StandardCharsets.UTF_8));
                    tsv.append(name).append('\t').append(song).append('\t').append(voice).append('\t').append(ins.id).append('\t')
                            .append(ins.semitones).append('\t').append(p.octaves).append('\t').append(p.key).append('\t')
                            .append(p.notes).append('\t').append(p.outOfRange).append('\t').append(p.unplayable).append('\n');
                }
            }
        }
        Files.write(out.resolve("parts.tsv"), tsv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
