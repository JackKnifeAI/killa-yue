package ai.jackknife.killayue;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Dialogs in the app's own dress instead of Android's grey box: the LuxFrame panel, the title in
 * the skin's lettering (the Killa script on the gold skin), body, list items and buttons in the
 * Killa text face, cream text, gold accents. Use Lux.Builder wherever AlertDialog.Builder was used.
 */
public class Lux {
    public static class Builder extends AlertDialog.Builder {
        public Builder(Context c) {
            super(c);
        }

        @Override
        public AlertDialog create() {
            final AlertDialog d = super.create();
            dress(d);
            return d;
        }
    }

    /** Puts the frame on now, so the grey box never shows, and styles the text once the views exist. */
    static void dress(final AlertDialog d) {
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new LuxFrame());
            d.getWindow().setDimAmount(0.72f);
        }
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                if (d.getWindow() == null) return;
                final View root = d.getWindow().getDecorView();
                style(root, d.getContext());
                // list rows are made as the list scrolls, so new ones are dressed as they appear
                root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        style(root, d.getContext());
                    }
                });
            }
        });
    }

    private static final Object DONE = new Object();

    static void style(View v, Context c) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (g instanceof AbsListView) {
                ((AbsListView) g).setSelector(new android.graphics.drawable.ColorDrawable(0x22000000 | (Skin.ACCENT & 0x00FFFFFF)));
                if (g instanceof android.widget.ListView) ((android.widget.ListView) g).setDivider(null);
            }
            for (int i = 0; i < g.getChildCount(); i++) style(g.getChildAt(i), c);
            return;
        }
        if (!(v instanceof TextView) || v.getTag(R.id.lux_done) == DONE) return;
        TextView t = (TextView) v;
        t.setTag(R.id.lux_done, DONE);
        int titleId = c.getResources().getIdentifier("alertTitle", "id", "android");
        Typeface text = Skin.text(), script = Skin.script();
        if (t.getId() == titleId && titleId != 0) {
            boolean useScript = Skin.SCRIPT_TITLES && script != null;
            t.setTypeface(useScript ? script : text, useScript ? Typeface.NORMAL : Typeface.BOLD);
            t.setTextColor(Skin.ACCENT);
            // the script is ornate: long titles get a smaller size so they stay readable
            int len = t.getText() == null ? 0 : t.getText().length();
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, useScript ? (len > 40 ? 19 : len > 24 ? 22 : 27) : (len > 40 ? 17 : 20));
            t.setShadowLayer(10, 0, 2, 0xE6000000);
            t.setLetterSpacing(useScript ? 0f : 0.04f);
            t.setMaxLines(3);
        } else if (t instanceof Button) {
            if (text != null) t.setTypeface(text, Typeface.BOLD);
            t.setTextColor(Skin.HI);
            t.setAllCaps(false);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setShadowLayer(12, 0, 0, Skin.ACCENT);
        } else if (t instanceof EditText) {
            if (text != null) t.setTypeface(text);
            t.setTextColor(Skin.TEXT);
            t.setHintTextColor(Skin.DIM);
            t.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Skin.ACCENT));
        } else {
            if (text != null) t.setTypeface(text);
            t.setTextColor(Skin.TEXT);
            t.setShadowLayer(4, 0, 1, 0x99000000);
            if (t instanceof CheckedTextView) {
                ((CheckedTextView) t).setCheckMarkTintList(android.content.res.ColorStateList.valueOf(Skin.ACCENT));
            }
        }
    }
}
