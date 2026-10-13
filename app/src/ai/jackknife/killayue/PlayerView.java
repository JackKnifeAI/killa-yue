package ai.jackknife.killayue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * The player deck at the bottom of the app: a record that spins while a song plays,
 * gold level bars, an amber scrubber, and five transport buttons
 * (previous, back ten seconds, play or pause, forward ten seconds, next).
 */
public class PlayerView extends View {
    private static final int PREV = 0, BACK = 1, PLAY = 2, FWD = 3, NEXT = 4;
    private static final int BARS = 16;

    private final MainActivity a;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final float[] bx = new float[5], br = new float[5];
    private float by;
    private float spin, energy;
    private long last;
    private int pressed = -1;
    private boolean scrubbing;
    private float scrubTo;

    public PlayerView(MainActivity a) {
        super(a);
        this.a = a;
        dp = getResources().getDisplayMetrics().density;
        setContentDescription("Music player");
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (132 * dp));
    }

    private boolean playing() {
        try {
            return a.player != null && a.player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private int duration() {
        try {
            return a.player == null ? 0 : a.player.getDuration();
        } catch (Exception e) {
            return 0;
        }
    }

    private int position() {
        try {
            return a.player == null ? 0 : a.player.getCurrentPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Vertical centre of the scrubber. */
    private float trackY() {
        return 63 * dp;
    }

    private float trackLeft() {
        return 20 * dp;
    }

    private float trackRight() {
        return getWidth() - 20 * dp;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(100, now - last) / 1000f;
        last = now;
        boolean on = playing();
        energy += ((on ? 1f : 0f) - energy) * Math.min(1f, dt * 4f);
        spin = (spin + dt * 200f * energy) % 360f; // a 33 rpm record turns 200 degrees a second

        // deck: near-black with a gold hairline along the top
        c.drawColor(0xFF080909);
        p.setShader(new LinearGradient(0, 0, w, 0, new int[] {0x00E7BD61, 0xFFE7BD61, 0x00E7BD61}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, Math.max(1f, dp), p);
        p.setShader(null);

        // the record
        float rr = 20 * dp, rx = 20 * dp + rr, ry = 10 * dp + rr;
        p.setShader(new RadialGradient(rx, ry, rr * 1.5f, ((int) (0x55 * energy)) << 24 | 0xFFB347, 0x00FFB347, Shader.TileMode.CLAMP));
        c.drawCircle(rx, ry, rr * 1.5f, p);
        p.setShader(null);
        Bitmap disc = Skin.dial();
        if (disc != null) {
            c.save();
            c.rotate(spin, rx, ry);
            c.drawBitmap(disc, null, new RectF(rx - rr, ry - rr, rx + rr, ry + rr), bmp);
            c.restore();
        }

        // level bars on the right; they follow a made-up pulse, not the audio itself
        float barsW = 62 * dp, barsL = w - 20 * dp - barsW, barsB = ry + 15 * dp, barsH = 30 * dp;
        float bw = barsW / BARS;
        for (int i = 0; i < BARS; i++) {
            double t = now / 1000.0;
            double v = 0.5 + 0.5 * Math.sin(t * (5.1 + i * 0.83) + i * 1.7);
            v *= 0.55 + 0.45 * Math.sin(t * 1.9 + i * 0.6);
            float h = 2.5f * dp + (float) Math.abs(v) * barsH * energy;
            float x = barsL + i * bw;
            p.setShader(new LinearGradient(0, barsB - h, 0, barsB, 0xFFFFE2A6, 0xFF8F6A22, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(x + bw * 0.18f, barsB - h, x + bw * 0.82f, barsB), bw * 0.3f, bw * 0.3f, p);
        }
        p.setShader(null);

        // title and time
        float tx = rx + rr + 12 * dp, tMax = barsL - tx - 10 * dp;
        txt.setTypeface(Skin.text());
        txt.setTextSize(14 * dp);
        txt.setColor(a.playTitle.length() > 0 ? Skin.CREAM : 0xFF9D988C);
        String title = a.playTitle.length() > 0 ? a.playTitle : "Nothing playing";
        c.drawText(TextUtils.ellipsize(title, new android.text.TextPaint(txt), tMax, TextUtils.TruncateAt.END).toString(),
                tx, ry - 2 * dp, txt);
        int dur = duration();
        int pos = scrubbing ? (int) (scrubTo * dur) : position();
        txt.setTypeface(Typeface.MONOSPACE);
        txt.setTextSize(11 * dp);
        txt.setColor(Skin.GOLD);
        c.drawText(MainActivity.clock(pos / 1000) + "  /  " + MainActivity.clock(dur / 1000), tx, ry + 14 * dp, txt);

        // scrubber
        float tl = trackLeft(), tr = trackRight(), ty = trackY();
        float frac = dur > 0 ? Math.max(0f, Math.min(1f, pos / (float) dur)) : 0f;
        float hx = tl + (tr - tl) * frac;
        p.setColor(0xFF1C1912);
        c.drawRoundRect(new RectF(tl, ty - 2.5f * dp, tr, ty + 2.5f * dp), 3 * dp, 3 * dp, p);
        p.setColor(0xFF54482D);
        for (int i = 0; i <= 10; i++) {
            float nx = tl + (tr - tl) * i / 10f;
            c.drawRect(nx - 0.5f * dp, ty + 4 * dp, nx + 0.5f * dp, ty + (i % 5 == 0 ? 8 : 6.5f) * dp, p);
        }
        if (hx > tl) {
            p.setShader(new LinearGradient(tl, 0, hx, 0, 0xFF8F5A12, 0xFFFFD98A, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(tl, ty - 2.5f * dp, hx, ty + 2.5f * dp), 3 * dp, 3 * dp, p);
            // a glint that runs along the played part while the song plays
            float gx = tl + (hx - tl) * (float) ((now % 2400) / 2400.0);
            p.setShader(new RadialGradient(gx, ty, 16 * dp, ((int) (0x99 * energy)) << 24 | 0xFFF3D0, 0x00FFF3D0, Shader.TileMode.CLAMP));
            c.drawRect(Math.max(tl, gx - 16 * dp), ty - 2.5f * dp, Math.min(hx, gx + 16 * dp), ty + 2.5f * dp, p);
        }
        p.setShader(new RadialGradient(hx, ty, 12 * dp, 0xAAFFB347, 0x00FFB347, Shader.TileMode.CLAMP));
        c.drawCircle(hx, ty, 12 * dp, p);
        p.setShader(new RadialGradient(hx - 1.5f * dp, ty - 1.5f * dp, 7 * dp, 0xFFFFF3D0, 0xFFD9861A, Shader.TileMode.CLAMP));
        c.drawCircle(hx, ty, (scrubbing ? 7.5f : 5.5f) * dp, p);
        p.setShader(null);

        // transport buttons
        by = 101 * dp;
        float gap = Math.min(58 * dp, (w - 40 * dp) / 5.2f);
        for (int i = 0; i < 5; i++) {
            bx[i] = w / 2f + (i - 2) * gap;
            br[i] = (i == PLAY ? 23 : 17) * dp;
            if (i == PLAY - 1) bx[i] -= 5 * dp;
            if (i == PLAY + 1) bx[i] += 5 * dp;
            if (i == PREV) bx[i] -= 5 * dp;
            if (i == NEXT) bx[i] += 5 * dp;
            Bitmap round = Skin.round();
            boolean down = pressed == i;
            if (i == PLAY || down) {
                float gl = br[i] * 1.4f;
                int al = down ? 0xAA : (int) (0x30 + 0x45 * energy * (0.6 + 0.4 * Math.sin(now / 320.0)));
                p.setShader(new RadialGradient(bx[i], by, gl, (al << 24) | 0xFFB347, 0x00FFB347, Shader.TileMode.CLAMP));
                c.drawCircle(bx[i], by, gl, p);
                p.setShader(null);
            }
            if (round != null) c.drawBitmap(round, null, new RectF(bx[i] - br[i], by - br[i], bx[i] + br[i], by + br[i]), bmp);
            glyph(c, i, bx[i], by + (down ? dp : 0), br[i] * 0.36f, on);
        }

        if (on || energy > 0.01f || scrubbing || pressed >= 0) postInvalidateOnAnimation();
    }

    private void tri(Path path, float x, float y, float s, boolean right) {
        float d = right ? 1 : -1;
        path.moveTo(x - d * s * 0.5f, y - s * 0.62f);
        path.lineTo(x + d * s * 0.5f, y);
        path.lineTo(x - d * s * 0.5f, y + s * 0.62f);
        path.close();
    }

    /** Draws a transport symbol in cast gold: s is half the symbol's height. */
    private void glyph(Canvas c, int which, float x, float y, float s, boolean on) {
        Path path = new Path();
        if (which == PLAY) {
            if (on) {
                path.addRoundRect(new RectF(x - s * 0.72f, y - s, x - s * 0.2f, y + s), s * 0.12f, s * 0.12f, Path.Direction.CW);
                path.addRoundRect(new RectF(x + s * 0.2f, y - s, x + s * 0.72f, y + s), s * 0.12f, s * 0.12f, Path.Direction.CW);
            } else {
                path.moveTo(x - s * 0.55f, y - s);
                path.lineTo(x + s * 0.95f, y);
                path.lineTo(x - s * 0.55f, y + s);
                path.close();
            }
        } else if (which == BACK || which == FWD) {
            boolean right = which == FWD;
            tri(path, x - s * 0.5f, y, s * 1.35f, right);
            tri(path, x + s * 0.5f, y, s * 1.35f, right);
        } else {
            boolean right = which == NEXT;
            float d = right ? 1 : -1;
            tri(path, x - d * s * 0.28f, y, s * 1.35f, right);
            path.addRoundRect(new RectF(x + d * s * 0.5f - s * 0.16f, y - s * 0.84f, x + d * s * 0.5f + s * 0.16f, y + s * 0.84f),
                    s * 0.08f, s * 0.08f, Path.Direction.CW);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xAA000000);
        c.save();
        c.translate(0, dp);
        c.drawPath(path, p);
        c.restore();
        p.setShader(new LinearGradient(0, y - s, 0, y + s, new int[] {0xFFFFF0C8, 0xFFE7BD61, 0xFFA87724}, null, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
    }

    private int buttonAt(float x, float y) {
        for (int i = 0; i < 5; i++) {
            float dx = x - bx[i], dy = y - by;
            float r = br[i] + 7 * dp; // a little larger than drawn, for fingers
            if (dx * dx + dy * dy <= r * r) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        float x = e.getX(), y = e.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            pressed = buttonAt(x, y);
            scrubbing = pressed < 0 && Math.abs(y - trackY()) < 16 * dp && duration() > 0;
        }
        if (scrubbing) {
            scrubTo = Math.max(0f, Math.min(1f, (x - trackLeft()) / (trackRight() - trackLeft())));
            if (action == MotionEvent.ACTION_UP) {
                a.seekTo(scrubTo);
                scrubbing = false;
            }
            if (action == MotionEvent.ACTION_CANCEL) scrubbing = false;
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            // the record and title open the large player with the lyrics
            if (pressed < 0 && y < trackY() - 16 * dp) {
                a.showKaraoke(a.karaokeView.getVisibility() != VISIBLE);
                performClick();
            }
            int hit = buttonAt(x, y);
            if (hit >= 0 && hit == pressed) {
                if (hit == PLAY) a.togglePlay();
                if (hit == PREV) a.playStep(-1);
                if (hit == NEXT) a.playStep(1);
                if (hit == BACK) a.seekBy(-10000);
                if (hit == FWD) a.seekBy(10000);
                performClick();
            }
            pressed = -1;
        }
        if (action == MotionEvent.ACTION_CANCEL) pressed = -1;
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** Lets the activity's completion listener compile against the right type. */
    static MediaPlayer.OnCompletionListener onEnd(final MainActivity a) {
        return new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) {
                if (!a.playStep(1)) a.playerView.invalidate();
            }
        };
    }
}
