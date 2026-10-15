package ai.jackknife.killayue.studio.ui;

import android.view.View;
import android.widget.LinearLayout;

import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.NativeEngine;

/** THE SPLITTER: stems (vocals, drums, bass and the rest) from a track. The model and the job come in 1d. */
final class SplitterScreen implements Screen {
    private final LinearLayout view;

    SplitterScreen(StudioActivity a) {
        view = Ui.column(a);
        view.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 16));
        view.addView(Ui.label(a, "The Splitter"));
        view.addView(Ui.text(a, "Split a song into vocals, drums, bass and the rest, each its own track. This is being built now.", Skin.TEXT, 15));
    }

    public String name() {
        return "Stems";
    }

    public View view() {
        return view;
    }

    public void shown() {}

    public void hidden() {}

    public void frame(NativeEngine.Status s) {}
}
