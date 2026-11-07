package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Requests through the mixnet: votes and comments for Protection (experimental), and the offer and pieces of a
 * hidden artist's song (HammerHidden). The host learns what was sent, never from which address. A request carries a
 * reply block, and the host answers through it, so neither side learns where the other is. Nothing falls back to a
 * direct connection: a request that cannot go through the mixnet fails with the reason.
 * See docs/designs/2026-10-24-mixnet-and-hidden-artists.md.
 */
public final class HammerMix {
    private HammerMix() {}

    static final int MAGIC = 0x4b4d5831; // "KMX1"
    static final int VOTE = 1, COMMENT = 2, ADOPT = 3, PIECE = 4, STATION = 5;
    /** Independent networks the mix nodes must stand on before anything is sent: fewer, and one operator may run them all. */
    public static final int NETWORKS = 3;
    /** How long one try waits for the host's answer; a request goes twice before it fails. */
    private static final long WAIT = 45000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The host's answer to a request: taken (with what it sends back) or refused with its reason. */
    static final class Answer {
        final boolean ok;
        final String why;
        final byte[] data;
        Answer(boolean ok, String why, byte[] data) {
            this.ok = ok;
            this.why = why;
            this.data = data;
        }
    }
    private static final Answer WAITING = new Answer(false, null, null);
    private static final Map<String, Answer> answers = new HashMap<String, Answer>();
    private static Thread reader;
    /** Requests the host side handles one after another; past this many waiting, new ones are dropped. */
    private static final ThreadPoolExecutor hosting = new ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(256), new ThreadPoolExecutor.DiscardPolicy());
    /** Where a helper takes hidden songs (null: this node adopts none). */
    public static volatile HammerAdoption adoption;
    // anonymous senders cannot be limited by address, so the host takes at most a few requests a second in all;
    // pieces of a song being adopted have their own, larger budget (each one is checked against an accepted offer)
    private static final double[] requests = {32, 4, 32}, pieces = {128, 64, 128};
    private static long requestsAt = System.nanoTime(), piecesAt = System.nanoTime();

    /** Starts reading the mixnet inbox: requests for the hosting server, answers for requests sent from here. */
    public static synchronized void start() {
        if (reader != null)
            return;
        reader = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(250);
                        read();
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable keepReading) {
                    }
                }
            }
        }, "hammer-mix");
        reader.setDaemon(true);
        reader.start();
    }

    private static void read() {
        String inbox = KillaNet.mixInbox();
        if (inbox.isEmpty())
            return;
        for (String line : inbox.split("\n")) {
            String[] f = line.split("\t");
            if (f.length != 3)
                continue;
            final byte[] message = unhex(f[2]);
            if (message == null)
                continue;
            if ("got".equals(f[0]) && message.length > 4 && take(message[4] == PIECE)) {
                hosting.execute(new Runnable() {
                    public void run() { host(message); }
                });
            } else if ("back".equals(f[0])) {
                answered(message);
            }
        }
    }

    private static synchronized boolean take(boolean piece) {
        long now = System.nanoTime();
        double[] b = piece ? pieces : requests;
        long at = piece ? piecesAt : requestsAt;
        b[0] = Math.min(b[2], b[0] + (now - at) / 1e9 * b[1]);
        if (piece)
            piecesAt = now;
        else
            requestsAt = now;
        if (b[0] < 1)
            return false;
        b[0]--;
        return true;
    }

    // ---------- sending ----------

    /**
     * The mixnet's numbers: mix nodes, independent networks, serving, cover on, loops sent, loops back, and 1 while
     * the gateway is gone and not yet replaced; or null.
     */
    public static long[] look() {
        String l = KillaNet.mixLook();
        if (l == null || l.startsWith("error"))
            return null;
        String[] f = l.trim().split("\t");
        if (f.length < 7)
            return null;
        long[] n = new long[7];
        for (int i = 0; i < 7; i++)
            n[i] = Long.parseLong(f[i]);
        return n;
    }

    /** Why a protected request to this host cannot go now, or null when it can. */
    public static String unready(Hammer.Card host) {
        if (!host.isP2p())
            return "This host is reached by its internet address, not through the Killa YuE network, so it cannot be "
                + "reached privately.";
        return unready();
    }

    /** Why nothing can go through the mixnet now, or null when it can. */
    public static String unready() {
        if (!KillaNet.running())
            return "The Killa YuE network is off.";
        long[] n = look();
        if (n == null)
            return "The mixnet is not running.";
        if (n[0] < 3)
            return "Only " + n[0] + " mix " + (n[0] == 1 ? "node is" : "nodes are")
                + " known so far; a protected send needs three.";
        if (n[1] < NETWORKS && !KillaNet.testNetworks())
            return "The mix nodes stand on only " + n[1] + " independent " + (n[1] == 1 ? "network" : "networks")
                + "; a protected send needs " + NETWORKS + ", so that no one operator runs the whole path.";
        if (n[6] != 0)
            return "This phone's gateway mix went away. A new one is chosen after five minutes, not at once, so that "
                + "a hostile mix cannot push phones onto itself by blocking their gateways.";
        return null;
    }

    public static void vote(Hammer.Card host, final Hammer.Vote vote, Hammer.Job job) throws Exception {
        vote.validate();
        String why = unready(host);
        if (why != null)
            throw new IOException(why + " Nothing was sent.");
        check(request(host.p2pId(), VOTE, host.token, new Body() {
            public void write(DataOutputStream o) throws IOException { vote.write(o); }
        }, 2, WAIT, job));
    }

    public static void comment(Hammer.Card host, final Hammer.Comment comment, Hammer.Job job) throws Exception {
        comment.validate();
        String why = unready(host);
        if (why != null)
            throw new IOException(why + " Nothing was sent.");
        check(request(host.p2pId(), COMMENT, host.token, new Body() {
            public void write(DataOutputStream o) throws IOException { comment.write(o); }
        }, 2, WAIT, job));
    }

    /**
     * Hands a station to a helper through the mixnet, as a hidden artist must, and as any artist may for the helpers
     * that took their songs. True when the helper kept it as new.
     */
    public static boolean station(String helper, final HammerStation s, Hammer.Job job) throws Exception {
        s.validate();
        String why = unready();
        if (why != null)
            throw new IOException(why + " Nothing was sent.");
        byte[] kept = check(request(helper, STATION, "", new Body() {
            public void write(DataOutputStream o) throws IOException { s.write(o); }
        }, 2, WAIT, job));
        return kept.length == 1 && kept[0] == 1;
    }

    static byte[] check(Answer a) throws IOException {
        if (a == null)
            throw new IOException("No answer came back through the mixnet: the host may be offline. Nothing was sent "
                                  + "directly.");
        if (!a.ok)
            throw new IOException("The host refused: " + a.why);
        return a.data;
    }

    interface Body {
        void write(DataOutputStream o) throws IOException;
    }

    /**
     * Sends one request to the node `peer` through the mixnet, up to `tries` times, each waiting `wait` ms for the
     * answer. Returns the answer, or null when none came.
     */
    static Answer request(String peer, int op, String token, Body body, int tries, long wait, Hammer.Job job)
        throws Exception {
        start();
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Hammer.hex(nonce);
        synchronized (answers) {
            answers.put(key, WAITING);
        }
        try {
            for (int attempt = 0; attempt < tries; attempt++) {
                job.check();
                byte[] surb = KillaNet.mixSurb();
                Hammer.require(surb != null, "The mixnet could not make a return path.");
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                DataOutputStream o = new DataOutputStream(b);
                o.writeInt(MAGIC);
                o.writeByte(op);
                o.write(nonce);
                o.writeShort(surb.length);
                o.write(surb);
                Hammer.text(o, token, 64);
                body.write(o);
                o.flush();
                String sent = KillaNet.mixSend(peer, b.toByteArray());
                Hammer.require("ok".equals(sent), sent.startsWith("error: ") ? sent.substring(7) : sent);
                Answer a = await(key, wait, job);
                if (a != null)
                    return a;
            }
            return null;
        } finally {
            synchronized (answers) {
                answers.remove(key);
            }
        }
    }

    /** The host's answer to the request with this nonce, or null when none came in time. */
    private static Answer await(String key, long millis, Hammer.Job job) throws Exception {
        long end = System.currentTimeMillis() + millis;
        synchronized (answers) {
            while (answers.get(key) == WAITING) {
                long left = end - System.currentTimeMillis();
                if (left <= 0)
                    return null;
                answers.wait(Math.min(left, 500));
                job.check();
            }
            return answers.get(key);
        }
    }

    private static void answered(byte[] message) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            if (in.readInt() != MAGIC)
                return;
            byte[] nonce = new byte[16];
            in.readFully(nonce);
            Answer a;
            if (in.readBoolean()) {
                byte[] data = new byte[in.readUnsignedShort()];
                in.readFully(data);
                a = new Answer(true, null, data);
            } else {
                a = new Answer(false, Hammer.text(in, 256), null);
            }
            String key = Hammer.hex(nonce);
            synchronized (answers) {
                if (answers.get(key) == WAITING) {
                    answers.put(key, a);
                    answers.notifyAll();
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ---------- hosting ----------

    /** A request that came through the mixnet: handled like one over a connection, answered through its reply block. */
    private static void host(byte[] message) {
        byte[] nonce = new byte[16], surb;
        int op;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
        try {
            if (in.readInt() != MAGIC)
                return;
            op = in.readUnsignedByte();
            in.readFully(nonce);
            int n = in.readUnsignedShort();
            if (n == 0 || n > 4096)
                return;
            surb = new byte[n];
            in.readFully(surb);
        } catch (IOException e) {
            return;
        }
        String why = null;
        byte[] data = new byte[0];
        try {
            String token = Hammer.text(in, 64);
            if (op == VOTE || op == COMMENT) {
                Hammer.Server server = KillaNet.server();
                Hammer.require(server != null, "Not hosting now");
                if (op == VOTE)
                    server.source().vote(Hammer.Vote.read(in), token);
                else
                    server.source().comment(Hammer.Comment.read(in), token);
            } else if (op == STATION) {
                Hammer.Server server = KillaNet.server();
                Hammer.require(server != null && server.source().publicObjects() != null, "Not hosting now");
                data = new byte[] {(byte)(server.source().publicObjects().carry(HammerStation.read(in)) ? 1 : 0)};
            } else if (op == ADOPT || op == PIECE) {
                HammerAdoption a = adoption;
                Hammer.require(a != null, "This helper does not adopt hidden songs");
                data = op == ADOPT ? a.offer(in) : a.piece(in);
            } else {
                throw new IOException("Unknown request");
            }
        } catch (Exception e) {
            why = e.getMessage() == null ? "Refused" : e.getMessage();
        }
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            o.writeInt(MAGIC);
            o.write(nonce);
            o.writeBoolean(why == null);
            if (why == null) {
                o.writeShort(data.length);
                o.write(data);
            } else {
                Hammer.text(o, why.length() > 200 ? why.substring(0, 200) : why, 256);
            }
            o.flush();
            KillaNet.mixReply(surb, b.toByteArray());
        } catch (IOException ignored) {
        }
    }

    static byte[] unhex(String s) {
        if (s.length() % 2 != 0)
            return null;
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0)
                return null;
            b[i] = (byte)(hi << 4 | lo);
        }
        return b;
    }
}
