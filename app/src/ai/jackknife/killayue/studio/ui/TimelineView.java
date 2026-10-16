package ai.jackknife.killayue.studio.ui;

import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.Clip;
import ai.jackknife.killayue.studio.Edit;
import ai.jackknife.killayue.studio.Peaks;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.Track;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The timeline: a lane per track with its clips' waveforms (drawn from their peak files at the zoom in use),
 * a ruler in seconds, and the loop region. Pinch to zoom, drag to scroll, tap to move the playhead, touch and
 * hold the ruler then drag to set the loop. Track headers on the left: the name in the track's colour, and
 * mute and solo. Redraws on edits and zooms only; the playhead is drawn by PlayheadView over it.
 */
final class TimelineView extends View {
    final StudioSession session;
    final int header, ruler, lane;
    double fpp = 2000; // frames per pixel
    double left = 0; // the frame at the lanes' left edge
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final HashMap<String, Peaks> peaks = new HashMap<String, Peaks>();
    private final HashSet<String> loading = new HashSet<String>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private final RectF r = new RectF();
    private Object loopGesture;
    private double loopAnchor = -1;
    int editMode; // 0 navigate, 1 move clips, 2 swipe a comp
    private String dragClip;
    private long dragFrame, dragStart, dragPreview;

    TimelineView(Context c, StudioSession s) {
        super(c);
        session = s;
        header = Ui.dp(c, 92);
        ruler = Ui.dp(c, 28);
        lane = Ui.dp(c, 72);
        text.setTypeface(Skin.text());
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                double focus = frameAt(d.getFocusX());
                fpp = Math.max(8, Math.min(200000, fpp / d.getScaleFactor()));
                left = Math.max(0, focus - (d.getFocusX() - header) * fpp);
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (loopAnchor >= 0)
                    return true;
                left = Math.max(0, left + dx * fpp);
                getParent().requestDisallowInterceptTouchEvent(Math.abs(dx) > Math.abs(dy));
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                tap(e.getX(), e.getY());
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (e.getY() >= ruler && e.getX() > header && session.project() != null) {
                    int row = (int) ((e.getY() - ruler) / lane);
                    long at = (long) frameAt(e.getX());
                    if (row >= 0 && row < session.project().tracks.size())
                        for (Clip cl : session.project().tracks.get(row).clips)
                            if (at >= cl.start && at < cl.start + cl.length) {
                                ClipMenu.show(getContext(), session, cl.id, at);
                                return;
                            }
                }
                if (e.getY() < ruler && e.getX() > header) { // hold the ruler, then drag: a loop
                    loopAnchor = Math.max(0, frameAt(e.getX()));
                    loopGesture = new Object();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                }
            }
        });
    }

    double frameAt(float x) {
        return left + (x - header) * fpp;
    }

    float xAt(double frame) {
        return (float) (header + (frame - left) / fpp);
    }

    @Override
    protected void onMeasure(int w, int h) {
        Project p = session.project();
        int tracks = p == null ? 0 : p.tracks.size();
        setMeasuredDimension(MeasureSpec.getSize(w), ruler + Math.max(1, tracks) * lane + Ui.dp(getContext(), 8));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editMode != 0 && session.project() != null && e.getPointerCount() == 1) {
            long at = Math.max(0, (long) frameAt(e.getX()));
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && e.getX() > header && e.getY() > ruler) {
                int row = (int) ((e.getY() - ruler) / lane);
                if (row >= 0 && row < session.project().tracks.size())
                    for (Clip cl : session.project().tracks.get(row).clips) {
                        if (at >= cl.start && at < cl.start + cl.length) {
                            dragClip = cl.id;
                            dragFrame = at;
                            dragStart = cl.start;
                            dragPreview = cl.start;
                            getParent().requestDisallowInterceptTouchEvent(true);
                            break;
                        }
                    }
            }
            if (dragClip != null) {
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    dragPreview = new ai.jackknife.killayue.studio.TempoMap(session.rate(), session.project().performance)
                                      .snap(Math.max(0, dragStart + at - dragFrame), 4);
                    invalidate();
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    try {
                        if (editMode == 1)
                            session.run(ai.jackknife.killayue.studio.ClipEdits.move(session.project(), dragClip, dragPreview));
                        else
                            session.run(ai.jackknife.killayue.studio.ClipEdits.comp(
                                session.project(), dragClip, Math.min(at, dragFrame), Math.max(at, dragFrame)));
                    } catch (Exception ex) {
                        Ui.toast(getContext(), ex.getMessage());
                    }
                    dragClip = null;
                    invalidate();
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    dragClip = null;
                    invalidate();
                    return true;
                }
                return true;
            }
        }
        scale.onTouchEvent(e);
        if (loopAnchor >= 0 && session.project() != null) {
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                double at = Math.max(0, frameAt(e.getX()));
                long s = (long) Math.min(loopAnchor, at), en = (long) Math.max(loopAnchor, at);
                if (en - s >= session.rate() / 20)
                    session.drag(Edit.setLoop(s, en, true), loopGesture);
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                loopAnchor = -1;
                loopGesture = null;
                return true;
            }
        }
        if (!scale.isInProgress())
            gestures.onTouchEvent(e);
        return true;
    }

    private void tap(float x, float y) {
        Project p = session.project();
        if (p == null)
            return;
        if (x < header && y > ruler) { // a track header: mute or solo
            int t = (int) ((y - ruler) / lane);
            if (t < 0 || t >= p.tracks.size())
                return;
            Track tr = p.tracks.get(t);
            float row = (y - ruler) - t * lane;
            if (row > lane * 0.55f) {
                if (x < header / 2)
                    session.run(Edit.mute(tr.id, !tr.mute));
                else
                    session.run(Edit.solo(tr.id, !tr.solo));
            }
            return;
        }
        if (x >= header)
            session.seek(Math.max(0, (long) frameAt(x)));
    }

    /** Fits the whole project in the width. */
    void fit() {
        Project p = session.project();
        long end = 0;
        if (p != null)
            for (Track t : p.tracks)
                for (Clip c : t.clips) end = Math.max(end, c.start + c.length);
        int w = getWidth() - header;
        if (end > 0 && w > 0) {
            fpp = Math.max(8, end * 1.05 / w);
            left = 0;
        }
        invalidate();
    }

    private Peaks peaksFor(final String file) {
        Peaks pk = peaks.get(file);
        if (pk != null || loading.contains(file) || session.dir() == null)
            return pk;
        loading.add(file);
        final File f = new File(session.dir(), file + ".kpk");
        loader.execute(new Runnable() {
            public void run() {
                Peaks got = null;
                try {
                    got = Peaks.read(f);
                } catch (Exception e) {
                    // no peak file: the clip draws as a block
                }
                final Peaks done = got;
                post(new Runnable() {
                    public void run() {
                        if (done != null)
                            peaks.put(file, done);
                        invalidate();
                    }
                });
            }
        });
        return null;
    }

    @Override
    protected void onDraw(Canvas c) {
        Project p = session.project();
        int w = getWidth();
        if (p == null)
            return;
        final int rate = p.rate;
        // the loop region
        if (p.loopEnd > p.loopStart) {
            fill.setColor(p.loopOn ? Skin.accent(46) : Skin.hi(20));
            c.drawRect(Math.max(header, xAt(p.loopStart)), 0, Math.min(w, xAt(p.loopEnd)), getHeight(), fill);
            fill.setColor(p.loopOn ? Skin.ACCENT : Skin.DIM);
            c.drawRect(xAt(p.loopStart) - 2, 0, xAt(p.loopStart) + 2, ruler, fill);
            c.drawRect(xAt(p.loopEnd) - 2, 0, xAt(p.loopEnd) + 2, ruler, fill);
        }
        if (dragClip != null && editMode == 1) {
            fill.setColor(Skin.accent(100));
            c.drawRect(xAt(dragPreview), ruler, xAt(dragPreview) + Ui.dp(getContext(), 4), getHeight(), fill);
        }
        for (java.util.Map.Entry<Long, String> marker : p.performance.markers.entrySet()) {
            float x = xAt(marker.getKey());
            if (x >= header && x < w) {
                line.setColor(Skin.ACCENT);
                c.drawLine(x, 0, x, ruler, line);
                text.setColor(Skin.HI);
                c.drawText(marker.getValue(), x + 3, ruler / 2, text);
            }
        }
        // the ruler: a tick every so many seconds, as many as fit
        text.setColor(Skin.DIM);
        text.setTextSize(Ui.dp(getContext(), 11));
        double secsPerPx = fpp / rate;
        double step = 1;
        for (double s : new double[] {0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300})
            if (s / secsPerPx >= Ui.dp(getContext(), 64)) {
                step = s;
                break;
            }
        line.setColor(Skin.hi(60));
        double first = Math.floor(left / rate / step) * step;
        for (double s = first; xAt(s * rate) < w; s += step) {
            float x = xAt(s * rate);
            if (x < header)
                continue;
            c.drawLine(x, ruler * 0.55f, x, ruler, line);
            int m = (int) (s / 60);
            double sec = s - m * 60;
            c.drawText(step < 1 ? String.format(java.util.Locale.ROOT, "%d:%04.1f", m, sec)
                                : String.format(java.util.Locale.ROOT, "%d:%02d", m, (int) sec),
                x + 4, ruler * 0.5f, text);
        }
        // the lanes
        for (int t = 0; t < p.tracks.size(); t++) {
            Track tr = p.tracks.get(t);
            float top = ruler + t * lane, bottom = top + lane - Ui.dp(getContext(), 4);
            boolean heard = p.audible(tr);
            for (Clip cl : tr.clips) {
                float x0 = Math.max(header, xAt(cl.start)), x1 = Math.min(w, xAt(cl.start + cl.length));
                if (x1 <= x0)
                    continue;
                r.set(x0, top, x1, bottom);
                fill.setColor((tr.color & 0x00ffffff) | (heard ? 0x44000000 : 0x18000000));
                c.drawRoundRect(r, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), fill);
                Peaks pk = peaksFor(cl.file);
                if (pk == null)
                    continue;
                line.setColor((tr.color & 0x00ffffff) | (heard ? 0xee000000 : 0x55000000));
                int level = pk.levelFor(fpp);
                long block = pk.block(level);
                float mid = (top + bottom) / 2, half = (bottom - top) / 2 * 0.92f;
                for (int x = (int) x0; x < (int) x1; x++) {
                    // the clip's frames under this pixel, then the blocks that hold them
                    double f0 = frameAt(x) - cl.start + cl.offset, f1 = f0 + fpp;
                    long b0 = Math.max(0, (long) (f0 / block)), b1 = Math.min(pk.count(level) - 1, (long) (f1 / block));
                    if (b1 < b0)
                        continue;
                    int mn = 32767, mx = -32767;
                    for (long b = b0; b <= b1; b++)
                        for (int ch = 0; ch < pk.channels; ch++) {
                            mn = Math.min(mn, pk.min(level, b, ch));
                            mx = Math.max(mx, pk.max(level, b, ch));
                        }
                    c.drawLine(x, mid - mx / 32767f * half, x, mid - mn / 32767f * half + 1, line);
                }
            }
            // the header: colour, name, M and S
            fill.setColor(Skin.CARD);
            c.drawRect(0, top, header, bottom, fill);
            fill.setColor(tr.color);
            c.drawRect(0, top, Ui.dp(getContext(), 4), bottom, fill);
            text.setColor(heard ? Skin.TEXT : Skin.DIM);
            text.setTextSize(Ui.dp(getContext(), 13));
            String name = tr.name;
            while (name.length() > 1 && text.measureText(name) > header - Ui.dp(getContext(), 14))
                name = name.substring(0, name.length() - 1);
            c.drawText(name, Ui.dp(getContext(), 10), top + lane * 0.36f, text);
            text.setTextSize(Ui.dp(getContext(), 14));
            text.setColor(tr.mute ? Skin.ACCENT : Skin.FAINT);
            c.drawText("M", header * 0.18f, top + lane * 0.8f, text);
            text.setColor(tr.solo ? Skin.ACCENT : Skin.FAINT);
            c.drawText("S", header * 0.62f, top + lane * 0.8f, text);
        }
    }

    void release() {
        loader.shutdownNow();
    }
}
