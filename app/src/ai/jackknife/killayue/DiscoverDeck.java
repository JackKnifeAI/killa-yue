package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Discover's player, cut from the same gold as the main deck: the song's cover on a spinning record, its title
 * gliding when it is long, the amber scrubber, and cast-gold buttons: upvote, downvote, previous, play or pause,
 * next, download and more. Votes show their counts and light up when they are yours.
 */
public class DiscoverDeck extends View {
    static final int UP = 0, DOWN = 1, PREV = 2, PLAY = 3, NEXT = 4, KEEP = 5, MORE = 6;

    interface Listener {
        void press(int button);
        void seek(int ms);
    }

    // what to show; the activity sets these and calls invalidate()
    String title = "", line = "";
    Bitmap cover;
    boolean playing, votable, keepable;
    int up = -1, down = -1, mine, position, duration;

    private final Listener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final float[] bx = new float[7], br = new float[7];
    private final long born = SystemClock.uptimeMillis();
    private float by, spin, energy;
    private long last;
    private int pressed = -1;
    private boolean scrubbing;
    private float scrubTo;

    public DiscoverDeck(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        dp = getResources().getDisplayMetrics().density;
        setContentDescription("Discover player");
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int)(122 * dp));
    }

    private float trackY() { return 58 * dp; }
    private float trackLeft() { return 12 * dp; }
    private float trackRight() { return getWidth() - 12 * dp; }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(100, now - last) / 1000f;
        last = now;
        energy += ((playing ? 1f : 0f) - energy) * Math.min(1f, dt * 4f);
        spin = (spin + dt * 200f * energy) % 360f;

        // the record, close to the left edge, with the song's cover as its label
        float rr = 19 * dp, rx = 10 * dp + rr, ry = 8 * dp + rr;
        p.setShader(new RadialGradient(rx, ry, rr * 1.5f, ((int)(0x55 * energy)) << 24 | Skin.GLOW, Skin.glow(0x00),
                                       Shader.TileMode.CLAMP));
        c.drawCircle(rx, ry, rr * 1.5f, p);
        p.setShader(null);
        c.save();
        c.rotate(spin, rx, ry);
        Bitmap disc = Skin.dial();
        if (disc != null)
            c.drawBitmap(disc, null, new RectF(rx - rr, ry - rr, rx + rr, ry + rr), bmp);
        if (cover != null) {
            float lr = rr * (disc != null ? 0.55f : 1f);
            Path label = new Path();
            label.addCircle(rx, ry, lr, Path.Direction.CW);
            c.save();
            c.clipPath(label);
            c.drawBitmap(cover, null, new RectF(rx - lr, ry - lr, rx + lr, ry + lr), bmp);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp);
            p.setColor(Skin.ACCENT);
            c.drawCircle(rx, ry, lr, p);
            p.setStyle(Paint.Style.FILL);
        }
        c.restore();

        // title (gliding when long) and the artist or status line, right beside the record
        float tx = rx + rr + 10 * dp, tMax = w - tx - 10 * dp;
        txt.setTypeface(Skin.text());
        txt.setTextSize(14.5f * dp);
        txt.setColor(Skin.TEXT);
        boolean moving = drawPanned(c, title, tx, ry - 3 * dp, tMax, now);
        txt.setTypeface(Skin.text());
        txt.setTextSize(11.5f * dp);
        txt.setColor(Skin.DIM);
        moving |= drawPanned(c, line, tx, ry + 13 * dp, tMax, now);

        // scrubber with the times at its ends
        int pos = scrubbing ? (int)(scrubTo * duration) : position;
        float tl = trackLeft(), tr = trackRight(), ty = trackY();
        float frac = duration > 0 ? Math.max(0f, Math.min(1f, pos / (float)duration)) : 0f;
        float hx = tl + (tr - tl) * frac;
        p.setColor(Skin.OFF);
        c.drawRoundRect(new RectF(tl, ty - 2 * dp, tr, ty + 2 * dp), 3 * dp, 3 * dp, p);
        if (hx > tl) {
            p.setShader(new LinearGradient(tl, 0, hx, 0, Skin.LO, Skin.HI, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(tl, ty - 2 * dp, hx, ty + 2 * dp), 3 * dp, 3 * dp, p);
            float gx = tl + (hx - tl) * (float)((now % 2400) / 2400.0);
            p.setShader(new RadialGradient(gx, ty, 14 * dp, ((int)(0x99 * energy)) << 24 | (Skin.HI & 0xFFFFFF),
                                           Skin.hi(0x00), Shader.TileMode.CLAMP));
            c.drawRect(Math.max(tl, gx - 14 * dp), ty - 2 * dp, Math.min(hx, gx + 14 * dp), ty + 2 * dp, p);
        }
        p.setShader(new RadialGradient(hx, ty, 10 * dp, Skin.glow(0xAA), Skin.glow(0x00), Shader.TileMode.CLAMP));
        c.drawCircle(hx, ty, 10 * dp, p);
        p.setShader(new RadialGradient(hx - dp, ty - dp, 6 * dp, Skin.HI, Skin.glow(0xFF), Shader.TileMode.CLAMP));
        c.drawCircle(hx, ty, (scrubbing ? 6.5f : 4.5f) * dp, p);
        p.setShader(null);
        txt.setTypeface(Typeface.MONOSPACE);
        txt.setTextSize(10 * dp);
        txt.setColor(Skin.ACCENT);
        c.drawText(MainActivity.clock(pos / 1000), tl, ty + 15 * dp, txt);
        String end = MainActivity.clock(duration / 1000);
        c.drawText(end, tr - txt.measureText(end), ty + 15 * dp, txt);

        // the buttons: votes on the left, transport in the middle, download and more on the right
        by = 96 * dp;
        float mid = w / 2f, gap = Math.min(50 * dp, (w - 24 * dp) / 7.6f);
        float[] place = {mid - 3.3f * gap, mid - 2.35f * gap, mid - gap, mid, mid + gap, mid + 2.35f * gap,
                         mid + 3.3f * gap};
        Bitmap round = Skin.round();
        for (int i = 0; i < 7; i++) {
            bx[i] = place[i];
            br[i] = (i == PLAY ? 20 : 15) * dp;
            boolean shown = i == PREV || i == PLAY || i == NEXT || i == MORE || (i <= DOWN ? votable : keepable);
            if (!shown) {
                br[i] = 0;
                continue;
            }
            boolean down = pressed == i, lit = (i == UP && mine == 1) || (i == DOWN && mine == -1);
            if (i == PLAY || down || lit) {
                float gl = br[i] * 1.45f;
                int al = down || lit ? 0xAA : (int)(0x30 + 0x45 * energy * (0.6 + 0.4 * Math.sin(now / 320.0)));
                p.setShader(new RadialGradient(bx[i], by, gl, (al << 24) | Skin.GLOW, Skin.glow(0x00),
                                               Shader.TileMode.CLAMP));
                c.drawCircle(bx[i], by, gl, p);
                p.setShader(null);
            }
            if (round != null)
                c.drawBitmap(round, null, new RectF(bx[i] - br[i], by - br[i], bx[i] + br[i], by + br[i]), bmp);
            glyph(c, i, bx[i], by + (down ? dp : 0), br[i] * 0.36f, lit);
            if (i <= DOWN && (i == UP ? up : this.down) >= 0) {
                String n = String.valueOf(i == UP ? up : this.down);
                txt.setTypeface(Skin.text());
                txt.setTextSize(10 * dp);
                txt.setColor(lit ? Skin.HI : Skin.DIM);
                c.drawText(n, bx[i] - txt.measureText(n) / 2, by + br[i] + 11 * dp, txt);
            }
        }

        if (playing || energy > 0.01f || scrubbing || pressed >= 0 || moving)
            postInvalidateOnAnimation();
    }

    /** Draws one line clipped to its space, gliding to and fro when it does not fit. True while it moves. */
    private boolean drawPanned(Canvas c, String s, float x, float y, float room, long now) {
        if (s == null || s.isEmpty())
            return false;
        float over = Math.max(0, txt.measureText(s) - room);
        float shift = PanText.pan(now - born, over, 28 * dp);
        c.save();
        c.clipRect(x, y - txt.getTextSize() * 1.2f, x + room, y + txt.getTextSize() * 0.4f);
        c.drawText(s, x - shift, y, txt);
        c.restore();
        return over > 0;
    }

    private void tri(Path path, float x, float y, float s, boolean right) {
        float d = right ? 1 : -1;
        path.moveTo(x - d * s * 0.5f, y - s * 0.62f);
        path.lineTo(x + d * s * 0.5f, y);
        path.lineTo(x - d * s * 0.5f, y + s * 0.62f);
        path.close();
    }

    /** A symbol in cast gold, as on the main deck; s is half its height. */
    private void glyph(Canvas c, int which, float x, float y, float s, boolean lit) {
        Path path = new Path();
        if (which == PLAY) {
            if (playing) {
                path.addRoundRect(new RectF(x - s * 0.72f, y - s, x - s * 0.2f, y + s), s * 0.12f, s * 0.12f,
                                  Path.Direction.CW);
                path.addRoundRect(new RectF(x + s * 0.2f, y - s, x + s * 0.72f, y + s), s * 0.12f, s * 0.12f,
                                  Path.Direction.CW);
            } else {
                path.moveTo(x - s * 0.55f, y - s);
                path.lineTo(x + s * 0.95f, y);
                path.lineTo(x - s * 0.55f, y + s);
                path.close();
            }
        } else if (which == PREV || which == NEXT) {
            boolean right = which == NEXT;
            float d = right ? 1 : -1;
            tri(path, x - d * s * 0.28f, y, s * 1.35f, right);
            path.addRoundRect(new RectF(x + d * s * 0.5f - s * 0.16f, y - s * 0.84f, x + d * s * 0.5f + s * 0.16f,
                                        y + s * 0.84f),
                              s * 0.08f, s * 0.08f, Path.Direction.CW);
        } else if (which == UP || which == DOWN) {
            float d = which == UP ? -1 : 1;  // a solid arrowhead
            path.moveTo(x, y + d * s * 0.95f);
            path.lineTo(x + s * 0.95f, y - d * s * 0.6f);
            path.lineTo(x - s * 0.95f, y - d * s * 0.6f);
            path.close();
        } else if (which == KEEP) {  // an arrow into a tray
            path.addRect(x - s * 0.18f, y - s, x + s * 0.18f, y + s * 0.1f, Path.Direction.CW);
            path.moveTo(x - s * 0.62f, y - s * 0.1f);
            path.lineTo(x + s * 0.62f, y - s * 0.1f);
            path.lineTo(x, y + s * 0.6f);
            path.close();
            path.addRect(x - s * 0.9f, y + s * 0.8f, x + s * 0.9f, y + s * 1.05f, Path.Direction.CW);
        } else {  // three dots
            for (int i = -1; i <= 1; i++)
                path.addCircle(x + i * s * 0.62f, y, s * 0.2f, Path.Direction.CW);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xAA000000);
        c.save();
        c.translate(0, dp);
        c.drawPath(path, p);
        c.restore();
        int[] gold = lit ? new int[] {0xFFFFFFFF, Skin.HI, Skin.ACCENT} : new int[] {Skin.HI, Skin.ACCENT, Skin.LO};
        p.setShader(new LinearGradient(0, y - s, 0, y + s, gold, null, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
    }

    private int buttonAt(float x, float y) {
        for (int i = 0; i < 7; i++) {
            if (br[i] <= 0)
                continue;
            float dx = x - bx[i], dy = y - by, r = br[i] + 6 * dp;
            if (dx * dx + dy * dy <= r * r)
                return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        float x = e.getX(), y = e.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            if (getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(true);
            pressed = buttonAt(x, y);
            scrubbing = pressed < 0 && Math.abs(y - trackY()) < 16 * dp && duration > 0;
        }
        if (scrubbing) {
            scrubTo = Math.max(0f, Math.min(1f, (x - trackLeft()) / (trackRight() - trackLeft())));
            if (action == MotionEvent.ACTION_UP) {
                listener.seek((int)(scrubTo * duration));
                scrubbing = false;
            }
            if (action == MotionEvent.ACTION_CANCEL)
                scrubbing = false;
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            int hit = buttonAt(x, y);
            if (hit >= 0 && hit == pressed) {
                listener.press(hit);
                performClick();
            }
            pressed = -1;
        }
        if (action == MotionEvent.ACTION_CANCEL)
            pressed = -1;
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
