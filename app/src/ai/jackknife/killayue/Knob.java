package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * A round button of the skin, like the player's transport knobs: the skin's round plate with a symbol cast in gold
 * on it, glowing while held, and breathing softly while lit (listening, say).
 */
final class Knob extends View {
    static final int PLAY = 0, PAUSE = 1, SEND = 2, SKIP = 3, PLUS = 4;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private int glyph;
    private boolean lit, down;

    Knob(Context c, int glyph) {
        super(c);
        this.glyph = glyph;
        setClickable(true);
    }

    void set(int glyph, boolean lit) {
        this.glyph = glyph;
        this.lit = lit;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean was = down;
        if (e.getAction() == MotionEvent.ACTION_DOWN)
            down = true;
        else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)
            down = false;
        if (was != down)
            invalidate();
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f * 0.74f;
        long now = SystemClock.uptimeMillis();
        if (down || lit) {
            int al = down ? 0xAA : (int) (0x55 + 0x35 * Math.sin(now / 420.0));
            p.setShader(new RadialGradient(cx, cy, r * 1.35f, (al << 24) | Skin.glow(0), Skin.glow(0), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 1.35f, p);
            p.setShader(null);
        }
        Bitmap round = Skin.round();
        if (round != null)
            c.drawBitmap(round, null, new RectF(cx - r, cy - r, cx + r, cy + r), bmp);
        else {
            p.setColor(Skin.PILL);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r / 14f);
            p.setColor(Skin.ACCENT);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
        }
        float s = r * 0.36f, y = cy + (down ? r * 0.04f : 0);
        Path path = new Path();
        switch (glyph) {
            case PLAY:
                path.moveTo(cx - s * 0.55f, y - s);
                path.lineTo(cx + s * 0.95f, y);
                path.lineTo(cx - s * 0.55f, y + s);
                path.close();
                break;
            case PAUSE:
                path.addRoundRect(new RectF(cx - s * 0.72f, y - s, cx - s * 0.2f, y + s), s * 0.12f, s * 0.12f, Path.Direction.CW);
                path.addRoundRect(new RectF(cx + s * 0.2f, y - s, cx + s * 0.72f, y + s), s * 0.12f, s * 0.12f, Path.Direction.CW);
                break;
            case SEND:  // a paper dart pointing up and right
                path.moveTo(cx - s, y + s * 0.15f);
                path.lineTo(cx + s, y - s * 0.75f);
                path.lineTo(cx + s * 0.1f, y + s);
                path.lineTo(cx - s * 0.1f, y + s * 0.3f);
                path.close();
                break;
            case SKIP:
                path.moveTo(cx - s * 0.95f, y - s * 0.8f);
                path.lineTo(cx + s * 0.25f, y);
                path.lineTo(cx - s * 0.95f, y + s * 0.8f);
                path.close();
                path.addRoundRect(new RectF(cx + s * 0.4f, y - s * 0.8f, cx + s * 0.78f, y + s * 0.8f), s * 0.1f, s * 0.1f,
                                  Path.Direction.CW);
                break;
            default:  // PLUS
                path.addRoundRect(new RectF(cx - s * 0.16f, y - s * 0.85f, cx + s * 0.16f, y + s * 0.85f), s * 0.1f, s * 0.1f,
                                  Path.Direction.CW);
                path.addRoundRect(new RectF(cx - s * 0.85f, y - s * 0.16f, cx + s * 0.85f, y + s * 0.16f), s * 0.1f, s * 0.1f,
                                  Path.Direction.CW);
        }
        p.setShader(new LinearGradient(cx, y - s, cx, y + s, new int[] {Skin.HI, Skin.ACCENT, Skin.LO}, null,
                                       Shader.TileMode.CLAMP));
        p.setShadowLayer(r * 0.12f, 0, r * 0.05f, 0xCC000000);
        c.drawPath(path, p);
        p.clearShadowLayer();
        p.setShader(null);
        if (lit)
            postInvalidateOnAnimation();
    }
}
