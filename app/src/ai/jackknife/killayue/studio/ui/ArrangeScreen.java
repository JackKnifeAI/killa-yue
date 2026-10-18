package ai.jackknife.killayue.studio.ui;

import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.Edit;
import ai.jackknife.killayue.studio.History;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.Project;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Arrange: the transport, then the timeline of tracks, with the playhead moving over it. */
final class ArrangeScreen implements Screen, StudioSession.Listener {
    private final StudioActivity a;
    private final LinearLayout view;
    private final TimelineView timeline;
    private final PlayheadView playhead;
    private final Button play, loop, undo, redo;
    private final TextView time, health;
    private long underruns = -1, xruns = -1;
    private boolean fitted;

    ArrangeScreen(StudioActivity a) {
        this.a = a;
        view = Ui.column(a);
        LinearLayout bar = Ui.row(a);
        bar.setPadding(Ui.dp(a, 10), Ui.dp(a, 2), Ui.dp(a, 10), Ui.dp(a, 4));
        Button start = RoundFace.button(a, "⏮");
        start.setContentDescription("Back to the start");
        play = RoundFace.button(a, "▶");
        play.setContentDescription("Play");
        loop = RoundFace.button(a, "↻");
        loop.setContentDescription("Loop");
        undo = RoundFace.button(a, "↶");
        undo.setContentDescription("Undo");
        redo = RoundFace.button(a, "↷");
        redo.setContentDescription("Redo");
        Button hist = RoundFace.button(a, "☰");
        hist.setContentDescription("History");
        Button export = RoundFace.button(a, "⤓");
        export.setContentDescription("Export");
        time = Ui.text(a, "0:00.0", Skin.TEXT, 15);
        time.setTypeface(Skin.text(), android.graphics.Typeface.BOLD);
        time.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 8), 0);
        health = Ui.text(a, "●", 0xff5fd068, 14);
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(a);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(a);
        for (View v : new View[] {start, play, loop, time, undo, redo, hist, export, health}) row.addView(v);
        scroller.addView(row);
        bar.addView(scroller);
        view.addView(bar);

        FrameLayout stage = new FrameLayout(a);
        ScrollView vertical = new ScrollView(a);
        timeline = new TimelineView(a, a.session);
        vertical.addView(timeline);
        stage.addView(vertical, new FrameLayout.LayoutParams(-1, -1));
        playhead = new PlayheadView(a, timeline);
        stage.addView(playhead, new FrameLayout.LayoutParams(-1, -1));
        view.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Project p = a.session.project();
                a.session.seek(p != null && p.loopOn ? p.loopStart : 0);
            }
        });
        play.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                NativeEngine.Status s = a.session.status();
                if (s != null && s.playing)
                    a.session.stop();
                else
                    a.session.play();
            }
        });
        loop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Project p = a.session.project();
                if (p == null)
                    return;
                if (p.loopEnd <= p.loopStart) {
                    Ui.toast(a, "Touch and hold the ruler, then drag, to mark a loop.");
                    return;
                }
                a.session.run(Edit.setLoop(p.loopStart, p.loopEnd, !p.loopOn));
            }
        });
        undo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                a.session.undo();
            }
        });
        redo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                History h = a.session.history();
                if (h == null)
                    return;
                List<String> choices = h.redoChoices();
                if (choices.size() <= 1) {
                    a.session.redo(-1);
                    return;
                }
                // more than one way forward: which branch?
                String[] items = new String[choices.size()];
                for (int i = 0; i < items.length; i++) items[i] = (i + 1) + ". " + choices.get(i);
                new Lux.Builder(a)
                    .setTitle("Redo which?")
                    .setItems(items,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                a.session.redo(w);
                            }
                        })
                    .show();
            }
        });
        export.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ExportDialog.show(a);
            }
        });
        hist.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                HistoryDialog.show(a);
            }
        });
        Button piano = Ui.button(a, "Keys", false);
        row.addView(piano);
        piano.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.showKeys(); } });
        Button automation = Ui.button(a, "Automation", false);
        row.addView(automation);
        automation.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { AutomationDialog.show(a); }
        });
        final Button mode = RoundFace.button(a, "✥");
        mode.setContentDescription("Timeline mode: navigate");
        row.addView(mode);
        mode.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                timeline.editMode = (timeline.editMode + 1) % 3;
                String label = new String[] {"Navigate", "Move clips", "Swipe comp"}[timeline.editMode];
                mode.setText(new String[] {"✥", "↔", "✂"}[timeline.editMode]);
                mode.setContentDescription("Timeline mode: " + label);
                Ui.toast(a, label);
            }
        });
        a.session.listen(this);
    }

    public String name() {
        return "Arrange";
    }

    public View view() {
        return view;
    }

    public void shown() {
        projectChanged();
        if (!fitted) {
            timeline.post(new Runnable() {
                public void run() {
                    timeline.fit();
                }
            });
            fitted = true;
        }
    }

    public void hidden() {}

    public void projectChanged() {
        History h = a.session.history();
        Project p = a.session.project();
        if (p == null)
            fitted = false;
        undo.setEnabled(h != null && h.canUndo());
        undo.setContentDescription(h != null && h.undoLabel() != null ? "Undo " + h.undoLabel() : "Undo");
        undo.setAlpha(undo.isEnabled() ? 1f : 0.45f);
        redo.setEnabled(h != null && h.canRedo());
        redo.setAlpha(redo.isEnabled() ? 1f : 0.45f);
        loop.setTextColor(p != null && p.loopOn ? Skin.HI : Skin.LO);
        timeline.requestLayout();
        timeline.invalidate();
    }

    public void frame(NativeEngine.Status s) {
        if (s == null)
            return;
        Project p = a.session.project();
        int rate = p == null ? 48000 : p.rate;
        double sec = s.position / (double) rate;
        int m = (int) (sec / 60);
        time.setText(String.format(java.util.Locale.ROOT, "%d:%04.1f", m, sec - m * 60));
        play.setText(s.playing ? "■" : "▶");
        play.setContentDescription(s.playing ? "Stop" : "Play");
        // health: green, amber after a dropout from the disk, red after one from the device
        if (underruns >= 0 && s.underruns > underruns && s.playing)
            health.setTextColor(0xffe0b04a);
        if (xruns >= 0 && s.xruns > xruns)
            health.setTextColor(0xffe05050);
        underruns = s.underruns;
        xruns = s.xruns;
        playhead.at(s.position, s.playing);
    }

    /** The playhead, drawn over the timeline every frame (the timeline itself redraws only on changes). */
    static final class PlayheadView extends View {
        private final TimelineView t;
        private final Paint p = new Paint();
        private long pos = -1;

        PlayheadView(Context c, TimelineView t) {
            super(c);
            this.t = t;
            p.setStrokeWidth(Ui.dp(c, 2));
        }

        void at(long frame, boolean playing) {
            if (playing) { // keep it in view: page on when it reaches the right edge
                float x = t.xAt(frame);
                if (x > t.getWidth() - Ui.dp(getContext(), 24) || x < t.header) {
                    t.left = Math.max(0, frame - Ui.dp(getContext(), 24) * t.fpp);
                    t.invalidate();
                }
            }
            if (frame != pos) {
                pos = frame;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (pos < 0)
                return;
            float x = t.xAt(pos);
            if (x < t.header || x > getWidth())
                return;
            p.setColor(Skin.ACCENT);
            c.drawLine(x, 0, x, getHeight(), p);
        }
    }
}
