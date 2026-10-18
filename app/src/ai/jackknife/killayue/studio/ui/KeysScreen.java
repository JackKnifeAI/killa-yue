package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.LuxFrame;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.ClipEdits;
import ai.jackknife.killayue.studio.Edit;
import ai.jackknife.killayue.studio.Json;
import ai.jackknife.killayue.studio.MidiFx;
import ai.jackknife.killayue.studio.MidiNotes;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.NotesEdit;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.Smf;
import ai.jackknife.killayue.studio.Sounds;
import ai.jackknife.killayue.studio.TempoMap;
import ai.jackknife.killayue.studio.Track;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KEYS: play, record and shape an instrument track. The track and its sound at the top; record and play; the
 * MIDI effects rack, the track's chain of effects applied as you play and as it plays back; the piano roll; and
 * the playable 88-key piano. MIDI keyboards over USB or Bluetooth play through the same effects. Recording keeps
 * what you played (the effects stay live, so changing them later changes the part); Print bakes them in.
 */
final class KeysScreen implements Screen, StudioSession.Listener, MidiDevices.Target, GrandKeysView.Target {
    static final int PICK_MIDI = 61, SAVE_MIDI = 62;
    final StudioActivity a;
    final LinearLayout view;
    final GrandKeysView keys;
    final Roll roll;
    final MidiDevices midi;
    final TextView info, soundName, trackName;
    final Button record, play, sustainButton;
    final LinearLayout rack;
    String track;
    int loadedSound = -1;
    File loadedDir;
    boolean sustain, rollShown = true, preparing, exportAsHeard;
    final boolean[] deferred = new boolean[128], touched = new boolean[128];
    final boolean[][] midiHeld = new boolean[16][128];
    final Player player = new Player();
    final Recorder recorder = new Recorder();

