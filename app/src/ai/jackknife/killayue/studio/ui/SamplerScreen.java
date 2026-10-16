package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.*;
import ai.jackknife.killayue.studio.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;
/** The Chamber: playable pads and their saved step pattern. All gestures enter the native event queue. */
final class SamplerScreen implements Screen, StudioSession.Listener {
    final StudioActivity a;
    final LinearLayout root, steps;
    final ScrollView page;
    final Button[] pads = new Button[16];
    int selected;
    SamplerScreen(final StudioActivity a) {
        this.a = a;
        root = Ui.column(a);
        page = new ScrollView(a);
        page.setFillViewport(true);
        page.addView(root);
        root.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
        TextView title = Ui.text(a, "THE CHAMBER", Skin.HI, 20);
        root.addView(title);
        root.addView(Ui.text(a, "Tap to play · hold to shape · slice any clip into the pads", Skin.DIM, 13));
        for (int row = 0; row < 4; row++) {
            LinearLayout line = Ui.row(a);
            line.setMotionEventSplittingEnabled(true);
            root.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(a, 80)));
            for (int col = 0; col < 4; col++) {
                final int k = row * 4 + col;
                final Button b = Ui.button(a, "" + (k + 1), false);
                pads[k] = b;
                b.setMinHeight(Ui.dp(a, 48));
                b.setContentDescription("Pad " + (k + 1));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                lp.setMargins(3, 3, 3, 3);
                line.addView(b, lp);
                b.setOnTouchListener(new View.OnTouchListener() {
                    public boolean onTouch(View v, android.view.MotionEvent e) {
                        if (a.session.engine() == null)
                            return false;
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            selected = k;
                            a.session.engine().pad(k, Math.max(.2f, Math.min(1, e.getPressure())), true);
                            pattern();
                        }
                        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
                            a.session.engine().pad(k, 0, false);
                        return false;
                    }
                });
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        if (p() == null)
                            return true;
                        selected = k;
                        shape();
                        return true;
                    }
                });
            }
        }
        LinearLayout tools = Ui.row(a);
        root.addView(tools);
        add(tools, "Sample", new Runnable() {
            public void run() { source(false); }
        });
        add(tools, "Chop", new Runnable() {
            public void run() { source(true); }
        });
        add(tools, "Kit", new Runnable() {
            public void run() {
                Project q = p().copy();
                for (int i = 0; i < 16; i++)
                    q.performance.pads[i] = new Performance.Pad();
                save(q, "Original drum kit");
            }
        });
        add(tools, "Shape", new Runnable() {
            public void run() { shape(); }
        });
        LinearLayout rhythm = Ui.row(a);
        root.addView(rhythm);
        add(rhythm, "Pattern", new Runnable() {
            public void run() {
                Project q = p().copy();
                q.performance.pattern = !q.performance.pattern;
                save(q, "Pattern playback");
                if (q.performance.pattern)
                    a.session.play();
            }
        });
        add(rhythm, "16 / 32", new Runnable() {
            public void run() {
                Project q = p().copy();
                q.performance.patternLength = q.performance.patternLength == 16 ? 32 : 16;
                save(q, "Pattern length");
            }
        });
        add(rhythm, "Swing", new Runnable() {
            public void run() {
                StudioDialogs.number(a, "Swing (0–75%)", p().performance.swing * 100, new StudioDialogs.Number() {
                    public void value(double v) {
                        Project q = p().copy();
                        q.performance.swing = v / 100;
                        save(q, "Swing");
                    }
                });
            }
        });
        steps = Ui.column(a);
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.addView(steps);
        root.addView(scroll);
        a.session.listen(this);
    }
    Project p() { return a.session.project(); }
    void save(Project q, String name) { a.session.run(ClipEdits.change(p(), q, name)); }
    void add(LinearLayout row, String name, final Runnable run) {
        Button b = Ui.button(a, name, false);
        b.setTextSize(12);
        b.setMinHeight(Ui.dp(a, 48));
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (p() != null)
                    try {
                        run.run();
                    } catch (Exception e) {
                        Ui.toast(a, e.getMessage());
                    }
            }
        });
    }
    void source(final boolean chop) {
        final ArrayList<Clip> clips = new ArrayList<Clip>();
        ArrayList<String> names = new ArrayList<String>();
        for (Track t : p().tracks)
            for (Clip c : t.clips) {
                clips.add(c);
                names.add(t.name + " · " + String.format(java.util.Locale.ROOT, "%.1fs", c.length / (double)p().rate));
            }
        new Lux.Builder(a)
            .setTitle(chop ? "Slice a clip into 16 pads" : "Audio for pad " + (selected + 1))
            .setItems(names.toArray(new String[0]),
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, int w) {
                              final Clip c = clips.get(w);
                              if (chop) {
                                  final Project before = p();
                                  final String text = before.toText();
                                  final java.io.File dir = a.session.dir();
                                  Ui.toast(a, "Finding transients…");
                                  new Thread(new Runnable() {
                                      public void run() {
                                          try {
                                              final long[] slices =
                                                  TransientSlices.find(new java.io.File(dir, c.file), c.offset, c.length, 16);
                                              a.runOnUiThread(new Runnable() {
                                                  public void run() {
                                                      if (a.session.project() != before || !before.toText().equals(text)) {
                                                          Ui.toast(a, "The project changed; choose the clip again.");
                                                          return;
                                                      }
                                                      Project q = before.copy();
                                                      for (int i = 0; i < slices.length - 1; i++) {
                                                          Performance.Pad z = q.performance.pads[i];
                                                          z.file = c.file;
                                                          z.offset = c.offset + slices[i];
                                                          z.length = slices[i + 1] - slices[i];
                                                          z.name = "Slice " + (i + 1);
                                                      }
                                                      save(q, "Chop transients into pads");
                                                  }
                                              });
                                          } catch (final Exception e) {
                                              a.runOnUiThread(new Runnable() {
                                                  public void run() { Ui.toast(a, e.getMessage()); }
                                              });
                                          }
                                      }
                                  }, "studio-slices").start();
                                  return;
                              }
                              Project q = p().copy();
                              int first = chop ? 0 : selected, count = chop ? 16 : 1;
                              for (int i = 0; i < count; i++) {
                                  Performance.Pad pad = q.performance.pads[first + i];
                                  pad.file = c.file;
                                  pad.offset = c.offset + c.length * i / count;
                                  pad.length = c.length * (i + 1) / count - c.length * i / count;
                                  pad.name = chop ? "Slice " + (i + 1) : "Sample";
                                  pad.attack = .002f;
                                  pad.release = .02f;
                              }
                              save(q, chop ? "Chop clip into pads" : "Assign sample");
                          }
                      })
            .show();
    }
    void shape() {
        final String[] labels = {"One-shot / gate / loop", "Tune (semitones)", "Attack (ms)",         "Decay (ms)", "Sustain (%)",
                                 "Release (ms)",           "Low-pass (Hz)",    "Choke group (0 off)", "Reverse"};
        new Lux.Builder(a)
            .setTitle("Pad " + (selected + 1))
            .setItems(labels,
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, final int w) {
                              if (w == 0) {
                                  new Lux.Builder(a)
                                      .setTitle("Playback mode")
                                      .setItems(new String[] {"One-shot", "Gate (hold)", "Loop (hold)"},
                                                new DialogInterface.OnClickListener() {
                                                    public void onClick(DialogInterface d, int mode) {
                                                        Project q = p().copy();
                                                        q.performance.pads[selected].mode = mode;
                                                        save(q, "Pad mode");
                                                    }
                                                })
                                      .show();
                                  return;
                              }
                              if (w == 8) {
                                  Project q = p().copy();
                                  q.performance.pads[selected].reverse = !q.performance.pads[selected].reverse;
                                  save(q, "Reverse pad");
                                  return;
                              }
                              Performance.Pad pad = p().performance.pads[selected];
                              double[] values = {
                                  0,          pad.tune, pad.attack * 1000, pad.decay * 1000, pad.sustain * 100, pad.release * 1000,
                                  pad.cutoff, pad.choke};
                              StudioDialogs.number(a, labels[w], values[w], new StudioDialogs.Number() {
                                  public void value(double v) {
                                      Project q = p().copy();
                                      Performance.Pad z = q.performance.pads[selected];
                                      switch (w) {
                                      case 1:
                                          z.tune = (float)v;
                                          break;
                                      case 2:
                                          z.attack = (float)v / 1000;
                                          break;
                                      case 3:
                                          z.decay = (float)v / 1000;
                                          break;
                                      case 4:
                                          z.sustain = (float)v / 100;
                                          break;
                                      case 5:
                                          z.release = (float)v / 1000;
                                          break;
                                      case 6:
                                          z.cutoff = (float)v;
                                          break;
                                      case 7:
                                          z.choke = (int)v;
                                          break;
                                      }
                                      save(q, "Shape pad");
                                  }
                              });
                          }
                      })
            .show();
    }
    void pattern() {
        steps.removeAllViews();
        if (p() == null)
            return;
        steps.addView(Ui.text(a, "Pad " + (selected + 1) + " · steps: off / soft / hard" + (p().performance.pattern ? " · PLAYING" : ""),
                              Skin.HI, 12));
        for (int row = 0; row < 2; row++) {
            LinearLayout line = Ui.row(a);
            steps.addView(line);
            for (int col = 0; col < p().performance.patternLength / 2; col++) {
                final int step = row * (p().performance.patternLength / 2) + col;
                float v = p().performance.steps[selected][step];
                Button b = Ui.button(a, "" + (step + 1), v > 0);
                b.setAlpha(v == 0 ? .45f : v < 1 ? .7f : 1);
                line.addView(b, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Project q = p().copy();
                        float old = q.performance.steps[selected][step];
                        q.performance.steps[selected][step] = old == 0 ? .5f : old < 1 ? 1 : 0;
                        save(q, "Step velocity");
                    }
                });
            }
        }
    }
    public void projectChanged() {
        if (p() == null)
            return;
        for (int i = 0; i < 16; i++) {
            Performance.Pad d = p().performance.pads[i];
            String label = d.name.isEmpty() ? new String[] {"Kick", "Snare", "Hat", "Tom"}[i % 4] : d.name;
            pads[i].setText((i + 1) + "\n" + label);
        }
        pattern();
    }
    public String name() { return "Pads"; }
    public View view() { return page; }
    public void shown() { projectChanged(); }
    public void hidden() {
        if (a.session.engine() != null)
            for (int i = 0; i < 16; i++)
                a.session.engine().pad(i, 0, false);
    }
    public void frame(NativeEngine.Status s) {}
}
