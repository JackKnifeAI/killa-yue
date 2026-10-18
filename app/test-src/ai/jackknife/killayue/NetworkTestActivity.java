package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;

/**
 * Installed only in STUDIO_TEST builds. Two phones, the Killa YuE network, nothing else: "host" publishes test tones
 * (never the Library) through this phone's node and writes its network card; "listen" reads a card pushed by adb and
 * runs every check against that host. Results go to the screen and to hammer-network-result.txt.
 *   adb shell am start -n ai.jackknife.killayue/.NetworkTestActivity --es mode host
 *   adb shell am start -n ai.jackknife.killayue/.NetworkTestActivity --es mode listen
 */
public final class NetworkTestActivity extends Activity {
    private static Hammer.Server server; // keeps hosting while the process lives

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        final TextView view = new TextView(this);
        final String mode = String.valueOf(getIntent().getStringExtra("mode"));
        view.setText("Killa YuE network test: " + mode);
        setContentView(view);
        new Thread(new Runnable() {
            public void run() {
                String result;
                File out = getExternalFilesDir(null);
                try {
                    String id = HammerAndroid.network(NetworkTestActivity.this);
                    File root = new File(getFilesDir(), "network-test");
                    if ("host".equals(mode)) {
                        if (server == null)
                            server = NetworkTest.host(HammerAndroid.identity(), HammerAndroid.certificate(), root,
                                                      new File(out, "network-card.txt"));
                        result = "hosting as " + id + "\n" + KillaNet.addresses();
                    } else {
                        result = NetworkTest.listen(root, new File(out, "network-card.txt"), 60000);
                    }
                } catch (Throwable e) {
                    java.io.StringWriter s = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(s));
                    result = s.toString();
                }
                final String output = result;
                try {
                    Store.write(new File(out, "hammer-network-result.txt"), output.getBytes("UTF-8"));
                } catch (Exception ignored) {
                }
                runOnUiThread(new Runnable() {
                    public void run() { view.setText(output); }
                });
            }
        }, "network-test").start();
    }
}
