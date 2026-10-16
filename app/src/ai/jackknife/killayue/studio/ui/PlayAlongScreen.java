package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.*;
import ai.jackknife.killayue.studio.*;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.projection.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.*;
final class PlayAlongScreen implements Screen, StudioSession.Listener {
    static final int MIC = 184, CAPTURE = 185;
    final StudioActivity a;
    final LinearLayout root;
    final ScrollView page;
    final TextView state, meter;
    final Button record;
    private String trackKey = "";
    private LinearLayout tracks;
    private long lastTap;
    private double tapped;
    private boolean monitoring;
    PlayAlongScreen(final StudioActivity a) {
        this.a = a;
        root = Ui.column(a);
        page = new ScrollView(a);
        page.setFillViewport(true);
        page.addView(root);
        root.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        state = Ui.text(a, "Arm a track, then record over your song.", Skin.TEXT, 16);
        root.addView(state);
        meter = Ui.text(a, "Input —", Skin.HI, 18);
        root.addView(meter);
        record
        = button("● Record", new Runnable() {
            public void run() {
                try {
                    if (a.session.recording.active())
                        a.session.stop();
                    else
                        a.session.recording.start();
                } catch (Exception e) {
                    Ui.toast(a, e.getMessage());
                }
            }
        });
        LinearLayout row = Ui.row(a);
        root.addView(row);
        add(row, "+ Track", new Runnable() {
            public void run() {
                Project p = a.session.project();
                if (p == null)
                    return;
                Project q = p.copy();
                Track t = q.addTrack("My take");
                t.role = "take";
                a.session.run(ClipEdits.change(p, q, "Add recording track"));
            }
        });
        add(row, "Input", new Runnable() {
            public void run() { input(); }
        });
        add(row, "Calibrate", new Runnable() {
            public void run() { calibrate(); }
        });
        LinearLayout tempo = Ui.row(a);
        root.addView(tempo);
        add(tempo, "Tempo", new Runnable() {
            public void run() {
                StudioDialogs.number(a, "Tempo (BPM)", p().performance.tempo, new StudioDialogs.Number() {
                    public void value(double v) {
                        Project q = p().copy();
                        q.performance.tempo = v;
                        save(q, "Tempo");
                    }
                });
            }
        });
        add(tempo, "Tap", new Runnable() {
            public void run() {
                long now = System.currentTimeMillis();
                if (lastTap > 0 && now - lastTap < 3000) {
                    double bpm = 60000.0 / (now - lastTap);
                    tapped = tapped == 0 ? bpm : .6 * tapped + .4 * bpm;
                    Project q = p().copy();
                    q.performance.tempo = Math.max(20, Math.min(400, tapped));
                    save(q, "Tap tempo");
                }
                lastTap = now;
            }
        });
        add(tempo, "Metre", new Runnable() {
            public void run() {
                new Lux.Builder(a)
                    .setTitle("Metre")
                    .setItems(new String[] {"3/4", "4/4", "6/8", "7/8"},
                              new DialogInterface.OnClickListener() {
                                  public void onClick(DialogInterface d, int w) {
                                      Project q = p().copy();
                                      q.performance.beats = new int[] {3, 4, 6, 7}[w];
                                      q.performance.beatUnit = w < 2 ? 4 : 8;
                                      save(q, "Metre");
                                  }
                              })
                    .show();
            }
        });
        LinearLayout options = Ui.row(a);
        root.addView(options);
        add(options, "Click", new Runnable() {
            public void run() {
                Project q = p().copy();
                q.performance.click = !q.performance.click;
                save(q, "Metronome");
            }
        });
        add(options, "Count-in", new Runnable() {
            public void run() {
                Project q = p().copy();
                q.performance.countIn = (q.performance.countIn + 1) % 3;
                save(q, "Count-in");
            }
        });
        add(options, "Punch", new Runnable() {
            public void run() {
                Project q = p().copy();
                q.performance.punch = !q.performance.punch;
                q.performance.punchStart = q.loopStart;
                q.performance.punchEnd = q.loopEnd;
                try {
                    save(q, "Punch range");
                } catch (Exception e) {
                    Ui.toast(a, "Mark a range on the Arrange ruler first.");
                }
            }
        });
        button("Input monitoring (headphones)", new Runnable() {
            public void run() {
                monitoring = !monitoring;
                a.session.engine().monitor(monitoring);
                Ui.toast(a, monitoring ? "Monitoring on — use headphones." : "Monitoring off.");
            }
        });
        button("Tempo change at playhead", new Runnable() {
            public void run() {
                StudioDialogs.number(a, "New tempo here (BPM)", new TempoMap(p().rate, p().performance).bpmAt(a.session.status().position),
                                     new StudioDialogs.Number() {
                                         public void value(double v) {
                                             Project q = p().copy();
                                             q.performance.tempos.put(a.session.status().position, v);
                                             save(q, "Tempo change");
                                         }
                                     });
            }
        });
        button("Marker at playhead", new Runnable() {
            public void run() {
                Project q = p().copy();
                q.performance.markers.put(a.session.status().position, "Marker " + (q.performance.markers.size() + 1));
                save(q, "Add marker");
            }
        });
        button("Jump to marker", new Runnable() {
            public void run() {
                final ArrayList<Long> positions = new ArrayList<Long>(p().performance.markers.keySet());
                String[] labels = new String[positions.size()];
                for (int i = 0; i < labels.length; i++)
                    labels[i] = p().performance.markers.get(positions.get(i));
                new Lux.Builder(a)
                    .setTitle("Markers")
                    .setItems(labels,
                              new DialogInterface.OnClickListener() {
                                  public void onClick(DialogInterface d, int w) { a.session.seek(positions.get(w)); }
                              })
                    .show();
            }
        });
        tracks = Ui.column(a);
        root.addView(tracks);
        a.session.listen(this);
    }
    Project p() { return a.session.project(); }
    void save(Project q, String title) { a.session.run(ClipEdits.change(p(), q, title)); }
    Button button(String name, Runnable run) { return add(root, name, run); }
    Button add(LinearLayout parent, String name, final Runnable run) {
        Button b = Ui.button(a, name, false);
        b.setMinHeight(Ui.dp(a, 48));
        parent.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        if (parent == root)
            b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (p() != null)
                    run.run();
            }
        });
        return b;
    }
    void input() {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MIC);
            return;
        }
        final ArrayList<Integer> ids = new ArrayList<Integer>();
        final ArrayList<String> names = new ArrayList<String>();
        ids.add(0);
        names.add("Microphone (default)");
        AudioManager am = (AudioManager)a.getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                ids.add(d.getId());
                names.add("USB · " + d.getProductName());
            }
        }
        names.add("Another app's audio");
        new Lux.Builder(a)
            .setTitle("Recording input")
            .setItems(names.toArray(new String[0]),
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, int w) {
                              try {
                                  Recording r = a.session.recording;
                                  r.arm(r.armed, w < ids.size() ? ids.get(w) : 0, w == ids.size());
                                  if (w == ids.size()) {
                                      if (Build.VERSION.SDK_INT < 29)
                                          throw new IllegalStateException("Playback capture needs Android 10 or newer.");
                                      a.startForegroundService(new Intent(a, StudioService.class));
                                      MediaProjectionManager pm =
                                          (MediaProjectionManager)a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                                      a.startActivityForResult(pm.createScreenCaptureIntent(), CAPTURE);
                                  } else
                                      Ui.toast(a, "Input ready. Headphones avoid recording the backing track.");
                              } catch (Exception e) {
                                  Ui.toast(a, e.getMessage());
                              }
                          }
                      })
            .show();
    }
    void calibrate() {
        if (a.session.recording.external) {
            Ui.toast(a, "Playback capture timing uses the capture clock; use clip nudge to align it.");
            return;
        }
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            input();
            return;
        }
        new Lux.Builder(a)
            .setTitle("Calibrate this audio route")
            .setItems(new String[] {"Measure with a speaker or loopback cable", "Enter compensation (ms)"},
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, int w) {
                              if (w == 1) {
                                  StudioDialogs.number(a, "Recording compensation (ms)",
                                                       a.session.recording.latency * 1000.0 / a.session.rate(), new StudioDialogs.Number() {
                                                           public void value(double v) {
                                                               a.session.recording.setLatency(Math.round(v * a.session.rate() / 1000));
                                                           }
                                                       });
                                  return;
                              }
                              try {
                                  a.session.recording.arm(a.session.recording.armed, a.session.recording.inputDevice, false);
                              } catch (Exception e) {
                                  Ui.toast(a, e.getMessage());
                                  return;
                              }
                              state.setText("Measuring this route…");
                              a.session.recording.calibrating = true;
                              new Thread(new Runnable() {
                                  public void run() {
                                      try {
                                          final long n = a.session.engine().measureLatency(3);
                                          a.runOnUiThread(new Runnable() {
                                              public void run() {
                                                  a.session.recording.calibrating = false;
                                                  a.session.recording.setLatency(n);
                                                  Ui.toast(a, "Compensation: " + String.format(java.util.Locale.ROOT, "%.2f ms",
                                                                                               n * 1000.0 / a.session.rate()));
                                              }
                                          });
                                      } catch (final Exception e) {
                                          a.runOnUiThread(new Runnable() {
                                              public void run() {
                                                  a.session.recording.calibrating = false;
                                                  Ui.toast(a, e.getMessage());
                                              }
                                          });
                                      }
                                  }
                              }, "studio-calibration").start();
                          }
                      })
            .show();
    }
    void result(int request, int result, Intent data) {
        if (request != CAPTURE)
            return;
        if (result != android.app.Activity.RESULT_OK) {
            a.stopService(new Intent(a, StudioService.class));
            Ui.toast(a, "Audio capture was not allowed.");
            return;
        }
        MediaProjectionManager pm = (MediaProjectionManager)a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        a.session.recording.setCapture(
            new CaptureSource(pm.getMediaProjection(result, data),
                              new Runnable() {
                                  public void run() {
                                      a.session.report("Playback capture ended.");
                                      a.session.stop();
                                  }
                              },
                              new Runnable() {
                                  public void run() {
                                      a.session.report("Capture stayed silent. The source may be paused or disallow recording.");
                                      a.session.stop();
                                  }
                              }));
        Ui.toast(a, "Capture ready. Arm a track and press Record.");
    }
    public void projectChanged() {
        if (p() == null)
            return;
        StringBuilder b = new StringBuilder();
        for (Track t : p().tracks)
            b.append(t.id).append(t.name).append(t.mute);
        b.append(a.session.recording.armed);
        if (b.toString().equals(trackKey))
            return;
        trackKey = b.toString();
        tracks.removeAllViews();
        for (final Track t : p().tracks) {
            LinearLayout row = Ui.row(a);
            tracks.addView(row);
            add(row, (t.id.equals(a.session.recording.armed) ? "● " : "○ ") + t.name, new Runnable() {
                public void run() {
                    if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        input();
                        return;
                    }
                    try {
                        Recording r = a.session.recording;
                        if (!r.external)
                            r.arm(t.id, r.inputDevice, false);
                        else
                            r.armed = t.id;
                        projectChanged();
                    } catch (Exception e) {
                        Ui.toast(a, e.getMessage());
                    }
                }
            });
            add(row, t.mute ? "Muted" : "Listen", new Runnable() {
                public void run() { a.session.run(Edit.mute(t.id, !t.mute)); }
            });
        }
    }
    public void frame(NativeEngine.Status s) {
        if (p() == null)
            return;
        Recording r = a.session.recording;
        if (a.session.engine() != null)
            a.session.engine().recordStatus(r.meter);
        meter.setText(String.format(java.util.Locale.ROOT, "Input %.0f dB · compensation %.1f ms",
                                    20 * Math.log10(Math.max(.00001, r.meter[0])), r.latency * 1000.0 / p().rate));
        record.setText(r.active() ? "■ Finish take" : "● Record")
        ;
        Performance f = p().performance;
        state.setText(String.format(java.util.Locale.ROOT, "%.1f BPM · %d/%d · click %s · count-in %d bars%s", f.tempo, f.beats, f.beatUnit,
                                    f.click ? "on" : "off", f.countIn, f.punch ? " · PUNCH" : ""));
    }
    public String name() { return "Record"; }
    public View view() { return page; }
    public void shown() { projectChanged(); }
    public void hidden() {}
}
