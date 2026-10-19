package ai.jackknife.killayue.peer;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** An optional single-node counter. Tokens are independent of public identities; no IP or activity log. */
public final class HammerCensus {
    private final File file;
    private String month;
    private final Set<String> tokens = new HashSet<String>();
    public volatile boolean enabled;
    public HammerCensus(File root) throws Exception {
        file = new File(root, "community-count.bin");
        if (file.isFile())
            try (DataInputStream in =
                     new DataInputStream(new ByteArrayInputStream(HammerStore.read(file, 7000000)))) {
                month = Hammer.text(in, 7);
                int n = in.readInt();
                Hammer.require(n >= 0 && n <= 100000, "Count capacity");
                for (int k = 0; k < n; k++) {
                    String t = Hammer.text(in, 64);
                    Hammer.require(Hammer.digest(t), "Invalid count token");
                    tokens.add(t);
                }
                Hammer.require(in.read() == -1, "Invalid count file");
            }
        rollover();
    }
    public static String month() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }
    public static String token(String secret, String node, String month) throws Exception {
        Hammer.require(Hammer.digest(secret) && Hammer.digest(node) && month.matches("[0-9]{4}-[0-9]{2}"),
                       "Invalid counting identity");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("US-ASCII"), "HmacSHA256"));
        return Hammer.hex(mac.doFinal(("HAMMER census 1\n" + node + "\n" + month).getBytes("US-ASCII")));
    }
    private void save() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        Hammer.text(o, month, 7);
        o.writeInt(tokens.size());
        for (String t : tokens)
            Hammer.text(o, t, 64);
        HammerStore.atomic(file, b.toByteArray());
    }
    public synchronized void rollover() throws Exception {
        String now = month();
        if (!now.equals(month)) {
            month = now;
            tokens.clear();
            save();
        }
    }
    public synchronized int count(String period, String token, boolean remove) throws Exception {
        Hammer.require(enabled, "This node does not provide a community count");
        rollover();
        Hammer.require(month.equals(period) && (token.isEmpty() || Hammer.digest(token)),
                       "Wrong month or count token");
        if (!token.isEmpty()) {
            if (remove) {
                if (tokens.remove(token))
                    save();
            } else if (!tokens.contains(token)) {
                Hammer.require(tokens.size() < 100000, "Monthly count full");
                tokens.add(token);
                save();
            }
        }
        return tokens.size();
    }
    public static int count(Hammer.Card peer, String token, boolean remove, Hammer.Job job) throws Exception {
        Hammer.require(!peer.isPrivate(), "Use a public counting node");
        try (Hammer.Connection c = new Hammer.Connection(peer, HammerCommunity.COUNT, job)) {
            String month = month();
            Hammer.text(c.out, month, 7);
            Hammer.text(c.out, token, 64);
            c.out.writeBoolean(remove);
            c.reply();
            Hammer.require(Hammer.text(c.in, 7).equals(month), "Counter month changed; retry");
            int count = c.in.readInt();
            Hammer.require(count >= 0 && count <= 100000, "Invalid count");
            return count;
        }
    }
}
