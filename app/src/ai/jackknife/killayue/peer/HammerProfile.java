package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.KeyPair;
import java.util.Base64;

/** Public, signed artist fields. No local filenames, URLs, HTML or generation metadata. */
public final class HammerProfile {
    public String name = "", bio = "", tags = "", image = "", key = "", sig = "";
    public long revision;
    public String id() throws Exception { return Hammer.hash(Base64.getDecoder().decode(key)); }
    private void fields(DataOutputStream out) throws IOException {
        Hammer.text(out, "HAMMER artist 1", 32);
        Hammer.text(out, name, 160);
        Hammer.text(out, bio, 2048);
        Hammer.text(out, tags, 512);
        Hammer.text(out, image, 64);
        out.writeLong(revision);
    }
    private byte[] payload() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        fields(new DataOutputStream(b));
        return b.toByteArray();
    }
    public void sign(KeyPair pair) throws Exception {
        key = Hammer.publicKey(pair.getPublic());
        sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        validate();
    }
    public void validate() throws Exception {
        Hammer.require(!name.trim().isEmpty() && Hammer.displayText(name) && Hammer.displayText(bio) &&
                           Hammer.displayText(tags) && (image.isEmpty() || Hammer.digest(image)) &&
                           revision > 0 && revision < Long.MAX_VALUE,
                       "Invalid artist profile");
        Hammer.require(Hammer.verify(key, sig, payload()), "Artist signature does not match");
    }
    public boolean matches(String query) {
        String text = (name + " " + bio + " " + tags).toLowerCase(java.util.Locale.ROOT);
        for (String word : query.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+"))
            if (!text.contains(word))
                return false;
        return true;
    }
    public void write(DataOutputStream out) throws IOException {
        fields(out);
        Hammer.text(out, key, 256);
        Hammer.text(out, sig, 256);
    }
    public static HammerProfile read(DataInputStream in) throws Exception {
        Hammer.require("HAMMER artist 1".equals(Hammer.text(in, 32)), "Unsupported artist record");
        HammerProfile p = new HammerProfile();
        p.name = Hammer.text(in, 160);
        p.bio = Hammer.text(in, 2048);
        p.tags = Hammer.text(in, 512);
        p.image = Hammer.text(in, 64);
        p.revision = in.readLong();
        p.key = Hammer.text(in, 256);
        p.sig = Hammer.text(in, 256);
        p.validate();
        return p;
    }
}
