package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.Edit;
import ai.jackknife.killayue.studio.InstrumentSamples;
import ai.jackknife.killayue.studio.Json;
import ai.jackknife.killayue.studio.MidiNotes;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.NotesEdit;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.TempoMap;
import ai.jackknife.killayue.studio.Track;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

final class PianoDialog {
    static void show(final StudioActivity a) {
        final Project p = a.session.project();
        if (p == null)
            return;
        final ArrayList<String> ids = new ArrayList<String>(), labels = new ArrayList<String>();
        for (Track t : p.tracks)
            if (t.notes.instrument >= 0) {
                ids.add(t.id);
                labels.add(t.name);
            }
        labels.add("Add instrument track…");
        new Lux.Builder(a)
            .setTitle("Piano roll")
            .setItems(labels.toArray(new String[0]),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which < ids.size())
                            prepare(a, ids.get(which), p.track(ids.get(which)).notes.instrument);
                        else
                            new Lux.Builder(a)
                                .setTitle("Choose sampled instrument")
                                .setItems(InstrumentSamples.NAMES,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int instrument) {
                                            prepare(a, null, instrument);
                                        }
                                    })
                                .show();
                    }
                })
            .show();
    }
    private static void prepare(final StudioActivity a, final String track, final int instrument) {
        if (a.session.recording.busy()) {
            Ui.toast(a, "Finish the take before preparing an instrument.");
            return;
        }
        final Project project = a.session.project();
        final NativeEngine engine = a.session.engine();
        final File dir = a.session.dir();
        if (engine == null || project == null)
            return;
        if (track == null && project.tracks.size() >= 64) {
            Ui.toast(a, "The project already has 64 tracks.");
            return;
        }
        final AtomicBoolean cancel = new AtomicBoolean(), finished = new AtomicBoolean();
        final TextView status = Ui.text(a, "Preparing original instrument samples…", Skin.TEXT, 15);
        int pad = Ui.dp(a, 18);
        status.setPadding(pad, pad, pad, pad);
        final AlertDialog progress = new Lux.Builder(a)
                                         .setTitle(InstrumentSamples.NAMES[instrument])
                                         .setView(status)
                                         .setCancelable(false)
                                         .setNegativeButton("Cancel",
                                             new DialogInterface.OnClickListener() {
                                                 public void onClick(DialogInterface d, int w) {
                                                     cancel.set(true);
                                                 }
                                             })
                                         .create();
        status.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) {
                if (!finished.get())
                    cancel.set(true);
            }
        });
        progress.show();
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                try {
                    InstrumentSamples.bank(dir, project.rate, instrument, new InstrumentSamples.Progress() {
                        public void at(final int done, final int total) {
                            a.runOnUiThread(new Runnable() {
                                public void run() {
                                    if (!cancel.get())
                                        status.setText("Preparing samples: " + done + " / " + total);
                                }
                            });
                        }
                    }, cancel);
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                final String why = error;
                finished.set(true);
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing() || a.isDestroyed())
                            return;
                        progress.dismiss();
                        if (cancel.get())
                            return;
                        if (why != null) {
                            Ui.toast(a, why);
                            return;
                        }
                        if (a.session.project() != project || a.session.engine() != engine)
                            return;
                        try {
                            engine.keyboard(dir, instrument);
                        } catch (Exception ex) {
                            Ui.toast(a, ex.getMessage());
                            return;
                        }
                        String id = track;
                        if (id == null) {
                            a.session.run(Edit.addInstrument(InstrumentSamples.NAMES[instrument], instrument));
                            id = project.tracks.get(project.tracks.size() - 1).id;
                        } else if (project.track(id) == null)
                            return;
                        new Editor(a, id).show();
                    }
                });
            }
        }, "studio-instrument-samples").start();
    }
    private static final class Editor implements MidiDevices.Target, KeyboardView.Target {
        final StudioActivity a;
        final String track;
        final Project project;
        final NativeEngine engine;
        final MidiDevices midi;
        final KeyboardView keyboard;
        final Roll roll;
        final boolean[][] held = new boolean[16][128], deferred = new boolean[16][128];
        final boolean[] sustain = new boolean[16];
        final boolean[] touches = new boolean[128];
        AlertDialog dialog;
        boolean closed;
        double first = 0, span = 8, grid = .25;
        int top = 72, selected = -1, mode;
        String[] modes = {"Draw / move", "Resize", "Velocity"};
        TextView info;
        Editor(StudioActivity a, String track) {
            this.a = a;
            this.track = track;
            project = a.session.project();
            engine = a.session.engine();
            midi = new MidiDevices(a, this);
            keyboard = new KeyboardView(a, this);
            roll = new Roll();
        }
        Track current() {
            return a.session.project() == project ? project.track(track) : null;
        }
        MidiNotes notes() {
            Track t = current();
            return t == null ? new MidiNotes() : t.notes;
        }
        void button(LinearLayout row, String title, final Runnable action) {
            Button b = Ui.button(a, title, false);
            row.addView(b);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    roll.cancel();
                    action.run();
                    update();
                }
            });
        }
        void show() {
            LinearLayout body = Ui.column(a);
            int pad = Ui.dp(a, 8);
            body.setPadding(pad, pad, pad, pad);
            info = Ui.text(a, "", Skin.TEXT, 13);
            body.addView(info);
            LinearLayout controls = new LinearLayout(a);
            HorizontalScrollView scroll = new HorizontalScrollView(a);
            scroll.addView(controls);
            body.addView(scroll);
            button(controls, "Play / stop", new Runnable() {
                public void run() {
                    NativeEngine.Status s = a.session.status();
                    if (s != null && s.playing)
                        a.session.stop();
                    else
                        a.session.play();
                }
            });
            button(controls, "Mode", new Runnable() {
                public void run() {
                    mode = (mode + 1) % 3;
                }
            });
            button(controls, "Edit note", new Runnable() {
                public void run() {
                    exact();
                }
            });
            button(controls, "Delete", new Runnable() {
                public void run() {
                    MidiNotes n = notes().copy();
                    MidiNotes.Note note = n.find(selected);
                    if (note != null) {
                        n.notes.remove(note);
                        save(n, "Delete note");
                        selected = -1;
                    }
                }
            });
            button(controls, "Quantise", new Runnable() {
                public void run() {
                    MidiNotes n = notes().copy();
                    n.quantise(grid);
                    save(n, "Quantise notes");
                }
            });
            button(controls, "Grid", new Runnable() {
                public void run() {
                    grid = grid == .25 ? .5 : grid == .5 ? 1 : grid == 1 ? .125 : .25;
                }
            });
            button(controls, "Undo", new Runnable() {
                public void run() {
                    a.session.undo();
                }
            });
            button(controls, "Redo", new Runnable() {
                public void run() {
                    a.session.redo(-1);
                }
            });
            button(controls, "MIDI", new Runnable() {
                public void run() {
                    midi.show();
                }
            });
            button(controls, "All notes off", new Runnable() {
                public void run() {
                    allOff();
                }
            });
            body.addView(roll, new LinearLayout.LayoutParams(-1, Ui.dp(a, 260)));
            LinearLayout navigation = new LinearLayout(a);
            HorizontalScrollView pages = new HorizontalScrollView(a);
            pages.addView(navigation);
            body.addView(pages);
            button(navigation, "← Beats", new Runnable() {
                public void run() {
                    first = Math.max(0, first - span);
                }
            });
            button(navigation, "Beats →", new Runnable() {
                public void run() {
                    first = Math.min(100000 - span, first + span);
                }
            });
            button(navigation, "Zoom", new Runnable() {
                public void run() {
                    span = span == 8 ? 4 : span == 4 ? 16 : 8;
                }
            });
            button(navigation, "Pitch ↓", new Runnable() {
                public void run() {
                    top = Math.max(59, top - 12);
                }
            });
            button(navigation, "Pitch ↑", new Runnable() {
                public void run() {
                    top = Math.min(84, top + 12);
                }
            });
            button(navigation, "Keys ↓", new Runnable() {
                public void run() {
                    keyboard.octave(-1);
                }
            });
            button(navigation, "Keys ↑", new Runnable() {
                public void run() {
                    keyboard.octave(1);
                }
            });
            body.addView(keyboard, new LinearLayout.LayoutParams(-1, Ui.dp(a, 190)));
            dialog = new Lux.Builder(a)
                         .setTitle(current().name + " · piano roll")
                         .setView(body)
                         .setNegativeButton("Close", null)
                         .create();
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface d) {
                    closed = true;
                    keyboard.release();
                    midi.close();
                    allOff();
                }
            });
            dialog.show();
            dialog.getWindow().setLayout(-1, -2);
            update();
        }
        void update() {
            if (current() == null) {
                if (dialog != null)
                    dialog.dismiss();
                return;
            }
            info.setText(modes[mode] + " · beats " + first + "–" + (first + span) + " · grid " + grid + " · velocity "
                + (notes().find(selected) == null ? "80%" : Math.round(notes().find(selected).velocity * 100) + "%"));
            roll.invalidate();
        }
        void save(MidiNotes n, String label) {
            if (current() == null)
                return;
            try {
                n.validate();
                if (!Json.write(n.json()).equals(Json.write(notes().json())))
                    a.session.run(new NotesEdit(project, track, n, label));
            } catch (IllegalArgumentException ex) {
                Ui.toast(a, ex.getMessage());
            }
            update();
        }
        void exact() {
            final MidiNotes.Note note = notes().find(selected);
            if (note == null) {
                Ui.toast(a, "Tap a note first.");
                return;
            }
            LinearLayout form = Ui.column(a);
            final EditText[] fields = new EditText[4];
            String[] names = {"Beat", "Length in beats", "MIDI pitch (36–84)", "Velocity (1–100%)"};
            String[] values = {"" + note.beat, "" + note.length, "" + note.key, "" + Math.round(note.velocity * 100)};
            for (int i = 0; i < 4; i++) {
                fields[i] = new EditText(a);
                fields[i].setSingleLine(true);
                fields[i].setInputType(
                    android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                fields[i].setText(values[i]);
                form.addView(Ui.text(a, names[i], Skin.TEXT, 13));
                form.addView(fields[i]);
            }
            final AlertDialog edit = new Lux.Builder(a)
                                         .setTitle("Edit note")
                                         .setView(form)
                                         .setPositiveButton("Apply", null)
                                         .setNegativeButton("Cancel", null)
                                         .create();
            edit.show();
            edit.getButton(-1).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        MidiNotes n = notes().copy();
                        MidiNotes.Note changed = n.find(note.id);
                        if (changed == null) {
                            edit.dismiss();
                            return;
                        }
                        changed.beat = Double.parseDouble(fields[0].getText().toString());
                        changed.length = Double.parseDouble(fields[1].getText().toString());
                        changed.key = Integer.parseInt(fields[2].getText().toString());
                        changed.velocity = Float.parseFloat(fields[3].getText().toString()) / 100;
                        n.validate();
                        save(n, "Edit note");
                        edit.dismiss();
                    } catch (IllegalArgumentException ex) {
                        Ui.toast(a, "Enter a valid beat, length, pitch and velocity.");
                    }
                }
            });
        }
        public void note(int key, float velocity, boolean on) {
            touches[key] = on;
            if (on)
                send(key, velocity, true);
            else
                releaseKey(key);
        }
        private void send(int key, float velocity, boolean on) {
            if (!closed && a.session.engine() == engine) {
                if (!engine.note(key, velocity, on) && !on)
                    engine.notesOff();
            }
        }
        public void note(int channel, int key, int velocity, boolean on) {
            if (key < MidiNotes.LOW || key > MidiNotes.HIGH)
                return;
            held[channel][key] = on;
            if (on) {
                deferred[channel][key] = false;
                send(key, velocity / 127f, true);
            } else if (sustain[channel])
                deferred[channel][key] = true;
            else
                releaseKey(key);
        }
        void releaseKey(int key) {
            if (touches[key])
                return;
            for (int ch = 0; ch < 16; ch++)
                if (held[ch][key] || deferred[ch][key])
                    return;
            send(key, 0, false);
        }
        public void control(int channel, int cc, int value) {
            if (cc == 64) {
                sustain[channel] = value >= 64;
                if (!sustain[channel])
                    for (int key = 36; key <= 84; key++)
                        if (deferred[channel][key]) {
                            deferred[channel][key] = false;
                            releaseKey(key);
                        }
            } else if (cc == 120 || cc == 123)
                allOff();
        }
        public void allOff() {
            java.util.Arrays.fill(touches, false);
            for (int ch = 0; ch < 16; ch++) {
                sustain[ch] = false;
                java.util.Arrays.fill(held[ch], false);
                java.util.Arrays.fill(deferred[ch], false);
            }
            if (a.session.engine() == engine)
                engine.notesOff();
        }
        final class Roll extends View {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            MidiNotes preview;
            MidiNotes.Note moving;
            double downBeat, oldBeat, oldLength;
            int downKey, oldKey;
            float downY, downX, oldVelocity;
            boolean dragged;
            Roll() {
                super(a);
                setContentDescription("Piano roll. Draw or move notes, or choose Resize or Velocity mode.");
            }
            float left() {
                return Ui.dp(a, 32);
            }
            float row() {
                return getHeight() / 24f;
            }
            float x(double beat) {
                return left() + (float) ((beat - first) / span) * (getWidth() - left());
            }
            float y(int key) {
                return (top - key) * row();
            }
            double beat(float x) {
                return Math.max(0, Math.min(100000, first + (x - left()) / Math.max(1, getWidth() - left()) * span));
            }
            int pitch(float y) {
                return Math.max(36, Math.min(84, top - (int) (y / row())));
            }
            double snap(double v) {
                return Math.round(v / grid) * grid;
            }
            void cancel() {
                preview = null;
                moving = null;
                invalidate();
            }
            protected void onDraw(Canvas c) {
                c.drawColor(Skin.DECK);
                paint.setTextSize(Ui.dp(a, 9));
                for (int r = 0; r < 24; r++) {
                    int key = top - r;
                    paint.setColor((key % 12 == 1 || key % 12 == 3 || key % 12 == 6 || key % 12 == 8 || key % 12 == 10)
                            ? Skin.CARD
                            : Skin.DECK);
                    c.drawRect(left(), r * row(), getWidth(), (r + 1) * row(), paint);
                    paint.setColor(Skin.DIM);
                    c.drawText("" + key, 2, (r + .8f) * row(), paint);
                }
                paint.setColor(Skin.EDGE);
                for (double b = first; b <= first + span; b += grid) c.drawLine(x(b), 0, x(b), getHeight(), paint);
                MidiNotes shown = preview == null ? notes() : preview;
                for (MidiNotes.Note n : shown.notes) {
                    if (n.key > top || n.key < top - 23 || n.beat + n.length < first || n.beat > first + span)
                        continue;
                    paint.setColor(n.id == selected ? Skin.HI : Skin.ACCENT);
                    float l = Math.max(left(), x(n.beat)), r = Math.min(getWidth(), x(n.beat + n.length));
                    c.drawRect(l, y(n.key) + 1, Math.max(l + 2, r), y(n.key) + row() - 1, paint);
                    paint.setColor(Skin.LO);
                    c.drawRect(l, y(n.key) + row() * (1 - n.velocity), Math.max(l + 2, r), y(n.key) + row() - 1, paint);
                }
                NativeEngine.Status status = a.session.status();
                if (status != null) {
                    double b = new TempoMap(project.rate, project.performance).beatAt(status.position);
                    if (b >= first && b <= first + span) {
                        paint.setColor(Skin.TEXT);
                        c.drawLine(x(b), 0, x(b), getHeight(), paint);
                    }
                }
            }
            final Runnable tick = new Runnable() {
                public void run() {
                    invalidate();
                    postDelayed(this, 60);
                }
            };
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                post(tick);
            }
            protected void onDetachedFromWindow() {
                removeCallbacks(tick);
                cancel();
                super.onDetachedFromWindow();
            }
            public boolean onTouchEvent(MotionEvent e) {
                if (current() == null)
                    return false;
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    cancel();
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    cancel();
                    return true;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    preview = notes().copy();
                    downBeat = beat(e.getX());
                    downKey = pitch(e.getY());
                    downY = e.getY();
                    downX = e.getX();
                    dragged = false;
                    moving = null;
                    for (int i = preview.notes.size() - 1; i >= 0; i--) {
                        MidiNotes.Note n = preview.notes.get(i);
                        if (n.key == downKey && downBeat >= n.beat && downBeat <= n.beat + n.length) {
                            moving = n;
                            break;
                        }
                    }
                    if (moving == null && mode == 0) {
                        if (preview.notes.size() >= MidiNotes.MAX) {
                            cancel();
                            Ui.toast(a, "This track already has 4096 notes.");
                            return true;
                        }
                        moving = preview.add(snap(downBeat), downKey);
                        moving.length = grid;
                    }
                    if (moving != null) {
                        selected = moving.id;
                        oldBeat = moving.beat;
                        oldLength = moving.length;
                        oldKey = moving.key;
                        oldVelocity = moving.velocity;
                    }
                    invalidate();
                    return true;
                }
                if (preview != null && moving != null
                    && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                    && (dragged || Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > Ui.dp(a, 6))) {
                    dragged = true;
                    if (mode == 0) {
                        moving.beat = Math.max(0, Math.min(100000, snap(oldBeat + beat(e.getX()) - downBeat)));
                        moving.key = Math.max(36, Math.min(84, oldKey + pitch(e.getY()) - downKey));
                    } else if (mode == 1)
                        moving.length = Math.max(grid, Math.min(256, snap(oldLength + beat(e.getX()) - downBeat)));
                    else
                        moving.velocity = Math.max(.01f, Math.min(1, oldVelocity + (downY - e.getY()) / Ui.dp(a, 160)));
                    invalidate();
                }
                if (action == MotionEvent.ACTION_UP) {
                    MidiNotes done = preview;
                    preview = null;
                    moving = null;
                    if (done != null)
                        save(done, "Edit piano roll");
                    performClick();
                    return true;
                }
                return true;
            }
            public boolean performClick() {
                super.performClick();
                return true;
            }
        }
    }
}
