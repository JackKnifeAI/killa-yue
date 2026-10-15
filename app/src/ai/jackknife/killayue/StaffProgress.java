package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The progress bar: a gold-framed music staff with cast-gold notes drifting along it.
 * The amber playhead always travels left to right: notes behind it are lit and trail
 * sparks, notes ahead of it are ghosts.
 */
public class StaffProgress extends View {
    // a little rising and falling phrase, in staff steps above the bottom line
    private static final int[] MELODY = {2, 4, 6, 5, 7, 5, 3, 4, 1, 3, 5};
    private static final long LAP_MS = 11000;

    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean indeterminate = true;
    private int max = 100, progress;
    private float shown; // eased fraction actually drawn

    private int heightDp = 72;

    public StaffProgress(Context c) {
        super(c);
    }

    /** A smaller bar (the whole picture scales with the height), for dialogs: 36 is the minified one. */
    public StaffProgress(Context c, int heightDp) {
        super(c);
        this.heightDp = heightDp;
    }

    public void setIndeterminate(boolean on) {
        indeterminate = on;
    }

    public void setMax(int m) {
        max = Math.max(1, m);
    }

    public void setProgress(int v) {
        progress = v;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        float dp = getResources().getDisplayMetrics().density;
        setMeasuredDimension(width, (int) (heightDp * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        Bitmap staff = Skin.staff();
        if (staff == null || getWidth() == 0) return;
        // the staff image's own measurements, which differ from skin to skin
        final float[] g = Skin.STAFF;
        final float SRC_W = g[0], SRC_H = g[1], CAP_L = g[2], CAP_R = g[3];
        final float CH_L = g[4], CH_R = g[5], CH_T = g[6], CH_B = g[7], NOTES_FROM = g[8], NOTES_TO = g[9];
        final float[] LINES = {g[10], g[11], g[12], g[13], g[14]};
        float k = getHeight() / SRC_H;
        float w = getWidth();
        long now = SystemClock.uptimeMillis();
        int capL = (int) (CAP_L * k), capR = (int) (CAP_R * k);

        // frame and staff, stretched only through its plain middle
        c.drawBitmap(staff, new Rect(0, 0, (int) CAP_L, (int) SRC_H), new Rect(0, 0, capL, getHeight()), bmp);
        c.drawBitmap(staff, new Rect((int) CAP_L, 0, (int) (SRC_W - CAP_R), (int) SRC_H),
                new Rect(capL, 0, (int) (w - capR), getHeight()), bmp);
        c.drawBitmap(staff, new Rect((int) (SRC_W - CAP_R), 0, (int) SRC_W, (int) SRC_H),
                new Rect((int) (w - capR), 0, (int) w, getHeight()), bmp);

        float chL = CH_L * k, chR = w - (SRC_W - CH_R) * k, chT = CH_T * k, chB = CH_B * k;
        RectF channel = new RectF(chL, chT, chR, chB);
        c.save();
        c.clipRect(channel);

        float from = NOTES_FROM * k, to = w - (SRC_W - NOTES_TO) * k;
        float target = indeterminate ? 0 : Math.min(1f, progress / (float) max);
        shown += (target - shown) * 0.08f;
        float head;
        float fade = 1f; // dims everything just before the sweep starts over
        if (indeterminate) {
            double sweep = (now % 6000) / 6000.0;
            head = from + (to - from) * (float) sweep;
            if (sweep > 0.88) fade = (float) ((1.0 - sweep) / 0.12);
        } else {
            head = from + (to - from) * shown;
        }

        // amber light pooled behind the playhead
        {
            int pool = ((int) (0x66 * fade)) << 24;
            p.setShader(new LinearGradient(chL, 0, head, 0, Skin.glow(0x00), pool | Skin.GLOW, Shader.TileMode.CLAMP));
            c.drawRect(chL, chT, head, chB, p);
        }
        float glowR = 46 * k;
        p.setShader(new RadialGradient(head, (chT + chB) / 2, glowR, Skin.glow(0x88), Skin.glow(0x00), Shader.TileMode.CLAMP));
        c.drawCircle(head, (chT + chB) / 2, glowR, p);
        p.setShader(new LinearGradient(0, chT, 0, chB, new int[] {Skin.hi(0x00), Skin.HI, Skin.hi(0x00)},
                null, Shader.TileMode.CLAMP));
        p.setStrokeWidth(Math.max(1.5f, 2f * k));
        c.drawLine(head, chT + 4 * k, head, chB - 4 * k, p);
        p.setShader(null);

        float half = (LINES[1] - LINES[0]) * k / 2f; // one staff step
        float bottom = LINES[4] * k;
        float noteH = 50 * k;
        int count = MELODY.length;
        for (int i = 0; i < count; i++) {
            double u = ((now % LAP_MS) / (double) LAP_MS + i / (double) count) % 1.0;
            float x = from + (to - from) * (float) u;
            float bob = (float) Math.sin(now / 420.0 + i * 1.7) * 1.6f * k;
            float y = bottom - MELODY[i] * half + bob;
            boolean lit = x <= head;
            float edge = (float) Math.min(1.0, Math.min(u, 1 - u) / 0.07);
            int alpha = (int) ((lit ? 70 + 185 * fade : 70) * edge);
            Bitmap note = Skin.note(i);
            if (note == null) continue;
            float noteW = noteH * note.getWidth() / note.getHeight();

            if (lit && fade > 0.3f) {
                float gr = 20 * k;
                p.setShader(new RadialGradient(x, y, gr, (((int) (150 * edge)) << 24) | Skin.GLOW, Skin.glow(0x00),
                        Shader.TileMode.CLAMP));
                c.drawCircle(x, y, gr, p);
                p.setShader(null);
                // sparks trailing behind a lit note
                for (int s = 1; s <= 4; s++) {
                    double tw = Math.sin(now / 130.0 + i * 2.3 + s * 1.9);
                    float sx = x - s * 11 * k - (float) Math.sin(now / 500.0 + s + i) * 3 * k;
                    float sy = y + (float) Math.cos(now / 370.0 + s * 2.1 + i) * 7 * k;
                    int a = (int) (edge * (110 + 100 * tw) / s);
                    if (a <= 0) continue;
                    p.setColor((Math.min(255, a) << 24) | (Skin.HI & 0xFFFFFF));
                    c.drawCircle(sx, sy, (1.9f - s * 0.3f) * k + 0.6f, p);
                }
            }

            // the note sways a little as it travels; its head sits on the staff position
            c.save();
            c.rotate((float) Math.sin(now / 600.0 + i) * 5f, x, y);
            bmp.setAlpha(alpha);
            float left = x - noteW * 0.28f, top = y - noteH * 0.86f;
            c.drawBitmap(note, null, new RectF(left, top, left + noteW, top + noteH), bmp);
            bmp.setAlpha(255);
            c.restore();
        }
        c.restore();

        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
