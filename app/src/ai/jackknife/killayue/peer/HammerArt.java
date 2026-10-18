package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.KeyPair;
import java.util.Base64;

/** A cover is a separately signed attachment; adding art never changes the audio hash. */
public final class HammerArt {
    public String song, image, key, sig;
    private byte[] payload() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        Hammer.text(out, "HAMMER cover 1", 32);
        Hammer.text(out, song, 64);
        Hammer.text(out, image, 64);
        return b.toByteArray();
    }
    public void sign(KeyPair pair) throws Exception {
        key = Hammer.publicKey(pair.getPublic());
        sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        validate();
    }
    public void validate() throws Exception {
        Hammer.require(Hammer.digest(song) && Hammer.digest(image), "Invalid cover reference");
        Hammer.require(Hammer.verify(key, sig, payload()), "Cover signature does not match");
    }
    public void write(DataOutputStream out) throws IOException {
        Hammer.text(out, song, 64);
        Hammer.text(out, image, 64);
        Hammer.text(out, key, 256);
        Hammer.text(out, sig, 256);
    }
    public static HammerArt read(DataInputStream in) throws Exception {
        HammerArt a = new HammerArt();
        a.song = Hammer.text(in, 64);
        a.image = Hammer.text(in, 64);
        a.key = Hammer.text(in, 256);
        a.sig = Hammer.text(in, 256);
        a.validate();
        return a;
    }
}
