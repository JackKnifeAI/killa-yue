package ai.jackknife.killayue;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Predicts how long the song being made has left, from the engine's own log lines.
 *
 * Each stage is predicted from what this phone measured on earlier songs (kept per mode,
 * gentle or normal), and the stage that is running is measured live. When the live speed
 * differs from the remembered speed (a hot phone throttles its GPU), the later stages are
 * scaled by the same factor. Before the first song the defaults come from a Galaxy S25 Ultra.
 */
public class Eta {
    // the engine's lines, as yue2.cpp prints them
    private static final Pattern P_TOK = Pattern.compile("\\[AR\\] (Score|Semantic) (\\d+)/(\\d+)");
    private static final Pattern P_TOK_DONE = Pattern.compile("\\[AR\\] (Score|Semantic) song \\d+: (\\d+) tokens");
    private static final Pattern P_SONG = Pattern.compile("\\[NAR\\] Song \\d+: \\d+ frames \\(([\\d.]+) s\\), prefix \\d+, (\\d+) chunk");
    private static final Pattern P_STEP = Pattern.compile("\\[NAR\\] Step (\\d+)/(\\d+), (\\d+) ms");

    static final int LOAD = 0, SCORE = 1, MELODY = 2, RENDER = 3, DECODE = 4;

    private static SharedPreferences prefs;
    private static volatile boolean active;
    private static volatile int phase;
    private static volatile long start, phaseStart;
    private static volatile int requestSec, steps;
    private static volatile String mode = "n";
    // live measurements for this song
    private static volatile int tokCur, tokMax, tok0;
    private static volatile long tok0At;
    private static volatile double liveTokMs;
    private static volatile double songSec;
    private static volatile int chunks, chunksDone, step, stepMs;
    private static volatile double speed = 1.0; // remembered time / live time; above 1 means faster than usual

    /** Called when a job starts. lengthSec is the requested upper limit, renderSteps the passes per chunk. */
    static void begin(Context c, int lengthSec, int renderSteps, boolean gentle) {
        prefs = c.getSharedPreferences("eta", 0);
        mode = gentle ? "g" : "n";
        requestSec = Math.max(10, lengthSec);
        steps = renderSteps > 0 ? renderSteps : 32;
        start = phaseStart = System.currentTimeMillis();
        phase = LOAD;
        tokCur = tokMax = tok0 = 0;
        liveTokMs = 0;
        songSec = 0;
        chunks = chunksDone = step = stepMs = 0;
        speed = 1.0;
        active = true;
    }

    static void end() {
        active = false;
    }

    // ---- remembered speeds (defaults measured on the S25 Ultra at full GPU clock) ----

    private static double get(String key, double def) {
        return prefs == null ? def : prefs.getFloat(key + "_" + mode, (float) def);
    }

    private static void learn(String key, double value) {
        if (prefs == null || !(value > 0) || Double.isInfinite(value)) return;
        double old = prefs.getFloat(key + "_" + mode, -1f);
        double v = old > 0 ? old * 0.5 + value * 0.5 : value;
        prefs.edit().putFloat(key + "_" + mode, (float) v).apply();
    }

    private static double loadSec() {
        return get("load", 20);
    }

    private static double tokMs() {
        return get("tok", "g".equals(mode) ? 65 : 48);
    }

    private static double scoreTokens() {
        return get("score", 1450);
    }

    // seconds per render step for a song of the given length: about 9.7 s at 20 s, growing a little faster than linearly
    private static double stepSec(double sec) {
        return get("step", "g".equals(mode) ? 12.5 : 9.7) * Math.pow(Math.max(5, sec) / 20.0, 1.2);
    }

    private static double decodeSec(double sec) {
        return get("decode", 0.2) * sec + 4;
    }

    // ---- reading the log ----

