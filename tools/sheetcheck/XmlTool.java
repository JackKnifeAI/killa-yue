package ai.jackknife.killayue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * java ai.jackknife.killayue.XmlTool OUTDIR SCORE.abc... : for check_musicxml.py, every score as MusicXML
 * (SONG.musicxml) and every voice of it written out for every instrument (SONG__VOICE__INSTRUMENT.musicxml,
 * with the part's ABC beside it), as the app writes them. OUTDIR/files.tsv: file, song, voice (or "score"),
 * instrument, octaves moved.
 */
public class XmlTool {
    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]);
        Files.createDirectories(out);
        StringBuilder tsv = new StringBuilder();
        for (int i = 1; i < a.length; i++) {
            Path in = Paths.get(a[i]);
            String song = in.getFileName().toString().replace(".abc", "");
            String abc = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);
            Files.write(out.resolve(song + ".musicxml"), MusicXml.write(abc, song, null).getBytes(StandardCharsets.UTF_8));
            tsv.append(song).append(".musicxml\t").append(song).append("\tscore\t-\t0\n");
            for (String voice : new String[] {"Vocal", "Ins"}) {
                for (Instruments.Instrument ins : Instruments.ALL) {
                    Transpose.Part p = Transpose.part(abc, voice, ins);
                    String name = song + "__" + voice + "__" + ins.id;
                    Files.write(out.resolve(name + ".musicxml"), MusicXml.write(p.abc, song, ins).getBytes(StandardCharsets.UTF_8));
                    Files.write(out.resolve(name + ".abc"), p.abc.getBytes(StandardCharsets.UTF_8));
                    tsv.append(name).append(".musicxml\t").append(song).append('\t').append(voice).append('\t').append(ins.id)
                            .append('\t').append(p.octaves).append('\n');
                }
            }
        }
        Files.write(out.resolve("files.tsv"), tsv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
