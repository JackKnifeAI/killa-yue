package ai.jackknife.killayue.studio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import java.util.List;

import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.History;
import ai.jackknife.killayue.studio.TreeLayout;

/**
 * The undo tree, drawn: every step a node, branches on their own lanes, the step you are on lit. Tap any step
 * to go there; nothing is ever thrown away, so you can always come back.
 */
final class HistoryDialog {
    private HistoryDialog() {}

    static void show(final StudioActivity a) {
        if (a.session.history() == null) return;
        final TreeView tree = new TreeView(a);
        HorizontalScrollView h = new HorizontalScrollView(a);
        h.addView(tree);
        ScrollView v = new ScrollView(a);
        v.addView(h);
        final StudioSession.Listener l = new StudioSession.Listener() {
            public void projectChanged() {
                tree.requestLayout();
                tree.invalidate();
            }
        };
        a.session.listen(l);
        AlertDialog d = new Lux.Builder(a).setTitle("History").setView(v).setPositiveButton("Close", null).create();
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface di) {
                a.session.unlisten(l);
            }
        });
        d.show();
        // scroll to where you are
        final HorizontalScrollView hs = h;
        final ScrollView vs = v;
        h.post(new Runnable() {
            public void run() {
                TreeLayout t = tree.layout();
                if (t == null) return;
                hs.scrollTo((int) tree.cx(t, t.current) - hs.getWidth() / 2, 0);
                vs.scrollTo(0, (int) tree.cy(t, t.current) - vs.getHeight() / 2);
            }
        });
    }

    static final class TreeView extends View {
        private final StudioActivity a;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int col, row, pad;
        private TreeLayout cached;
        private int cachedFor = -1, cachedSteps = -1;

        TreeView(StudioActivity a) {
            super(a);
            this.a = a;
            col = Ui.dp(a, 76);
            row = Ui.dp(a, 52);
            pad = Ui.dp(a, 24);
            line.setStrokeWidth(Ui.dp(a, 2));
            text.setTypeface(Skin.text());
            text.setTextSize(Ui.dp(a, 10));
            text.setTextAlign(Paint.Align.CENTER);
        }

        TreeLayout layout() {
            History h = a.session.history();
            if (h == null) return null;
            int n = h.steps().size();
            if (cached == null || cachedSteps != n || cachedFor != h.currentStep()) {
                cached = TreeLayout.of(h);
                cachedSteps = n;
                cachedFor = h.currentStep();
            }
            return cached;
        }

        @Override
        protected void onMeasure(int w, int hs) {
            TreeLayout t = layout();
            int cols = t == null ? 1 : t.columns, lanes = t == null ? 1 : t.lanes;
            setMeasuredDimension(Math.max(cols * col + 2 * pad, Ui.dp(getContext(), 280)), lanes * row + 2 * pad);
        }

        float cx(TreeLayout t, int id) {
            return pad + t.x[id] * col + col / 2f;
        }

        float cy(TreeLayout t, int id) {
            return pad + t.y[id] * row + row / 3f;
        }

        @Override
        protected void onDraw(Canvas c) {
            History h = a.session.history();
            TreeLayout t = layout();
            if (h == null || t == null) return;
            List<History.Step> steps = h.steps();
            // the path from the start to where you are, lit
            boolean[] onPath = new boolean[steps.size()];
            for (int s = t.current; s >= 0; s = steps.get(s).parent) onPath[s] = true;
            for (History.Step s : steps) {
                if (s.parent < 0) continue;
                line.setColor(onPath[s.id] ? Skin.ACCENT : Skin.hi(80));
                float px = cx(t, s.parent), py = cy(t, s.parent), x = cx(t, s.id), y = cy(t, s.id);
                if (py == y) c.drawLine(px, py, x, y, line);
                else {  // down to the branch's lane, then across
                    c.drawLine(px, py, px + col / 2f, y, line);
                    c.drawLine(px + col / 2f, y, x, y, line);
                }
            }
            float r = Ui.dp(getContext(), 8);
            for (History.Step s : steps) {
                float x = cx(t, s.id), y = cy(t, s.id);
                fill.setColor(s.id == t.current ? Skin.HI : onPath[s.id] ? Skin.ACCENT : Skin.DIM);
                c.drawCircle(x, y, s.id == t.current ? r * 1.35f : r, fill);
                text.setColor(s.id == t.current ? Skin.HI : Skin.TEXT);
                String label = s.label;
                while (label.length() > 1 && text.measureText(label) > col - Ui.dp(getContext(), 6)) label = label.substring(0, label.length() - 1);
                c.drawText(label, x, y + r + Ui.dp(getContext(), 14), text);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            TreeLayout t = layout();
            History h = a.session.history();
            if (t == null || h == null) return true;
            float best = Ui.dp(getContext(), 30);
            int pick = -1;
            for (History.Step s : h.steps()) {
                float d = (float) Math.hypot(e.getX() - cx(t, s.id), e.getY() - cy(t, s.id));
                if (d < best) {
                    best = d;
                    pick = s.id;
                }
            }
            if (pick >= 0 && pick != h.currentStep()) {
                a.session.jumpTo(pick);
                Ui.toast(getContext(), "Now at: " + h.steps().get(pick).label);
            }
            return true;
        }
    }
}
