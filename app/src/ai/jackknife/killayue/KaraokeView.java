package ai.jackknife.killayue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * The large player: a big spinning record and the lyrics, karaoke style. The line being
 * sung sits in the middle and fills with gold from left to right; sung lines drift up and
 * fade, coming lines wait below. Line times are estimates (see Karaoke) until the user
 * times the song by hand: in that mode each tap marks the start of the next line.
 */
public class KaraokeView extends View {
    private final MainActivity a;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private float scroll, spin, energy;
    private long last;

    public KaraokeView(MainActivity a) {
        super(a);
        this.a = a;
        dp = getResources().getDisplayMetrics().density;
        setBackground(Skin.background(a));
        setContentDescription("Lyrics");
    }

    private int position() {
        try {
            return a.player == null ? 0 : a.player.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean playing() {
        try {
            return a.player != null && a.player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    /** Draws one centred line, shrinking the type if it would not fit the width. */
    private float fit(String s, float size, float maxWidth) {
        txt.setTextSize(size);
        float w = txt.measureText(s);
        if (w > maxWidth) {
            txt.setTextSize(Math.max(11 * dp, size * maxWidth / w));
            w = txt.measureText(s);
        }
        return w;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(100, now - last) / 1000f;
        last = now;
        boolean on = playing();
        energy += ((on ? 1f : 0f) - energy) * Math.min(1f, dt * 4f);
        spin = (spin + dt * 200f * energy) % 360f;
        int pos = position();

        // the record, large
        float rr = Math.min(w * 0.2f, h * 0.13f), rx = w / 2f, ry = 22 * dp + rr;
        p.setShader(new RadialGradient(rx, ry, rr * 1.7f, ((int) (0x66 * energy)) << 24 | Skin.GLOW, Skin.glow(0x00), Shader.TileMode.CLAMP));
        c.drawCircle(rx, ry, rr * 1.7f, p);
        p.setShader(null);
        Bitmap disc = Skin.dial();
        if (disc != null) {
            c.save();
            c.rotate(spin, rx, ry);
            c.drawBitmap(disc, null, new RectF(rx - rr, ry - rr, rx + rr, ry + rr), bmp);
            c.restore();
        }
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Skin.script());
        txt.setColor(Skin.ACCENT);
        txt.setShader(null);
        fit(a.playTitle.length() > 0 ? a.playTitle : "Nothing playing", (Skin.SCRIPT_TITLES ? 26 : 20) * dp, w - 48 * dp);
        float titleY = ry + rr + 40 * dp;
        c.drawText(a.playTitle.length() > 0 ? a.playTitle : "Nothing playing", rx, titleY, txt);

        ArrayList<Karaoke.Line> lines = a.karaoke;
        float top = titleY + 22 * dp, bottom = h - 34 * dp;
        float mid = top + (bottom - top) * 0.42f;
        float step = 46 * dp;
        txt.setTypeface(Skin.text());

        if (lines == null || lines.size() == 0) {
            txt.setColor(Skin.DIM);
            txt.setTextSize(15 * dp);
            c.drawText(a.player == null ? "Play a song to see its lyrics here" : "This song has no lyrics", rx, mid, txt);
        } else {
            // which line is being sung, or the last one that was
            int cur = -1;
            for (int i = 0; i < lines.size(); i++) if (pos >= lines.get(i).start) cur = i;
            if (a.timing) cur = a.timingIndex - 1; // while timing, follow the taps, not the estimate
            float target = cur < 0 ? -0.6f : cur;
            // between lines the list keeps gliding, so the next line arrives as its time comes
            if (cur >= 0 && cur + 1 < lines.size() && pos > lines.get(cur).end) {
                float gap = lines.get(cur + 1).start - lines.get(cur).end;
                if (gap > 0) target += 0.45f * Math.min(1f, (pos - lines.get(cur).end) / gap);
            }
            scroll += (target - scroll) * Math.min(1f, dt * 6f);
            if (Math.abs(target - scroll) > 4) scroll = target; // a seek: jump

            c.save();
            c.clipRect(0, top, w, bottom);
            for (int i = 0; i < lines.size(); i++) {
                float y = mid + (i - scroll) * step;
                if (y < top - step || y > bottom + step) continue;
                Karaoke.Line l = lines.get(i);
                float dist = Math.abs(i - scroll);
                boolean active = i == cur;
                float size = (active ? 23 : 17) * dp;
                float tw = fit(l.text, size, w - 40 * dp);
                float fade = Math.max(0.12f, 1f - dist * 0.24f);
                // edges of the list fade out
                fade *= Math.max(0f, Math.min(1f, Math.min((y - top) / (40 * dp), (bottom - y) / (40 * dp))));
                int alpha = (int) (255 * fade);
                txt.setShader(null);
                if (!active) {
                    txt.setColor((alpha << 24) | ((i < cur ? Skin.LO : Skin.TEXT) & 0xFFFFFF));
                    c.drawText(l.text, rx, y, txt);
                    continue;
                }
                float f = Math.max(0f, Math.min(1f, (pos - l.start) / (float) Math.max(1, l.end - l.start)));
                if (a.timing) f = 1f;
                float left = rx - tw / 2f;
                txt.setColor(Skin.FAINT); // not yet sung
                c.drawText(l.text, rx, y, txt);
                c.save();
                c.clipRect(left, y - size * 1.2f, left + tw * f, y + size * 0.5f);
                txt.setShadowLayer(10 * dp, 0, 0, Skin.glow(0xCC));
                txt.setShader(new LinearGradient(0, y - size, 0, y + size * 0.2f, Skin.HI, Skin.ACCENT, Shader.TileMode.CLAMP));
                c.drawText(l.text, rx, y, txt);
                txt.clearShadowLayer();
                txt.setShader(null);
                c.restore();
                // the spark at the point being sung
                if (f > 0 && f < 1) {
                    float sx = left + tw * f;
                    p.setShader(new RadialGradient(sx, y - size * 0.3f, 12 * dp, Skin.hi(0xCC), Skin.hi(0x00), Shader.TileMode.CLAMP));
                    c.drawCircle(sx, y - size * 0.3f, 12 * dp, p);
                    p.setShader(null);
                }
            }
            c.restore();

            // a long wait before the next line: three gold dots count it in
            int nextStart = cur + 1 < lines.size() ? lines.get(cur + 1).start : -1;
            boolean resting = cur < 0 || pos > lines.get(cur).end;
            if (!a.timing && nextStart > 0 && resting && nextStart - pos > 2500) {
                for (int i = 0; i < 3; i++) {
                    double pulse = 0.5 + 0.5 * Math.sin(now / 300.0 - i * 0.9);
                    p.setColor(((int) (70 + 150 * pulse * energy) << 24) | (Skin.ACCENT & 0xFFFFFF));
                    c.drawCircle(rx + (i - 1) * 16 * dp, top + 12 * dp, (3.2f + (float) pulse) * dp, p);
                }
            }
        }

        txt.setTypeface(Skin.text());
        txt.setShader(null);
        txt.setColor(Skin.FAINT);
        txt.setTextSize(11 * dp);
        String note = a.timing ? "Tap anywhere here the moment the next line begins"
                : a.timedByHand ? (a.playMeta != null && a.playMeta.has("_synced") ? "Synced to the singing" : "Timed by hand")
                : "Timing is estimated from the score";
        if (a.timing) txt.setColor(Skin.ACCENT);
        c.drawText(note, rx, h - 12 * dp, txt);

        if (getVisibility() == VISIBLE && (on || energy > 0.01f)) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && a.timing) {
            a.tapLine();
            performClick();
        }
        return a.timing;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
