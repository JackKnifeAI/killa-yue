package ai.jackknife.killayue.peer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Read-only deployed-node check. Supply a public card file; optional --network uses a fresh temporary node.
 */
public final class CommunityProbe {
    public static void main(String[] args) throws Exception {
        Hammer.Card peer = Hammer.Card.parse(
            new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.US_ASCII).trim());
        try {
            if (peer.isP2p()) {
                File data = Files.createTempDirectory("community-probe-").toFile();
                KillaNet.start(data, Hammer.random(), KillaNet.NO_MAINLINE | KillaNet.NO_UPNP, 0,
                               args.length > 1 ? args[1] : "");
                Thread.sleep(5000);
            }
            System.out.println("Public tracks: " + Hammer.catalog(peer, new Hammer.Job()).size());
            System.out.println("Public artist profiles: " + Hammer.profiles(peer, new Hammer.Job()).size());
            System.out.println("Monthly participating installations: " +
                               HammerCensus.count(peer, "", false, new Hammer.Job()));
            System.out.println("Board records: " +
                               HammerCommunity.list(peer, "board", peer.pin, new Hammer.Job()).size());
            System.out.println("Pinned TLS and community protocol: PASS");
        } finally {
            KillaNet.stop();
        }
    }
}
