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
                    HammerAndroid.store(HammerTestActivity.this);
                    result = HammerIntegration.run(HammerAndroid.identity(), HammerAndroid.certificate(),
                        new File(getFilesDir(), "hammer-test-" + System.currentTimeMillis()),
                        new HammerIntegration.Controls() {
                            public void run(final Runnable action) throws Exception {
                                final java.util.concurrent.CountDownLatch done =
                                    new java.util.concurrent.CountDownLatch(1);
                                final java.util.concurrent.atomic.AtomicReference<Throwable> error =
                                    new java.util.concurrent.atomic.AtomicReference<Throwable>();
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        try {
                                            action.run();
                                        } catch (Throwable failure) {
                                            error.set(failure);
                                        } finally {
                                            done.countDown();
                                        }
                                    }
                                });
                                if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new Exception("UI cancellation timed out");
                                if (error.get() != null)
                                    throw new Exception("UI cancellation failed", error.get());
                            }
                        });
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
