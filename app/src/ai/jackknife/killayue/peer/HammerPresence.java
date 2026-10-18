package ai.jackknife.killayue.peer;

import java.io.*;
import java.net.*;
import java.security.KeyPair;
import java.util.Base64;

/** Short-lived, owner-signed public host announcement. Never carries circle capabilities. */
public final class HammerPresence {
    public Hammer.Card card;
    public String key, sig;
    public long expires;
    private byte[] payload() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        Hammer.text(out, "HAMMER presence 1", 32);
        Hammer.text(out, card.encode(), 1024);
        out.writeLong(expires);
        return b.toByteArray();
    }
    public void sign(KeyPair pair) throws Exception {
        key = Hammer.publicKey(pair.getPublic());
        sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        validate();
    }
    public void validate() throws Exception {
        Hammer.require(card != null && !card.isPrivate() && publicAddress(card.host),
                       "Only public numeric endpoints can be announced");
        long now = System.currentTimeMillis();
        Hammer.require(expires > now && expires <= now + 3600000L, "Expired or excessive presence lifetime");
        Hammer.require(Hammer.hash(Base64.getDecoder().decode(key)).equals(card.pin) &&
                           Hammer.verify(key, sig, payload()),
                       "Presence signature does not match host");
    }
    public static boolean publicAddress(String host) {
        try {
            // No DNS lookups or rebinding through peer-provided hostnames.
            if (!host.matches("[0-9.]+") && !host.matches("[0-9a-fA-F:]+"))
                return false;
            InetAddress ip = InetAddress.getByName(host);
            if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress() ||
                ip.isSiteLocalAddress() || ip.isMulticastAddress())
                return false;
            byte[] b = ip.getAddress();
            if (b.length == 4) {
                int a = b[0] & 255, c = b[1] & 255;
                return a > 0 && a < 224 && !(a == 100 && c >= 64 && c <= 127) && !(a == 169 && c == 254) &&
                    !(a == 198 && (c == 18 || c == 19));
            }
            return (b[0] & 0xe0) == 0x20; // Public global-unicast IPv6 only.
        } catch (Exception e) {
            return false;
        }
    }
    public void write(DataOutputStream out) throws IOException {
        Hammer.text(out, card.encode(), 1024);
        out.writeLong(expires);
        Hammer.text(out, key, 256);
        Hammer.text(out, sig, 256);
    }
    public static HammerPresence read(DataInputStream in) throws Exception {
        HammerPresence p = new HammerPresence();
        p.card = Hammer.Card.parse(Hammer.text(in, 1024));
        p.expires = in.readLong();
        p.key = Hammer.text(in, 256);
        p.sig = Hammer.text(in, 256);
        p.validate();
        return p;
    }
}
