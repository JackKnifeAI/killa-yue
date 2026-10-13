package ai.jackknife.killayue;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * Tells Android what is playing, so the song shows up where the system shows media:
 * the status bar's now-playing chip, the notification shade, the lock screen, and
 * headset or car buttons. The skin's record is the artwork, and it turns as the song plays.
 *
 * The system draws those places itself; an app supplies the title, the picture, the
 * colour and the buttons. The turning is done by sending the picture again at a new angle.
 */
public class NowPlaying extends Service {
    static final String PREV = "ai.jackknife.killayue.PREV", TOGGLE = "ai.jackknife.killayue.TOGGLE",
            NEXT = "ai.jackknife.killayue.NEXT";
    private static final int NOTE_ID = 3;
    private static final int ART = 256;

    private static MediaSession session;
    private static NowPlaying self;
    private static String shownTitle = "";
    private static boolean shownPlaying;
    private static float angle;
    private static long lastTurn;

    /** Call whenever playback starts, pauses, seeks, changes song or stops. */
    static void update(MainActivity a) {
        boolean has = a.player != null;
        boolean playing = false;
        int pos = 0, dur = 0;
        try {
            if (has) {
                playing = a.player.isPlaying();
                pos = a.player.getCurrentPosition();
                dur = a.player.getDuration();
            }
        } catch (Exception e) {
            has = false;
        }
        if (!has) {
            clear(a);
            return;
        }
        if (session == null) {
            session = new MediaSession(a.getApplicationContext(), "KillaYuE");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    act(TOGGLE, true);
                }

                @Override
                public void onPause() {
                    act(TOGGLE, false);
                }

                @Override
                public void onSkipToNext() {
                    act(NEXT, false);
                }

                @Override
                public void onSkipToPrevious() {
                    act(PREV, false);
                }

                @Override
                public void onSeekTo(long ms) {
                    MainActivity m = MainActivity.inst;
                    if (m != null && m.player != null) {
                        m.player.seekTo((int) ms);
                        update(m);
                    }
                }
            });
        }
        session.setActive(true);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f)
                .build());
        Bitmap art = record(angle);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, a.playTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Killa YuE")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .build());
        shownTitle = a.playTitle;
        shownPlaying = playing;
        Intent i = new Intent(a, NowPlaying.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) Context.class.getMethod("startForegroundService", Intent.class).invoke(a, i);
            else a.startService(i);
        } catch (Exception e) {
            a.startService(i);
        }
        if (self != null) self.post(art);
    }

    /** Runs on the app's tick: turns the record a little while a song is playing. */
    static void turn(MainActivity a) {
        if (session == null || !shownPlaying || a.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastTurn < 1100) return;
        lastTurn = now;
        angle = (angle + 36f) % 360f;
        update(a);
    }

    static void clear(Context c) {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        shownPlaying = false;
        if (self != null) {
            self.stopForeground(true);
            self.stopSelf();
        }
    }

    private static void act(final String what, final boolean wantPlaying) {
        final MainActivity m = MainActivity.inst;
        if (m == null) return;
        m.ui.post(new Runnable() {
            public void run() {
                if (PREV.equals(what)) m.playStep(-1);
                else if (NEXT.equals(what)) m.playStep(1);
                else m.togglePlay();
            }
        });
    }

    /** The skin's record on a square, turned to the given angle. */
    private static Bitmap record(float degrees) {
        Bitmap out = Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Skin.DECK);
        Bitmap disc = Skin.dial();
        if (disc != null) {
            c.rotate(degrees, ART / 2f, ART / 2f);
            float inset = ART * 0.04f;
            c.drawBitmap(disc, null, new RectF(inset, inset, ART - inset, ART - inset), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        }
        return out;
    }

    private PendingIntent button(String action) {
        Intent i = new Intent(this, NowPlaying.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i, 0x04000000);
    }

    private void post(Bitmap art) {
        Notification.Builder b = null;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object chan = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance("player", "Now playing", Integer.valueOf(2));
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, chan);
                b = Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(this, "player");
            }
        } catch (Exception e) {
            b = null;
        }
        if (b == null) b = new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        // a record that turns in the status bar while the song plays, and rests when it is paused
        b.setSmallIcon(shownPlaying ? R.drawable.ic_record_spin : R.drawable.ic_record_0).setContentTitle(shownTitle).setContentText("Killa YuE")
                .setLargeIcon(art).setColor(Skin.ACCENT).setOngoing(shownPlaying).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, 0x04000000))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", button(PREV)).build())
                .addAction(new Notification.Action.Builder(
                        shownPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        shownPlaying ? "Pause" : "Play", button(TOGGLE)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", button(NEXT)).build());
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);
        startForeground(NOTE_ID, b.build());
        if (!shownPlaying) stopForeground(false); // paused: the card stays, and may be swiped away
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        self = this;
        String action = intent == null ? null : intent.getAction();
        if (action != null) act(action, false);
        post(record(angle));
        if (session == null) {
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (self == this) self = null;
        super.onDestroy();
    }
}
