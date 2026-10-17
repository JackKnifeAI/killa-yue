package ai.jackknife.killayue.studio;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Test builds only (build.sh with STUDIO_TEST=1): the Studio engine's checks on the phone, through the real
 * audio device. Results go to logcat (tag StudioTest) and to files/studio-test.txt. Start one with adb:
 *
 *   am start -n ai.jackknife.killayue/.studio.StudioTestActivity --es test stress --ei seconds 600
 *
 * stress: 16 looping tracks through the device, an edit every 5 ms and a seek every 20 s; passes with no
 *         underrun outside the 300 ms after a seek and no device xrun after the first 2 s.
 * latency: the round trip, measured three times (grant the microphone first:
 *         pm grant ai.jackknife.killayue android.permission.RECORD_AUDIO); passes when they agree within 1 ms.
 * route: plays a quiet tone for the given seconds and reports every reopen (plug and unplug headphones).
 * Extras: --ef master DB (default -40, quiet), --ei seconds N.
 */
public class StudioTestActivity extends Activity {
    private TextView text;
    private volatile boolean alive = true;
    private File report;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        text = new TextView(this);
        text.setTextIsSelectable(true);
        text.setPadding(24, 24, 24, 24);
        ScrollView sv = new ScrollView(this);
        sv.addView(text);
        setContentView(sv);
        final String test = String.valueOf(getIntent().getStringExtra("test"));
        final int seconds = getIntent().getIntExtra("seconds", 60);
        final float master = getIntent().getFloatExtra("master", -40f);
        report = new File(getFilesDir(), "studio-test.txt");
        report.delete();
        if (test.equals("mixer-ui")) {
            ai.jackknife.killayue.studio.ui.MixerProof.start(this);
            return;
        }
        if (test.equals("playalong-recover")) {
            ai.jackknife.killayue.studio.ui.PlayAlongProof.recover(this);
            return;
        }
        if (test.equals("playalong-ui") || test.equals("playalong-crash")) {
            ai.jackknife.killayue.studio.ui.PlayAlongProof.start(this);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    NativeEngine.load();
                    if (test.equals("mixer") || test.equals("effects")) {
                        String oldTmp = System.getProperty("java.io.tmpdir");
                        try {
                            System.setProperty("java.io.tmpdir", getCacheDir().getPath());
                            NativeMixerTest.main(new String[0]);
                            if (test.equals("effects")) {
                                NativeEffectsTest.main(new String[0]);
                                log("PASS effects offline JNI/export fixture: " + NativeEffectsTest.passed + " assertions");
                            }
                            log("PASS mixer offline JNI/export fixture: " + NativeMixerTest.passed + " assertions");
                        } finally {
                            if (oldTmp != null) System.setProperty("java.io.tmpdir", oldTmp);
                        }
                        stress(seconds, master);
                    } else if (test.equals("playalong"))
                        playAlong(seconds);
                    else if (test.equals("stress"))
                        stress(seconds, master);
                    else if (test.equals("latency"))
                        latency();
                    else if (test.equals("route"))
                        route(seconds, master);
                    else
                        log("RESULT unknown test \"" + test + "\" (stress, latency or route)");
                } catch (Throwable t) {
                    log("RESULT FAIL " + t);
                }
            }
        }, "StudioTest").start();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    private void log(final String s) {
        Log.i("StudioTest", s);
        try {
            FileWriter w = new FileWriter(report, true);
            w.write(s + "\n");
            w.close();
        } catch (IOException x) {
            // logcat has it
        }
        runOnUiThread(new Runnable() {
            public void run() {
                text.append(s + "\n");
            }
        });
    }

    private static String device(NativeEngine.Status s) {
        return String.format(java.util.Locale.ROOT,
            "device %d Hz, burst %d, buffer %d, %s, callback policy %d priority %d, output latency %.1f ms", s.deviceRate, s.burst,
            s.bufferFrames, s.exclusive ? "exclusive" : "shared", s.callbackPolicy, s.callbackPriority, s.outputLatencyMs);
    }

    private void playAlong(int seconds) throws Exception {
        File dir = new File(getFilesDir(), "playalong-proof");
        new File(dir, "takes").mkdirs();
        NativeEngine e = NativeEngine.onDevice(true);
        try {
            e.start();
            Project p = tracks(e.rate(), Math.max(seconds, 10), dir);
            p.masterDb = -24;
            e.loop(0, 2L * p.rate, true);
            e.load(p, dir);
            e.record(new File(dir, "takes/proof.wav"), 0, false);
            e.play();
            long before = e.status().underruns;
            long began = System.currentTimeMillis();
            int voices = Math.max(2, Math.min(64, getIntent().getIntExtra("voices", 2)));
            log("playalong starting: " + seconds + " seconds, " + voices + " pad triggers per half second");
            for (int i = 0; i < Math.max(seconds, 10) * 20 && alive; i++) {
                if (i % 10 == 0) {
                    for (int v = 0; v < voices; v++)
                        e.pad((i + v) % 16, voices > 2 ? .03f : .2f, true);
                }
                Thread.sleep(50);
            }
            e.stop();
            long[] result = new long[3];
            String err = e.stopRecord(result);
            if (err != null)
                throw new IOException(err);
            WavIn wav = WavIn.open(new File(dir, "takes/proof.wav"));
            long frames = wav.frames;
            wav.close();
            if (frames < 8L * p.rate || result[1] < 0 || result[2] < 0)
                throw new IOException("Recording or anchors missing.");
            NativeStretch.peaks(new File(dir, "takes/proof.wav"));
            NativeEngine.Status status = e.status();
            log(device(status));
            log("recorded frames=" + frames + " first=" + result[1] + " outputAnchor=" + result[2]);
            log("dropouts during mixed recording=" + (status.underruns - before) + " xruns=" + status.xruns
                + " callbackMaxMs=" + status.callbackMaxMs);
            if (status.underruns > before)
                throw new IOException("Audio underruns during recording.");
            if (!new File(dir, "takes/proof.wav.kpk").isFile())
                throw new IOException("Waveform peaks missing.");
            log("RESULT PASS playalong: microphone + 16 tracks + pads + durable WAV + peaks, " + (System.currentTimeMillis() - began)
                + " ms");
        } finally {
            e.close();
        }
    }

    // a mono float WAV of a sine
    private static void tone(File f, int rate, double hz, float level, int frames) throws IOException {
        int data = frames * 4;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + data).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII"))
            .putInt(16)
            .putShort((short) 3)
            .putShort((short) 1)
            .putInt(rate)
            .putInt(rate * 4)
            .putShort((short) 4)
            .putShort((short) 32);
        b.put("data".getBytes("US-ASCII")).putInt(data);
        for (int i = 0; i < frames; i++) b.putFloat((float) (level * Math.sin(2 * Math.PI * hz * i / rate)));
        FileOutputStream o = new FileOutputStream(f);
        o.write(b.array());
        o.close();
    }

    // 16 tracks, each a 2-second tone looped clip after clip for the whole run
    private Project tracks(int rate, int seconds, File dir) throws IOException {
        new File(dir, "audio").mkdirs();
        Project p = new Project("Stress", rate);
        long len = 2L * rate, total = (seconds + 30L) * rate;
        for (int t = 0; t < 16; t++) {
            // whole cycles in 2 s, so the loop joins without a click
            tone(new File(dir, "audio/t" + t + ".wav"), rate, 110 + 27.5 * t, 0.2f, (int) len);
            Track tr = p.addTrack("Tone " + t);
            for (long at = 0; at < total; at += len) p.addClip(tr, "audio/t" + t + ".wav", at, 0, len);
        }
        return p;
    }

    private void stress(int seconds, float master) throws Exception {
        File dir = new File(getFilesDir(), "studio-test");
        NativeEngine e = NativeEngine.onDevice(false);
        try {
            Project p = tracks(e.rate(), seconds, dir);
            final boolean effectsTest = "effects".equals(getIntent().getStringExtra("test"));
            final boolean mixerTest = effectsTest || "mixer".equals(getIntent().getStringExtra("test"));
            if (mixerTest) {
                for (int i = 0; i < 8; i++) p.mixer.addGroup("Group " + (i + 1));
                for (int i = 0; i < p.tracks.size(); i++) {
                    String id = p.tracks.get(i).id;
                    p.mixer.edit(id).route = p.mixer.groups.get(i % 8);
                    p.mixer.edit(id).reverb = .12f;
                    p.mixer.edit(id).delay = .08f;
                    if (effectsTest) {
                        for (int kind = Effects.EQ; kind <= Effects.FILTER; kind++) {
                            if (i >= 8 && Effects.wet(kind)) continue;
                            p.mixer.addSlot(id, kind);
                        }
                    } else for (int k = 0; k < 8; k++) p.mixer.addSlot(id, k % 2 + 1).value = k % 2 == 0 ? -1 : .9f;
                }
                for (String id : p.mixer.groups) {
                    p.mixer.edit(id).gainDb = -6;
                    p.mixer.edit(id).reverb = .1f;
                    for (int k = 0; k < 8; k++) p.mixer.addSlot(id, 1).value = -.5f;
                }
                for (String id : new String[] {Mixer.MASTER, Mixer.REVERB, Mixer.DELAY})
                    for (int k = 0; k < 8; k++) p.mixer.addSlot(id, 1).value = -.5f;
                log(effectsTest ? "effects: 16 tracks, all seven effects, 16 wet inserts, 8 groups, shared sends; send/parameter edits every 25 ms"
                    : "mixer: 8 groups, both sends, 8 inserts on all 27 strips; send edit every 25 ms");
            }
            log("stress: 16 tracks, " + p.tracks.get(0).clips.size() + " clips each, " + seconds + " s, master " + master + " dB");
            e.load(p, dir);
            e.master(master);
            e.start();
            e.play();
            Random rng = new Random(1);
            long t0 = System.nanoTime(), lastSeek = 0, lastStatus = 0, lastLine = 0;
            long prevUnder = 0, prevXruns = 0, bad = 0, afterSeek = 0, xrunsBad = 0, xrunsEarly = 0;
            NativeEngine.Status s = e.status();
            while (alive) {
                long ms = (System.nanoTime() - t0) / 1000000;
                if (ms >= seconds * 1000L)
                    break;
                String id = p.tracks.get(rng.nextInt(16)).id;
                if (mixerTest) {
                    p.mixer.edit(id).reverb = rng.nextFloat() * .2f;
                    p.mixer.edit(id).delay = rng.nextFloat() * .2f;
                    if (effectsTest) p.mixer.edit(id).chain.get(0).set(1, rng.nextFloat()*12-6);
                    e.mixer(p);
                }
                switch (rng.nextInt(4)) {
                    case 0:
                        e.gain(id, -20f * rng.nextFloat());
                        break;
                    case 1:
                        e.pan(id, 2f * rng.nextFloat() - 1f);
                        break;
                    case 2:
                        e.mute(id, rng.nextInt(10) == 0);
                        break;
                    default:
                        e.solo(id, rng.nextInt(50) == 0);
                        break;
                }
                if (ms - lastSeek >= 20000) {
                    e.seek((long) (rng.nextDouble() * seconds / 2 * e.rate()));
                    lastSeek = ms;
                }
                if (ms - lastStatus >= 100) {
                    s = e.status();
                    long du = s.underruns - prevUnder, dx = s.xruns - prevXruns;
                    if (ms - lastSeek < 300)
                        afterSeek += du;
                    else
                        bad += du;
                    if (ms < 2000)
                        xrunsEarly += dx;
                    else
                        xrunsBad += dx;
                    prevUnder = s.underruns;
                    prevXruns = s.xruns;
                    lastStatus = ms;
                    if (s.error != null)
                        log("device: " + s.error);
                }
                if (ms - lastLine >= 10000) {
                    log(String.format(java.util.Locale.ROOT,
                        "t=%ds pos=%.1fs underruns %d (+%d after seeks), xruns %d (+%d at start), callbacks %d, max %.2f ms, avg %.3f ms, "
                        + "slow %d",
                        ms / 1000, s.position / (double) e.rate(), bad, afterSeek, xrunsBad, xrunsEarly, s.callbacks, s.callbackMaxMs,
                        s.callbackAvgMs, s.slowCallbacks));
                    lastLine = ms;
                }
                Thread.sleep(mixerTest ? 25 : 5);
            }
            e.stop();
            s = e.status();
            log(device(s));
            log(String.format(java.util.Locale.ROOT,
                "callbacks %d, slowest %.2f ms (a burst is %.2f ms), average %.3f ms, %d slower than a burst", s.callbacks, s.callbackMaxMs,
                1000.0 * s.burst / s.deviceRate, s.callbackAvgMs, s.slowCallbacks));
            log("RESULT " + (alive && bad == 0 && xrunsBad == 0 ? "PASS" : "FAIL") + " stress: underruns " + bad + " (+" + afterSeek
                + " within 300 ms of a seek), device xruns " + xrunsBad + " (+" + xrunsEarly + " in the first 2 s)");
        } finally {
            e.close();
            for (File f : new File(dir, "audio").listFiles()) f.delete();
        }
    }

    private void latency() throws Exception {
        NativeEngine e = NativeEngine.onDevice(true);
        try {
            e.start();
            Thread.sleep(700); // let both streams settle
            long[] got = new long[3];
            for (int k = 0; k < 3; k++) {
                try {
                    got[k] = e.measureLatency(5);
                } finally {
                    log("heard: " + e.latencyReport());
                }
                log(String.format(java.util.Locale.ROOT, "round trip %d: %d frames = %.2f ms", k + 1, got[k], 1000.0 * got[k] / e.rate()));
                Thread.sleep(300);
            }
            NativeEngine.Status s = e.status();
            log(device(s) + ", microphone channels " + s.inputChannels);
            long lo = Math.min(got[0], Math.min(got[1], got[2])), hi = Math.max(got[0], Math.max(got[1], got[2]));
            log("RESULT " + (hi - lo <= e.rate() / 1000 ? "PASS" : "FAIL") + " latency: " + got[0] + ", " + got[1] + ", " + got[2]
                + " frames (spread " + (hi - lo) + ")");
        } catch (IOException x) {
            log("RESULT FAIL latency: " + x.getMessage());
        } finally {
            e.close();
        }
    }

    private void route(int seconds, float master) throws Exception {
        File dir = new File(getFilesDir(), "studio-test");
        NativeEngine e = NativeEngine.onDevice(false);
        try {
            new File(dir, "audio").mkdirs();
            tone(new File(dir, "audio/route.wav"), e.rate(), 440, 0.2f, 2 * e.rate());
            Project p = new Project("Route", e.rate());
            Track t = p.addTrack("Tone");
            for (long at = 0; at < (seconds + 30L) * e.rate(); at += 2L * e.rate()) p.addClip(t, "audio/route.wav", at, 0, 2L * e.rate());
            e.load(p, dir);
            e.master(master);
            e.start();
            e.play();
            log("route: playing for " + seconds + " s; plug and unplug headphones, or connect Bluetooth");
            int reopened = 0;
            long t0 = System.nanoTime(), lastPos = -1;
            NativeEngine.Status s = e.status();
            while (alive && (System.nanoTime() - t0) / 1000000000L < seconds) {
                Thread.sleep(250);
                s = e.status();
                if (s.reopened != reopened) {
                    reopened = s.reopened;
                    log("reopened (" + reopened + "): " + device(s));
                }
                if (s.error != null)
                    log("device: " + s.error);
                lastPos = s.position;
            }
            log("RESULT " + (s.running && lastPos > 0 ? "PASS" : "FAIL") + " route: reopened " + reopened + " times, still running "
                + s.running);
        } finally {
            e.close();
            for (File f : new File(dir, "audio").listFiles()) f.delete();
        }
    }
}