    static void line(String s) {
        if (!active) return;
        long now = System.currentTimeMillis();
        Matcher m = P_TOK.matcher(s);
        if (m.find()) {
            int p = "Score".equals(m.group(1)) ? SCORE : MELODY;
            if (p != phase) enter(p, now);
            tokCur = Integer.parseInt(m.group(2));
            tokMax = Integer.parseInt(m.group(3));
            if (tok0At == 0 || tokCur < tok0) {
                tok0 = tokCur;
                tok0At = now;
            } else if (tokCur > tok0 && now > tok0At) {
                liveTokMs = (now - tok0At) / (double) (tokCur - tok0);
                speed = tokMs() / liveTokMs;
            }
            return;
        }
        m = P_TOK_DONE.matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(2));
            if ("Score".equals(m.group(1))) learn("score", n);
            if (liveTokMs > 0) learn("tok", liveTokMs);
            if ("Semantic".equals(m.group(1))) songSec = n / 25.0; // 25 melody tokens per second of song
            return;
        }
        m = P_SONG.matcher(s);
        if (m.find()) {
            enter(RENDER, now);
            songSec = Double.parseDouble(m.group(1));
            chunks = Integer.parseInt(m.group(2));
            chunksDone = 0;
            return;
        }
        m = P_STEP.matcher(s);
        if (m.find()) {
            if (phase != RENDER) enter(RENDER, now);
            int st = Integer.parseInt(m.group(1));
            if (st < step) chunksDone++;
            step = st;
            steps = Integer.parseInt(m.group(2));
            stepMs = Integer.parseInt(m.group(3));
            if (stepMs > 0) speed = stepSec(songSec / Math.max(1, chunks)) / (stepMs / 1000.0);
            return;
        }
        if (s.contains("[VAE]") && phase != DECODE) {
            if (phase == RENDER && stepMs > 0 && songSec > 0) {
                // remember the step time as if the song were 20 s long, so any length can be predicted
                learn("step", stepMs / 1000.0 / Math.pow(Math.max(5, songSec / Math.max(1, chunks)) / 20.0, 1.2));
            }
            enter(DECODE, now);
        } else if (s.contains("[Pipeline] Done") && phase == DECODE && songSec > 0) {
            learn("decode", Math.max(0, (now - phaseStart) / 1000.0 - 4) / songSec);
        }
    }

    private static void enter(int p, long now) {
        if (phase == LOAD && p > LOAD) learn("load", (now - phaseStart) / 1000.0);
        phase = p;
        phaseStart = now;
        tok0At = 0;
    }

    // ---- the prediction ----

    /** Seconds left, or -1 while there is nothing sensible to say yet. */
    static long secondsLeft() {
        if (!active) return -1;
        long now = System.currentTimeMillis();
        double f = Math.max(0.2, Math.min(5, speed)); // how this song is running compared with the remembered speed
        double sec = songSec > 0 ? songSec : requestSec; // the requested length is only an upper limit
        double left = 0;
        int p = phase;
        if (p == LOAD) left += Math.max(3, loadSec() - (now - phaseStart) / 1000.0);
        if (p <= SCORE) {
            double done = p == SCORE ? tokCur : 0;
            double total = Math.max(scoreTokens(), done + 50);
            left += (total - done) * (liveTokMs > 0 && p == SCORE ? liveTokMs : tokMs() / f) / 1000.0;
        }
        if (p <= MELODY) {
            double done = p == MELODY ? tokCur : 0;
            double total = p == MELODY && tokMax > 0 ? tokMax : sec * 25;
            left += Math.max(0, total - done) * (liveTokMs > 0 && p == MELODY ? liveTokMs : tokMs() / f) / 1000.0;
        }
        if (p <= RENDER) {
            int ch = Math.max(1, chunks);
            double perStep = p == RENDER && stepMs > 0 ? stepMs / 1000.0 : stepSec(sec / ch) / f;
            int doneSteps = p == RENDER ? chunksDone * steps + step : 0;
            left += Math.max(0, ch * steps - doneSteps) * perStep;
        }
        if (p <= DECODE) {
            double d = decodeSec(sec) / f;
            if (p == DECODE) d = Math.max(2, d - (now - phaseStart) / 1000.0);
            left += d;
        }
        return Math.round(left);
    }

    /** "about 12 min left", or "" when no estimate is possible yet. */
    static String text() {
        long s = secondsLeft();
        if (s < 0) return "";
        if (System.currentTimeMillis() - start < 8000) return ""; // give the first readings a moment
        if (s < 60) return "under a minute left";
        long min = (s + 30) / 60;
        if (min < 60) return "about " + min + " min left";
        return "about " + (min / 60) + " h " + String.format(java.util.Locale.US, "%02d", min % 60) + " min left";
    }
}
