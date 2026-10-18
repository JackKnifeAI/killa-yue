package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.Activity;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android player regression: UI polling must not interrupt asynchronous preparation. */
final class HammerPlaybackTest {
    interface Action { void run() throws Exception; }
    static void ui(Activity activity, final Action action) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Exception> failure = new AtomicReference<Exception>();
        activity.runOnUiThread(new Runnable() {
            public void run() {
                try { action.run(); } catch (Exception e) { failure.set(e); }
                finally { done.countDown(); }
            }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new IOException("Playback UI did not respond");
        if (failure.get() != null) throw failure.get();
    }
    static String run(final Activity activity) throws Exception {
        File root = new File(activity.getFilesDir(), "hammer-playback-test-" + System.currentTimeMillis());
        Hammer.require(root.mkdirs(), "Cannot create playback fixture");
        File audio = HammerIntegration.wave(root, "quiet.wav", 6, 48000, 2);
        byte[] wave = Files.readAllBytes(audio.toPath());
        for (int at = 44; at + 1 < wave.length; at += 2) {
            short sample = (short)((wave[at] & 255) | (wave[at + 1] << 8));
            sample /= 32;
            wave[at] = (byte)sample; wave[at + 1] = (byte)(sample >> 8);
        }
        Files.write(audio.toPath(), wave);
        HammerStore store = new HammerStore(new File(root, "host"));
        final Hammer.Song song = store.publish(audio, "Quiet player regression", "test", true, true,
            HammerAndroid.identity());
        Hammer.Server server = new Hammer.Server(
            Hammer.serverContext(HammerAndroid.identity(), HammerAndroid.certificate()),
            new InetSocketAddress("127.0.0.1", 0), store);
        final Hammer.Card peer = new Hammer.Card("127.0.0.1", server.port(), HammerAndroid.pin(), "");
        try {
            for (final int mode : new int[] {HammerBuffer.AUTO, HammerBuffer.ORIGINAL, HammerBuffer.SAVER}) {
                ui(activity, new Action() { public void run() throws Exception {
                    HammerService.play(activity, Collections.singletonList(peer), song, mode);
                }});
                final boolean[] advanced = {false};
                long deadline = System.nanoTime() + 15000000000L;
                while (!advanced[0] && System.nanoTime() < deadline) {
                    ui(activity, new Action() { public void run() throws Exception {
                        int duration = HammerService.duration(), position = HammerService.position();
                        Hammer.require(!HammerService.playback.contains("interrupted"),
                            "UI polling interrupted preparation in mode " + mode);
                        advanced[0] = duration >= 5900 && position >= 120;
                    }});
                    Thread.sleep(20);
                }
                Hammer.require(advanced[0], "Player did not advance in mode " + mode);
                ui(activity, new Action() { public void run() throws Exception {
                    HammerService.pause();
                    Hammer.require(HammerService.playback.equals("Paused"), "Pause failed");
                    HammerService.seek(1000);
                    HammerService.pause();
                    Hammer.require(HammerService.playback.equals("Playing"), "Resume failed");
                    HammerService.stopPlayback();
                    Hammer.require(HammerService.duration() == 0 && HammerService.position() == 0,
                        "Stopped player controls are not cleared");
                }});
            }
            return "Android player: Auto, Original and Data saver preparation/polling, playback, pause, seek, resume and stop passed.";
        } finally {
            ui(activity, new Action() { public void run() { HammerService.stopPlayback(); }});
            server.close();
        }
    }
}
