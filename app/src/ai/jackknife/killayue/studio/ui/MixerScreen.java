package ai.jackknife.killayue.studio.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.HashMap;

import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.Edit;
import ai.jackknife.killayue.studio.FaderScale;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.Track;

/**
 * The Mixer: a strip per track (pan knob, fader beside its meter, mute and solo) and the master. A drag is one
 * undo step; a double tap puts a fader to 0 dB or a knob to the centre.
 */
final class MixerScreen implements Screen, StudioSession.Listener {
    private final StudioActivity a;
    private final HorizontalScrollView view;
    private final LinearLayout strips;
    private final ArrayList<Strip> list = new ArrayList<Strip>();
    private final HashMap<String, float[]> meters = new HashMap<String, float[]>();
    private String layoutKey = "";

    /** Killa Studio's gold knob face, shared by every strip. */
    private static android.graphics.Bitmap knobArt;

    MixerScreen(StudioActivity a) {
        this.a = a;
        view = new HorizontalScrollView(a);
        view.setFillViewport(true);
        view.setBackground(Skin.background(a));
        if (knobArt == null) {
            try (java.io.InputStream in = a.getAssets().open("studio/gold-knob.png")) {
                knobArt = android.graphics.BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                knobArt = null;  // the drawn knob below still works without the art
            }
        }
        strips = Ui.row(a);
        strips.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 8));
        view.addView(strips);
        a.session.listen(this);
    }

    public String name() {
        return "Mixer";
    }

    public View view() {
        return view;
    }

    public void shown() {
        projectChanged();
    }

    public void hidden() {}

    public void projectChanged() {
        Project p = a.session.project();
        StringBuilder key = new StringBuilder();
        if (p != null) for (Track t : p.tracks) key.append(t.id).append(',');
        if (!key.toString().equals(layoutKey)) {
            layoutKey = key.toString();
            strips.removeAllViews();
            list.clear();
            if (p != null) {
                for (Track t : p.tracks) add(new Strip(a, t.id));
                add(new Strip(a, null));
            }
        }
        for (Strip s : list) s.invalidate();
    }

    private void add(Strip s) {
        list.add(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 88), -1);
        lp.rightMargin = Ui.dp(a, 6);
        strips.addView(s, lp);
    }

    public void frame(NativeEngine.Status s) {
        if (s == null) return;
        a.session.meters(meters);
        for (Strip st : list) {
            float[] m = st.id == null ? new float[] {s.peakL, s.peakR} : meters.get(st.id);
            st.meter(m);
        }
    }

    static float dbToPos(float db) {
        return FaderScale.pos(db);
    }

    static float posToDb(float u) {
        return FaderScale.db(u);
    }

    /** One channel strip, drawn whole: name, pan knob, fader and meter, mute and solo. id null: the master. */
    final class Strip extends View {
        final String id;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final GestureDetector taps;
        private float meterL, meterR, holdL, holdR;
        private long lastFrame;
        private Object gesture;
        private int dragging;  // 0 none, 1 fader, 2 knob
        private float downY, downValue;

        Strip(Context c, String id) {
            super(c);
            this.id = id;
            text.setTypeface(Skin.text());
            text.setTextAlign(Paint.Align.CENTER);
            line.setStrokeWidth(Ui.dp(c, 3));
            line.setStrokeCap(Paint.Cap.ROUND);
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    return tapLamps(e.getX(), e.getY());
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    int part = part(e.getY());
                    if (part == 1) set(0f, false);
                    else if (part == 2 && id != null) a.session.run(Edit.pan(id, 0f));
                    return true;
                }
            });
        }

        Track track() {
            Project p = a.session.project();
            return p == null || id == null ? null : p.track(id);
        }

        float db() {
            Project p = a.session.project();
            if (p == null) return 0;
            Track t = track();
            return id == null ? p.masterDb : t == null ? 0 : t.gainDb;
        }

        private float nameH() {
            return Ui.dp(getContext(), 30);
        }

        private float knobH() {
            return id == null ? 0 : Ui.dp(getContext(), 64);
        }

        private float lampsH() {
            return id == null ? Ui.dp(getContext(), 16) : Ui.dp(getContext(), 48);
        }

        private float faderTop() {
            return nameH() + knobH() + Ui.dp(getContext(), 14);
        }

        private float faderBottom() {
            return getHeight() - lampsH() - Ui.dp(getContext(), 14);
        }

        /** 1 fader, 2 knob, 3 lamps, 0 the name. */
        private int part(float y) {
            if (y < nameH()) return 0;
            if (y < nameH() + knobH()) return 2;
            if (y > getHeight() - lampsH()) return 3;
            return 1;
        }

        private boolean tapLamps(float x, float y) {
            Track t = track();
            if (t == null || part(y) != 3) return false;
            if (x < getWidth() / 2f) a.session.run(Edit.mute(t.id, !t.mute));
            else a.session.run(Edit.solo(t.id, !t.solo));
            return true;
        }

        private void set(float db, boolean drag) {
            Edit e = id == null ? Edit.masterGain(db) : Edit.gain(id, db);
            if (drag) a.session.drag(e, gesture);
            else a.session.run(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            taps.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    int part = part(e.getY());
                    dragging = part == 1 ? 1 : part == 2 ? 2 : 0;
                    gesture = new Object();
                    downY = e.getY();
                    Track t = track();
                    downValue = dragging == 1 ? dbToPos(db()) : t == null ? 0 : t.pan;
                    if (dragging != 0) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = downY - e.getY();
                    if (Math.abs(dy) < Ui.dp(getContext(), 3)) return true;
                    if (dragging == 1) {
                        float span = faderBottom() - faderTop();
                        set(Math.round(posToDb(downValue + dy / span) * 10f) / 10f, true);
                    } else if (dragging == 2 && id != null) {
                        float pan = Math.max(-1f, Math.min(1f, downValue + dy / Ui.dp(getContext(), 120)));
                        if (Math.abs(pan) < 0.03f) pan = 0f;  // a little catch at the centre
                        a.session.drag(Edit.pan(id, Math.round(pan * 100f) / 100f), gesture);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = 0;
                    gesture = null;
                    return true;
                default:
                    return true;
            }
        }

        void meter(float[] m) {
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e9f;
            lastFrame = now;
            float l = m == null ? 0 : m[0], rr = m == null ? 0 : m[1];
            // fall at 24 dB a second; the held peak lingers a second, then falls
            float fall = (float) Math.pow(10, -24 * dt / 20);
            meterL = Math.max(l, meterL * fall);
            meterR = Math.max(rr, meterR * fall);
            holdL = Math.max(meterL, holdL * (float) Math.pow(10, -8 * dt / 20));
            holdR = Math.max(meterR, holdR * (float) Math.pow(10, -8 * dt / 20));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            Project p = a.session.project();
            if (p == null) return;
            Track t = track();
            if (id != null && t == null) return;
            float w = getWidth(), h = getHeight();
            boolean heard = t == null || p.audible(t);
            // the strip's plate
            fill.setShader(null);
            fill.setColor((Skin.CARD & 0x00FFFFFF) | 0x8C000000);  // translucent, so the leather shows through
            r.set(0, 0, w, h);
            c.drawRoundRect(r, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(Ui.dp(getContext(), 1));
            fill.setColor((Skin.ACCENT & 0x00FFFFFF) | 0x55000000);
            float half = getResources().getDisplayMetrics().density * 0.5f;
            r.inset(half, half);
            c.drawRoundRect(r, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), fill);
            fill.setStyle(Paint.Style.FILL);
            // name, with the track's colour above it
            fill.setColor(t == null ? Skin.ACCENT : t.color);
            c.drawRect(Ui.dp(getContext(), 10), Ui.dp(getContext(), 4), w - Ui.dp(getContext(), 10), Ui.dp(getContext(), 7), fill);
            text.setTextSize(Ui.dp(getContext(), 12));
            text.setColor(heard ? Skin.TEXT : Skin.DIM);
            String name = t == null ? "Master" : t.name;
            while (name.length() > 1 && text.measureText(name) > w - Ui.dp(getContext(), 8)) name = name.substring(0, name.length() - 1);
            c.drawText(name, w / 2, Ui.dp(getContext(), 24), text);
            // the pan knob
            if (t != null) {
                // Killa Studio's knob: a ring of ticks lit out from the centre, the gold face, a dark pointer
                float cx = w / 2, cy = nameH() + knobH() / 2, rad = knobH() * 0.30f;
                float lineW = line.getStrokeWidth();
                line.setStrokeWidth(Ui.dp(getContext(), 1));
                for (int i = 0; i <= 24; i++) {
                    double tick = Math.toRadians(135 + i * 270 / 24.0);
                    float at = (i - 12) / 12f;  // -1 left .. 1 right
                    boolean lit = t.pan < 0 ? at <= 0 && at >= t.pan : at >= 0 && at <= t.pan;
                    line.setColor(lit ? Skin.ACCENT : 0xff494332);
                    c.drawLine(cx + (float) Math.cos(tick) * (rad + 3), cy + (float) Math.sin(tick) * (rad + 3),
                            cx + (float) Math.cos(tick) * (rad + 6), cy + (float) Math.sin(tick) * (rad + 6), line);
                }
                fill.setShader(new android.graphics.RadialGradient(cx, cy, rad + 2, new int[] {0xff564721, 0xff080808, 0xffb48a39},
                        new float[] {0, .92f, 1}, android.graphics.Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, rad + 2, fill);
                fill.setShader(null);
                fill.setColor(0xFFFFFFFF);
                if (knobArt != null) c.drawBitmap(knobArt, null, new RectF(cx - rad, cy - rad, cx + rad, cy + rad), fill);
                else {
                    fill.setColor(Skin.EDGE);
                    c.drawCircle(cx, cy, rad * 0.8f, fill);
                }
                double ang = Math.toRadians(-90 + t.pan * 135);
                line.setStrokeWidth(getResources().getDisplayMetrics().density * 2.5f);
                line.setColor(0xff33240d);
                c.drawLine(cx + (float) Math.cos(ang) * rad * 0.48f, cy + (float) Math.sin(ang) * rad * 0.48f,
                        cx + (float) Math.cos(ang) * rad * 0.81f, cy + (float) Math.sin(ang) * rad * 0.81f, line);
                line.setStrokeWidth(lineW);
                text.setTextSize(Ui.dp(getContext(), 10));
                text.setColor(Skin.DIM);
                c.drawText(t.pan == 0 ? "C" : (t.pan < 0 ? "L" : "R") + Math.round(Math.abs(t.pan) * 100), cx, cy + rad + Ui.dp(getContext(), 12), text);
            }
            // fader slot and meter
            float top = faderTop(), bottom = faderBottom(), span = bottom - top;
            float slotX = w * 0.36f, meterX = w * 0.66f, mw = Ui.dp(getContext(), 5);
            fill.setColor(Skin.DECK);
            r.set(slotX - Ui.dp(getContext(), 3), top, slotX + Ui.dp(getContext(), 3), bottom);
            c.drawRoundRect(r, 4, 4, fill);
            // 0 dB mark
            float zero = bottom - dbToPos(0) * span;
            line.setStrokeWidth(Ui.dp(getContext(), 1));
            line.setColor(Skin.hi(120));
            c.drawLine(slotX - Ui.dp(getContext(), 12), zero, slotX - Ui.dp(getContext(), 6), zero, line);
            line.setStrokeWidth(Ui.dp(getContext(), 3));
            // meters: green to amber to red, in dBFS on the fader's scale
            for (int ch = 0; ch < 2; ch++) {
                float x = meterX + ch * (mw + Ui.dp(getContext(), 2));
                fill.setShader(null);
                fill.setColor(Skin.DECK);
                c.drawRect(x, top, x + mw, bottom, fill);
                float lv = ch == 0 ? meterL : meterR, hold = ch == 0 ? holdL : holdR;
                float db = lv <= 1e-6f ? -60f : (float) (20 * Math.log10(lv));
                float y = bottom - dbToPos(db) * span;
                fill.setShader(new LinearGradient(0, bottom, 0, top, new int[] {0xff3fae5a, 0xffe0b04a, 0xffe05050},
                        new float[] {0f, dbToPos(-6), 1f}, Shader.TileMode.CLAMP));
                if (lv > 1e-6f) c.drawRect(x, y, x + mw, bottom, fill);
                fill.setShader(null);
                float hdb = hold <= 1e-6f ? -60f : (float) (20 * Math.log10(hold));
                fill.setColor(hold >= 1f ? 0xffe05050 : Skin.HI);
                if (hold > 1e-6f) c.drawRect(x, bottom - dbToPos(hdb) * span - 2, x + mw, bottom - dbToPos(hdb) * span, fill);
            }
            // the fader cap
            float db = db(), y = bottom - dbToPos(db) * span;
            r.set(slotX - Ui.dp(getContext(), 14), y - Ui.dp(getContext(), 9), slotX + Ui.dp(getContext(), 14), y + Ui.dp(getContext(), 9));
            fill.setColor(Skin.ACCENT);
            c.drawRoundRect(r, Ui.dp(getContext(), 4), Ui.dp(getContext(), 4), fill);
            fill.setColor(Skin.DECK);
            c.drawRect(r.left + Ui.dp(getContext(), 3), y - 1, r.right - Ui.dp(getContext(), 3), y + 1, fill);
            text.setTextSize(Ui.dp(getContext(), 11));
            text.setColor(Skin.TEXT);
            c.drawText(db <= -59.9f ? "-∞" : String.format(java.util.Locale.ROOT, "%+.1f", db), w / 2, bottom + Ui.dp(getContext(), 12), text);
            // mute and solo lamps
            if (t != null) {
                float ly = h - lampsH() / 2 + Ui.dp(getContext(), 4);
                for (int k = 0; k < 2; k++) {
                    boolean on = k == 0 ? t.mute : t.solo;
                    float lx = k == 0 ? w * 0.28f : w * 0.72f;
                    fill.setColor(on ? (k == 0 ? 0xffe0607e : Skin.ACCENT) : Skin.DECK);
                    c.drawCircle(lx, ly, Ui.dp(getContext(), 14), fill);
                    text.setTextSize(Ui.dp(getContext(), 13));
                    text.setColor(on ? 0xff101010 : Skin.DIM);
                    c.drawText(k == 0 ? "M" : "S", lx, ly + Ui.dp(getContext(), 5), text);
                }
            }
        }
    }
}
