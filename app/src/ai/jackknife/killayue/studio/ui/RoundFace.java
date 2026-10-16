package ai.jackknife.killayue.studio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.Button;

import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;

/** The player's round transport button (the skin's round.png), glowing while held, as a background for a Button. */
final class RoundFace extends Drawable {
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private boolean pressed;

    /** A round button the size of the player's, its symbol in the middle. */
    static Button button(Context c, String symbol) {
        Button b = new Button(c);
        b.setText(symbol);
        b.setAllCaps(false);
        b.setTextColor(Skin.HI);
        b.setTextSize(18);
        b.setTypeface(Skin.text(), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(new RoundFace());
        b.setContentDescription(symbol);
        int s = Ui.dp(c, 50);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(s, s);
        lp.rightMargin = Ui.dp(c, 4);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float r = Math.min(b.width(), b.height()) / 2f, cx = b.exactCenterX(), cy = b.exactCenterY();
        if (pressed) {
            p.setShader(new RadialGradient(cx, cy, r, (0xAA << 24) | Skin.GLOW, Skin.glow(0x00), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
        }
        Bitmap round = Skin.round();
        float br = r * 0.92f;
        if (round != null) c.drawBitmap(round, null, new RectF(cx - br, cy - br, cx + br, cy + br), p);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean down = false;
        for (int s : state) if (s == android.R.attr.state_pressed) down = true;
        if (down == pressed) return false;
        pressed = down;
        invalidateSelf();
        return true;
    }

    @Override
    public void setAlpha(int a) {
        p.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
        p.setColorFilter(f);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
