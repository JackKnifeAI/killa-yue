package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.MidiFx;
import ai.jackknife.killayue.studio.MidiNotes;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * One MIDI effect's settings. Every change is heard at once on the keys; closing the panel keeps the settings
 * as one step of undo. Choices of up to five are buttons; longer lists open a list; numbers are sliders.
 */
final class FxDialog {
    private FxDialog() {}
    static void show(final StudioActivity a, final KeysScreen keys, final int index) {
        final MidiNotes working = keys.notes().copy();
        if (index >= working.fx.effects.size())
            return;
        final MidiFx.Effect e = working.fx.effects.get(index);
        final MidiFx.Param[] ps = e.params();
        ScrollView scroll = new ScrollView(a);
        LinearLayout body = Ui.column(a);
        int pad = Ui.dp(a, 18);
        body.setPadding(pad, Ui.dp(a, 4), pad, Ui.dp(a, 8));
        scroll.addView(body);
        TextView about = Ui.text(a, MidiFx.about(e.kind), Skin.DIM, 13);
        body.addView(about, Ui.gap(a, 2));
        final Button power = Ui.button(a, e.on ? "● On" : "○ Bypassed", e.on);
        body.addView(power, Ui.gap(a, 8));
        power.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                e.on = !e.on;
                power.setText(e.on ? "● On" : "○ Bypassed");
                keys.player.setChain(working.fx);
            }
        });
        for (int i = 0; i < ps.length; i++) body.addView(control(a, keys, working, e, ps[i], i), Ui.gap(a, 10));
        final AlertDialog d = new Lux.Builder(a)
                                  .setTitle(e.title())
                                  .setView(scroll)
                                  .setPositiveButton("Done", null)
                                  .setNeutralButton("Reset", null)
                                  .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface di) {
                keys.save(working, e.title() + " settings");
            }
        });
        d.show();
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (int i = 0; i < ps.length; i++) e.p[i] = ps[i].initial;
                d.dismiss();
                keys.save(working, e.title() + " reset");
                show(a, keys, index);
            }
        });
    }
    static View control(final StudioActivity a, final KeysScreen keys, final MidiNotes working, final MidiFx.Effect e,
                        final MidiFx.Param p, final int i) {
        LinearLayout box = Ui.column(a);
        final TextView label = Ui.text(a, "", Skin.TEXT, 14);
        label.setTypeface(Skin.text(), Typeface.BOLD);
        box.addView(label);
        final Runnable relabel = new Runnable() {
            public void run() {
                label.setText(p.name + "  ·  " + p.show(e.p[i]));
            }
        };
        relabel.run();
        if (p.labels != null && p.labels.length <= 5) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            final LinearLayout row = Ui.row(a);
            hs.addView(row);
            final Button[] bs = new Button[p.labels.length];
            for (int k = 0; k < p.labels.length; k++) {
                final int v = k;
                bs[k] = Ui.chip(a, p.labels[k]);
                bs[k].setTextColor(Math.round(e.p[i]) == k ? Skin.HI : Skin.LO);
                bs[k].setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        e.p[i] = v;
                        for (int j = 0; j < bs.length; j++) bs[j].setTextColor(j == v ? Skin.HI : Skin.LO);
                        relabel.run();
                        keys.player.setChain(working.fx);
                    }
                });
                row.addView(bs[k]);
            }
            box.addView(hs);
        } else if (p.labels != null) {
            final Button pick = Ui.button(a, p.show(e.p[i]), false);
            pick.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    new Lux.Builder(a)
                        .setTitle(p.name)
                        .setSingleChoiceItems(p.labels, (int) Math.round(e.p[i]), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                e.p[i] = w;
                                pick.setText(p.show(w));
                                relabel.run();
                                keys.player.setChain(working.fx);
                                d.dismiss();
                            }
                        })
                        .show();
                }
            });
            box.addView(pick);
        } else {
            final int steps = (int) Math.round((p.max - p.min) / p.step);
            SeekBar bar = new SeekBar(a);
            bar.setMax(steps);
            bar.setProgress((int) Math.round((e.p[i] - p.min) / p.step));
            bar.setContentDescription(p.name);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int v, boolean user) {
                    if (!user)
                        return;
                    e.p[i] = Math.max(p.min, Math.min(p.max, p.min + v * p.step));
                    relabel.run();
                }
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) { keys.player.setChain(working.fx); }
            });
            box.addView(bar);
        }
        return box;
    }
}
