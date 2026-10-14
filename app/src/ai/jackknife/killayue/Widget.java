package ai.jackknife.killayue;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;

/**
 * The home-screen widget: the song that is playing (cover, title, play and pause) and, while a
 * song is being made, its stage, how long it has run and the time left.
 *
 * The widget only reads a few fields that other parts of the app keep up to date, so it can be
 * refreshed from any thread, including the engine's log reader while the screen is off.
 */
public class Widget extends AppWidgetProvider {
    static final String TOGGLE = "ai.jackknife.killayue.WIDGET_TOGGLE";
    private static final int COVER = 160;

    // what is playing, set by NowPlaying
    private static volatile String title = "";
    private static volatile boolean playing;
    private static volatile Bitmap cover;
    private static volatile String coverOf = "";

    private static String shown = "";
    private static long shownAt;

    /** Called by NowPlaying whenever the song, its cover or the play state changes. art may be null. */
    static void nowPlaying(Context c, String songTitle, boolean isPlaying, Bitmap art) {
        boolean changed = isPlaying != playing || !songTitle.equals(title);
        if (!songTitle.equals(coverOf) || (cover == null && art != null)) {
            cover = art == null ? null : Bitmap.createScaledBitmap(art, COVER, COVER, true);
            coverOf = songTitle;
            changed = true;
        }
        title = songTitle;
        playing = isPlaying;
        // NowPlaying calls this every second or so to turn its record; the widget only cares about changes
        if (changed) refresh(c, true);
    }

    static void stopped(Context c) {
        title = "";
        playing = false;
        cover = null;
        coverOf = "";
        refresh(c, true);
    }

    /**
     * Redraws every placed widget. Without force, a redraw that would show the same text, or that
     * comes within a few seconds of the last one, is skipped: the engine reports progress many
     * times a second and the home screen should not be woken for each.
     */
    static void refresh(Context c, boolean force) {
        if (c == null) return;
        try {
            Context app = c.getApplicationContext();
            AppWidgetManager wm = AppWidgetManager.getInstance(app);
            int[] ids = wm.getAppWidgetIds(new ComponentName(app, Widget.class));
            if (ids == null || ids.length == 0) return;

            boolean busy = MainActivity.jobBusy;
            String stage = EngineService.stage;
            int cur = EngineService.progCur, max = EngineService.progMax;
            String head, line, left = "";
            if (busy) {
                long min = Math.max(0, (System.currentTimeMillis() - MainActivity.jobStart) / 60000);
                String making = MainActivity.pendingTitle;
                head = title.length() > 0 ? title : making.length() > 0 ? "Making " + making : "Making a song";
                line = (stage.length() > 0 ? stage : "Starting") + "  \u00b7  " + (min < 1 ? "just started" : min + " min in");
                left = Eta.text();
            } else if (title.length() > 0) {
                head = title;
                line = playing ? "Playing" : "Paused";
            } else {
                head = "Killa YuE";
                line = "Tap to open";
            }
            String sig = head + "|" + line + "|" + left + "|" + playing + "|" + cur + "/" + max + "|" + coverOf;
            long now = System.currentTimeMillis();
            synchronized (Widget.class) {
                // while a song is made the home screen is short of time too, so it is redrawn less often
                if (!force && (sig.equals(shown) || now - shownAt < (busy ? 10000 : 3000))) return;
                shown = sig;
                shownAt = now;
            }

            Skin.colours(app);
            RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.w_title, head);
            v.setTextColor(R.id.w_title, Skin.ACCENT);
            v.setTextViewText(R.id.w_line, line);
            v.setTextColor(R.id.w_line, Skin.TEXT);
            v.setTextViewText(R.id.w_left, left);
            v.setTextColor(R.id.w_left, Skin.DIM);
            v.setViewVisibility(R.id.w_left, left.length() > 0 ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.w_prog, busy ? View.VISIBLE : View.GONE);
            // the stage's own count when the engine gives one; a moving bar while it does not
            v.setProgressBar(R.id.w_prog, 1000, max > 0 ? (int) (1000L * cur / max) : 0, busy && max <= 0);
            Bitmap art = cover;
            if (art != null) v.setImageViewBitmap(R.id.w_cover, art);
            else v.setImageViewResource(R.id.w_cover, R.mipmap.ic_launcher);
            v.setImageViewResource(R.id.w_toggle, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

            PendingIntent open = PendingIntent.getActivity(app, 0, new Intent(app, MainActivity.class), 0x04000000);
            v.setOnClickPendingIntent(R.id.w_root, open);
            // with a song loaded the button plays and pauses it; with none it opens the app
            MainActivity a = MainActivity.inst;
            if (a != null && title.length() > 0) {
                Intent t = new Intent(app, Widget.class).setAction(TOGGLE);
                v.setOnClickPendingIntent(R.id.w_toggle, PendingIntent.getBroadcast(app, 1, t, 0x04000000));
            } else {
                v.setOnClickPendingIntent(R.id.w_toggle, open);
            }
            wm.updateAppWidget(ids, v);
        } catch (Exception e) {
            // the widget is a courtesy; nothing else depends on it
        }
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager wm, int[] ids) {
        refresh(c, true);
    }

    @Override
    public void onReceive(Context c, Intent in) {
        if (TOGGLE.equals(in.getAction())) {
            final MainActivity m = MainActivity.inst;
            if (m != null) {
                m.ui.post(new Runnable() {
                    public void run() {
                        m.togglePlay();
                    }
                });
            }
            return;
        }
        super.onReceive(c, in);
    }
}
