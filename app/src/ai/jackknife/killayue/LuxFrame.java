package ai.jackknife.killayue;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The panel behind every dialog and drop-down: the skin's leather (or wallpaper), darkened,
 * inside a bronze outer rim, a polished inner rim in the skin's metal, a fine hairline and
 * a rivet in each corner. Colours and art come from the current skin.
 */
public class LuxFrame extends Drawable {
    private final float dp = Resources.getSystem().getDisplayMetrics().density;
    private final float radius;
    private final int pad;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Shader art, rimShader, shade;
    private Rect shadersFor;

    public LuxFrame() {
        this(22, 12);
    }

    /** radiusDp: corner radius; padDp: room kept clear for the content inside the rims. */
    public LuxFrame(float radiusDp, float padDp) {
        radius = radiusDp * dp;
        pad = Math.round(padDp * dp);
    }

    private void shaders(Rect b) {
        if (b.equals(shadersFor)) return;
        shadersFor = new Rect(b);
        Bitmap bg = Skin.backgroundBitmap();
        art = null;
        if (bg != null) {
            if (Skin.tiledBackground()) {
                BitmapShader s = new BitmapShader(bg, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                Matrix m = new Matrix();
                float scale = 260 * dp / bg.getWidth(); // a fine grain, as on the main screen
                m.setScale(scale, scale);
                s.setLocalMatrix(m);
                art = s;
            } else {
                BitmapShader s = new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                float scale = Math.max(b.width() / (float) bg.getWidth(), b.height() / (float) bg.getHeight());
                m.setScale(scale, scale);
                m.postTranslate(b.left + (b.width() - bg.getWidth() * scale) / 2f, b.top + (b.height() - bg.getHeight() * scale) / 2f);
                s.setLocalMatrix(m);
                art = s;
            }
        }
        // light from the upper left, as on the app's buttons
        rimShader = new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[] {Skin.LO, Skin.HI, Skin.ACCENT, Skin.LO, Skin.HI, Skin.LO},
                new float[] {0f, 0.18f, 0.38f, 0.6f, 0.82f, 1f}, Shader.TileMode.CLAMP);
        shade = new RadialGradient(b.exactCenterX(), b.top + b.height() * 0.3f, Math.max(b.width(), b.height()) * 0.85f,
                new int[] {0x8C000000, 0xB8000000, 0xE6000000}, new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        shaders(b);
        RectF r = new RectF(b);

        // the material, darkened so text stays easy to read
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(Skin.DECK);
        c.drawRoundRect(r, radius, radius, paint);
        if (art != null) {
            paint.setShader(art);
            c.drawRoundRect(r, radius, radius, paint);
        }
        paint.setShader(shade);
        c.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);

        // outer rim: dark bronze
        paint.setStyle(Paint.Style.STROKE);
        float w = 2.6f * dp;
        paint.setStrokeWidth(w);
        paint.setColor(Skin.LO);
        RectF o = inset(r, w / 2);
        c.drawRoundRect(o, radius - w / 2, radius - w / 2, paint);
        // a shadow line inside it, so the polished rim stands proud
        paint.setStrokeWidth(1.2f * dp);
        paint.setColor(0xCC000000);
        RectF s = inset(r, w + 0.6f * dp);
        c.drawRoundRect(s, radius - w, radius - w, paint);
        // polished rim
        paint.setStrokeWidth(1.8f * dp);
        paint.setShader(rimShader);
        RectF p = inset(r, w + 2.2f * dp);
        c.drawRoundRect(p, radius - w - 1.6f * dp, radius - w - 1.6f * dp, paint);
        paint.setShader(null);
        // hairline
        paint.setStrokeWidth(0.8f * dp);
        paint.setColor((Skin.ACCENT & 0x00FFFFFF) | 0x55000000);
        RectF h = inset(r, w + 6.5f * dp);
        c.drawRoundRect(h, radius - w - 5f * dp, radius - w - 5f * dp, paint);

        // rivets
        paint.setStyle(Paint.Style.FILL);
        float in = w + 12f * dp, rr = 3.1f * dp;
        float[][] at = {{r.left + in, r.top + in}, {r.right - in, r.top + in}, {r.left + in, r.bottom - in}, {r.right - in, r.bottom - in}};
        for (int i = 0; i < at.length; i++) {
            float x = at[i][0], y = at[i][1];
            paint.setShader(null);
            paint.setColor(0xAA000000);
            c.drawCircle(x + 0.6f * dp, y + 0.9f * dp, rr + 0.6f * dp, paint);
            paint.setShader(new RadialGradient(x - rr * 0.35f, y - rr * 0.35f, rr * 1.4f,
                    new int[] {0xFFFFFFFF, Skin.HI, Skin.ACCENT, Skin.LO}, new float[] {0f, 0.2f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, rr, paint);
        }
        paint.setShader(null);
    }

    private static RectF inset(RectF r, float by) {
        return new RectF(r.left + by, r.top + by, r.right - by, r.bottom - by);
    }

    @Override
    public boolean getPadding(Rect padding) {
        padding.set(pad, pad, pad, pad);
        return true;
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
    }

    @Override
    public void setAlpha(int alpha) {
        // the frame is always drawn whole
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        // not tinted
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
