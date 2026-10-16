package ai.jackknife.killayue;

import android.app.Activity;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * How everything works: assets/guide.txt in the app's own dialog. "# " starts a part, "## " a control,
 * "- " a list item, anything else is a paragraph. Every new control gets its line there (CONTRIBUTING.md).
 */
public final class Guide {
    private Guide() {}

    public static void show(Activity a) {
        LinearLayout col = Ui.column(a);
        col.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), Ui.dp(a, 8));
        try (BufferedReader in = new BufferedReader(new InputStreamReader(a.getAssets().open("guide.txt"), "UTF-8"))) {
            String line;
            boolean first = true;
            while ((line = in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                TextView t;
                if (line.startsWith("# ")) {
                    if (first) {  // the dialog's title already says it
                        first = false;
                        continue;
                    }
                    t = Ui.text(a, line.substring(2), Skin.HI, 19);
                    t.setTypeface(Skin.text(), Typeface.BOLD);
                    t.setPadding(0, Ui.dp(a, 18), 0, Ui.dp(a, 4));
                } else if (line.startsWith("## ")) {
                    t = Ui.text(a, line.substring(3), Skin.ACCENT, 15);
                    t.setTypeface(Skin.text(), Typeface.BOLD);
                    t.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 2));
                } else if (line.startsWith("- ")) {
                    t = Ui.text(a, "•  " + line.substring(2), Skin.TEXT, 14);
                    t.setPadding(Ui.dp(a, 8), Ui.dp(a, 2), 0, Ui.dp(a, 2));
                } else {
                    t = Ui.text(a, line, Skin.TEXT, 14);
                    t.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 2));
                }
                col.addView(t);
            }
        } catch (Exception e) {
            col.addView(Ui.text(a, "The guide could not be read: " + e.getMessage(), Skin.TEXT, 14));
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(col);
        new Lux.Builder(a).setTitle("How Killa YuE works").setView(scroll).setPositiveButton("Close", null).show();
    }
}