    KeysScreen(final StudioActivity a) {
        this.a = a;
        midi = new MidiDevices(a, this);
        view = Ui.column(a);
        int pad = Ui.dp(a, 10);
        view.setPadding(pad, 0, pad, Ui.dp(a, 4));

        // the track and its sound, framed in gold
        LinearLayout head = Ui.row(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(new LuxFrame(14, 6));
        LinearLayout names = Ui.column(a);
        trackName = Ui.text(a, "", Skin.DIM, 12);
        soundName = Ui.text(a, "Choose a sound", Skin.HI, 22);
        soundName.setTypeface(Skin.script());
        soundName.setSingleLine(true);
        names.addView(trackName);
        names.addView(soundName);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        names.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseTrack(); }
        });
        Button soundB = RoundFace.button(a, "♪");
        soundB.setContentDescription("Choose this track's sound");
        soundB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { SoundsDialog.show(a, KeysScreen.this); }
        });
        head.addView(soundB);
        record = RoundFace.button(a, "●");
        record.setContentDescription("Record notes");
        record.setTextColor(0xffe0405a);
        record.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleRecord(); }
        });
        head.addView(record);
        play = RoundFace.button(a, "▶");
        play.setContentDescription("Play or stop");
        play.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (recorder.on) {
                    toggleRecord();
                    return;
                }
                NativeEngine.Status s = a.session.status();
                if (s != null && s.playing)
                    a.session.stop();
                else
                    a.session.play();
            }
        });
        head.addView(play);
        Button more = RoundFace.button(a, "⋯");
        more.setContentDescription("More: MIDI keyboards, MIDI files, print effects");
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { menu(); }
        });
        head.addView(more);
        view.addView(head, Ui.gap(a, 2));

        // the MIDI effects rack
        HorizontalScrollView rackScroll = new HorizontalScrollView(a);
        rackScroll.setHorizontalScrollBarEnabled(false);
        rack = Ui.row(a);
        rack.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 2));
        rackScroll.addView(rack);
        view.addView(rackScroll);

        roll = new Roll();
        LinearLayout tools = Ui.row(a);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        final Button[] modeButtons = new Button[4];
        for (int i = 0; i < 4; i++) {
            final int m = i;
            modeButtons[i] = Ui.chip(a, roll.modes[i]);
            modeButtons[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    roll.mode = m;
                    for (int k = 0; k < 4; k++) modeButtons[k].setTextColor(k == m ? Skin.HI : Skin.LO);
                }
            });
            modeButtons[i].setTextColor(i == 0 ? Skin.HI : Skin.LO);
            tools.addView(modeButtons[i]);
        }
        final Button gridB = Ui.chip(a, "Grid 1/16");
        gridB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                double[] g = {.25, .125, 1 / 6.0, .5, 1};
                String[] n = {"1/16", "1/32", "1/16T", "1/8", "1/4"};
                int at = 0;
                for (int i = 0; i < g.length; i++)
                    if (Math.abs(g[i] - roll.grid) < 1e-9)
                        at = i;
                at = (at + 1) % g.length;
                roll.grid = g[at];
                gridB.setText("Grid " + n[at]);
                roll.invalidate();
            }
        });
        tools.addView(gridB);
        Button zoomB = Ui.chip(a, "Zoom");
        zoomB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                roll.span = roll.span == 8 ? 16 : roll.span == 16 ? 4 : 8;
                roll.invalidate();
            }
        });
        tools.addView(zoomB);
        Button undoB = Ui.chip(a, "↶ Undo"), redoB = Ui.chip(a, "↷ Redo");
        undoB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { a.session.undo(); }
        });
        redoB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { a.session.redo(-1); }
        });
        tools.addView(undoB);
        tools.addView(redoB);
        info = Ui.text(a, "", Skin.DIM, 12);
        info.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 4), 0);
        tools.addView(info);
        HorizontalScrollView toolScroll = new HorizontalScrollView(a);
        toolScroll.setHorizontalScrollBarEnabled(false);
        toolScroll.addView(tools);
        view.addView(toolScroll);
        view.addView(roll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // the keyboard's own controls
        LinearLayout pianoBar = Ui.row(a);
        pianoBar.setGravity(Gravity.CENTER_VERTICAL);
        pianoBar.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 2));
        keys = new GrandKeysView(a, this);
        small(pianoBar, "‹ Oct", new Runnable() { public void run() { keys.scroll(-7); } });
        small(pianoBar, "Oct ›", new Runnable() { public void run() { keys.scroll(7); } });
        small(pianoBar, "− Keys", new Runnable() { public void run() { keys.zoom(keys.across + 3); } });
        small(pianoBar, "+ Keys", new Runnable() { public void run() { keys.zoom(keys.across - 3); } });
        small(pianoBar, "Rows", new Runnable() { public void run() { keys.rows(keys.rows == 1 ? 2 : 1); } });
        small(pianoBar, "Roll", new Runnable() {
            public void run() {
                rollShown = !rollShown;
                roll.setVisibility(rollShown ? View.VISIBLE : View.GONE);
                keys.setLayoutParams(new LinearLayout.LayoutParams(-1, rollShown ? Ui.dp(a, 190) : 0, rollShown ? 0 : 1f));
            }
        });
        sustainButton = Ui.button(a, "Sustain", false);
        sustainButton.setTextSize(12);
        sustainButton.setContentDescription("Sustain pedal: hold, or double-tap to latch");
        sustainButton.setOnTouchListener(new View.OnTouchListener() {
            long lastUp;
            boolean latched;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    pedal(true);
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    long now = System.currentTimeMillis();
                    if (!latched && now - lastUp < 350) {
                        latched = true;  // double tap: the pedal stays down
                    } else {
                        latched = false;
                        pedal(false);
                    }
                    lastUp = now;
                    return true;
                }
                return true;
            }
        });
        pianoBar.addView(sustainButton);
        HorizontalScrollView barScroll = new HorizontalScrollView(a);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.addView(pianoBar);
        view.addView(barScroll);
        view.addView(keys, new LinearLayout.LayoutParams(-1, Ui.dp(a, 190)));
    }
    void small(LinearLayout row, String title, final Runnable r) {
        Button b = Ui.button(a, title, false);
        b.setTextSize(12);
        b.setMinHeight(Ui.dp(a, 38));
        b.setMinimumHeight(Ui.dp(a, 38));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(a, 40));
        lp.rightMargin = Ui.dp(a, 4);
        row.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
    }

    public String name() { return "Keys"; }
    public View view() { return view; }
    public void shown() {
        a.session.listen(this);
        if (current() == null)
            track = firstInstrument();
        refresh();
        if (current() != null)
            prepare(current().notes.instrument, false);
        else
            chooseTrack();
    }
    public void hidden() {
        if (recorder.on)
            toggleRecord();
        keys.release();
        allOff();
        a.session.unlisten(this);
    }
    public void frame(NativeEngine.Status s) {
        boolean playing = s != null && s.playing;
        play.setText(playing ? "■" : "▶");
        record.setTextColor(recorder.on ? (System.currentTimeMillis() / 400 % 2 == 0 ? 0xffff5070 : 0xff802030) : 0xffe0405a);
        if (s != null)
            player.anchor(s);
        roll.invalidate();
    }
    public void projectChanged() {
        if (current() == null && track != null)
            track = firstInstrument();
        refresh();
    }

    Project project() { return a.session.project(); }
    Track current() {
        Project p = project();
        return p == null || track == null ? null : p.track(track);
    }
    MidiNotes notes() {
        Track t = current();
        return t == null ? new MidiNotes() : t.notes;
    }
    String firstInstrument() {
        Project p = project();
        if (p == null)
            return null;
        for (Track t : p.tracks)
            if (t.notes.instrument >= 0)
                return t.id;
        return null;
    }

    void refresh() {
        Track t = current();
        if (t == null) {
            trackName.setText("NO INSTRUMENT TRACK");
            soundName.setText("Tap to add one");
            rack.removeAllViews();
            info.setText("");
            return;
        }
        Sounds.Sound s = Sounds.get(t.notes.instrument);
        trackName.setText(t.name.toUpperCase(java.util.Locale.ROOT) + " · " + t.notes.notes.size() + " NOTES · TAP TO CHANGE TRACK");
        soundName.setText(s == null ? "No sound" : s.name);
        buildRack();
        MidiFx.Chain chain = t.notes.fx;
        player.setChain(chain);
        info.setText(chain.empty() ? "" : "gold shadows: what the effects play");
        // a Scale lock in the chain lights its keys
        MidiFx.Effect scale = null;
        for (MidiFx.Effect e : chain.effects)
            if (e.on && e.kind.equals("scale"))
                scale = e;
        if (scale == null)
            keys.scale(-1, null);
        else {
            boolean[] pcs = new boolean[12];
            for (int pc : MidiFx.SCALES[(int) Math.round(scale.p[1])]) pcs[pc] = true;
            keys.scale((int) Math.round(scale.p[0]), pcs);
        }
        roll.invalidate();
    }

    // ------------------------------------------------------------------ tracks and sounds

    void chooseTrack() {
        final Project p = project();
        if (p == null)
            return;
        final ArrayList<String> ids = new ArrayList<String>();
        ArrayList<String> labels = new ArrayList<String>();
        for (Track t : p.tracks)
            if (t.notes.instrument >= 0) {
                ids.add(t.id);
                Sounds.Sound s = Sounds.get(t.notes.instrument);
                labels.add(t.name + (s == null ? "" : " · " + s.name));
            }
        labels.add("＋ New instrument track…");
        new Lux.Builder(a)
            .setTitle("Instrument tracks")
            .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w < ids.size()) {
                        track = ids.get(w);
                        refresh();
                        prepare(current().notes.instrument, false);
                    } else
                        SoundsDialog.show(a, KeysScreen.this, true);
                }
            })
            .show();
    }

    /** From the sounds browser: a sound for this track, or for a new track. */
    void soundChosen(int sound, boolean newTrack) {
        Project p = project();
        if (p == null)
            return;
        if (newTrack || current() == null) {
            if (p.tracks.size() >= 64) {
                Ui.toast(a, "The project already has 64 tracks.");
                return;
            }
            a.session.run(Edit.addInstrument(Sounds.get(sound).name, sound));
            track = p.tracks.get(p.tracks.size() - 1).id;
        } else if (current().notes.instrument != sound) {
            MidiNotes n = notes().copy();
            n.instrument = sound;
            save(n, "Sound: " + Sounds.get(sound).name);
        }
        refresh();
        prepare(sound, true);
    }

    /** Makes every key of a sound ready and loads it into the live keyboard. */
    void prepare(final int sound, boolean force) {
        final File dir = a.session.dir();
        final NativeEngine engine = a.session.engine();
        final Project project = project();
        if (engine == null || dir == null || project == null || preparing)
            return;
        if (!force && sound == loadedSound && dir.equals(loadedDir))
            return;
        if (a.session.recording.busy()) {
            Ui.toast(a, "Finish the take before changing the instrument.");
            return;
        }
        final Sounds.Sound s = Sounds.get(sound);
        if (s == null)
            return;
        if (s.acoustic() && !ai.jackknife.killayue.studio.SoundPacks.installed(dir, s)) {
            new Lux.Builder(a)
                .setTitle(s.name)
                .setMessage("This sound is a recorded instrument. Download it once and every project can use it.")
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        SoundsDialog.download(a, s, new Runnable() {
                            public void run() { prepare(sound, true); }
                        });
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
            return;
        }
        preparing = true;
        final AtomicBoolean cancel = new AtomicBoolean();
        final TextView status = Ui.text(a, "Tuning all 88 keys…", Skin.TEXT, 15);
        int pad = Ui.dp(a, 18);
        status.setPadding(pad, pad, pad, pad);
        final AlertDialog progress = new Lux.Builder(a)
                                         .setTitle(s.name)
                                         .setView(status)
                                         .setCancelable(false)
                                         .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                             public void onClick(DialogInterface d, int w) { cancel.set(true); }
                                         })
                                         .create();
        progress.show();
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                try {
                    Sounds.bank(dir, project.rate, sound, new Sounds.Progress() {
                        long last;
                        public void at(final int done, final int total) {
                            long now = System.currentTimeMillis();
                            if (now - last < 80 && done < total)
                                return;
                            last = now;
                            a.runOnUiThread(new Runnable() {
                                public void run() { status.setText("Tuning all 88 keys: " + done * 100 / total + "%"); }
                            });
                        }
                    }, cancel);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String why = error;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        preparing = false;
                        if (a.isFinishing() || a.isDestroyed())
                            return;
                        progress.dismiss();
                        if (cancel.get())
                            return;
                        if (why != null) {
                            Ui.toast(a, why);
                            return;
                        }
                        if (a.session.engine() != engine || a.session.project() != project)
                            return;
                        try {
                            engine.keyboard(dir, sound);
                            loadedSound = sound;
                            loadedDir = dir;
                        } catch (Exception e) {
                            Ui.toast(a, e.getMessage());
                        }
                    }
                });
            }
        }, "studio-keys-prepare").start();
    }

    // ------------------------------------------------------------------ the MIDI effects rack

    void buildRack() {
        rack.removeAllViews();
        final Track t = current();
        if (t == null)
            return;
        List<MidiFx.Effect> fx = t.notes.fx.effects;
        for (int i = 0; i < fx.size(); i++) rack.addView(pedal(fx.get(i), i), pedalParams());
        if (fx.size() < MidiFx.Chain.MAX)
            rack.addView(card("＋", "Add effect", null, true, new Runnable() {
                public void run() { addEffect(); }
            }, null), pedalParams());
        rack.addView(card("✦", "Presets", null, true, new Runnable() {
            public void run() { presets(); }
        }, null), pedalParams());
    }
    LinearLayout.LayoutParams pedalParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 118), Ui.dp(a, 74));
        lp.rightMargin = Ui.dp(a, 6);
        return lp;
    }
    View pedal(final MidiFx.Effect e, final int index) {
        return card(e.on ? "●" : "○", e.title(), summary(e), e.on, new Runnable() {
            public void run() { FxDialog.show(a, KeysScreen.this, index); }
        }, new Runnable() {
            public void run() { effectMenu(index); }
        });
    }
    /** A pedal: gold-framed, its LED lit when on. */
    View card(String led, String title, String detail, boolean on, final Runnable tap, final Runnable hold) {
        LinearLayout c = Ui.column(a);
        c.setBackground(new LuxFrame(12, 6));
        c.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = Ui.text(a, led + "  " + title, on ? Skin.HI : Skin.DIM, 14);
        l.setTypeface(Skin.text(), Typeface.BOLD);
        l.setSingleLine(true);
        if (on)
            l.setShadowLayer(Ui.dp(a, 6), 0, 0, Skin.glow(200));
        c.addView(l);
        if (detail != null) {
            TextView d = Ui.text(a, detail, Skin.DIM, 11);
            d.setMaxLines(2);
            c.addView(d);
        }
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { tap.run(); }
        });
        if (hold != null)
            c.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    hold.run();
                    return true;
                }
            });
        c.setContentDescription(title + (detail == null ? "" : ": " + detail));
        return c;
    }
    static String summary(MidiFx.Effect e) {
        MidiFx.Param[] ps = e.params();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(3, ps.length); i++) b.append(i == 0 ? "" : " · ").append(ps[i].show(e.p[i]));
        return b.toString();
    }
    void addEffect() {
        final String[] kinds = MidiFx.KINDS;
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) labels[i] = MidiFx.make(kinds[i]).title() + " — " + MidiFx.about(kinds[i]);
        new Lux.Builder(a)
            .setTitle("Add a MIDI effect")
            .setItems(labels, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    MidiNotes n = notes().copy();
                    try {
                        n.fx.add(kinds[w]);
                    } catch (IllegalArgumentException ex) {
                        Ui.toast(a, ex.getMessage());
                        return;
                    }
                    save(n, "Add " + MidiFx.make(kinds[w]).title());
                    FxDialog.show(a, KeysScreen.this, n.fx.effects.size() - 1);
                }
            })
            .show();
    }
    void presets() {
        new Lux.Builder(a)
            .setTitle("MIDI effect presets")
            .setItems(MidiFx.PRESETS, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    MidiNotes n = notes().copy();
                    n.fx = MidiFx.preset(w);
                    save(n, "Preset: " + MidiFx.PRESETS[w]);
                }
            })
            .show();
    }
    void effectMenu(final int index) {
        final MidiFx.Effect e = notes().fx.effects.get(index);
        new Lux.Builder(a)
            .setTitle(e.title())
            .setItems(new String[] {e.on ? "Bypass" : "Turn on", "Move earlier", "Move later", "Duplicate", "Remove"},
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, int w) {
                              MidiNotes n = notes().copy();
                              List<MidiFx.Effect> fx = n.fx.effects;
                              if (index >= fx.size())
                                  return;
                              if (w == 0)
                                  fx.get(index).on = !fx.get(index).on;
                              else if (w == 1 && index > 0)
                                  fx.add(index - 1, fx.remove(index));
                              else if (w == 2 && index < fx.size() - 1)
                                  fx.add(index + 1, fx.remove(index));
                              else if (w == 3 && fx.size() < MidiFx.Chain.MAX) {
                                  MidiFx.Chain one = MidiFx.Chain.read(java.util.Collections.singletonList(fx.get(index).json()));
                                  fx.add(index + 1, one.effects.get(0));
                              } else if (w == 4)
                                  fx.remove(index);
                              save(n, "MIDI effects");
                          }
                      })
            .show();
    }

    // ------------------------------------------------------------------ edits

    void save(MidiNotes n, String label) {
        if (current() == null)
            return;
        try {
            n.validate();
            if (!Json.write(n.json()).equals(Json.write(notes().json())))
                a.session.run(new NotesEdit(project(), track, n, label));
        } catch (IllegalArgumentException ex) {
            Ui.toast(a, ex.getMessage());
        }
        refresh();
    }

    void menu() {
        final String[] items = {"MIDI keyboards (USB or Bluetooth)…", "Bring in a MIDI file…", "Save as a MIDI file…",
                                "Save as a MIDI file, effects printed…", "Print effects into the notes",
                                "Quantise to the grid", "Snap new recordings: " + recorder.snapName(),
                                "Recording: " + (recorder.overdub ? "add to the part (overdub)" : "replace what is there"),
                                "Keys play at: " + (keys.fixedVelocity > 0 ? "a fixed level" : "where you touch them"),
                                "All notes off"};
        new Lux.Builder(a)
            .setTitle("Keys")
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    switch (w) {
                        case 0: midi.show(); break;
                        case 1: pickMidi(); break;
                        case 2:
                        case 3: saveMidi(w == 3); break;
                        case 4: print(); break;
                        case 5: {
                            MidiNotes n = notes().copy();
                            n.quantise(roll.grid);
                            save(n, "Quantise notes");
                            break;
                        }
                        case 6: recorder.snap = (recorder.snap + 1) % recorder.SNAPS.length; break;
                        case 7: recorder.overdub = !recorder.overdub; break;
                        case 8: keys.fixedVelocity = keys.fixedVelocity > 0 ? -1 : .8f; break;
                        default: allOff();
                    }
                }
            })
            .show();
    }
    void print() {
        Track t = current();
        if (t == null || t.notes.fx.empty()) {
            Ui.toast(a, "This track has no MIDI effects to print.");
            return;
        }
        MidiNotes n = new MidiNotes();
        n.instrument = t.notes.instrument;
        for (MidiNotes.Note played : t.notes.played(project())) {
            if (n.notes.size() >= MidiNotes.MAX)
                break;
            MidiNotes.Note c = n.add(played.beat, played.key);
            c.length = played.length;
            c.velocity = played.velocity;
        }
        save(n, "Print MIDI effects");
        Ui.toast(a, "The effects are now in the notes; the rack is empty.");
    }
    void pickMidi() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"audio/midi", "audio/x-midi", "audio/sp-midi", "application/x-midi", "application/octet-stream"});
        a.startActivityForResult(i, PICK_MIDI);
    }
    void saveMidi(boolean asHeard) {
        exportAsHeard = asHeard;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/midi");
        i.putExtra(Intent.EXTRA_TITLE, (project() == null ? "Killa YuE" : project().name) + ".mid");
        a.startActivityForResult(i, SAVE_MIDI);
    }
    void result(int request, int result, Intent data) {
        if (result != Activity.RESULT_OK || data == null || data.getData() == null)
            return;
        Uri uri = data.getData();
        if (request == PICK_MIDI)
            importMidi(uri);
        else if (request == SAVE_MIDI && project() != null) {
            try (OutputStream out = a.getContentResolver().openOutputStream(uri, "w")) {
                out.write(Smf.write(project(), exportAsHeard));
                Ui.toast(a, "Saved the instrument tracks as a MIDI file.");
            } catch (Exception e) {
                Ui.toast(a, "Could not save the MIDI file: " + e.getMessage());
            }
        }
    }
    void importMidi(Uri uri) {
        final Project p = project();
        if (p == null)
            return;
        final Smf.Song song;
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0;) {
                b.write(buf, 0, n);
                if (b.size() > 16 << 20)
                    throw new java.io.IOException("That MIDI file is too large.");
            }
            song = Smf.read(b.toByteArray());
        } catch (Exception e) {
            Ui.toast(a, "Could not read the MIDI file: " + e.getMessage());
            return;
        }
        if (song.parts.isEmpty()) {
            Ui.toast(a, "That MIDI file has no notes.");
            return;
        }
        final boolean empty = p.tracks.isEmpty() || (p.tracks.size() == 1 && p.tracks.get(0).clips.isEmpty() && p.tracks.get(0).notes.notes.isEmpty());
        int dropped = 0;
        for (Smf.Part part : song.parts) dropped += part.dropped;
        String msg = song.parts.size() + (song.parts.size() == 1 ? " part" : " parts") + " at " + Math.round(song.tempo) + " BPM. "
            + "Each becomes an instrument track with the nearest sound (recorded instruments you have not downloaded "
            + "play a designed sound until you do)."
            + (dropped > 0 ? " " + dropped + " notes outside the 88 keys are left out." : "");
        new Lux.Builder(a)
            .setTitle("Bring in the MIDI file")
            .setMessage(msg)
            .setPositiveButton(empty ? "Bring in, at its tempo" : "Bring in", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { addParts(song, empty); }
            })
            .setNeutralButton(empty ? "Keep this tempo" : null, empty ? new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { addParts(song, false); }
            } : null)
            .setNegativeButton("Cancel", null)
            .show();
    }
    void addParts(Smf.Song song, boolean useTempo) {
        Project p = project();
        if (p == null)
            return;
        Project q = p.copy();
        if (useTempo) {
            q.performance.tempo = Math.max(20, Math.min(300, song.tempo));
            q.performance.tempos.clear();
            TempoMap map = new TempoMap(q.rate, q.performance);
            for (java.util.Map.Entry<Double, Double> e : song.tempos.entrySet())
                q.performance.tempos.put(map.frameAt(e.getKey()), Math.max(20, Math.min(300, e.getValue())));
        }
        String firstNew = null;
        for (Smf.Part part : song.parts) {
            if (q.tracks.size() >= 64)
                break;
            int sound = Smf.sound(part);
            Sounds.Sound s = Sounds.get(sound);
            if (s.acoustic() && !ai.jackknife.killayue.studio.SoundPacks.installed(a.session.dir(), s))
                sound = Sounds.fallback(sound);
            Track t = q.addTrack(part.name.isEmpty() ? Sounds.get(sound).name : part.name);
            t.role = "instrument:" + sound;
            t.notes = Smf.notes(part, sound);
            if (firstNew == null)
                firstNew = t.id;
        }
        try {
            q.validate();
            a.session.run(ClipEdits.change(p, q, "Bring in MIDI file"));
        } catch (Exception e) {
            Ui.toast(a, e.getMessage());
            return;
        }
        if (firstNew != null)
            track = firstNew;
        refresh();
        if (current() != null)
            prepare(current().notes.instrument, true);
    }

    // ------------------------------------------------------------------ playing

    public void note(int key, float velocity, boolean on) {
        touched[key] = on;
        input(key, velocity, on);
    }
    public void note(int channel, int key, int velocity, boolean on) {
        if (key < 0 || key > 127)
            return;
        midiHeld[channel][key] = on;
        if (on)
            keys.reveal(key);
        input(key, velocity / 127f, on);
    }
    public void control(int channel, int cc, int value) {
        if (cc == 64)
            pedal(value >= 64);
        else if (cc == 120 || cc == 123)
            allOff();
    }
    /** A key pressed or let go, from the screen or a MIDI keyboard; the pedal holds the let-go ones. */
    void input(int key, float velocity, boolean on) {
        if (on) {
            deferred[key] = false;
            send(key, velocity, true);
            return;
        }
        if (touched[key])
            return;
        for (int ch = 0; ch < 16; ch++)
            if (midiHeld[ch][key])
                return;
        if (sustain)
            deferred[key] = true;
        else
            send(key, 0, false);
    }
    void pedal(boolean down) {
        sustain = down;
        sustainButton.setBackground(new Skin.Plate(down, down));
        sustainButton.setTextColor(down ? Skin.HI : Skin.LO);
        if (!down)
            for (int k = 0; k < 128; k++)
                if (deferred[k]) {
                    deferred[k] = false;
                    send(k, 0, false);
                }
    }
    void send(int key, float velocity, boolean on) {
        double beat = player.beat();
        recorder.note(beat, key, velocity, on);
        player.input(beat, key, velocity, on);
    }
    public void allOff() {
        java.util.Arrays.fill(touched, false);
        java.util.Arrays.fill(deferred, false);
        for (boolean[] h : midiHeld) java.util.Arrays.fill(h, false);
        sustain = false;
        player.reset();
        NativeEngine e = a.session.engine();
        if (e != null)
            e.notesOff();
        keys.unlightAll();
    }

    /**
     * Runs the track's MIDI effects in real time on its own thread and plays what comes out on the live keyboard.
     * The beat follows the transport while it plays, and a free clock at the project's tempo while it is stopped.
     */
    final class Player implements Runnable {
        MidiFx.Chain.Live live;
        String chainJson = "";
        Thread thread;
        long anchorPos, anchorNanos;
        boolean playing;
        double freeOrigin = Double.NaN;
        synchronized void setChain(MidiFx.Chain chain) {
            String j = Json.write(chain.json());
            if (j.equals(chainJson) && live != null)
                return;
            chainJson = j;
            NativeEngine e = a.session.engine();
            if (e != null)
                e.notesOff();
            live = chain.empty() ? null : MidiFx.Chain.read(chain.json()).new Live(tempo());
            notifyAll();
        }
        double tempo() { return project() == null ? 120 : project().performance.tempo; }
        synchronized void anchor(NativeEngine.Status s) {
            if (s.playing != playing || Math.abs(s.position - estimate()) > a.session.rate() / 20) {
                anchorPos = s.position;
                anchorNanos = System.nanoTime();
            }
            playing = s.playing;
        }
        long estimate() { return anchorPos + (playing ? (System.nanoTime() - anchorNanos) * a.session.rate() / 1000000000L : 0); }
        /** Now, in beats: the transport (less what is still in the output buffer) or the free clock. */
        synchronized double beat() {
            Project p = project();
            if (p == null)
                return 0;
            NativeEngine.Status s = a.session.status();
            if (s != null && s.playing) {
                long latency = Math.round(s.outputLatencyMs * a.session.rate() / 1000);
                return new TempoMap(p.rate, p.performance).beatAt(Math.max(0, estimate() - latency));
            }
            return System.nanoTime() / 1e9 * tempo() / 60;
        }
        synchronized void input(double beat, int key, float velocity, boolean on) {
            NativeEngine e = a.session.engine();
            if (e == null)
                return;
            if (live == null) {
                if (GrandKeysView.valid(key))
                    if (!e.note(key, velocity, on) && !on)
                        e.notesOff();
                light(key, on);
                return;
            }
            live.note(beat, key, velocity, on);
            drain(beat);
            if (thread == null) {
                thread = new Thread(this, "studio-midi-effects");
                thread.setPriority(Thread.MAX_PRIORITY);
                thread.start();
            }
            notifyAll();
        }
        void drain(double beat) {
            NativeEngine e = a.session.engine();
            if (live == null || e == null)
                return;
            for (int[] s : live.poll(beat)) {
                if (!GrandKeysView.valid(s[0]))
                    continue;
                boolean on = s[2] == 1;
                if (!e.note(s[0], s[1] / 1000f, on) && !on)
                    e.notesOff();
                light(s[0], on);
            }
        }
        void light(final int key, final boolean on) {
            keys.post(new Runnable() {
                public void run() { keys.light(key, on); }
            });
        }
        synchronized void reset() {
            if (live != null)
                live = MidiFx.Chain.read(Json.parse(chainJson)).new Live(tempo());
        }
        public void run() {
            synchronized (this) {
                while (!a.isDestroyed()) {
                    try {
                        if (live == null) {
                            wait(500);
                            continue;
                        }
                        live.tempo(tempo());
                        double now = beat();
                        drain(now);
                        double due = live.due();
                        long waitMs = due == Double.POSITIVE_INFINITY ? 200
                                      : Math.max(1, Math.min(200, (long) ((due - now) * 60000 / tempo())));
                        wait(waitMs);
                    } catch (InterruptedException e) {
                        return;
                    } catch (RuntimeException e) {
                        // an effect that misbehaves must not stop the keyboard: start the chain afresh
                        reset();
                    }
                }
            }
        }
    }

    /** Recording: what you play (before the effects), in beats, written into the track when you stop. */
    final class Recorder {
        final double[] SNAPS_BEATS = {0, .5, .25, 1 / 6.0, .125};
        final String[] SNAPS = {"off (as played)", "1/8", "1/16", "1/16 triplets", "1/32"};
        boolean on, overdub = true;
        int snap;
        double from, until;
        final ArrayList<MidiNotes.Note> taken = new ArrayList<MidiNotes.Note>();
        final MidiNotes.Note[] open = new MidiNotes.Note[128];
        String snapName() { return SNAPS[snap]; }
        void start(double beat) {
            taken.clear();
            java.util.Arrays.fill(open, null);
            from = beat;
            on = true;
        }
        void note(double beat, int key, float velocity, boolean down) {
            if (!on || !GrandKeysView.valid(key))
                return;
            if (down) {
                if (open[key] != null)
                    close(open[key], beat);
                MidiNotes.Note n = new MidiNotes.Note();
                n.key = key;
                n.beat = Math.max(0, beat);
                n.velocity = Math.max(.01f, Math.min(1, velocity));
                open[key] = n;
                taken.add(n);
            } else if (open[key] != null) {
                close(open[key], beat);
                open[key] = null;
            }
        }
        void close(MidiNotes.Note n, double beat) { n.length = Math.max(1 / 64.0, Math.min(256, beat - n.beat)); }
        MidiNotes stop(double beat) {
            on = false;
            until = beat;
            for (int k = 0; k < 128; k++)
                if (open[k] != null) {
                    close(open[k], beat);
                    open[k] = null;
                }
            MidiNotes n = notes().copy();
            if (!overdub)
                for (int i = n.notes.size() - 1; i >= 0; i--) {
                    MidiNotes.Note x = n.notes.get(i);
                    if (x.beat < until && x.beat + x.length > from)
                        n.notes.remove(i);
                }
            double g = SNAPS_BEATS[snap];
            for (MidiNotes.Note t : taken) {
                if (n.notes.size() >= MidiNotes.MAX)
                    break;
                double beatAt = g > 0 ? Math.round(t.beat / g) * g : t.beat;
                MidiNotes.Note c = n.add(Math.max(0, Math.min(100000, beatAt)), t.key);
                c.length = Math.max(1 / 64.0, g > 0 ? Math.max(g, Math.round(t.length / g) * g) : t.length);
                c.velocity = t.velocity;
            }
            return n;
        }
    }
    void toggleRecord() {
        Project p = project();
        if (p == null || current() == null) {
            Ui.toast(a, "Choose an instrument track first.");
            return;
        }
        if (!recorder.on) {
            if (a.session.recording.busy()) {
                Ui.toast(a, "An audio take is recording.");
                return;
            }
            NativeEngine.Status s = a.session.status();
            int countIn = p.performance.countIn;
            if (s != null && !s.playing && countIn > 0) {
                // count in from the bars before the playhead, so the first note can land on the downbeat
                TempoMap map = new TempoMap(p.rate, p.performance);
                double at = map.beatAt(s.position);
                a.session.seek(map.frameAt(Math.max(0, at - countIn * p.performance.beats * 4.0 / p.performance.beatUnit)));
            }
            recorder.start(s != null ? new TempoMap(p.rate, p.performance).beatAt(s.position) : 0);
            if (s == null || !s.playing)
                a.session.play();
            Ui.toast(a, "Recording notes" + (recorder.overdub ? " (adding to the part)" : " (replacing)"));
        } else {
            MidiNotes n = recorder.stop(player.beat());
            a.session.stop();
            int added = recorder.taken.size();
            if (added == 0) {
                Ui.toast(a, "Nothing was played.");
                return;
            }
            save(n, "Record " + added + (added == 1 ? " note" : " notes"));
            Ui.toast(a, "Recorded " + added + (added == 1 ? " note." : " notes."));
        }
    }

    // ------------------------------------------------------------------ the piano roll

    final class Roll extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        double first = 0, span = 8, grid = .25;
        int top = 79, rows = 24, selected = -1, mode;
        final String[] modes = {"Draw / move", "Resize", "Velocity", "Erase"};
        MidiNotes preview;
        MidiNotes.Note moving;
        double downBeat, oldBeat, oldLength;
        int downKey, oldKey;
        float downY, downX, oldVelocity;
        boolean dragged, panning;
        float panX, panY;
        Roll() {
            super(a);
            setContentDescription("Piano roll. Tap empty space to draw a note; drag a note to move it. Two fingers scroll.");
            setOnLongClickListener(null);
        }
        float left() { return Ui.dp(a, 34); }
        float head() { return Ui.dp(a, 24); }
        float row() { return (getHeight() - head()) / (float) rows; }
        float x(double beat) { return left() + (float) ((beat - first) / span) * (getWidth() - left()); }
        float y(int key) { return head() + (top - key) * row(); }
        double beat(float x) { return Math.max(0, Math.min(100000, first + (x - left()) / Math.max(1, getWidth() - left()) * span)); }
        int pitch(float y) { return Math.max(MidiNotes.LOW, Math.min(MidiNotes.HIGH, top - (int) Math.floor((y - head()) / row()))); }
        double snap(double v) { return Math.round(v / grid) * grid; }
        void cancel() {
            preview = null;
            moving = null;
            invalidate();
        }
        protected void onDraw(Canvas c) {
            c.drawColor(Skin.DECK);
            paint.setTextSize(Ui.dp(a, 9));
            paint.setTypeface(Skin.text());
            for (int r = 0; r < rows; r++) {
                int key = top - r;
                boolean black = key % 12 == 1 || key % 12 == 3 || key % 12 == 6 || key % 12 == 8 || key % 12 == 10;
                paint.setColor(black ? Skin.CARD : Skin.DECK);
                c.drawRect(left(), y(key), getWidth(), y(key) + row(), paint);
                paint.setColor(black ? 0xff1a1714 : 0xffe9e0cc);
                c.drawRect(0, y(key) + .5f, left() - 2, y(key) + row() - .5f, paint);
                if (key % 12 == 0) {
                    paint.setColor(0xff6b5a3a);
                    c.drawText(GrandKeysView.name(key), 3, y(key) + row() * .8f, paint);
                    paint.setColor(Skin.EDGE);
                    c.drawLine(left(), y(key) + row(), getWidth(), y(key) + row(), paint);
                }
            }
            // the ruler: bars and beats
            paint.setColor(0xff0e0c0a);
            c.drawRect(0, 0, getWidth(), head(), paint);
            Project p = project();
            int perBar = p == null ? 4 : Math.max(1, p.performance.beats * 4 / p.performance.beatUnit);
            for (double b = Math.ceil(first / grid) * grid; b <= first + span; b += grid) {
                boolean bar = Math.abs(b / perBar - Math.rint(b / perBar)) < 1e-6, beatLine = Math.abs(b - Math.rint(b)) < 1e-6;
                paint.setColor(bar ? Skin.ACCENT : beatLine ? Skin.EDGE : Skin.FAINT);
                c.drawLine(x(b), head(), x(b), getHeight(), paint);
                if (bar) {
                    paint.setColor(Skin.HI);
                    c.drawText("" + (int) (Math.rint(b / perBar) + 1), x(b) + 3, head() * .7f, paint);
                }
            }
            MidiNotes shown = preview == null ? notes() : preview;
            // the effects' result, faint, under the written notes
            if (preview == null && !shown.fx.empty() && p != null)
                for (MidiNotes.Note n : shown.played(p)) drawNote(c, n, Skin.glow(70), false);
            for (MidiNotes.Note n : shown.notes) drawNote(c, n, n.id == selected ? Skin.HI : Skin.ACCENT, true);
            NativeEngine.Status status = a.session.status();
            if (status != null && p != null) {
                double b = new TempoMap(p.rate, p.performance).beatAt(status.position);
                if (b >= first && b <= first + span) {
                    paint.setColor(recorder.on ? 0xffff5070 : Skin.TEXT);
                    paint.setStrokeWidth(Ui.dp(a, 2));
                    c.drawLine(x(b), 0, x(b), getHeight(), paint);
                    paint.setStrokeWidth(0);
                } else if (status.playing && b > first + span)
                    first = Math.floor(b / span) * span;  // follow the playhead page by page
            }
        }
        void drawNote(Canvas c, MidiNotes.Note n, int color, boolean solid) {
            if (n.key > top || n.key <= top - rows || n.beat + n.length < first || n.beat > first + span)
                return;
            float l = Math.max(left(), x(n.beat)), r = Math.min(getWidth(), x(n.beat + n.length)), rad = Ui.dp(a, 3);
            paint.setColor(color);
            c.drawRoundRect(l, y(n.key) + 1, Math.max(l + 3, r), y(n.key) + row() - 1, rad, rad, paint);
            if (solid) {
                paint.setColor(0x66000000);
                c.drawRect(l + 2, y(n.key) + row() * (1 - n.velocity * .8f), Math.max(l + 4, l + 2 + (r - l) * .12f), y(n.key) + row() - 2, paint);
            }
        }
        final Runnable tick = new Runnable() {
            public void run() {
                invalidate();
                postDelayed(this, 50);
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
            if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
                cancel();
                panning = true;
                panX = (e.getX(0) + e.getX(1)) / 2;
                panY = (e.getY(0) + e.getY(1)) / 2;
                return true;
            }
            if (panning) {
                if (action == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) {
                    float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
                    double db = -(mx - panX) / Math.max(1, getWidth() - left()) * span;
                    int dk = Math.round((my - panY) / row());
                    if (Math.abs(db) >= grid) {
                        first = Math.max(0, Math.min(100000 - span, first + db));
                        panX = mx;
                    }
                    if (dk != 0) {
                        top = Math.max(MidiNotes.LOW + rows - 1, Math.min(MidiNotes.HIGH, top + dk));
                        panY = my;
                    }
                    invalidate();
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    panning = false;
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                cancel();
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                if (e.getY() < head()) {
                    // the ruler: move the playhead there
                    Project p = project();
                    if (p != null)
                        a.session.seek(new TempoMap(p.rate, p.performance).frameAt(snap(beat(e.getX()))));
                    return true;
                }
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
                if (mode == 3) {
                    if (moving != null)
                        preview.notes.remove(moving);
                    moving = null;
                    invalidate();
                    return true;
                }
                if (moving == null && mode == 0) {
                    if (preview.notes.size() >= MidiNotes.MAX) {
                        cancel();
                        Ui.toast(a, "This track already has 4096 notes.");
                        return true;
                    }
                    moving = preview.add(Math.floor(downBeat / grid) * grid, downKey);
                    moving.length = grid;
                    audition(downKey);
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
            if (mode == 3 && preview != null && action == MotionEvent.ACTION_MOVE) {
                double b = beat(e.getX());
                int k = pitch(e.getY());
                for (int i = preview.notes.size() - 1; i >= 0; i--) {
                    MidiNotes.Note n = preview.notes.get(i);
                    if (n.key == k && b >= n.beat && b <= n.beat + n.length)
                        preview.notes.remove(i);
                }
                invalidate();
                return true;
            }
            if (preview != null && moving != null && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                && (dragged || Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > Ui.dp(a, 6))) {
                dragged = true;
                if (mode == 0) {
                    moving.beat = Math.max(0, Math.min(100000, snap(oldBeat + beat(e.getX()) - downBeat)));
                    int k = Math.max(MidiNotes.LOW, Math.min(MidiNotes.HIGH, oldKey + pitch(e.getY()) - downKey));
                    if (k != moving.key)
                        audition(k);
                    moving.key = k;
                } else if (mode == 1)
                    moving.length = Math.max(grid, Math.min(256, snap(oldLength + beat(e.getX()) - downBeat)));
                else if (mode == 2)
                    moving.velocity = Math.max(.01f, Math.min(1, oldVelocity + (downY - e.getY()) / Ui.dp(a, 160)));
                invalidate();
            }
            if (action == MotionEvent.ACTION_UP) {
                MidiNotes done = preview;
                preview = null;
                moving = null;
                if (done != null)
                    save(done, mode == 3 ? "Erase notes" : "Edit piano roll");
                performClick();
                return true;
            }
            return true;
        }
        void audition(final int key) {
            NativeEngine e = a.session.engine();
            NativeEngine.Status s = a.session.status();
            if (e == null || (s != null && s.playing))
                return;
            e.note(key, .7f, true);
            postDelayed(new Runnable() {
                public void run() {
                    NativeEngine en = a.session.engine();
                    if (en != null)
                        en.note(key, 0, false);
                }
            }, 220);
        }
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
