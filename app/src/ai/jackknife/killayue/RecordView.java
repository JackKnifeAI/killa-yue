package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/**
 * A small record that turns while music plays and slows to a stop when it ends: black vinyl with its grooves, a
 * gold label carrying a mark so the turning shows, and the light lying still across it as on a real record.
 */
final class RecordView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private boolean spinning;
    private float angle, speed;
    private long last;

    RecordView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
    }

    void setSpinning(boolean on) {
        if (on == spinning)
            return;
        spinning = on;
        last = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        long dt = last == 0 ? 16 : Math.min(100, now - last);
        last = now;
        // spins up and down over about half a second, a turntable's start and stop
        speed += ((spinning ? 1f : 0f) - speed) * Math.min(1f, dt / 450f);
        if (speed < 0.003f && !spinning)
            speed = 0;
        angle = (angle + speed * 0.2f * dt) % 360f;  // 33 1/3 turns a minute at full speed

        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f * 0.84f;
        p.setStyle(Paint.Style.FILL);
        if (speed > 0) {  // the warm glow of a record playing
            p.setShader(new RadialGradient(cx, cy, r * 1.19f, new int[] {Skin.glow((int) (0x70 * speed)), Skin.glow(0)},
                                           new float[] {0.72f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 1.19f, p);
        }
        p.setShader(new RadialGradient(cx, cy, r, 0xFF1E1E1E, 0xFF050505, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);

        c.save();
        c.rotate(angle, cx, cy);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(0.6f, dp * 0.5f));
        for (int i = 0; i < 9; i++) {
            p.setColor(i % 3 == 0 ? 0x2CFFFFFF : 0x14FFFFFF);
            c.drawCircle(cx, cy, r * (0.46f + i * 0.058f), p);
        }
        p.setColor(Skin.accent(0x99));
        p.setStrokeWidth(dp);
        c.drawCircle(cx, cy, r - dp * 0.5f, p);  // the gold rim
        p.setStyle(Paint.Style.FILL);
        float label = r * 0.36f;
        p.setShader(new RadialGradient(cx - label * 0.3f, cy - label * 0.3f, label * 1.4f,
                                       new int[] {Skin.HI, Skin.ACCENT, Skin.LO}, new float[] {0f, 0.55f, 1f},
                                       Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, label, p);
        p.setShader(null);
        // the label's printing: a fine ring and two curved lines of lettering, so the turning shows
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0x66000000);
        p.setStrokeWidth(Math.max(0.6f, dp * 0.5f));
        c.drawCircle(cx, cy, label * 0.8f, p);
        p.setStrokeWidth(label * 0.09f);
        p.setStrokeCap(Paint.Cap.ROUND);
        RectF arc = new RectF(cx - label * 0.58f, cy - label * 0.58f, cx + label * 0.58f, cy + label * 0.58f);
        c.drawArc(arc, 205, 110, false, p);
        p.setStrokeWidth(label * 0.06f);
        c.drawArc(arc, 25, 110, false, p);
        p.setStyle(Paint.Style.FILL);
        c.restore();

        // the light stays still while the record turns under it
        p.setShader(new SweepGradient(cx, cy, new int[] {0, 0x30FFFFFF, 0, 0, 0x22FFFFFF, 0, 0},
                                      new float[] {0f, 0.09f, 0.2f, 0.5f, 0.6f, 0.7f, 1f}));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        p.setColor(0xFF0A0A0A);
        c.drawCircle(cx, cy, r * 0.05f, p);

        if (spinning || speed > 0)
            postInvalidateOnAnimation();
    }
}
