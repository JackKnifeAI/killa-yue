package ai.jackknife.killayue.peer;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * The Killa YuE network node (libkillanet.so, rust-libp2p). It finds peers by PeerId instead of address and carries
 * HAMMER's TLS connections inside encrypted libp2p streams: outbound through a one-use loopback port, inbound
 * through a loopback bridge that hands each stream to the HAMMER server with the remote PeerId, so admission is
 * counted per peer. Plain Java, so the desktop companion can use it too.
 */
public final class KillaNet {
    private KillaNet() {}

    private static native String nativeStart(String dir, String agent, int inboundPort, byte[] token, int flags,
                                             int port, String entry);
    private static native void nativeAnnounce(boolean on);
    private static native void nativeLookup();
    private static native void nativeHelp(boolean on);
    private static native int nativeOpen(String peerId, byte[] token);
    private static native String nativePeers();
    private static native String nativeStatus();
    private static native void nativeStop();

    /** Identify's agent string carries the HAMMER pin, so peers found nearby can be dialled with a full card. */
    static final String AGENT = "killa-yue/1 hammer-pin/";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] TOKEN = new byte[32];
    /**
     * The bridge's loopback address, IPv4 on both sides: the native node listens on and dials 127.0.0.1, while
     * InetAddress.getLoopbackAddress() is ::1 on Android, which never meets it.
     */
    static final InetAddress LOOPBACK;
    static {
        InetAddress a;
        try {
            a = InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new AssertionError(e);
        }
        LOOPBACK = a;
    }
    private static volatile String peerId;
    private static volatile Hammer.Server server;
    private static ServerSocket bridge;

    public static boolean running() { return peerId != null; }
    public static String peerId() { return peerId; }

    /** Start flags. HELP: relay for others and answer DHT queries (Wi-Fi and charging, or a computer). */
    public static final int HELP = 1, NO_MDNS = 2, NO_MAINLINE = 4, RELAY_ONLY = 8, NO_UPNP = 16, TEST_REACHABLE = 32;
    /** QUIC and TCP share this port, so an address found in the BitTorrent DHT can be dialled both ways. */
    public static final int PORT = 47291;

    public static String start(File dataDir, String pin) throws IOException { return start(dataDir, pin, 0, PORT, ""); }

    /**
     * Starts the node once per process; later calls return the same PeerId. dataDir holds the node key and the
     * peers remembered between runs. entry: newline-separated multiaddrs ending in /p2p/PeerId, for first contact
     * besides the BitTorrent DHT and remembered peers.
     */
    public static synchronized String start(File dataDir, String pin, int flags, int port, String entry)
        throws IOException {
        if (peerId != null)
            return peerId;
        try {
            System.loadLibrary("killanet");
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("The network library is missing from this build");
        }
        RANDOM.nextBytes(TOKEN);
        bridge = new ServerSocket();
        bridge.bind(new InetSocketAddress(LOOPBACK, 0), 16);
        final ServerSocket listener = bridge;
        Thread t = new Thread(new Runnable() {
            public void run() { bridge(listener); }
        }, "killanet-bridge");
        t.setDaemon(true);
        t.start();
        dataDir.mkdirs();
        String id = nativeStart(dataDir.getAbsolutePath(), AGENT + pin, bridge.getLocalPort(), TOKEN, flags, port,
                                entry == null ? "" : entry);
        helping = (flags & HELP) != 0;
        if (id == null || id.startsWith("error")) {
            try {
                bridge.close();
            } catch (IOException ignored) {
            }
            throw new IOException("Network did not start" + (id == null ? "" : ": " + id.substring(5).trim()));
        }
        peerId = id;
        return id;
    }

    /** The HAMMER server that inbound streams are handed to, or null to refuse them (hosting off). */
    public static void serve(Hammer.Server s) { server = s; }

    /** Offer this host in the worldwide public host list (the DHT), or stop offering it. Public hosts only. */
    public static void announce(boolean on) {
        if (peerId != null)
            nativeAnnounce(on);
    }

    /**
     * Volunteer as a relay and DHT server, or stop. A helper serves as a relay only once it is confirmed reachable
     * from the internet, so a phone behind NAT never offers relay spots nobody could use.
     */
    public static synchronized void help(boolean on) {
        // Android reports network changes every few seconds (signal strength); switch only on a real change
        if (running() && (helping == null || helping != on)) {
            helping = on;
            nativeHelp(on);
        }
    }
    private static Boolean helping;
    /** Ask the DHT for public hosts now; they are connected to and appear in peers() as hosts. */
    public static void lookup() {
        if (peerId != null)
            nativeLookup();
    }

    /**
     * Connects transport to peerId through the node. The caller then runs TLS over it as over any socket.
     * Fails quickly when the node is off; a peer that cannot be reached closes the socket within 30 seconds.
     */
    public static void connect(Socket transport, String peerId, int timeoutMillis) throws IOException {
        if (KillaNet.peerId == null)
            throw new IOException("The Killa YuE network is not running");
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        int port = nativeOpen(peerId, token);
        if (port <= 0)
            throw new IOException("Invalid peer");
        transport.connect(new InetSocketAddress(LOOPBACK, port), timeoutMillis);
        transport.setTcpNoDelay(true);
        OutputStream out = transport.getOutputStream();
        out.write(token);
        out.flush();
    }

    /** Inbound streams: token, PeerId, then the peer's TLS bytes, handed to the HAMMER server. */
    private static void bridge(ServerSocket listener) {
        while (!listener.isClosed())
            try {
                final Socket s = listener.accept();
                Thread t = new Thread(new Runnable() {
                    public void run() { admit(s); }
                }, "killanet-in");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                break;
            }
    }

    private static void admit(Socket s) {
        try {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] got = new byte[32];
            in.readFully(got);
            if (!java.security.MessageDigest.isEqual(got, TOKEN))
                throw new IOException("Not the node");
            byte[] id = new byte[in.readUnsignedByte()];
            in.readFully(id);
            s.setSoTimeout(0);
            Hammer.Server host = server;
            if (host == null || id.length == 0)
                throw new IOException("Not hosting");
            host.admit(s, "p2p:" + new String(id, "US-ASCII"));
        } catch (IOException e) {
            Hammer.close(s);
        }
    }

    /**
     * A peer the node knows: its PeerId, its HAMMER pin, whether it is on this network, connected, ever reached
     * through a relay, offered in the public host list, and turned direct by hole punching.
     */
    public static final class Peer {
        public final String id, pin;
        public final boolean local, connected, relayed, host, punched;
        Peer(String id, String pin, boolean local, boolean connected, boolean relayed, boolean host, boolean punched) {
            this.id = id;
            this.pin = pin;
            this.local = local;
            this.connected = connected;
            this.relayed = relayed;
            this.host = host;
            this.punched = punched;
        }
    }

    /** Killa YuE peers the node has met; only those that told us a valid HAMMER pin. */
    public static List<Peer> peers() {
        List<Peer> out = new ArrayList<Peer>();
        if (peerId == null)
            return out;
        for (String line : nativePeers().split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length != 7 || !f[1].startsWith(AGENT))
                continue;
            String pin = f[1].substring(AGENT.length()).trim();
            if (Hammer.digest(pin))
                out.add(new Peer(f[0], pin, "1".equals(f[2]), "1".equals(f[3]), "1".equals(f[4]), "1".equals(f[5]),
                                 "1".equals(f[6])));
        }
        return out;
    }

    /** Stops native networking and its local bridge; desktop shutdown must not leave listeners behind. */
    public static synchronized void stop() {
        server = null;
        if (peerId != null) { nativeStop(); peerId = null; }
        if (bridge != null) try { bridge.close(); } catch (IOException ignored) {}
    }

    /** One line for Settings: whether the node runs and how many peers are connected. */
    public static String status() {
        if (peerId == null)
            return "Network off";
        String[] f = nativeStatus().split("\t");
        int n = f.length > 1 ? Integer.parseInt(f[1]) : 0;
        String reach = f.length > 3 ? f[3] : "unknown";
        int relays = f.length > 4 ? Integer.parseInt(f[4]) : 0;
        return "Network on · " + n + (n == 1 ? " peer" : " peers") + " · " +
            ("unknown".equals(reach) ? "checking reachability"
             : reach.startsWith("public") ? "reachable from the internet"
             : relays > 0 ? "reachable through " + relays + (relays == 1 ? " relay" : " relays")
             : "behind NAT, looking for a relay");
    }

    /** The node's listen addresses, for the engine log and tests. */
    public static String addresses() {
        if (peerId == null)
            return "";
        String[] f = nativeStatus().split("\t");
        StringBuilder b = new StringBuilder();
        for (int i = 5; i < f.length; i++)
            b.append(i > 5 ? " " : "").append(f[i]);
        return b.toString();
    }
}
