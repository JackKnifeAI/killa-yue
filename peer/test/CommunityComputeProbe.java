package ai.jackknife.killayue.peer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
/** Operator-only synthetic audio check. Reads a private invitation locally; never prints it. */
public final class CommunityComputeProbe {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        Hammer.Card direct = Hammer.Card.parse(
            new String(Files.readAllBytes(new File(root, "direct.card").toPath()), StandardCharsets.US_ASCII)
                .trim());
        String token =
            new String(HammerStore.read(new File(root, "circle.key"), 64), StandardCharsets.US_ASCII);
        Hammer.Card privateCard = new Hammer.Card(direct.host, direct.port, direct.pin, token);
        for (Hammer.Song song : Hammer.catalog(privateCard, new Hammer.Job()))
            if (song.title.equals("Community node verification tone")) {
                long start = System.nanoTime();
                long frames = Hammer.frames(privateCard, song.id, new Hammer.Job());
                byte[] encoded = Hammer.segment(privateCard, song.id, 0, 48000, new Hammer.Job());
                Hammer.require(frames == 96000 && encoded.length > 0, "Synthetic audio compute differs");
                System.out.println("Deployed node transcoded 96000 synthetic frames: " + encoded.length +
                                   " bytes, " + ((System.nanoTime() - start) / 1000000) +
                                   " ms including pinned TLS.");
                for (Hammer.Song publicSong : Hammer.catalog(direct, new Hammer.Job()))
                    Hammer.require(!publicSong.id.equals(song.id), "Private fixture leaked");
                System.out.println("Private fixture absent from public catalog: PASS");
                return;
            }
        throw new IOException("No synthetic node fixture");
    }
}
