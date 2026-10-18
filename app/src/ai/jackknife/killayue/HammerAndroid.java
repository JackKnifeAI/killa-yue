package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import javax.security.auth.x500.X500Principal;

/** HAMMER's identity is separate from APK signing and never leaves Android Keystore. */
final class HammerAndroid {
    private static HammerStore store;
    static synchronized HammerStore store(Context c) throws Exception {
        if (store == null) {
            store = new HammerStore(new File(c.getFilesDir(), "hammer"));
            HammerMedia.codec = new HammerCodec(c);
        }
        return store;
    }
    static synchronized KeyStore keys() throws Exception { return keys("hammer-identity-v1"); }
    static synchronized KeyStore keys(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(alias)) {
            KeyPairGenerator g =
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            long now = System.currentTimeMillis();
            g.initialize(
                new KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                                KeyProperties.DIGEST_SHA384)
                    .setCertificateSubject(new X500Principal("CN=HAMMER peer"))
                    .setCertificateSerialNumber(new BigInteger(120, new SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(new Date(now - 86400000L))
                    .setCertificateNotAfter(new Date(now + 10L * 365 * 86400000L))
                    .build());
            g.generateKeyPair();
            ks.load(null);
        }
        return ks;
    }
    static KeyPair identity() throws Exception {
        KeyStore k = keys();
        return new KeyPair(k.getCertificate("hammer-identity-v1").getPublicKey(),
                           (PrivateKey)k.getKey("hammer-identity-v1", null));
    }
    static X509Certificate certificate() throws Exception {
        return (X509Certificate)keys().getCertificate("hammer-identity-v1");
    }
    static KeyPair voteIdentity(String song, String audience) throws Exception {
        String alias = "hammer-vote-" + Hammer.hash((song + audience).getBytes("UTF-8"));
        KeyStore k = keys(alias);
        return new KeyPair(k.getCertificate(alias).getPublicKey(), (PrivateKey)k.getKey(alias, null));
    }
    static String pin() throws Exception { return Hammer.hash(certificate().getPublicKey().getEncoded()); }
}
