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
    private static android.net.wifi.WifiManager.MulticastLock multicast;
    /**
     * Starts the Killa YuE network node once (libkillanet.so), with the multicast lock Android needs before peers on
     * the same Wi-Fi can be seen. Returns the PeerId. Call off the main thread.
     */
    static synchronized String network(Context c) throws Exception {
        if (multicast == null) {
            android.net.wifi.WifiManager w =
                (android.net.wifi.WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicast = w.createMulticastLock("killa-yue-network");
            multicast.setReferenceCounted(false);
            multicast.acquire();
        }
        // a phone helps the network (relays for others, answers DHT queries) only on Wi-Fi while charging, and
        // starts or stops helping as that changes
        int flags = helper(c) ? KillaNet.HELP : 0;
        String id = KillaNet.start(new File(c.getFilesDir(), "network"), pin(), flags, KillaNet.PORT, "");
        watchHelping(c.getApplicationContext());
        return id;
    }
    private static boolean watching;
    private static String network = "";
    /**
     * Tells the node about a real change of network or address. Android follows every new default network with this
     * report, and repeats it often, so only a different network or address set counts.
     */
    private static synchronized void moved(android.net.Network n, android.net.LinkProperties p) {
        StringBuilder now = new StringBuilder(n.toString());
        for (android.net.LinkAddress a : p.getLinkAddresses())
            now.append(' ').append(a.getAddress().getHostAddress());
        String seen = now.toString();
        if (seen.equals(network))
            return;
        boolean first = network.isEmpty();
        network = seen;
        if (!first)
            KillaNet.moved();
    }
    private static void watchHelping(final Context c) {
        if (watching)
            return;
        watching = true;
        final Runnable check = new Runnable() {
            public void run() { KillaNet.help(helper(c)); }
        };
        android.content.IntentFilter power = new android.content.IntentFilter();
        power.addAction(android.content.Intent.ACTION_POWER_CONNECTED);
        power.addAction(android.content.Intent.ACTION_POWER_DISCONNECTED);
        c.registerReceiver(new android.content.BroadcastReceiver() {
            public void onReceive(Context x, android.content.Intent i) { check.run(); }
        }, power);
        try {
            ((android.net.ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE))
                .registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                    public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities caps) {
                        check.run();
                    }
                    public void onLost(android.net.Network n) { check.run(); }
                    // a new default network (Wi-Fi to mobile data and back) or new addresses on it (a carrier
                    // handing out another address): the node reconnects now, not when old connections time out
                    public void onLinkPropertiesChanged(android.net.Network n, android.net.LinkProperties p) {
                        moved(n, p);
                    }
                });
        } catch (Exception noCallback) {
        }
    }
    static boolean helper(Context c) {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm.isActiveNetworkMetered())
                return false;
            android.content.Intent b = c.registerReceiver(
                null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            return b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0 &&
                !MainActivity.jobBusy;
        } catch (Exception e) {
            return false;
        }
    }
    static String pin() throws Exception { return Hammer.hash(certificate().getPublicKey().getEncoded()); }
}
