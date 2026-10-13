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
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.InputStream;

/**
 * The app's look. A skin is a folder of artwork under assets/skins/ plus a palette and a
 * few measurements of its staff image. Everything that draws asks this class for bitmaps
 * and colours, so choosing another skin restyles the whole app.
 */
public class Skin {
    static final String[] IDS = {"gold", "cyber", "foundry"};
    static final String[] NAMES = {"Killa Gold", "Neon District", "Foundry"};

    // palette of the loaded skin
    static int ACCENT, HI, LO, GLOW, TEXT, DIM, FAINT, EDGE, OFF, DECK, CARD, PILL;
    /** Titles and main buttons use the gel script; other skins set them in the text face, in capitals. */
    static boolean SCRIPT_TITLES;
    /**
     * Staff image measurements in its own pixels: width, height, left cap, right cap,
     * channel left, right, top, bottom, where notes enter and leave, then the five line centres.
     */
    static float[] STAFF;

    private static String loaded;
    private static Bitmap action, nav, bg, staff, dial, round;
    private static boolean tileBg;
    private static final Bitmap[] notes = new Bitmap[6];
    private static Typeface script, text;

    static String id(Context c) {
        String want = c.getSharedPreferences("cfg", 0).getString("skin", IDS[0]);
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(want)) return want;
        return IDS[0];
    }

    static int themeFor(String id) {
        if ("cyber".equals(id)) return R.style.App_Cyber;
        if ("foundry".equals(id)) return R.style.App_Foundry;
        return R.style.App;
    }

    private static void palette(String id) {
        if ("cyber".equals(id)) {
            ACCENT = 0xFF19E6FF; HI = 0xFFD6FBFF; LO = 0xFF0B7C99; GLOW = 0xFF2BD6;
            TEXT = 0xFFE8F8FF; DIM = 0xFF8496B4; FAINT = 0xFF4C5E7A; EDGE = 0xFF16425C; OFF = 0xFF141C2B;
            DECK = 0xFF04050A; CARD = 0xF2060912; PILL = 0xFF060912;
            SCRIPT_TITLES = false;
            STAFF = STAFF_CYBER;
        } else if ("foundry".equals(id)) {
            ACCENT = 0xFFFF7A1A; HI = 0xFFFFD2A6; LO = 0xFF9C4A10; GLOW = 0xFF6A1A;
            TEXT = 0xFFE6E3DD; DIM = 0xFF98948C; FAINT = 0xFF5F5C57; EDGE = 0xFF45484B; OFF = 0xFF1E2022;
            DECK = 0xFF090A0A; CARD = 0xF20E0F10; PILL = 0xFF0E0F10;
            SCRIPT_TITLES = false;
            STAFF = STAFF_FOUNDRY;
        } else {
            ACCENT = 0xFFE7BD61; HI = 0xFFFFE2A6; LO = 0xFF8F6A22; GLOW = 0xFFB347;
            TEXT = 0xFFE9E3D5; DIM = 0xFF9D988C; FAINT = 0xFF6E6552; EDGE = 0xFF54482D; OFF = 0xFF1C1912;
            DECK = 0xFF080909; CARD = 0xF20B0C0C; PILL = 0xFF0B0C0C;
            SCRIPT_TITLES = true;
            STAFF = STAFF_GOLD;
        }
    }

    private static final float[] STAFF_GOLD = {1027, 155, 172, 80, 34, 993, 21, 133, 176, 978, 37.4f, 53f, 68.5f, 84f, 100f};
    private static float[] STAFF_CYBER = STAFF_GOLD, STAFF_FOUNDRY = STAFF_GOLD;

    /** The glow colour of the skin at the given opacity (0 to 255). */
    static int glow(int alpha) {
        return (alpha << 24) | GLOW;
    }

    /** The highlight colour of the skin at the given opacity. */
    static int hi(int alpha) {
        return (alpha << 24) | (HI & 0xFFFFFF);
    }

    /** The accent colour of the skin at the given opacity. */
    static int accent(int alpha) {
        return (alpha << 24) | (ACCENT & 0xFFFFFF);
    }

    static synchronized void load(Context c) {
        String id = id(c);
        if (id.equals(loaded)) return;
        try {
            palette(id);
            action = bitmap(c, id, "btnA", 1);
            nav = bitmap(c, id, "btnB", 1);
            staff = bitmap(c, id, "staff", 1);
            dial = bitmap(c, id, "dial", 1);
            round = bitmap(c, id, "round", 1);
            for (int i = 0; i < notes.length; i++) notes[i] = bitmap(c, id, "note" + (i + 1), 1);
            tileBg = "gold".equals(id);
            bg = bitmap(c, id, "bg", tileBg ? 2 : 1);
            if (text == null) {
                script = Typeface.createFromAsset(c.getAssets(), "fonts/KillaGel-BoldScript.ttf");
                text = Typeface.createFromAsset(c.getAssets(), "fonts/KillaMeridian-Text.ttf");
            }
            loaded = id;
        } catch (Exception e) {
            if (IDS[0].equals(id)) throw new IllegalStateException("The app's artwork is missing", e);
            // a damaged or missing skin must not lock the user out: go back to the built-in one
            c.getSharedPreferences("cfg", 0).edit().putString("skin", IDS[0]).apply();
            loaded = null;
            load(c);
        }
    }

    /** The page background: tiled black leather for the gold skin, a darkened wallpaper for the others. */
    static Drawable background(Context c) {
        if (tileBg) {
            BitmapDrawable d = new BitmapDrawable(c.getResources(), bg);
            d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            d.setColorFilter(0xFF444444, android.graphics.PorterDuff.Mode.MULTIPLY);
            return d;
        }
        return new Cover(bg);
    }

    /** Draws a bitmap so it fills the view, cropped at the centre, without distortion. */
    private static class Cover extends Drawable {
        private final Bitmap b;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);

        Cover(Bitmap b) {
            this.b = b;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect r = getBounds();
            float scale = Math.max(r.width() / (float) b.getWidth(), r.height() / (float) b.getHeight());
            int w = (int) (r.width() / scale), h = (int) (r.height() / scale);
            int x = (b.getWidth() - w) / 2, y = (b.getHeight() - h) / 2;
            canvas.drawBitmap(b, new Rect(x, y, x + w, y + h), r, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    private static Bitmap bitmap(Context c, String id, String name, int sample) throws Exception {
        InputStream in = c.getAssets().open("skins/" + id + "/" + name + ".png");
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeStream(in, null, o);
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

    /** The face for titles and main buttons. */
    static Typeface script() {
        return SCRIPT_TITLES ? script : text;
    }

    static Typeface text() {
        return text;
    }

    /** A button plate stretched to any width: the skin's main button, or its quieter sibling. */
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
            int capSrc = Math.round(s.height() * 0.54f);
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
