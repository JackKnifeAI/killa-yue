package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * The Killa YuE medallion as a dial. The logo stays upright so its script is always
 * readable; a ring of gold ticks and an amber jewel travel around it to show the value.
 * Drag around the dial to turn it.
 */
public class DialView extends View {
    public interface Change {
        void value(int value, boolean finished);
    }

    private static final float START = 135f, SWEEP = 270f; // degrees, clockwise from three o'clock
    private static final int TICKS = 54;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Change change;
    private int max = 100, value;

    public DialView(Context c, int max, int value, Change change) {
        super(c);
        this.max = Math.max(1, max);
        this.value = value;
        this.change = change;
        setContentDescription("Song length dial");
    }

    public void setValue(int v) {
        v = Math.max(0, Math.min(max, v));
        if (v == value) return;
        value = v;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        float dp = getResources().getDisplayMetrics().density;
        int s = (int) (132 * dp);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        float face = r * 0.78f;       // the medallion
        float t0 = r * 0.84f, t1 = r * 0.95f; // tick ring
        float frac = value / (float) max;

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i <= TICKS; i++) {
            float f = i / (float) TICKS;
            double a = Math.toRadians(START + SWEEP * f);
            boolean lit = f <= frac + 0.0001f;
            boolean major = i % 9 == 0;
            p.setStrokeWidth(r * (major ? 0.028f : 0.016f));
            p.setColor(lit ? (major ? 0xFFFFE2A6 : 0xFFE7BD61) : 0xFF4A4233);
            float in = major ? t0 - r * 0.02f : t0;
            c.drawLine(cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in,
                    cx + (float) Math.cos(a) * t1, cy + (float) Math.sin(a) * t1, p);
        }
        p.setStyle(Paint.Style.FILL);

        Bitmap dial = Skin.dial();
        if (dial != null) c.drawBitmap(dial, null, new RectF(cx - face, cy - face, cx + face, cy + face), bmp);

        // the amber jewel that marks the value
        double a = Math.toRadians(START + SWEEP * frac);
        float jx = cx + (float) Math.cos(a) * (t0 + t1) / 2f, jy = cy + (float) Math.sin(a) * (t0 + t1) / 2f;
        float glow = r * 0.16f;
        p.setShader(new RadialGradient(jx, jy, glow, 0xCCFFB347, 0x00FFB347, Shader.TileMode.CLAMP));
        c.drawCircle(jx, jy, glow, p);
        p.setShader(new RadialGradient(jx - r * 0.012f, jy - r * 0.012f, r * 0.06f, 0xFFFFF3D0, 0xFFE08A1E,
                Shader.TileMode.CLAMP));
        c.drawCircle(jx, jy, r * 0.05f, p);
        p.setShader(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            double deg = Math.toDegrees(Math.atan2(e.getY() - getHeight() / 2f, e.getX() - getWidth() / 2f));
            double rel = ((deg - START) % 360 + 360) % 360;
            // the gap at the bottom of the dial snaps to the nearer end
            float f = rel <= SWEEP ? (float) (rel / SWEEP) : (rel - SWEEP < (360 - SWEEP) / 2 ? 1f : 0f);
            int v = Math.round(f * max);
            boolean done = action == MotionEvent.ACTION_UP;
            if (v != value || done) {
                value = v;
                invalidate();
                change.value(value, done);
            }
            if (done) performClick();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) return true;
        return false;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
