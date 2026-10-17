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
    Button repeatBtn, levelsBtn, songBtn, muteBtn, soloBtn;
    final Button[] banks = new Button[4];
    final boolean[] touchHeld = new boolean[16];
    final boolean[][] midiHeld = new boolean[16][16];
    final double[] hitBuffer = new double[1+4*256];
    MidiDevices midi;
    SamplerScreen(final StudioActivity a) {
        this.a = a;
        root = Ui.column(a);
        page = new ScrollView(a);
        page.setFillViewport(true);
        page.addView(root);
        root.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
        TextView title = Ui.text(a, "THE CHAMBER", Skin.HI, 20);
        root.addView(title);
        root.addView(Ui.text(a, "1. Tap a pad to choose it (it lights up).\n2. Sample puts a sound on it: a clip from this project, or a sound file on the phone.\n"
                + "3. Hold a pad to shape it. Chop slices one sound across all 16 pads; Kit brings the drums back.", Skin.TEXT, 13));
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
                            if(p()!=null&&!p().performance.chamber.levels) selected = k;
                            touchHeld[k]=true;
                            float vel = Math.max(.2f, Math.min(1, e.getPressure()));
                            a.session.engine().pad(k, vel, true);
                            pattern();
                        }
                        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            touchHeld[k]=false;releasePad(k);
                        }
                        return false;
                    }
                });
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        if (p() == null)
                            return true;
                        selected = p().performance.chamber.levels?p().performance.chamber.levelPad:k;
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
        LinearLayout perform = Ui.row(a);
        root.addView(perform);
        recordBtn = add(perform, "● Record pads", new Runnable() {
            public void run() { setRecording(!recording); }
        });
        add(perform, "Clear pad", new Runnable() {
            public void run() {
                Project q = p().copy();
                java.util.Arrays.fill(q.performance.chamber.rows()[selected], 0f);
                save(q, "Clear pad " + (selected + 1));
            }
        });
        add(perform, "Bounce to a track", new Runnable() {
            public void run() { bounce(); }
        });
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
        LinearLayout bankRow=Ui.row(a);root.addView(bankRow);
        for(int bank=0;bank<4;bank++){final int index=bank;banks[bank]=add(bankRow,""+(char)('A'+bank),new Runnable(){public void run(){collectHits(recording);Project q=p().copy();q.performance.chamber.active=index;save(q,"Select pattern "+(char)('A'+index));}});}
        LinearLayout songRow=Ui.row(a);root.addView(songRow);
        songBtn=add(songRow,"Song: off",new Runnable(){public void run(){collectHits(recording);Project q=p().copy();q.performance.chamber.song=!q.performance.chamber.song;save(q,"Song mode");}});
        add(songRow,"Song order",new Runnable(){public void run(){songOrder();}});
        add(songRow,"MIDI",new Runnable(){public void run(){openMidi();}});
        LinearLayout liveRow=Ui.row(a);root.addView(liveRow);
        repeatBtn=add(liveRow,"Repeat: off",new Runnable(){public void run(){Project q=p().copy();q.performance.chamber.repeat=(q.performance.chamber.repeat+1)%4;save(q,"Note repeat");}});
        levelsBtn=add(liveRow,"16 levels: off",new Runnable(){public void run(){allPadsOff();Project q=p().copy();q.performance.chamber.levels=!q.performance.chamber.levels;q.performance.chamber.levelPad=selected;save(q,"16 velocity levels");}});
        LinearLayout mixRow=Ui.row(a);root.addView(mixRow);
        muteBtn=add(mixRow,"Mute pad",new Runnable(){public void run(){Project q=p().copy();q.performance.chamber.mute[selected]=!q.performance.chamber.mute[selected];save(q,"Mute pad "+(selected+1));}});
        soloBtn=add(mixRow,"Solo pad",new Runnable(){public void run(){Project q=p().copy();q.performance.chamber.solo[selected]=!q.performance.chamber.solo[selected];save(q,"Solo pad "+(selected+1));}});
        add(mixRow,"All pads off",new Runnable(){public void run(){allPadsOff();}});
        steps = Ui.column(a);
        root.addView(steps, new LinearLayout.LayoutParams(-1, -2));
        a.session.listen(this);
    }
    Project p() { return a.session.project(); }
    void save(Project q, String name) { a.session.run(ClipEdits.change(p(), q, name)); }
    Button add(LinearLayout row, String name, final Runnable run) {
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
        return b;
    }
    void source(final boolean chop) {
        final ArrayList<Clip> clips = new ArrayList<Clip>();
        ArrayList<String> names = new ArrayList<String>();
        for (Track t : p().tracks)
            for (Clip c : t.clips) {
                clips.add(c);
                names.add(t.name + " · " + String.format(java.util.Locale.ROOT, "%.1fs", c.length / (double)p().rate));
            }
        names.add("A sound file on the phone…");
        new Lux.Builder(a)
            .setTitle(chop ? "Slice a sound into 16 pads" : "Sound for pad " + (selected + 1))
            .setItems(names.toArray(new String[0]),
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, int w) {
                              if (w == clips.size()) pickFile(chop);
                              else use(clips.get(w), chop);
                          }
                      })
            .show();
    }

    /** Puts a clip's audio on the chosen pad, or slices it across all sixteen. */
    void use(final Clip c, final boolean chop) {
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

    static final int PICK_SOUND = 62;
    private boolean pickChop;

    void pickFile(boolean chop) {
        pickChop = chop;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        a.startActivityForResult(i, PICK_SOUND);
    }

    /** The picked file comes into the project's audio folder, then onto the pads like any clip. */
    void result(int request, int result, Intent data) {
        if (request != PICK_SOUND || result != android.app.Activity.RESULT_OK || data == null || data.getData() == null || p() == null)
            return;
        final android.net.Uri uri = data.getData();
        final boolean chop = pickChop;
        final java.io.File dir = a.session.dir();
        final int rate = p().rate;
        final String rel = "audio/pad-" + System.currentTimeMillis() + ".wav";
        Ui.toast(a, "Bringing the sound in…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final long frames = ImportJob.run(a, uri, new java.io.File(dir, rel), rate, new ImportJob.Progress() {
                        public void at(float fraction) {}
                    }, new java.util.concurrent.atomic.AtomicBoolean());
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            if (p() == null || a.session.dir() == null || !a.session.dir().equals(dir)) return;
                            Clip c = new Clip();
                            c.file = rel;
                            c.offset = 0;
                            c.length = frames;
                            use(c, chop);
                        }
                    });
                } catch (final Exception e) {
                    a.runOnUiThread(new Runnable() {
                        public void run() { Ui.toast(a, "That sound could not be brought in: " + e.getMessage()); }
                    });
                }
            }
        }, "studio-pad-import").start();
    }
    void shape() {
        final String[] labels = {"One-shot / gate / loop", "Tune (semitones)", "Attack (ms)",         "Decay (ms)", "Sustain (%)",
                                 "Release (ms)",           "Low-pass (Hz)",    "Choke group (0 off)", "Reverse",
                                 "Start (seconds into the sound)", "Length (seconds)"};
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
                              if (w == 9 || w == 10) {
                                  trim(w == 9);
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
    void releasePad(int pad) {
        if(touchHeld[pad])return;for(int channel=0;channel<16;channel++)if(midiHeld[channel][pad])return;
        if(a.session.engine()!=null&&!a.session.engine().pad(pad,0,false))a.session.engine().padsOff();
    }
    void allPadsOff(){Arrays.fill(touchHeld,false);for(boolean[] channel:midiHeld)Arrays.fill(channel,false);if(a.session.engine()!=null)a.session.engine().padsOff();}
    void openMidi(){if(midi==null)midi=new MidiDevices(a,new MidiDevices.Target(){
        public void note(int channel,int key,int velocity,boolean on){int pad=key-36;if(pad<0||pad>=16||a.session.engine()==null)return;midiHeld[channel][pad]=on;if(on)a.session.engine().pad(pad,velocity/127f,true);else releasePad(pad);}
        public void control(int channel,int controller,int value){if(controller==120||controller==123)allPadsOff();}
        public void allOff(){allPadsOff();}
    });midi.show();}
    void songOrder(){
        final EditText text=new EditText(a);StringBuilder order=new StringBuilder();for(int bank:p().performance.chamber.chain){if(order.length()>0)order.append(' ');order.append((char)('A'+bank));}text.setText(order.toString());text.setHint("A A B A C D");text.setSingleLine(false);
        final android.app.AlertDialog dialog=new Lux.Builder(a).setTitle("Song order · up to 128 patterns").setView(text).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create();dialog.show();
        dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){public void onClick(View v){try{
            collectHits(recording);Project q=p().copy();Chamber chamber=q.performance.chamber;chamber.chain.clear();
            for(String item:text.getText().toString().trim().toUpperCase(java.util.Locale.ROOT).split("[ ,]+")){if(item.length()!=1||item.charAt(0)<'A'||item.charAt(0)>'D')throw new IllegalArgumentException("Use pattern letters A, B, C and D, separated by spaces.");chamber.chain.add(item.charAt(0)-'A');}
            chamber.song=true;chamber.validate();save(q,"Edit pad song order");dialog.dismiss();
        }catch(IllegalArgumentException ex){Ui.toast(a,ex.getMessage());}}});
    }

    // ---------- performing: record hits onto the grid, then bounce the pads to their own track ----------

    boolean recording;
    Button recordBtn;
    private Object pass;  // one recording pass is one undo step

    void setRecording(boolean on) {
        if (p() == null) return;
        collectHits(recording);
        recording = on;
        recordBtn.setText(on ? "■ Stop recording" : "● Record pads");
        recordBtn.setTextColor(on ? 0xffe0607e : Skin.TEXT);
        if (!on) {
            pass = null;
            return;
        }
        pass = new Object();
        if (!p().performance.pattern) {
            Project q = p().copy();
            q.performance.pattern = true;
            save(q, "Pattern playback");
        }
        NativeEngine.Status st = a.session.status();
        if (st == null || !st.playing) a.session.play();
        Ui.toast(a, "Recording: play the pads; each hit lands on the nearest step.");
    }

    /** Native timestamps include note repeats and 16-level velocities. One recording pass is one undo. */
    void collectHits(boolean record) {
        NativeEngine engine=a.session.engine();Project cur=p();if(engine==null||cur==null)return;
        Project changed=null;boolean missed=false;
        for(int batch=0;batch<8;batch++){
            int count=engine.padHits(hitBuffer);missed|=hitBuffer[0]>0;
            for(int i=0;i<count&&record;i++){
                long frame=(long)hitBuffer[1+4*i];int pad=(int)hitBuffer[2+4*i],bank=(int)hitBuffer[4+4*i];float velocity=(float)hitBuffer[3+4*i];
                if(frame<0||pad<0||pad>=16||bank<0||bank>3)continue;
                double sixteenth=new TempoMap(cur.rate,cur.performance).beatAt(frame)*4;long base=(long)Math.floor(sixteenth),at=base;double best=Double.MAX_VALUE;
                for(long step=base-1;step<=base+1;step++){double delta=Math.abs(sixteenth-(step+((step&1)!=0?cur.performance.swing:0)));if(delta<best){best=delta;at=step;}}
                int step=(int)Math.floorMod(at,cur.performance.patternLength);
                float old=(changed==null?cur:changed).performance.chamber.patterns[bank][pad][step];
                if(velocity>old){if(changed==null)changed=cur.copy();changed.performance.chamber.patterns[bank][pad][step]=velocity;}
            }
            if(count<256)break;
        }
        if(changed!=null)a.session.drag(ClipEdits.change(cur,changed,"Record pads"),pass);
        if(record&&missed){recording=false;pass=null;recordBtn.setText("● Record pads");recordBtn.setTextColor(Skin.TEXT);Ui.toast(a,"Pad recording stopped: some hits arrived faster than they could be saved.");}
    }

    /** The pads, rendered alone into a new track, and the pattern switched off so it is not heard twice: one undo. */
    void bounce() {
        if(recording)setRecording(false);
        final Project snap = p()==null?null:p().copy();
        if (snap == null) return;
        boolean any = false;
        Chamber chamber=snap.performance.chamber;
        for(int cycle=0;cycle<(chamber.song?chamber.chain.size():1);cycle++){
            int bank=chamber.song?chamber.chain.get(cycle):chamber.active;
            for(int pad=0;pad<16;pad++)if(chamber.audible(pad))for(float x:chamber.patterns[bank][pad])any|=x>0;
        }
        if (!any) {
            Ui.toast(a, "There is nothing in the pattern yet: record some pads, or tap the steps.");
            return;
        }
        if (recording) setRecording(false);
        final java.io.File dir = a.session.dir();
        final Project pads = snap.copy();
        pads.tracks.clear();
        pads.mixer = pads.mixer.stem(null);
        pads.masterDb = 0;
        pads.performance.pattern = true;
        final String rel = "audio/pads-" + System.currentTimeMillis() + ".wav";
        final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        Ui.toast(a, "Bouncing the pads…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final long frames=Math.max(ExportJob.length(snap,dir),ExportJob.length(pads,dir));
                    ExportJob.render(pads, dir, new java.io.File(dir, rel), frames, false, "Pads", new ExportJob.Progress() {
                        public void at(String what, float f) {}
                    }, cancel);
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            Project cur = p();
                            if (cur == null || a.session.dir() == null || !a.session.dir().equals(dir)) return;
                            Project off = cur.copy();
                            off.performance.pattern = false;
                            a.session.run(Edit.compound("Bounce pads to a track", ClipEdits.change(cur, off, "Pattern off"),
                                    Edit.addTrack("Pads", rel, frames, "pads")));
                            Ui.toast(a, "The pads are a track now: mix, move, edit or delete it like any other.");
                        }
                    });
                } catch (final Exception e) {
                    a.runOnUiThread(new Runnable() {
                        public void run() { Ui.toast(a, "The bounce did not work: " + e.getMessage()); }
                    });
                }
            }
        }, "studio-bounce").start();
    }

    /** Which part of its sound the chosen pad plays: where it starts, or how long it runs, in seconds. */
    void trim(final boolean start) {
        Performance.Pad pad = p().performance.pads[selected];
        if (pad.file == null || pad.file.isEmpty()) {
            Ui.toast(a, "Put a sound on this pad first, with Sample or Chop.");
            return;
        }
        final long total;
        try {
            WavIn in = WavIn.open(new java.io.File(a.session.dir(), pad.file));
            total = in.frames;
            in.close();
        } catch (Exception e) {
            Ui.toast(a, "The pad's sound could not be read: " + e.getMessage());
            return;
        }
        final int rate = p().rate;
        String whole = String.format(java.util.Locale.ROOT, "%.2f", total / (double) rate);
        StudioDialogs.number(a, start ? "Start, in seconds (the sound is " + whole + " s)" : "Length, in seconds",
                (start ? pad.offset : pad.length) / (double) rate, new StudioDialogs.Number() {
                    public void value(double v) {
                        Project q = p().copy();
                        Performance.Pad z = q.performance.pads[selected];
                        long f = Math.round(v * rate);
                        if (start) {
                            z.offset = Math.max(0, Math.min(total - 1, f));
                            z.length = Math.max(1, Math.min(z.length, total - z.offset));
                        } else {
                            z.length = Math.max(1, Math.min(total - z.offset, f));
                        }
                        save(q, "Trim pad");
                    }
                });
    }

    /** The chosen pad glows, so it is clear which one Sample, Shape and the steps work on. */
    void highlight() {
        for (int i = 0; i < 16; i++) {
            boolean on = i == selected;
            pads[i].setAlpha(p()!=null&&!p().performance.chamber.audible(i)?.4f:1f);
            pads[i].setTextColor(on ? Skin.HI : Skin.TEXT);
            pads[i].setShadowLayer(on ? Ui.dp(a, 8) : 0, 0, 0, on ? (0xEE << 24) | Skin.GLOW : 0);
            if (on) {  // a lit ring in the skin's accent, clear on every skin
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setCornerRadius(Ui.dp(a, 12));
                ring.setStroke(Ui.dp(a, 3), Skin.ACCENT);
                pads[i].setForeground(ring);
            } else {
                pads[i].setForeground(null);
            }
        }
    }

    void pattern() {
        highlight();
        steps.removeAllViews();
        if (p() == null)
            return;
        Chamber chamber=p().performance.chamber;
        repeatBtn.setText(new String[]{"Repeat: off","Repeat: 1/4","Repeat: 1/8","Repeat: 1/16"}[chamber.repeat]);
        levelsBtn.setText(chamber.levels?"16 levels: pad "+(chamber.levelPad+1):"16 levels: off");
        songBtn.setText(chamber.song?"Song: on":"Song: off");muteBtn.setText(chamber.mute[selected]?"Unmute pad":"Mute pad");soloBtn.setText(chamber.solo[selected]?"Unsolo pad":"Solo pad");
        for(int bank=0;bank<4;bank++)banks[bank].setTextColor(chamber.active==bank?Skin.HI:Skin.TEXT);
        steps.addView(Ui.text(a, "Pattern "+(char)('A'+chamber.active)+" · Pad " + (selected + 1) + " · steps: off / soft / hard" + (p().performance.pattern ? " · PLAYING" : ""),
                              Skin.HI, 12));
        // eight steps a row, each an equal share of the width, so 16 and 32 both fit the screen
        int rows = p().performance.patternLength / 8;
        for (int row = 0; row < rows; row++) {
            LinearLayout line = Ui.row(a);
            steps.addView(line, new LinearLayout.LayoutParams(-1, -2));
            for (int col = 0; col < 8; col++) {
                final int step = row * 8 + col;
                float v = p().performance.chamber.rows()[selected][step];
                Button b = Ui.button(a, "" + (step + 1), v > 0);
                b.setAlpha(v == 0 ? .45f : v < 1 ? .7f : 1);
                b.setSingleLine(true);
                b.setTextSize(11);
                b.setPadding(0, 0, 0, 0);
                b.setMinWidth(0);
                b.setMinimumWidth(0);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1);
                sp.setMargins(2, 2, 2, 2);
                line.addView(b, sp);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Project q = p().copy();
                        float old = q.performance.chamber.rows()[selected][step];
                        q.performance.chamber.rows()[selected][step] = old == 0 ? .5f : old < 1 ? 1 : 0;
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
            pads[i].setText((i + 1) + (p().performance.chamber.mute[i]?" M":"") + (p().performance.chamber.solo[i]?" S":"") + "\n" + label);
        }
        pattern();
    }
    public String name() { return "Pads"; }
    public View view() { return page; }
    public void shown() { projectChanged(); }
    public void hidden() {
        if(recording)setRecording(false);allPadsOff();if(midi!=null){midi.close();midi=null;}
    }
    public void frame(NativeEngine.Status s) {collectHits(recording);}
}
