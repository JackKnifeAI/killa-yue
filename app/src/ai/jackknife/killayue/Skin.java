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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;

/**
 * The app's look. A skin is a folder of artwork under assets/skins/ plus a palette and a
 * few measurements of its staff image. Everything that draws asks this class for bitmaps
 * and colours, so choosing another skin restyles the whole app.
 */
public class Skin {
    static final String[] IDS = {"gold", "cyber", "foundry"};
    static final String[] NAMES = {"Killa Gold", "Neon District", "Foundry"};

    // palette of the loaded skin
    public static int ACCENT, HI, LO, GLOW, TEXT, DIM, FAINT, EDGE, OFF, DECK, CARD, PILL;
    /** Titles and main buttons use the gel script; other skins set them in the text face, in capitals. */
    public static boolean SCRIPT_TITLES;
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

    public static String id(Context c) {
        String want = c.getSharedPreferences("cfg", 0).getString("skin", IDS[0]);
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(want)) return want;
        if (SkinPack.installed(c, want)) return want;
        return IDS[0];
    }

    /** The built-in skins, then the installed community ones. */
    static ArrayList<String> ids(Context c) {
        ArrayList<String> out = new ArrayList<String>(java.util.Arrays.asList(IDS));
        out.addAll(SkinPack.ids(c));
        return out;
    }

    static ArrayList<String> names(Context c) {
        ArrayList<String> out = new ArrayList<String>(java.util.Arrays.asList(NAMES));
        ArrayList<String> more = SkinPack.ids(c);
        for (int i = 0; i < more.size(); i++) out.add(SkinPack.name(c, more.get(i)));
        return out;
    }

    /** The built-in skin with this id, or the first one. */
    static String builtin(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return id;
        return IDS[0];
    }

    static String builtinName(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        return NAMES[0];
    }

    /**
     * The built-in skin a skin is based on: itself, or for a community skin the one it names
     * as its base, whose dialog colours and share page it borrows.
     */
    public static String base(Context c, String id) {
        if (!SkinPack.installed(c, id)) return builtin(id);
        JSONObject j = SkinPack.info(c, id);
        return builtin(j == null ? "" : j.optString("base"));
    }

    public static int themeFor(Context c, String id) {
        return themeFor(base(c, id));
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
    private static final float[] STAFF_CYBER = {1030, 171, 112, 83, 41, 989, 23, 141, 116, 975, 45f, 61f, 77f, 93f, 109f};
    private static final float[] STAFF_FOUNDRY = {1030, 164, 136, 100, 66, 972, 20, 141, 140, 958, 33.5f, 52f, 71f, 89.5f, 108f};

    /** The glow colour of the skin at the given opacity (0 to 255). */
    public static int glow(int alpha) {
        return (alpha << 24) | GLOW;
    }

    /** The highlight colour of the skin at the given opacity. */
    public static int hi(int alpha) {
        return (alpha << 24) | (HI & 0xFFFFFF);
    }

    /** The accent colour of the skin at the given opacity. */
    public static int accent(int alpha) {
        return (alpha << 24) | (ACCENT & 0xFFFFFF);
    }

    /** Just the palette, for places that draw no artwork (the home-screen widget). Community skins included. */
    static synchronized void colours(Context c) {
        if (loaded != null) return;
        String id = id(c);
        try {
            JSONObject pack = SkinPack.installed(c, id) ? SkinPack.info(c, id) : null;
            palette(pack == null ? id : base(c, id));
            if (pack != null) community(pack);
        } catch (Exception e) {
            palette(IDS[0]);
        }
    }

    /** Makes the next load read the skin again, after it was installed anew. */
    static synchronized void forget() {
        loaded = null;
    }

    public static synchronized void load(Context c) {
        String id = id(c);
        if (id.equals(loaded)) return;
        try {
            JSONObject pack = SkinPack.installed(c, id) ? SkinPack.info(c, id) : null;
            if (SkinPack.installed(c, id) && pack == null) throw new Exception("skin.json is unreadable");
            palette(pack == null ? id : base(c, id));
            if (pack != null) community(pack);
            action = bitmap(c, id, "btnA", 1);
            nav = bitmap(c, id, "btnB", 1);
            staff = bitmap(c, id, "staff", 1);
            dial = bitmap(c, id, "dial", 1);
            round = bitmap(c, id, "round", 1);
            for (int i = 0; i < notes.length; i++) notes[i] = bitmap(c, id, "note" + (i + 1), 1);
            // the built-in skins all draw one picture fitted to the screen (Killa Gold's leather used to be
            // a repeated tile, which showed its edges); a community skin may still ask for a tile
            tileBg = pack != null && pack.optBoolean("tiledBackground", false);
            darkPage = pack == null && "gold".equals(id);
            bg = bitmap(c, id, tileBg ? "bg" : "wall", tileBg ? 2 : 1);
            if (pack != null && pack.optJSONArray("staff") == null) {
                // no measurements given: the base skin's, scaled to this staff picture
                float[] m = STAFF.clone();
                float sx = staff.getWidth() / m[0], sy = staff.getHeight() / m[1];
                for (int i = 0; i < m.length; i++) m[i] *= i == 0 || i == 2 || i == 3 || (i >= 4 && i <= 5) || (i >= 8 && i <= 9) ? sx : sy;
                STAFF = m;
            }
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

    /** The skin's background art, for panels that draw it themselves (LuxFrame). */
    private static boolean darkPage; // Killa Gold's leather is darkened on the page, as the tile was

    static Bitmap backgroundBitmap() {
        return bg;
    }

    static boolean tiledBackground() {
        return tileBg;
    }

    /** The page background: tiled black leather for the gold skin, a darkened wallpaper for the others. */
    public static Drawable background(Context c) {
        if (tileBg) {
            BitmapDrawable d = new BitmapDrawable(c.getResources(), bg);
            d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            d.setColorFilter(0xFF444444, android.graphics.PorterDuff.Mode.MULTIPLY);
            return d;
        }
        Cover cv = new Cover(bg);
        if (darkPage) cv.p.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFF444444, android.graphics.PorterDuff.Mode.MULTIPLY));
        return cv;
    }

    /** Draws a bitmap so it fills the view, cropped at the centre, without distortion. */
    private static class Cover extends Drawable {
        private final Bitmap b;
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);

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

    /** A community skin's colours over its base skin's, and its staff measurements if it gives them. */
    private static void community(JSONObject j) throws Exception {
        JSONObject cs = j.optJSONObject("colors");
        ACCENT = SkinPack.colour(cs, "accent", ACCENT);
        HI = SkinPack.colour(cs, "hi", HI);
        LO = SkinPack.colour(cs, "lo", LO);
        GLOW = SkinPack.colour(cs, "glow", 0xFF000000 | GLOW) & 0xFFFFFF; // glow carries no opacity of its own
        TEXT = SkinPack.colour(cs, "text", TEXT);
        DIM = SkinPack.colour(cs, "dim", DIM);
        FAINT = SkinPack.colour(cs, "faint", FAINT);
        EDGE = SkinPack.colour(cs, "edge", EDGE);
        OFF = SkinPack.colour(cs, "off", OFF);
        DECK = SkinPack.colour(cs, "deck", DECK);
        CARD = SkinPack.colour(cs, "card", CARD);
        PILL = SkinPack.colour(cs, "pill", PILL);
        SCRIPT_TITLES = j.optBoolean("scriptTitles", false);
        JSONArray st = j.optJSONArray("staff");
        if (st != null && st.length() == 15) {
            float[] m = new float[15];
            for (int i = 0; i < 15; i++) m[i] = (float) st.getDouble(i);
            STAFF = m;
        }
    }

    private static Bitmap bitmap(Context c, String id, String name, int sample) throws Exception {
        // wallpapers are photographs, so they are stored as JPEG; everything else needs transparency
        String file = name + ("wall".equals(name) ? ".jpg" : ".png");
        InputStream in = SkinPack.installed(c, id) ? new java.io.FileInputStream(new java.io.File(SkinPack.dir(c, id), file))
                : c.getAssets().open("skins/" + id + "/" + file);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) throw new Exception(file + " is not a picture"); // a broken skin falls back to the built-in one
            return b;
        } finally {
            in.close();
        }
    }

    public static Bitmap round() {
        return round;
    }

    public static Bitmap dial() {
        return dial;
    }

    public static Bitmap staff() {
        return staff;
    }

    public static Bitmap note(int i) {
        return notes[i % notes.length];
    }

    /** The face for titles and main buttons. */
    public static Typeface script() {
        return SCRIPT_TITLES ? script : text;
    }

    public static Typeface text() {
        return text;
    }

    /** A button plate stretched to any width: the skin's main button, or its quieter sibling. */
    public static class Plate extends Drawable {
        private final boolean gold, sunk;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private boolean pressed;

        public Plate(boolean gold) {
            this(gold, false);
        }

        /** sunk: the plate held down, for the selected tab of a row: pushed in, darker, shaded under its top edge. */
        public Plate(boolean gold, boolean sunk) {
            this.gold = gold;
            this.sunk = sunk;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (sunk) {
                int in = Math.max(1, b.height() / 22);
                b = new Rect(b.left + in, b.top + in, b.right - in, b.bottom - in);
            }
            Bitmap bmp = gold ? action : nav;
            Rect s = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
            float scale = b.height() / (float) s.height();
            // the rounded, knurled ends keep their shape; only the plain middle stretches
            int capSrc = Math.round(s.height() * 0.54f);
            int capDst = Math.min((int) (capSrc * scale), b.width() / 2);
            p.setColorFilter(sunk ? new LightingColorFilter(0xFF8C8C8C, 0) : pressed ? new LightingColorFilter(0xFFFFFFFF, 0x00302410) : null);
            canvas.drawBitmap(bmp, new Rect(s.left, s.top, s.left + capSrc, s.bottom),
                    new Rect(b.left, b.top, b.left + capDst, b.bottom), p);
            canvas.drawBitmap(bmp, new Rect(s.left + capSrc, s.top, s.right - capSrc, s.bottom),
                    new Rect(b.left + capDst, b.top, b.right - capDst, b.bottom), p);
            canvas.drawBitmap(bmp, new Rect(s.right - capSrc, s.top, s.right, s.bottom),
                    new Rect(b.right - capDst, b.top, b.right, b.bottom), p);
            if (sunk) {  // the shadow of the rim on the pushed-in face
                Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
                sh.setShader(new android.graphics.LinearGradient(0, b.top, 0, b.top + b.height() * 0.45f, 0x99000000, 0x00000000,
                        android.graphics.Shader.TileMode.CLAMP));
                float r = b.height() / 2f;
                canvas.drawRoundRect(new android.graphics.RectF(b.left + r * 0.35f, b.top + b.height() * 0.12f, b.right - r * 0.35f,
                        b.bottom - b.height() * 0.12f), r, r, sh);
            }
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
