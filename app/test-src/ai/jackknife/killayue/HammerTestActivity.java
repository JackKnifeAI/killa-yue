package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.HammerIntegration;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;

/** Installed only in STUDIO_TEST builds. Test audio and hosts are isolated from the real library. */
public final class HammerTestActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        final TextView view = new TextView(this);
        view.setText("HAMMER integration running");
        setContentView(view);
        new Thread(new Runnable() {
            public void run() {
                String result;
                try {
                    result = HammerIntegration.run(HammerAndroid.identity(), HammerAndroid.certificate(),
                        new File(getFilesDir(), "hammer-test-" + System.currentTimeMillis()));
                } catch (Throwable e) {
                    java.io.StringWriter s = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(s));
                    result = s.toString();
                }
                final String output = result;
                try {
                    Store.write(new File(getExternalFilesDir(null), "hammer-test-result.txt"),
                        output.getBytes("UTF-8"));
                } catch (Exception ignored) {
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        view.setText(output);
                    }
                });
            }
        }, "hammer-integration").start();
    }
}
