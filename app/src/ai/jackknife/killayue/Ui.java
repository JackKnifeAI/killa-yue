package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The app's small view builders, for any screen in the skin's dress: labels, fields, gold and plain plates,
 * chips, rows. MainActivity's own helpers hand over to these, so every screen (the Studio included) looks the
 * same. Call Skin.load(context) first.
 */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable box(Context c, int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    public static GradientDrawable glass(Context c, int edge) {
        GradientDrawable g = box(c, Skin.CARD, 12);
        g.setStroke(dp(c, 2), edge);
        return g;
    }

    public static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Skin.DIM);
        t.setTextSize(13);
        t.setTypeface(Skin.text(), Typeface.BOLD);
        t.setShadowLayer(4, 0, 1, 0x99000000);
        t.setPadding(0, dp(c, 16), 0, dp(c, 5));
        return t;
    }

    public static TextView text(Context c, String s, int color, int size) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        return t;
    }

    /** A screen's title in the skin's script lettering, as the sheet viewer and the wake-up screen show theirs. */
    public static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(Skin.ACCENT);
        t.setTypeface(Skin.script(), Skin.SCRIPT_TITLES ? Typeface.NORMAL : Typeface.BOLD);
        t.setTextSize(Skin.SCRIPT_TITLES ? 30 : 22);
        if (!Skin.SCRIPT_TITLES) {
            t.setAllCaps(true);
            t.setLetterSpacing(0.1f);
        }
        t.setShadowLayer(8, 0, 2, 0xCC000000);
        return t;
    }

    public static EditText edit(Context c, String hint, int minLines) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(Skin.FAINT);
        e.setTextColor(Skin.TEXT);
        e.setTextSize(15);
        e.setBackground(glass(c, Skin.EDGE));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        e.setGravity(Gravity.TOP | Gravity.START);
        if (minLines > 1) {
            e.setMinLines(minLines);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    public static Button button(Context c, String s, boolean primary) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setShadowLayer(5, 0, 2, 0xCC000000);
        b.setStateListAnimator(null);
        if (primary) {
            b.setTypeface(Skin.script(), Skin.SCRIPT_TITLES ? Typeface.NORMAL : Typeface.BOLD);
            b.setTextSize(Skin.SCRIPT_TITLES ? 24 : 18);
            if (!Skin.SCRIPT_TITLES) {
                b.setAllCaps(true);
                b.setLetterSpacing(0.1f);
            }
            b.setTextColor(Skin.ACCENT);
            b.setBackground(new Skin.Plate(true));
            b.setPadding(dp(c, 30), 0, dp(c, 30), dp(c, 2));
            b.setMinHeight(dp(c, 64));
            b.setMinimumHeight(dp(c, 64));
        } else {
            b.setTypeface(Skin.text(), Typeface.BOLD);
            b.setTextSize(15);
            b.setTextColor(Skin.TEXT);
            b.setBackground(new Skin.Plate(false));
            b.setPadding(dp(c, 18), 0, dp(c, 18), dp(c, 1));
            b.setMinHeight(dp(c, 50));
            b.setMinimumHeight(dp(c, 50));
        }
        return b;
    }

    public static Button chip(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Skin.ACCENT);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        GradientDrawable pill = box(c, Skin.PILL, 18);
        pill.setStroke(dp(c, 1), Skin.EDGE);
        b.setBackground(pill);
        b.setMinHeight(dp(c, 36));
        b.setMinimumHeight(dp(c, 36));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(c, 36));
        lp.rightMargin = dp(c, 8);
        b.setLayoutParams(lp);
        return b;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    public static LinearLayout.LayoutParams gap(Context c, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, top);
        return lp;
    }

    public static HorizontalScrollView chipRow(Context c, String[] names, final Runnable[] actions) {
        HorizontalScrollView h = new HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout l = row(c);
        for (int i = 0; i < names.length; i++) {
            Button chip = chip(c, names[i]);
            final Runnable r = actions[i];
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    r.run();
                }
            });
            l.addView(chip);
        }
        h.addView(l);
        h.setPadding(0, dp(c, 8), 0, 0);
        return h;
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * A round portrait in a gold ring, the artist medallion of Discover and the artist page. src may be null:
     * then the ring holds the first letter of the name in the skin's script.
     */
    public static android.graphics.Bitmap medallion(Context c, android.graphics.Bitmap src, String name, int sizeDp) {
        int px = dp(c, sizeDp), ring = Math.max(2, px / 28);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        float r = px / 2f, inner = r - ring * 1.5f;
        if (src != null) {
            int side = Math.min(src.getWidth(), src.getHeight());
            android.graphics.BitmapShader sh = new android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            float scale = inner * 2 / side;
            m.setScale(scale, scale);
            m.postTranslate(r - src.getWidth() * scale / 2, r - src.getHeight() * scale / 2);
            sh.setLocalMatrix(m);
            p.setShader(sh);
            cv.drawCircle(r, r, inner, p);
            p.setShader(null);
        } else {
            p.setColor(Skin.CARD);
            cv.drawCircle(r, r, inner, p);
            String letter = name == null || name.trim().isEmpty() ? "♪" : name.trim().substring(0, 1).toUpperCase();
            p.setTypeface(Skin.script());
            p.setColor(Skin.ACCENT);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setTextSize(inner);
            cv.drawText(letter, r, r - (p.descent() + p.ascent()) / 2, p);
        }
        // the gold ring, lit from the left as the plates are
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(ring);
        p.setShader(new android.graphics.LinearGradient(0, 0, px, px, Skin.HI, Skin.LO, android.graphics.Shader.TileMode.CLAMP));
        cv.drawCircle(r, r, r - ring, p);
        return out;
    }
}
