package ai.jackknife.killayue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;

/**
 * The screen the wake-up song rings on: over the lock screen, the newest song playing at alarm
 * volume and growing louder, with Stop and Snooze. If the library is empty or the song cannot be
 * played, the phone's own alarm sound plays instead: an alarm must always ring.
 */
public class WakeActivity extends Activity {
    private static final long RAMP_MS = 30000, GIVE_UP_MS = 10 * 60000;

    private final Handler ui = new Handler();
    private MediaPlayer player;
    private long started;
    private TextView title, note;
    private ImageView cover;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(Skin.themeFor(Skin.id(this)));
        super.onCreate(b);
        Skin.load(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { // the same, the way Android 8.1 and later want it
            Activity.class.getMethod("setShowWhenLocked", boolean.class).invoke(this, true);
            Activity.class.getMethod("setTurnScreenOn", boolean.class).invoke(this, true);
        } catch (Exception e) {
            // older Android: the window flags above do it
        }
        Wake.rang(this);
        // a song already playing in the app gives way to the alarm
        MainActivity m = MainActivity.inst;
        if (m != null && m.player != null && m.player.isPlaying()) m.togglePlay();

        float d = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding((int) (24 * d), (int) (24 * d), (int) (24 * d), (int) (24 * d));
        col.setBackground(Skin.background(this));
        TextView hello = new TextView(this);
        hello.setText("Good morning");
        hello.setTextColor(Skin.ACCENT);
        hello.setTextSize(Skin.SCRIPT_TITLES ? 40 : 28);
        hello.setTypeface(Skin.script());
        hello.setGravity(Gravity.CENTER);
        col.addView(hello);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImageBitmap(Skin.dial());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (220 * d), (int) (220 * d));
        lp.topMargin = lp.bottomMargin = (int) (24 * d);
        col.addView(cover, lp);
        title = new TextView(this);
        title.setTextColor(Skin.TEXT);
        title.setTextSize(20);
        title.setTypeface(Skin.text(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        col.addView(title);
        note = new TextView(this);
        note.setTextColor(Skin.DIM);
        note.setTextSize(14);
        note.setGravity(Gravity.CENTER);
        col.addView(note);
        Button stop = plate("Stop", true);
        Button snooze = plate("Snooze " + Wake.SNOOZE_MIN + " min", false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, (int) (56 * d));
        bp.topMargin = (int) (32 * d);
        col.addView(stop, bp);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, (int) (48 * d));
        sp.topMargin = (int) (12 * d);
        col.addView(snooze, sp);
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        snooze.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Wake.at(WakeActivity.this, System.currentTimeMillis() + Wake.SNOOZE_MIN * 60000L);
                finish();
            }
        });
        setContentView(col);

        started = System.currentTimeMillis();
        final android.content.Context app = getApplicationContext();
        // the library is on shared storage, which can be slow; the alarm sound waits for it at most a few seconds
        new Thread(new Runnable() {
            public void run() {
                final JSONObject song = newest(app);
                final Bitmap art = song == null ? null : Covers.load(Covers.coverFor(app, song), 2);
                ui.post(new Runnable() {
                    public void run() {
                        if (!isFinishing() && player == null) begin(song, art);
                    }
                });
            }
        }, "wake-song").start();
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!isFinishing() && player == null) begin(null, null);
            }
        }, 5000);
        ui.post(ramp);
    }

    private Button plate(String s, boolean main) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(main ? 18 : 15);
        b.setTextColor(main ? Skin.ACCENT : Skin.TEXT);
        b.setShadowLayer(5, 0, 2, 0xCC000000);
        b.setTypeface(main ? Skin.script() : Skin.text(), Typeface.BOLD);
        b.setBackground(new Skin.Plate(main));
        return b;
    }

    /** The newest song made: library files are named by the time they were made. */
    static JSONObject newest(android.content.Context c) {
        File[] files = Store.library(c).listFiles();
        if (files == null) return null;
        File best = null;
        for (int i = 0; i < files.length; i++) {
            String n = files[i].getName();
            if (n.endsWith(".json") && (best == null || n.compareTo(best.getName()) > 0)) best = files[i];
        }
        if (best == null) return null;
        try {
            JSONObject m = new JSONObject(new String(Store.read(best), "UTF-8"));
            return new File(Store.library(c), m.optString("_audio")).isFile() ? m : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void begin(JSONObject song, Bitmap art) {
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            if (song != null) {
                player.setDataSource(new File(Store.library(this), song.optString("_audio")).getAbsolutePath());
                title.setText(song.optString("_title", "Untitled"));
                long made = song.optLong("_created");
                note.setText(made > started - 12 * 3600000L ? "Made while you slept" : "Your newest song");
                if (art != null) cover.setImageBitmap(art);
            } else {
                throw new Exception("no song");
            }
            player.setLooping(true);
            player.prepare();
        } catch (Exception e) {
            // no song, or it would not play: the phone's own alarm
            try {
                player.reset();
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                player.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
                player.setLooping(true);
                player.prepare();
                title.setText("Wake up");
                note.setText(song == null ? "No songs in the library yet" : "The song could not be played");
            } catch (Exception e2) {
                note.setText("Could not play a sound");
                return;
            }
        }
        player.setVolume(0.15f, 0.15f);
        player.start();
    }

    /** Starts quiet and grows to full alarm volume over half a minute; stops by itself after ten minutes. */
    private final Runnable ramp = new Runnable() {
        public void run() {
            long t = System.currentTimeMillis() - started;
            if (t > GIVE_UP_MS) {
                finish();
                return;
            }
            if (player != null) {
                float v = (float) Math.min(1.0, 0.15 + 0.85 * t / (double) RAMP_MS);
                try {
                    player.setVolume(v, v);
                } catch (Exception e) {
                    // released
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    public void onBackPressed() {
        // back does not silence an alarm by accident; Stop or Snooze do
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
