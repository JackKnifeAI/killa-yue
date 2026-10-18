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

    private static native String nativeStart(String keyFile, String agent, int inboundPort, byte[] token);
    private static native int nativeOpen(String peerId, byte[] token);
    private static native String nativePeers();
    private static native String nativeStatus();
    private static native void nativeStop();

    /** Identify's agent string carries the HAMMER pin, so peers found nearby can be dialled with a full card. */
    static final String AGENT = "killa-yue/1 hammer-pin/";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] TOKEN = new byte[32];
    private static volatile String peerId;
    private static volatile Hammer.Server server;
    private static ServerSocket bridge;

    public static boolean running() { return peerId != null; }
    public static String peerId() { return peerId; }

    /** Starts the node once per process; later calls return the same PeerId. dataDir holds the node key. */
    public static synchronized String start(File dataDir, String pin) throws IOException {
        if (peerId != null)
            return peerId;
        try {
            System.loadLibrary("killanet");
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("The network library is missing from this build");
        }
        RANDOM.nextBytes(TOKEN);
        bridge = new ServerSocket();
        bridge.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        Thread t = new Thread(new Runnable() {
            public void run() { bridge(); }
        }, "killanet-bridge");
        t.setDaemon(true);
        t.start();
        dataDir.mkdirs();
        String id = nativeStart(new File(dataDir, "node.key").getAbsolutePath(), AGENT + pin, bridge.getLocalPort(),
                                TOKEN);
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
        transport.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMillis);
        transport.setTcpNoDelay(true);
        OutputStream out = transport.getOutputStream();
        out.write(token);
        out.flush();
    }

    /** Inbound streams: token, PeerId, then the peer's TLS bytes, handed to the HAMMER server. */
    private static void bridge() {
        while (!bridge.isClosed())
            try {
                final Socket s = bridge.accept();
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

    /** A peer the node knows: its PeerId, its HAMMER pin if it told us, and whether it is on this network. */
    public static final class Peer {
        public final String id, pin;
        public final boolean local, connected;
        Peer(String id, String pin, boolean local, boolean connected) {
            this.id = id;
            this.pin = pin;
            this.local = local;
            this.connected = connected;
        }
    }

    /** Killa YuE peers the node has met; only those that told us a valid HAMMER pin. */
    public static List<Peer> peers() {
        List<Peer> out = new ArrayList<Peer>();
        if (peerId == null)
            return out;
        for (String line : nativePeers().split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length != 4 || !f[1].startsWith(AGENT))
                continue;
            String pin = f[1].substring(AGENT.length()).trim();
            if (Hammer.digest(pin))
                out.add(new Peer(f[0], pin, "1".equals(f[2]), "1".equals(f[3])));
        }
        return out;
    }

    /** One line for Settings: whether the node runs and how many peers are connected. */
    public static String status() {
        if (peerId == null)
            return "Network off";
        String[] f = nativeStatus().split("\t");
        int n = f.length > 1 ? Integer.parseInt(f[1]) : 0;
        return "Network on · " + n + (n == 1 ? " peer" : " peers") + " connected";
    }

    /** The node's listen addresses, for the engine log and tests. */
    public static String addresses() {
        if (peerId == null)
            return "";
        String[] f = nativeStatus().split("\t");
        StringBuilder b = new StringBuilder();
        for (int i = 3; i < f.length; i++)
            b.append(i > 3 ? " " : "").append(f[i]);
        return b.toString();
    }
}
