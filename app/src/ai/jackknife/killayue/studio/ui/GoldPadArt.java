package ai.jackknife.killayue.studio.ui;

import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.widget.Button;
import java.io.IOException;
import java.io.InputStream;

/** Shared artwork, with independent pressed/pulse state for each Gold pad. */
final class GoldPadArt extends Drawable implements Runnable {
    private static Bitmap rest, lit;
    private static boolean tried;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect square = new Rect();
    private boolean pressed;
    private long pulseUntil;

    static void apply(Button button) {
        Context c = button.getContext();
        if (!"gold".equals(Skin.id(c))) return;
        if (!tried) {
            tried = true;
            try {
                rest = read(c, "skins/gold/pad.png");
                lit = read(c, "skins/gold/pad-lit.png");
            } catch (IOException ex) {
                rest = lit = null;
            }
        }
        if (rest == null || lit == null) return;
        button.setBackground(new GoldPadArt());
        button.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
        button.setTextSize(12);
    }

    private static Bitmap read(Context c, String path) throws IOException {
        try (InputStream in = c.getAssets().open(path)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    static void flash(Button button) {
        Drawable background = button.getBackground();
        if (!(background instanceof GoldPadArt)) return;
        GoldPadArt art = (GoldPadArt) background;
        art.pulseUntil = SystemClock.uptimeMillis() + 120;
        art.unscheduleSelf(art);
        art.scheduleSelf(art, art.pulseUntil);
        art.invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int side = Math.min(bounds.width(), bounds.height());
        int x = bounds.left + (bounds.width() - side) / 2;
        int y = bounds.top + (bounds.height() - side) / 2;
        square.set(x, y, x + side, y + side);
        Bitmap bitmap = pressed || SystemClock.uptimeMillis() < pulseUntil ? lit : rest;
        if (bitmap != null) canvas.drawBitmap(bitmap, null, square, paint);
    }
    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] state) {
        boolean down = false;
        for (int value : state) if (value == android.R.attr.state_pressed) down = true;
        if (down == pressed) return false;
        pressed = down;
        invalidateSelf();
        return true;
    }
    @Override public void run() { invalidateSelf(); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
