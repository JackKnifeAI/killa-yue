package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.io.InputStream;

/** Killa Studio's button plates and typefaces, with bright colour faces. */
public class Skin {
    static final int GOLD = 0xFFE7BD61, CREAM = 0xFFFFF6DE;

    private static Bitmap action, nav, leather, staff, dial, round;
    private static final Bitmap[] notes = new Bitmap[6];
    private static Typeface script, text;

    static synchronized void load(Context c) {
        if (action != null) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            action = bitmap(c, "btnA");
            nav = bitmap(c, "btnB");
            staff = bitmap(c, "staff");
            dial = bitmap(c, "dial");
            round = bitmap(c, "round");
            for (int i = 0; i < notes.length; i++) notes[i] = bitmap(c, "note" + (i + 1));
            InputStream in;
            in = c.getAssets().open("materials/black-leather.png");
            leather = BitmapFactory.decodeStream(in, null, o);
            in.close();
            script = Typeface.createFromAsset(c.getAssets(), "fonts/KillaGel-BoldScript.ttf");
            text = Typeface.createFromAsset(c.getAssets(), "fonts/KillaMeridian-Text.ttf");
        } catch (Exception e) {
            throw new IllegalStateException("Killa materials missing from the app", e);
        }
    }

    /** Killa Studio's black leather, tiled and darkened the same way the studio does it. */
    static Drawable leather(Context c) {
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(c.getResources(), leather);
        d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        d.setColorFilter(0xFF444444, android.graphics.PorterDuff.Mode.MULTIPLY);
        return d;
    }

    private static Bitmap bitmap(Context c, String name) throws Exception {
        InputStream in = c.getAssets().open("materials/" + name + ".png");
        try {
            return BitmapFactory.decodeStream(in);
        } finally {
            in.close();
        }
    }

    static Bitmap round() {
        return round;
    }

    static Bitmap dial() {
        return dial;
    }

    static Bitmap staff() {
        return staff;
    }

    static Bitmap note(int i) {
        return notes[i % notes.length];
    }

    static Typeface script() {
        return script;
    }

    static Typeface text() {
        return text;
    }

    /** A button plate stretched to any width: the gold capsule, or the brushed black tab. */
    static class Plate extends Drawable {
        private final boolean gold;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private boolean pressed;

        Plate(boolean gold) {
            this.gold = gold;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            Bitmap bmp = gold ? action : nav;
            Rect s = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
            float scale = b.height() / (float) s.height();
            // the rounded, knurled ends keep their shape; only the plain middle stretches
            int capSrc = gold ? 150 : 40;
            int capDst = Math.min((int) (capSrc * scale), b.width() / 2);
            p.setColorFilter(pressed ? new LightingColorFilter(0xFFFFFFFF, 0x00302410) : null);
            canvas.drawBitmap(bmp, new Rect(s.left, s.top, s.left + capSrc, s.bottom),
                    new Rect(b.left, b.top, b.left + capDst, b.bottom), p);
            canvas.drawBitmap(bmp, new Rect(s.left + capSrc, s.top, s.right - capSrc, s.bottom),
                    new Rect(b.left + capDst, b.top, b.right - capDst, b.bottom), p);
            canvas.drawBitmap(bmp, new Rect(s.right - capSrc, s.top, s.right, s.bottom),
                    new Rect(b.right - capDst, b.top, b.right, b.bottom), p);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean now = false;
            for (int i = 0; i < state.length; i++) if (state[i] == android.R.attr.state_pressed) now = true;
            if (now == pressed) return false;
            pressed = now;
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
