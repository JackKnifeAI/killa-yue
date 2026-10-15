package ai.jackknife.killayue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    // filled from the skin when the activity starts
    static int BG, CARD, TEXT, DIM, ACCENT, EDGE;
    static final int PICK_AUDIO = 7, PICK_ADAPTER = 8, PICK_RECIPE = 9, PICK_SKIN = 10;
    static volatile String adapterMsg = "";

    static final String[] STYLES = {
        "Pop|pop, catchy hook, bright synths, polished production",
        "Hip-hop|hip-hop, punchy drums, deep 808 bass, confident rap vocal",
        "Trap|trap, dark melody, rolling hi-hats, heavy 808, autotuned vocal",
        "R&B|r&b, smooth vocal, warm electric piano, slow groove",
        "Rock|rock, electric guitars, live drums, powerful vocal",
        "Metal|metal, distorted guitars, double kick drums, aggressive vocal",
        "EDM|edm, four on the floor, big synth lead, festival drop",
        "House|house, groovy bassline, piano stabs, soulful vocal",
        "Lo-fi|lo-fi hip-hop, mellow keys, vinyl crackle, relaxed beat",
        "Country|country, acoustic guitar, pedal steel, storytelling vocal",
        "Jazz|jazz, upright bass, brushed drums, piano, smoky vocal",
        "Reggae|reggae, offbeat guitar, deep bass, laid back vocal",
        "Ballad|piano ballad, emotional vocal, strings, slow tempo",
        "Female|female voice", "Male|male voice", "Fast|140 BPM", "Slow|80 BPM",
    };
    static final String[] SECTIONS = {"[Intro]", "[Verse]", "[Pre-Chorus]", "[Chorus]", "[Bridge]", "[Outro]"};
    static final String[] COT_LABELS = {"Full score (melody and chords)", "Melody only", "No score"};
    static final String[] COT = {"full", "melody", "off"};
    static final String[] FORMATS = {"mp3", "wav16", "wav24"};
    static final String[] DEVICES = {"Vulkan0", "CPU"};
    static final String[] DEVICE_LABELS = {"GPU (Adreno, Vulkan)", "CPU (slow fallback)"};
    static final int[] SEQS = {4096, 8192, 16384, 0};
    static final String[] SEQ_LABELS = {"Short songs (4096)", "Normal (8192)", "Long songs (16384)", "Maximum (uses most memory)"};


    // one job at a time, shared across activity restarts
    static volatile boolean jobBusy;
    static volatile String jobId, jobMsg = "", jobError = "";
    static volatile String runningCheckpoint = ""; // the checkpoint folder of the song being made
    static volatile boolean jobCancel, engineHealthy;
    static volatile long jobStart;
    static MainActivity inst;

    final Handler ui = new Handler();
    SharedPreferences cfg;
    boolean ready;

    TextView header, banner, lengthLabel, progText, engineStatus, logView, storageNote;
    static final int TAB_CREATE = 0, TAB_LIBRARY = 1, TAB_LYRICS = 2, TAB_ENGINE = 3;
    View[] panels = new View[4];
    Button[] tabs = new Button[4];
    EditText style, lyrics, score, steps, variations, lmSeed, seed, cfgScale, bitrate, tempScore, tempSong, adaptersBox;
    SeekBar lengthBar;
    PlayerView playerView;
    KaraokeView karaokeView;
    ArrayList<Karaoke.Line> karaoke = new ArrayList<Karaoke.Line>();
    FrameLayout lyricsChips;
    JSONObject playMeta;
    boolean timing, timedByHand;
    int timingIndex;
    int[] timingTimes;
    EditText songTitle;
    static volatile String pendingTitle = "";
    static volatile String pendingAlbum = "";
    /** When an album is playing, its songs in album order; otherwise null and the library order is used. */
    ArrayList<JSONObject> albumOrder;
    String playTitle = "";
    final ArrayList<JSONObject> playlist = new ArrayList<JSONObject>();
    DialView lengthDial;
    Spinner cotSpin, formatSpin, deviceSpin, quantSpin, seqSpin;
    LinearLayout advBox, libraryBox, modelBox, adapterBox;
    TextView adapterStatus, coverStatus, syncStatus;
    String adapterSeen = "", packsSeen = null;
    long adapterSeenAt;
    int logSeen = -1;
    long logShownAt;

    // Facts from storage and the GPU for the status line. Shared storage can stall for seconds
    // while a song is made, so these are gathered on the "probe" thread, never on the screen thread.
    static volatile boolean snapReady, snapCore, snapCovers, snapSync;
    static volatile long snapAt;
    static volatile String snapHeat = "", snapAdapters = "", snapBg = "";
    static volatile java.util.HashMap<String, Boolean> snapHave = new java.util.HashMap<String, Boolean>(),
            snapPart = new java.util.HashMap<String, Boolean>();
    Button genBtn, engineBtn, scoreToggle;
    StaffProgress prog;
    CheckBox keepBox, gentleBox;
    TextView bgStatus;
    String[] modelNames;
    TextView[] modelText;
    Button[] modelBtn;

    // optional inputs carried from a library track or an imported recording
    String semTokens = "", srcTokens = "", carryName = "";
    double srcStart = 0, srcEnd = -1;

    MediaPlayer player;
    String playingFile = "";
    byte[] pickedAudio;
    String pickedName = "audio";

    int dp(int v) {
        return Ui.dp(this, v);
    }

    // ---------- small view builders (the shared ones live in Ui, so every screen wears the same dress) ----------

    GradientDrawable box(int color, int radius) {
        return Ui.box(this, color, radius);
    }

    GradientDrawable glass(int edge) {
        return Ui.glass(this, edge);
    }

    TextView label(String s) {
        return Ui.label(this, s);
    }

    TextView text(String s, int color, int size) {
        return Ui.text(this, s, color, size);
    }

    EditText edit(String hint, int minLines) {
        return Ui.edit(this, hint, minLines);
    }

    EditText numEdit(String hint) {
        EditText e = edit(hint, 1);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }

    Button button(String s, boolean primary) {
        return Ui.button(this, s, primary);
    }

    Button chip(String s) {
        return Ui.chip(this, s);
    }

    LinearLayout column() {
        return Ui.column(this);
    }

    LinearLayout row() {
        return Ui.row(this);
    }

    LinearLayout.LayoutParams weight() {
        return Ui.weight();
    }

    LinearLayout.LayoutParams gap(int top) {
        return Ui.gap(this, top);
    }

    Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        // the closed field and the open list both in the skin's lettering; the list on the gold-rimmed panel
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int pos, View v, ViewGroup parent) {
                return dressItem(super.getView(pos, v, parent), false);
            }

            @Override
            public View getDropDownView(int pos, View v, ViewGroup parent) {
                return dressItem(super.getDropDownView(pos, v, parent), true);
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setPopupBackgroundDrawable(new LuxFrame(18, 8));
        s.setBackground(glass(EDGE));
        s.setPadding(dp(6), dp(8), dp(6), dp(8));
        return s;
    }

    View dressItem(View v, boolean open) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTypeface(Skin.text());
            t.setTextColor(TEXT);
            t.setShadowLayer(4, 0, 1, 0x99000000);
            if (open) t.setPadding(dp(18), dp(12), dp(18), dp(12));
        }
        return v;
    }

    HorizontalScrollView chipRow(String[] names, final Runnable[] actions) {
        return Ui.chipRow(this, names, actions);
    }

    View labelled(String name, View field) {
        LinearLayout c = column();
        c.addView(label(name));
        c.addView(field);
        LinearLayout.LayoutParams lp = weight();
        lp.rightMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    void alert(String title, String msg) {
        new Lux.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    // ---------- lifecycle ----------

    @Override
    protected void onCreate(Bundle b) {
        setTheme(Skin.themeFor(this, Skin.id(this)));
        super.onCreate(b);
        inst = this;
        cfg = getSharedPreferences("cfg", 0);
        Skin.load(this);
        BG = Skin.DECK;
        CARD = Skin.CARD;
        TEXT = Skin.TEXT;
        DIM = Skin.DIM;
        ACCENT = Skin.ACCENT;
        EDGE = Skin.EDGE;
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
            requestPermissions(new String[] {"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
        }

        LinearLayout root = column();
        root.setBackground(Skin.background(this));
        root.setFitsSystemWindows(true);

        LinearLayout top = row();
        top.setPadding(dp(18), dp(8), dp(18), dp(8));
        top.setBackgroundColor(Skin.DECK);
        TextView title = text(Skin.SCRIPT_TITLES ? "Killa YuE" : "KILLA YuE", Skin.ACCENT, Skin.SCRIPT_TITLES ? 30 : 22);
        title.setTypeface(Skin.script(), Skin.SCRIPT_TITLES ? Typeface.NORMAL : Typeface.BOLD);
        if (!Skin.SCRIPT_TITLES) title.setLetterSpacing(0.12f);
        top.addView(title, weight());
        header = text("", DIM, 12);
        top.addView(header);
        root.addView(top);

        LinearLayout tabRow = row();
        tabRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        String[] names = {"Create", "Library", "Lyrics", "Engine"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabs[i] = button(names[i], false);
            tabs[i].setTextSize(14);
            LinearLayout.LayoutParams lp = weight();
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            tabRow.addView(tabs[i], lp);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showTab(idx);
                }
            });
        }
        // the Studio has a screen of its own: this tab opens it
        Button studio = button("Studio", false);
        studio.setTextSize(14);
        studio.setBackground(new Skin.Plate(true));
        studio.setTextColor(Skin.LO);
        studio.setPadding(dp(12), 0, dp(12), dp(2));
        LinearLayout.LayoutParams slp = weight();
        slp.leftMargin = dp(3);
        slp.rightMargin = dp(3);
        tabRow.addView(studio, slp);
        studio.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ai.jackknife.killayue.studio.ui.StudioActivity.open(MainActivity.this);
            }
        });
        root.addView(tabRow);

        FrameLayout content = new FrameLayout(this);
        panels[0] = scroll(buildCreate());
        panels[1] = scroll(buildLibrary());
        panels[2] = buildLyrics();
        panels[3] = scroll(buildEngine());
        for (int i = 0; i < 4; i++) content.addView(panels[i]);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildPlayer());
        setContentView(root);

        songTitle.setText(cfg.getString("draft_title", ""));
        style.setText(cfg.getString("draft_style", ""));
        lyrics.setText(cfg.getString("draft_lyrics", ""));
        score.setText(cfg.getString("draft_score", ""));
        if (score.getText().length() > 0) showScore(true);
        refreshLibrary();
        ready = true;
        int startTab = cfg.getInt("tab", -1); // set when the skin was just changed, to come back to the same place
        cfg.edit().remove("tab").apply();
        showTab(startTab >= 0 ? startTab : Store.coreReady(this) ? TAB_CREATE : TAB_ENGINE);
        tick.run();
        healthLoop();
        probeLoop();
        String check = PhoneCheck.firstLaunch(this);
        if (check.length() > 0) alert("About this phone", check);
        handleLink(getIntent());
    }

    View scroll(View v) {
        ScrollView s = new ScrollView(this);
        v.setPadding(dp(16), dp(4), dp(16), dp(24));
        s.addView(v);
        return s;
    }

    void showTab(int idx) {
        if (timing && idx != TAB_LYRICS) endTiming(false);
        for (int i = 0; i < 4; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean on = i == idx;
            tabs[i].setBackground(new Skin.Plate(true));
            tabs[i].setTextColor(on ? Skin.HI : Skin.LO);
            tabs[i].setPadding(dp(12), 0, dp(12), dp(2));
        }
        if (idx == TAB_LIBRARY) refreshLibrary();
        if (idx == TAB_LYRICS) karaokeView.invalidate();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refreshModels();
        refreshLibrary();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ringtones.resume(this); // back from allowing Killa YuE to change the phone's sounds
        if (wakeBtn != null) wakeBtn.setText(wakeLabel());
    }

    @Override
    protected void onPause() {
        super.onPause();
        // keep what was typed, so closing the app never loses a lyric
        cfg.edit().putString("draft_title", songTitle.getText().toString())
                .putString("draft_style", style.getText().toString())
                .putString("draft_lyrics", lyrics.getText().toString())
                .putString("draft_score", score.getText().toString()).apply();
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    void probeLoop() {
        new Thread(new Runnable() {
            public void run() {
                while (inst == MainActivity.this) {
                    try {
                        probe();
                    } catch (Exception e) {
                        // the next round tries again
                    }
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "probe").start();
    }

    void probe() {
        long at = System.currentTimeMillis();
        java.util.HashMap<String, Boolean> have = new java.util.HashMap<String, Boolean>(), part = new java.util.HashMap<String, Boolean>();
        String[] names = modelNames;
        if (names == null) names = new String[] {Store.backbone(this), Store.VAE, Store.MERT, Store.SHEET, Store.TOKENIZER};
        for (int i = 0; i < names.length; i++) {
            have.put(names[i], Boolean.valueOf(Store.have(this, names[i])));
            part.put(names[i], Boolean.valueOf(new File(Store.models(this), names[i] + ".part").isFile()));
        }
        StringBuilder now = new StringBuilder();
        ArrayList<File> ads = installedAdapters();
        for (int i = 0; i < ads.size(); i++) now.append(ads.get(i).getName()).append('|');
        snapHave = have;
        snapPart = part;
        snapCore = Store.coreReady(this);
        snapCovers = Covers.anyReady(this);
        snapSync = LyricSync.ready(this);
        snapAdapters = now.toString();
        snapHeat = gpuHeat();
        snapBg = Background.status(this);
        snapAt = at;
        snapReady = true;
    }

    static boolean is(java.util.HashMap<String, Boolean> m, String k) {
        Boolean b = m.get(k);
        return b != null && b.booleanValue();
    }

    void healthLoop() {
        new Thread(new Runnable() {
            public void run() {
                while (inst == MainActivity.this) {
                    engineHealthy = EngineService.running && Api.healthy();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "health").start();
    }

    final Runnable tick = new Runnable() {
        public void run() {
            refreshStatus();
            watchTyping();
            runQueue();
            NowPlaying.turn(MainActivity.this);
            if (jobBusy) Widget.refresh(MainActivity.this, false); // the time it has run, and the time left
            // the lyrics view animates itself only while music plays; this wakes it when playback starts with the tab open
            if (karaokeView != null && panels[TAB_LYRICS].getVisibility() == View.VISIBLE) karaokeView.invalidate();
            ui.postDelayed(this, 600);
        }
    };

    // ---------- Create ----------

    View buildCreate() {
        LinearLayout c = column();

        banner = text("", TEXT, 13);
        banner.setBackground(glass(Skin.ACCENT));
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clearCarry();
            }
        });
        c.addView(banner, gap(8));

        c.addView(chipRow(new String[] {"Undo", "Redo", "Clear style and lyrics", "Resume a song"}, new Runnable[] {
            new Runnable() {
                public void run() {
                    undo();
                }
            },
            new Runnable() {
                public void run() {
                    redo();
                }
            },
            new Runnable() {
                public void run() {
                    if (val(style).length() == 0 && val(lyrics).length() == 0) return;
                    checkpoint();
                    songTitle.setText("");
                    style.setText("");
                    lyrics.setText("");
                    settle();
                    toast("Cleared. Undo brings it back.");
                }
            },
            new Runnable() {
                public void run() {
                    resumeMenu();
                }
            },
        }));

        c.addView(label("Song title"));
        songTitle = edit("Optional. Left empty, the first sung line is used.", 1);
        c.addView(songTitle);

        c.addView(label("Style"));
        style = edit("Genre, mood, instruments, voice, tempo", 2);
        c.addView(style);
        String[] sn = new String[STYLES.length];
        Runnable[] sa = new Runnable[STYLES.length];
        for (int i = 0; i < STYLES.length; i++) {
            final String[] p = STYLES[i].split("\\|");
            sn[i] = p[0];
            sa[i] = new Runnable() {
                public void run() {
                    String cur = style.getText().toString().trim();
                    style.setText(cur.length() == 0 ? p[1] : cur + ", " + p[1]);
                    style.setSelection(style.getText().length());
                }
            };
        }
        c.addView(chipRow(sn, sa));
        c.addView(savedRow("styles", "style", style));

        c.addView(label("Lyrics"));
        lyrics = edit("[Verse]\nYour words here\n\n[Chorus]\n...", 7);
        c.addView(lyrics);
        Runnable[] la = new Runnable[SECTIONS.length];
        for (int i = 0; i < SECTIONS.length; i++) {
            final String tag = SECTIONS[i];
            la[i] = new Runnable() {
                public void run() {
                    String cur = lyrics.getText().toString();
                    String add = (cur.length() == 0 || cur.endsWith("\n\n")) ? "" : (cur.endsWith("\n") ? "\n" : "\n\n");
                    lyrics.append(add + tag + "\n");
                    lyrics.requestFocus();
                }
            };
        }
        c.addView(chipRow(SECTIONS, la));
        c.addView(savedRow("lyrics", "lyrics", lyrics));
        // Write with AI: a language model on the phone writes or rewrites the style and lyrics (Songwriter)
        c.addView(chipRow(new String[] {"Write with AI", "Rewrite style", "Rewrite lyrics", "Sources", "Songwriter model"}, new Runnable[] {
            new Runnable() {
                public void run() {
                    Songwriter.write(MainActivity.this);
                }
            },
            new Runnable() {
                public void run() {
                    Songwriter.rewrite(MainActivity.this, "style");
                }
            },
            new Runnable() {
                public void run() {
                    Songwriter.rewrite(MainActivity.this, "lyrics");
                }
            },
            new Runnable() {
                public void run() {
                    Songwriter.showSources(MainActivity.this);
                }
            },
            new Runnable() {
                public void run() {
                    Songwriter.pickModel(MainActivity.this);
                }
            },
        }));

        lengthLabel = label("Length");
        lengthBar = new SeekBar(this);
        lengthBar.setMax(70); // 10 s to 360 s in 5 s steps
        lengthBar.setProgress(cfg.getInt("length", 4));
        lengthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (lengthDial != null) lengthDial.setValue(p);
                showLength();
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                cfg.edit().putInt("length", s.getProgress()).apply();
            }
        });
        lengthDial = new DialView(this, 70, lengthBar.getProgress(), new DialView.Change() {
            public void value(int v, boolean finished) {
                lengthBar.setProgress(v);
                if (finished) cfg.edit().putInt("length", v).apply();
            }
        });
        LinearLayout lengthRow = row();
        lengthRow.addView(lengthDial);
        LinearLayout lengthCol = column();
        lengthCol.setPadding(dp(12), 0, 0, 0);
        lengthCol.addView(lengthLabel);
        lengthCol.addView(lengthBar);
        TextView hint = text("A limit, not a target: the song ends when the lyrics do. Turn the dial or drag the slider.", DIM, 12);
        hint.setPadding(dp(14), dp(4), 0, 0);
        lengthCol.addView(hint);
        lengthRow.addView(lengthCol, weight());
        c.addView(lengthRow, gap(10));
        showLength();

        c.addView(label("Score"));
        cotSpin = spinner(COT_LABELS);
        c.addView(cotSpin);
        scoreToggle = button("Show score editor", false);
        c.addView(scoreToggle, gap(8));
        score = edit("ABC score. Leave empty and the model composes one. A finished song puts its score here when you choose Edit score.", 6);
        score.setTypeface(Typeface.MONOSPACE);
        score.setTextSize(12);
        score.setVisibility(View.GONE);
        c.addView(score, gap(8));
        scoreToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showScore(score.getVisibility() != View.VISIBLE);
            }
        });

        final Button advToggle = button("Advanced settings", false);
        c.addView(advToggle, gap(8));
        advBox = column();
        advBox.setVisibility(View.GONE);
        steps = numEdit("32");
        variations = numEdit("1");
        lmSeed = numEdit("random");
        seed = numEdit("random");
        cfgScale = numEdit("default");
        bitrate = numEdit("128");
        tempScore = numEdit("0.7");
        tempSong = numEdit("1.0");
        LinearLayout r1 = row();
        r1.addView(labelled("Render steps", steps));
        r1.addView(labelled("Variations (1-4)", variations));
        advBox.addView(r1);
        LinearLayout r2 = row();
        r2.addView(labelled("Melody seed", lmSeed));
        r2.addView(labelled("Sound seed", seed));
        advBox.addView(r2);
        LinearLayout r3 = row();
        r3.addView(labelled("Score creativity", tempScore));
        r3.addView(labelled("Song creativity", tempSong));
        advBox.addView(r3);
        LinearLayout r4 = row();
        r4.addView(labelled("Guidance", cfgScale));
        r4.addView(labelled("MP3 kbps", bitrate));
        advBox.addView(r4);
        advBox.addView(label("File format"));
        formatSpin = spinner(FORMATS);
        advBox.addView(formatSpin);
        advBox.addView(label("Style adapters (LoRA), as file:strength, comma separated"));
        adaptersBox = edit("mystyle.safetensors:1.0", 1);
        advBox.addView(adaptersBox);
        c.addView(advBox);
        advToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = advBox.getVisibility() != View.VISIBLE;
                advBox.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });

        genBtn = button("Generate song", true);
        c.addView(genBtn, gap(18));
        LinearLayout qRow = row();
        Button addQ = button("Add to queue", false);
        queueBtn = button("Queue", false);
        LinearLayout.LayoutParams ql = weight();
        ql.rightMargin = dp(8);
        qRow.addView(addQ, ql);
        qRow.addView(queueBtn, weight());
        c.addView(qRow, gap(10));
        addQ.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addToQueue();
            }
        });
        queueBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showQueue();
            }
        });
        loadQueue();
        genBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (jobBusy) cancelJob();
                else if (!cfg.getBoolean("bg_asked", false) && !Background.exempt(MainActivity.this)) askBackgroundThenGenerate();
                else generate();
            }
        });
        prog = new StaffProgress(this);
        prog.setVisibility(View.GONE);
        c.addView(prog, gap(10));
        progText = text("", TEXT, 14);
        progText.setShadowLayer(4, 0, 1, 0x99000000);
        c.addView(progText, gap(4));

        Button imp = button("Use a recording: cover it or continue it", false);
        c.addView(imp, gap(18));
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("audio/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Pick a recording"), PICK_AUDIO);
            }
        });
        return c;
    }

    void showLength() {
        lengthLabel.setText("Length: up to " + clock(lengthSeconds()));
    }

    int lengthSeconds() {
        return 10 + lengthBar.getProgress() * 5;
    }

    void showScore(boolean show) {
        score.setVisibility(show ? View.VISIBLE : View.GONE);
        scoreToggle.setText(show ? "Hide score editor" : "Show score editor");
    }

    static String clock(int s) {
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    // ---------- undo and redo for the style, lyrics and score boxes ----------

    final ArrayList<String[]> undoStack = new ArrayList<String[]>(), redoStack = new ArrayList<String[]>();
    String[] settled, lastSeen;

    String[] snapshot() {
        return new String[] {style.getText().toString(), lyrics.getText().toString(), score.getText().toString(),
            songTitle.getText().toString()};
    }

    static boolean same(String[] a, String[] b) {
        return a != null && b != null && a[0].equals(b[0]) && a[1].equals(b[1]) && a[2].equals(b[2]) && a[3].equals(b[3]);
    }

    void pushUndo(String[] snap) {
        if (undoStack.size() > 0 && same(undoStack.get(undoStack.size() - 1), snap)) return;
        undoStack.add(snap);
        while (undoStack.size() > 60) undoStack.remove(0);
    }

    /** Call before a change made by a button, so the state just before it can be restored. */
    void checkpoint() {
        String[] now = snapshot();
        if (settled != null && !same(settled, now)) pushUndo(settled);
        pushUndo(now);
        redoStack.clear();
    }

    /** Call after a change made by a button or by undo and redo. */
    void settle() {
        settled = snapshot();
        lastSeen = settled;
    }

    /** Runs on the tick: typing that has paused becomes one undo step. */
    void watchTyping() {
        String[] now = snapshot();
        if (settled == null) {
            settle();
            return;
        }
        if (!same(now, settled) && same(now, lastSeen)) {
            pushUndo(settled);
            redoStack.clear();
            settled = now;
        }
        lastSeen = now;
    }

    void apply(String[] snap) {
        style.setText(snap[0]);
        lyrics.setText(snap[1]);
        score.setText(snap[2]);
        songTitle.setText(snap[3]);
        settle();
    }

    void undo() {
        String[] now = snapshot();
        if (settled != null && !same(now, settled)) pushUndo(settled);
        while (undoStack.size() > 0 && same(undoStack.get(undoStack.size() - 1), now)) {
            undoStack.remove(undoStack.size() - 1);
        }
        if (undoStack.size() == 0) {
            toast("Nothing to undo");
            return;
        }
        redoStack.add(now);
        apply(undoStack.remove(undoStack.size() - 1));
    }

    void redo() {
        if (redoStack.size() == 0) {
            toast("Nothing to redo");
            return;
        }
        pushUndo(snapshot());
        apply(redoStack.remove(redoStack.size() - 1));
    }

    // ---------- saved styles and lyrics ----------

    File savedFile(String kind) {
        return new File(Store.dir(this, "saved"), kind + ".json");
    }

    JSONArray loadSaved(String kind) {
        try {
            return new JSONArray(new String(Store.read(savedFile(kind)), "UTF-8"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    void storeSaved(String kind, JSONArray items) {
        try {
            Store.write(savedFile(kind), items.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            toast("Could not save to " + savedFile(kind).getAbsolutePath());
        }
    }

    View savedRow(final String kind, final String word, final EditText box) {
        return chipRow(new String[] {("lyrics".equals(word) ? "Save these " : "Save this ") + word, "My saved " + kind}, new Runnable[] {
            new Runnable() {
                public void run() {
                    saveCurrent(kind, word, box);
                }
            },
            new Runnable() {
                public void run() {
                    pickSaved(kind, box);
                }
            },
        });
    }

    void saveCurrent(final String kind, String word, final EditText box) {
        final String body = box.getText().toString();
        if (body.trim().length() == 0) {
            toast("There is no " + word + " to save yet");
            return;
        }
        String guess = "";
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length && guess.length() == 0; i++) {
            String l = lines[i].trim();
            if (l.length() > 0 && !l.startsWith("[") && !l.startsWith("(")) guess = l;
        }
        if (guess.length() > 40) guess = guess.substring(0, 40);
        final EditText name = edit("Name", 1);
        name.setText(guess);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(name);
        new Lux.Builder(this).setTitle(("lyrics".equals(word) ? "Save these " : "Save this ") + word + " as").setView(f)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = val(name).length() == 0 ? "Untitled" : val(name);
                        JSONArray items = loadSaved(kind);
                        JSONArray kept = new JSONArray();
                        try {
                            for (int i = 0; i < items.length(); i++) {
                                if (!n.equals(items.getJSONObject(i).optString("name"))) kept.put(items.getJSONObject(i));
                            }
                            JSONObject o = new JSONObject();
                            o.put("name", n);
                            o.put("text", body);
                            kept.put(o);
                        } catch (Exception e) {
                            toast("Could not save");
                            return;
                        }
                        storeSaved(kind, kept);
                        toast("Saved as " + n);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    void pickSaved(final String kind, final EditText box) {
        final JSONArray items = loadSaved(kind);
        if (items.length() == 0) {
            toast("Nothing saved yet");
            return;
        }
        final String[] names = new String[items.length()];
        for (int i = 0; i < names.length; i++) names[i] = items.optJSONObject(names.length - 1 - i).optString("name");
        new Lux.Builder(this).setTitle("My saved " + kind).setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                final int index = names.length - 1 - which;
                final JSONObject item = items.optJSONObject(index);
                new Lux.Builder(MainActivity.this).setTitle(item.optString("name"))
                        .setMessage(item.optString("text"))
                        .setPositiveButton("Use it", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dd, int ww) {
                                checkpoint();
                                box.setText(item.optString("text"));
                                settle();
                            }
                        })
                        .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dd, int ww) {
                                JSONArray kept = new JSONArray();
                                for (int i = 0; i < items.length(); i++) if (i != index) kept.put(items.opt(i));
                                storeSaved(kind, kept);
                                toast("Removed from saved " + kind);
                            }
                        })
                        .setNegativeButton("Cancel", null).show();
            }
        }).show();
    }

    void clearCarry() {
        semTokens = "";
        srcTokens = "";
        carryName = "";
        srcStart = 0;
        srcEnd = -1;
        showBanner();
    }

    void showBanner() {
        String s = "";
        if (semTokens.length() > 0) {
            s = "Re-rendering \"" + carryName + "\": same melody and words, new sound. Change the sound seed or steps. Tap to clear.";
        } else if (srcTokens.length() > 0) {
            s = "New song starts with \"" + carryName + "\" from " + (int) srcStart + " s to "
                    + (srcEnd < 0 ? "its end" : (int) srcEnd + " s") + ". Tap to clear.";
        }
        banner.setText(s);
        banner.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
    }

    static String val(EditText e) {
        return e.getText().toString().trim();
    }

    JSONObject buildRequest() throws Exception {
        JSONObject r = new JSONObject();
        r.put("style", val(style));
        r.put("lyrics", lyrics.getText().toString());
        r.put("cot", COT[cotSpin.getSelectedItemPosition()]);
        r.put("duration", lengthSeconds());
        if (val(score).length() > 0) r.put("abc", score.getText().toString());
        if (val(steps).length() > 0) r.put("steps", Integer.parseInt(val(steps)));
        if (val(variations).length() > 0) {
            r.put("synth_batch_size", Math.max(1, Math.min(4, Integer.parseInt(val(variations)))));
        }
        if (val(lmSeed).length() > 0) r.put("lm_seed", Long.parseLong(val(lmSeed)));
        if (val(seed).length() > 0) r.put("seed", Long.parseLong(val(seed)));
        if (val(cfgScale).length() > 0) r.put("cfg_scale", Double.parseDouble(val(cfgScale)));
        if (val(bitrate).length() > 0) r.put("mp3_bitrate", Integer.parseInt(val(bitrate)));
        r.put("output_format", FORMATS[formatSpin.getSelectedItemPosition()]);
        if (val(tempScore).length() > 0) {
            r.put("abc_sampling", sampling(0.7, 0.9, 30, 1.005, 100, 32, 4096, Double.parseDouble(val(tempScore))));
        }
        if (val(tempSong).length() > 0) {
            r.put("semantic_sampling", sampling(1.0, 0.95, 100, 1.2, 50, 200, 9000, Double.parseDouble(val(tempSong))));
        }
        String ad = val(adaptersBox);
        if (ad.length() > 0) {
            JSONArray arr = new JSONArray();
            String[] items = ad.split(",");
            for (int i = 0; i < items.length; i++) {
                String it = items[i].trim();
                if (it.length() == 0) continue;
                int k = it.lastIndexOf(':');
                JSONObject a = new JSONObject();
                a.put("name", k > 0 ? it.substring(0, k).trim() : it);
                a.put("scale", k > 0 ? Double.parseDouble(it.substring(k + 1).trim()) : 1.0);
                arr.put(a);
            }
            r.put("adapters", arr);
        }
        if (semTokens.length() > 0) {
            r.put("semantic_tokens", semTokens);
        } else if (srcTokens.length() > 0) {
            r.put("source_tokens", srcTokens);
            r.put("source_start", srcStart);
            r.put("source_end", srcEnd);
        }
        return r;
    }

    static JSONObject sampling(double t, double p, int k, double rep, int win, int min, int max, double temp) throws Exception {
        JSONObject o = new JSONObject();
        o.put("temperature", temp);
        o.put("top_p", p);
        o.put("top_k", k);
        o.put("repetition_penalty", rep);
        o.put("penalty_window", win);
        o.put("min_tokens", min);
        o.put("max_tokens", max);
        return o;
    }

    /** The form as a request, or null (with a message shown) when it cannot be sent. */
    JSONObject formRequest() {
        if (val(style).length() == 0 && val(lyrics).length() == 0 && semTokens.length() == 0) {
            toast("Write a style or some lyrics first");
            return null;
        }
        if (dropMissingAdapters()) toast("An adapter that is no longer installed was left out");
        try {
            return buildRequest();
        } catch (Exception e) {
            alert("Check the settings", "One of the numbers in Advanced settings is not valid.");
            return null;
        }
    }

    /** Asked once, before the first song: Android's own dialog for running without battery optimisation. */
    void askBackgroundThenGenerate() {
        cfg.edit().putBoolean("bg_asked", true).apply();
        new Lux.Builder(this).setTitle("Let songs finish in the background?")
                .setMessage("A song takes a while. To keep making it while you use other apps or the screen is off, "
                        + "Android needs to let Killa YuE run without battery optimisation. The next screen asks for that.\n\n"
                        + "You can change it later on the Engine tab.")
                .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Background.askExemption(MainActivity.this);
                        generate();
                    }
                })
                .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        generate();
                    }
                }).show();
    }

    void generate() {
        JSONObject req = formRequest();
        pendingAlbum = "";
        if (req != null) startSong(req, val(songTitle), false);
    }

    void startSong(final JSONObject req, String title, final boolean fromQueue) {
        final Context app = getApplicationContext();
        pendingTitle = title;
        // a checkpoint folder: the song can carry on from where it stops (Resume)
        Resume.prepare(app, req, title, pendingAlbum);
        startJob("Starting", new Task() {
            public void run() throws Exception {
                ensureEngine(app);
                jobMsg = "Queued";
                runningCheckpoint = req.optString("checkpoint");
                String id = Api.synth(req);
                waitJob(id);
                jobMsg = "Saving";
                int n = saveTracks(app, Api.parts(Api.resultRaw(id)), req);
                if (n == 0) throw new Exception("The engine finished but returned no audio.");
                Resume.finished(req);
                runningCheckpoint = "";
            }

            public void done() {
                coverNewest();
                if (fromQueue) {
                    refreshLibrary();
                    toast(queue.size() > 0 ? "Song ready. " + queue.size() + " more in the queue." : "Song ready. The queue is finished.");
                } else {
                    showTab(TAB_LIBRARY);
                    toast("Song ready");
                }
            }
        });
    }

    /** After a song is saved: paint a cover for it if the cover model is installed. */
    void coverNewest() {
        refreshLibrary(new Runnable() {
            public void run() {
                if (playlist.size() > 0 && !Covers.coverFor(MainActivity.this, playlist.get(0)).isFile()) makeCover(playlist.get(0), false);
                syncNewest();
            }
        });
    }

    // ---------- wake-up song ----------

    Button wakeBtn;

    String wakeLabel() {
        String w = Wake.describe(this);
        return w.length() > 0 ? "Wake me at " + w : "Set a wake-up song";
    }

    void askWake() {
        final boolean on = Wake.on(this);
        final String[] items = on ? new String[] {"Change the time", "Turn it off"} : new String[] {"Choose a time"};
        new Lux.Builder(this).setTitle(on ? "Wake me at " + Wake.describe(this) : "Wake-up song")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if ("Turn it off".equals(items[w])) {
                            Wake.off(MainActivity.this);
                            wakeBtn.setText(wakeLabel());
                            toast("The wake-up song is off");
                        } else {
                            pickWakeTime();
                        }
                    }
                }).show();
    }

    void pickWakeTime() {
        new android.app.TimePickerDialog(this, new android.app.TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(android.widget.TimePicker t, final int h, final int min) {
                final String[] when = {"Every morning", "Just once"};
                new Lux.Builder(MainActivity.this).setTitle("How often?").setItems(when, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Wake.set(MainActivity.this, h, min, w == 0);
                        wakeBtn.setText(wakeLabel());
                        long in = (Wake.next(h, min) - System.currentTimeMillis()) / 60000;
                        toast("The alarm rings in " + (in / 60) + " h " + (in % 60) + " min. Start the queue and leave the phone charging.");
                    }
                }).show();
            }
        }, cfg.getInt("wake_h", 7), cfg.getInt("wake_m", 0), android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    // ---------- queue: songs lined up to be made one after another ----------

    static final ArrayList<JSONObject> queue = new ArrayList<JSONObject>();
    static volatile boolean queueRunning;
    static final int[] QUEUE_LEVELS = {50, 60, 70, 75, 80, 85, 90, 95};
    Button queueBtn;
    String queueNote = "";

    File queueFile() {
        return new File(Store.dir(this, "saved"), "queue.json");
    }

    void loadQueue() {
        if (queue.size() > 0) return;
        try {
            JSONArray a = new JSONArray(new String(Store.read(queueFile()), "UTF-8"));
            for (int i = 0; i < a.length(); i++) queue.add(a.getJSONObject(i));
        } catch (Exception e) {
            // no queue saved yet
        }
    }

    void saveQueue() {
        try {
            Store.write(queueFile(), new JSONArray(queue).toString().getBytes("UTF-8"));
        } catch (Exception e) {
            toast("Could not save the queue");
        }
    }

    void addToQueue() {
        JSONObject req = formRequest();
        if (req == null) return;
        try {
            JSONObject item = new JSONObject();
            String t = val(songTitle);
            item.put("title", t.length() > 0 ? t : titleFor(req));
            item.put("given", t);
            item.put("req", req);
            queue.add(item);
            saveQueue();
            toast("Added to the queue. Open the queue to start it.");
        } catch (Exception e) {
            toast("Could not add this song to the queue");
        }
    }

    /** Charge in percent, or -1 when unknown; charging[0] is set when plugged in. */
    int battery(boolean[] charging) {
        Intent b = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return -1;
        if (charging != null) charging[0] = b.getIntExtra("plugged", 0) != 0;
        int level = b.getIntExtra("level", -1), scale = b.getIntExtra("scale", 100);
        return level < 0 ? -1 : level * 100 / Math.max(1, scale);
    }

    void showQueue() {
        if (queue.size() == 0) {
            alert("Queue", "Nothing is queued. Fill in a song and press \"Add to queue\"; do that for as many songs as you like, "
                    + "then start the queue and leave the phone on its charger. The battery level it waits for is set on the Engine tab.");
            return;
        }
        final String[] names = new String[queue.size()];
        for (int i = 0; i < names.length; i++) names[i] = (i + 1) + ".  " + queue.get(i).optString("title");
        AlertDialog.Builder b = new Lux.Builder(this)
                .setTitle(queueRunning ? "Queue (running)" : "Queue (paused)")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, final int which) {
                        new Lux.Builder(MainActivity.this).setTitle(queue.get(which).optString("title"))
                                .setMessage("Take this song out of the queue?")
                                .setPositiveButton("Take it out", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dd, int w) {
                                        if (which < queue.size()) queue.remove(which);
                                        saveQueue();
                                    }
                                }).setNegativeButton("Keep", null).show();
                    }
                })
                .setNegativeButton("Close", null);
        if (queueRunning) {
            b.setPositiveButton("Pause", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    queueRunning = false;
                    toast("Queue paused. The song being made now will still finish.");
                }
            });
        } else {
            b.setPositiveButton("Start", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    jobError = "";
                    queueRunning = true;
                    toast("Queue started. Keep the phone on its charger.");
                }
            });
        }
        b.show();
    }

    /** Runs on the tick: starts the next queued song when the engine is free and the phone is fit for it. */
    void runQueue() {
        queueBtn.setText(queue.size() > 0 ? "Queue (" + queue.size() + ")" : "Queue");
        queueNote = "";
        if (!queueRunning) return;
        if (queue.size() == 0) {
            queueRunning = false;
            return;
        }
        if (jobBusy) return;
        if (jobError.length() > 0 && !"Cancelled".equals(jobError)) {
            // a failure stops the queue so the same fault is not repeated all night
            queueRunning = false;
            queueNote = "The queue stopped because a song failed.";
            return;
        }
        boolean[] charging = new boolean[1];
        int level = battery(charging);
        int need = cfg.getInt("queue_battery", 75);
        if (level >= 0 && level < need) {
            queueNote = "Queue is waiting for the battery to reach " + need + "% (now " + level + "%"
                    + (charging[0] ? ", charging)" : ", not charging)");
            return;
        }
        String heat = gpuHeat();
        if (heat.length() > 0) {
            queueNote = "Queue is waiting for the phone to cool down.";
            return;
        }
        JSONObject item = queue.remove(0);
        saveQueue();
        JSONObject req = item.optJSONObject("req");
        pendingAlbum = item.optString("album");
        if (req != null) startSong(req, item.optString("given"), true);
    }

    // ---------- jobs ----------

    interface Task {
        void run() throws Exception;

        void done();
    }

    void startJob(String msg, final Task t) {
        if (jobBusy) {
            toast("The engine is busy with another job");
            return;
        }
        jobBusy = true;
        jobCancel = false;
        jobError = "";
        Songwriter.cancel();
        Songwriter.stopNow(this); // the song engine needs the memory the songwriter's model holds
        jobMsg = msg;
        jobId = null;
        jobStart = System.currentTimeMillis();
        EngineService.setStage("", 0, 0);
        int st = 32;
        try {
            if (val(steps).length() > 0) st = Integer.parseInt(val(steps));
        } catch (Exception e) {
            // the default number of render passes
        }
        Eta.begin(getApplicationContext(), lengthSeconds(), st, EngineService.gentle(this));
        Widget.refresh(this, true);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    t.run();
                } catch (Exception e) {
                    err = jobCancel ? "Cancelled" : String.valueOf(e.getMessage());
                }
                final String ferr = err;
                Eta.end();
                jobBusy = false;
                jobId = null;
                jobError = ferr == null ? "" : ferr;
                EngineService.setStage("", 0, 0);
                Widget.refresh(inst != null ? inst : EngineService.self, true); // the screen may be off and the app closed
                if (ferr == null) EngineService.announce("Finished. Tap to open.");
                else if (!"Cancelled".equals(ferr)) EngineService.announce("That did not work: " + ferr);
                final MainActivity a = inst;
                if (a != null) {
                    a.ui.post(new Runnable() {
                        public void run() {
                            if (ferr == null) t.done();
                            else if (!"Cancelled".equals(ferr)) a.failed(ferr);
                        }
                    });
                }
            }
        }, "job").start();
    }

    /** A song that failed: offer to carry on from its checkpoint when something was saved. */
    void failed(String err) {
        final String cp = runningCheckpoint;
        runningCheckpoint = "";
        Resume.Unfinished found = null;
        if (cp.length() > 0) {
            for (Resume.Unfinished u : Resume.list(this, "")) if (u.dir.getAbsolutePath().equals(cp)) found = u;
        }
        if (found == null || !found.resumable()) {
            alert("That did not work", err);
            return;
        }
        final Resume.Unfinished u = found;
        new Lux.Builder(this).setTitle("That did not work")
                .setMessage(err + "\n\nWhat was made so far is saved (" + u.describe().replaceFirst("^.*?·  ", "")
                        + "). Resume carries on from there.")
                .setPositiveButton("Resume", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        resume(u);
                    }
                }).setNegativeButton("Later", null).show();
    }

    void resume(Resume.Unfinished u) {
        try {
            pendingAlbum = u.request.optString("_album");
            startSong(Resume.request(u), u.title, false);
        } catch (Exception e) {
            alert("Could not resume", String.valueOf(e.getMessage()));
        }
    }

    /** Songs that stopped part way: carry one on, or let it go. */
    void resumeMenu() {
        final ArrayList<Resume.Unfinished> list = Resume.list(this, jobBusy ? runningCheckpoint : "");
        if (list.isEmpty()) {
            toast("No unfinished songs");
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).describe();
        new Lux.Builder(this).setTitle("Unfinished songs").setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int w) {
                final Resume.Unfinished u = list.get(w);
                AlertDialog.Builder b = new Lux.Builder(MainActivity.this).setTitle(u.title.length() > 0 ? u.title : "Untitled")
                        .setMessage(u.describe().replaceFirst("^.*?·  ", ""))
                        .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d2, int w2) {
                                Resume.delete(u.dir);
                                toast("Deleted");
                            }
                        }).setNegativeButton("Back", null);
                if (u.resumable()) {
                    b.setPositiveButton("Resume", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d2, int w2) {
                            if (jobBusy) {
                                toast("The engine is busy with another job");
                                return;
                            }
                            resume(u);
                        }
                    });
                }
                b.show();
            }
        }).setNegativeButton("Close", null).show();
    }

    void cancelJob() {
        jobCancel = true;
        final String id = jobId;
        if (id != null) {
            new Thread(new Runnable() {
                public void run() {
                    Api.cancel(id);
                }
            }).start();
        }
    }

    static void ensureEngine(Context app) throws Exception {
        if (!Store.coreReady(app)) throw new Exception("The model is not downloaded yet. Open the Engine tab and download it.");
        if (!EngineService.running) EngineService.start(app);
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 180000) {
            if (jobCancel) throw new Exception("Cancelled");
            if (Api.healthy()) {
                engineHealthy = true;
                return;
            }
            if (!EngineService.running && System.currentTimeMillis() - t0 > 4000) {
                throw new Exception("The engine did not start. " + EngineService.lastError + "\nSee the log on the Engine tab.");
            }
            Thread.sleep(500);
        }
        throw new Exception("The engine did not answer within three minutes. See the log on the Engine tab.");
    }

    static void waitJob(String id) throws Exception {
        jobId = id;
        jobMsg = "";
        while (true) {
            Thread.sleep(1500);
            if (!EngineService.running) {
                String why = EngineService.lastError;
                if (why.startsWith("Android stopped the engine")) throw new Exception(why);
                throw new Exception("The engine stopped while working. " + why
                        + "\nThis usually means the phone ran out of memory. Try a shorter song, a smaller context, or the CPU device.");
            }
            String s = Api.status(id);
            if ("done".equals(s)) return;
            if ("cancelled".equals(s)) {
                jobCancel = true;
                throw new Exception("Cancelled");
            }
            if ("failed".equals(s)) throw new Exception("The engine reported a failure. " + EngineService.lastError);
        }
    }

    static String titleFor(JSONObject req) {
        // the first sung line: not a [Section] tag, not a (stage direction); pasted text can carry invisible characters
        String[] lines = req.optString("lyrics").replace("\u200b", "").replace("\ufeff", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.length() > 0 && !l.startsWith("[") && !l.startsWith("(")) return l.length() > 48 ? l.substring(0, 48) : l;
        }
        String s = req.optString("style").trim();
        if (s.length() > 0) return s.length() > 48 ? s.substring(0, 48) : s;
        return "Untitled";
    }

    static int saveTracks(Context app, ArrayList<Api.Part> parts, JSONObject sent) throws Exception {
        File lib = Store.library(app);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        JSONObject meta = null;
        int n = 0;
        for (int i = 0; i < parts.size(); i++) {
            Api.Part p = parts.get(i);
            if (p.type.startsWith("application/json")) {
                meta = new JSONObject(new String(p.body, "UTF-8"));
            } else if (p.type.startsWith("audio/")) {
                if (meta == null) meta = new JSONObject(sent.toString());
                String ext = p.type.contains("wav") ? ".wav" : ".mp3";
                String base = stamp + "-" + (n + 1);
                Store.write(new File(lib, base + ext), p.body);
                String tok = meta.optString("semantic_tokens");
                int frames = tok.length() == 0 ? 0 : tok.split(",").length;
                String title = pendingTitle.length() > 0 ? pendingTitle : titleFor(meta);
                meta.put("_title", title + (n > 0 ? " (take " + (n + 1) + ")" : ""));
                meta.put("_audio", base + ext);
                meta.put("_created", System.currentTimeMillis());
                meta.put("_seconds", frames / 25);
                Store.write(new File(lib, base + ".json"), meta.toString().getBytes("UTF-8"));
                if (pendingAlbum.length() > 0) Albums.add(app, pendingAlbum, base + ".json"); // made for an album
                try {
                    Tags.write(new File(lib, base + ext), meta.optString("_title"),
                            Karaoke.build(meta.optString("lyrics"), meta.optString("abc"), frames * 40));
                } catch (Exception e) {
                    // the song itself is saved; the lyrics file can be made later from the Lyrics tab
                }
                meta = null;
                n++;
            }
        }
        return n;
    }

    // ---------- import a recording ----------

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (code == PICK_ADAPTER) {
            importAdapterFile(data.getData());
            return;
        }
        if (code == PICK_RECIPE) {
            openRecipe(data.getData());
            return;
        }
        if (code == PICK_SKIN) {
            installSkin(data.getData());
            return;
        }
        if (code != PICK_AUDIO) return;
        final Uri u = data.getData();
        String last = u.getLastPathSegment();
        pickedName = last == null ? "recording" : last.substring(last.lastIndexOf('/') + 1);
        final String[] options = {
            "Cover it (melody only, best for a new style)",
            "Cover it (melody and chords)",
            "Start a new song with part of it",
            "Re-render it through YuE",
        };
        new Lux.Builder(this).setTitle("What should happen with this recording?")
                .setItems(options, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        importAudio(u, which);
                    }
                }).show();
    }

    void importAudio(final Uri u, final int mode) {
        final Context app = getApplicationContext();
        final boolean transcribe = mode < 2;
        if (!Store.have(app, Store.MERT) || !Store.have(app, transcribe ? Store.SHEET : Store.TOKENIZER)) {
            alert("One more download needed", "Working from a recording needs the "
                    + (transcribe ? "cover pack" : "recording pack") + ". Download it on the Engine tab, then stop and start the engine.");
            showTab(TAB_ENGINE);
            return;
        }
        final String[] out = new String[1];
        startJob("Reading the recording", new Task() {
            public void run() throws Exception {
                InputStream in = getContentResolver().openInputStream(u);
                byte[] audio;
                try {
                    audio = Api.readAll(in);
                } finally {
                    in.close();
                }
                ensureEngine(app);
                String id = Api.upload(transcribe ? "/transcribe" : "/tokenize", audio, "audio", mode == 0);
                waitJob(id);
                byte[] raw = Api.resultRaw(id);
                JSONObject j;
                try {
                    j = new JSONObject(new String(raw, "UTF-8"));
                } catch (Exception e) {
                    ArrayList<Api.Part> ps = Api.parts(raw);
                    if (ps.size() == 0) throw new Exception("The engine returned nothing for this recording.");
                    j = new JSONObject(new String(ps.get(0).body, "UTF-8"));
                }
                out[0] = transcribe ? j.optString("abc") : j.optString("codes");
                if (out[0].length() == 0) throw new Exception("Nothing could be read from this recording.");
            }

            public void done() {
                clearCarry();
                carryName = pickedName;
                if (transcribe) {
                    score.setText(out[0]);
                    showScore(true);
                    cotSpin.setSelection(mode == 0 ? 1 : 0);
                    toast("Score ready. Write a style and lyrics, then Generate.");
                } else if (mode == 2) {
                    srcTokens = out[0];
                    askSection(out[0].split(",").length / 25);
                } else {
                    semTokens = out[0];
                    showBanner();
                    toast("Recording loaded. Generate renders it again.");
                }
            }
        });
    }

    void askSection(int total) {
        LinearLayout l = row();
        l.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText a = numEdit("0");
        final EditText b = numEdit(String.valueOf(total));
        l.addView(labelled("From second", a));
        l.addView(labelled("To second", b));
        new Lux.Builder(this).setTitle("Which part starts the new song?")
                .setMessage("The source is " + clock(total) + " long.").setView(l)
                .setPositiveButton("Use it", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            srcStart = val(a).length() == 0 ? 0 : Double.parseDouble(val(a));
                            srcEnd = val(b).length() == 0 ? -1 : Double.parseDouble(val(b));
                        } catch (Exception e) {
                            srcStart = 0;
                            srcEnd = -1;
                        }
                        showBanner();
                        showTab(0);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        clearCarry();
                    }
                }).show();
    }

    // ---------- Library ----------

    View buildLibrary() {
        libraryBox = column();
        return libraryBox;
    }

    /** What the Library tab shows, read from storage off the screen thread. */
    static class LibData {
        final ArrayList<File> metas = new ArrayList<File>();
        final ArrayList<JSONObject> loaded = new ArrayList<JSONObject>();
        final java.util.HashMap<String, Integer> familySize = new java.util.HashMap<String, Integer>();
        final ArrayList<android.graphics.Bitmap> thumbs = new ArrayList<android.graphics.Bitmap>();
    }

    int libraryGen;
    final ArrayList<Runnable> libraryAfter = new ArrayList<Runnable>();

    void refreshLibrary() {
        refreshLibrary(null);
    }

    /**
     * Reads the library on a background thread (shared storage can stall for seconds while a song
     * is made), then rebuilds the list on screen. after, if given, runs once the list is shown.
     */
    void refreshLibrary(final Runnable after) {
        if (libraryBox == null) return;
        if (after != null) libraryAfter.add(after); // kept until a refresh lands, even if this one is superseded
        final int gen = ++libraryGen;
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final LibData d = loadLibrary(app);
                ui.post(new Runnable() {
                    public void run() {
                        if (gen != libraryGen || libraryBox == null) return; // a newer refresh is on its way
                        showLibrary(d);
                        ArrayList<Runnable> run = new ArrayList<Runnable>(libraryAfter);
                        libraryAfter.clear();
                        for (int i = 0; i < run.size(); i++) run.get(i).run();
                    }
                });
            }
        }, "library").start();
    }

    static LibData loadLibrary(Context c) {
        LibData d = new LibData();
        File[] files = Store.library(c).listFiles();
        if (files == null) return d;
        final java.util.HashMap<File, Long> mod = new java.util.HashMap<File, Long>();
        for (int i = 0; i < files.length; i++) {
            if (!files[i].getName().endsWith(".json")) continue;
            d.metas.add(files[i]);
            mod.put(files[i], Long.valueOf(files[i].lastModified()));
        }
        java.util.Collections.sort(d.metas, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(mod.get(b).longValue(), mod.get(a).longValue());
            }
        });
        for (int i = 0; i < d.metas.size(); i++) {
            JSONObject j = null;
            try {
                j = new JSONObject(new String(Store.read(d.metas.get(i)), "UTF-8"));
                String fam = family(j);
                Integer n = d.familySize.get(fam);
                d.familySize.put(fam, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
            } catch (Exception e) {
                j = null;
            }
            d.loaded.add(j);
            d.thumbs.add(j == null ? null : Covers.load(Covers.coverFor(c, j), 8));
        }
        return d;
    }

    void showLibrary(LibData d) {
        libraryBox.removeAllViews();
        ArrayList<File> metas = d.metas;
        if (metas.size() == 0) {
            TextView t = text("No songs yet. Make one on the Create tab.", DIM, 16);
            t.setPadding(0, dp(24), 0, 0);
            libraryBox.addView(t);
            return;
        }
        libraryBox.addView(chipRow(new String[] {"Albums", "Share the app", "Sleep timer", "Open a recipe"}, new Runnable[] {
            new Runnable() {
                public void run() {
                    showAlbums();
                }
            },
            new Runnable() {
                public void run() {
                    shareAppQr();
                }
            },
            new Runnable() {
                public void run() {
                    askSleepTimer();
                }
            },
            new Runnable() {
                public void run() {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, "Pick a .killa recipe"), PICK_RECIPE);
                }
            },
        }));
        playlist.clear();
        SimpleDateFormat df = new SimpleDateFormat("d MMM, HH:mm", Locale.US);
        java.util.HashMap<String, Integer> familySize = d.familySize;
        ArrayList<JSONObject> loaded = d.loaded;
        for (int i = 0; i < metas.size(); i++) {
            final File mf = metas.get(i);
            final JSONObject m = loaded.get(i);
            if (m == null) continue;
            playlist.add(m);
            LinearLayout r = row();
            r.setBackground(glass(EDGE));
            r.setPadding(dp(14), dp(12), dp(8), dp(12));
            android.graphics.Bitmap thumb = d.thumbs.get(i);
            if (thumb != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageBitmap(thumb);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(54), dp(54));
                tl.rightMargin = dp(12);
                r.addView(iv, tl);
            }
            LinearLayout info = column();
            TextView t = text(m.optString("_title", "Untitled"), TEXT, 15);
            t.setSingleLine(true);
            info.addView(t);
            String sub = clock(m.optInt("_seconds")) + "  ·  " + df.format(new Date(m.optLong("_created")));
            int takes = familySize.get(family(m)).intValue();
            if (takes > 1) sub += "  ·  one of " + takes + " takes";
            String st = m.optString("style");
            if (st.length() > 0) sub += "\n" + (st.length() > 60 ? st.substring(0, 60) + "…" : st);
            info.addView(text(sub, DIM, 12));
            r.addView(info, weight());
            Button more = chip("⋯");
            r.addView(more);
            r.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    retitle(mf, m);
                    return true;
                }
            });
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    albumOrder = null; // a song tapped in the library plays in library order
                    play(m);
                }
            });
            more.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    trackMenu(mf, m);
                }
            });
            libraryBox.addView(r, gap(10));
        }
    }

    void loadPrompt(JSONObject m, boolean withScore) {
        checkpoint();
        style.setText(m.optString("style"));
        lyrics.setText(m.optString("lyrics"));
        String cot = m.optString("cot", "full");
        for (int i = 0; i < COT.length; i++) if (COT[i].equals(cot)) cotSpin.setSelection(i);
        score.setText(withScore ? m.optString("abc") : "");
        showScore(withScore && m.optString("abc").length() > 0);
        settle();
    }

    void trackMenu(final File mf, final JSONObject m) {
        final String title = m.optString("_title", "Untitled");
        final String tokens = m.optString("semantic_tokens");
        final String[] items = {
            "Play", "Show the lyrics", "Other takes of this song", "New sound, same song", "Edit the score and render again",
            "Open in Studio", "Continue from this song", "Use the same style and lyrics", "Make a cover", "Make a lyric video", "Make it a ringtone", "Sheet music and MIDI", "Add to an album", "Share", "Share as a recipe",
            "Share with a QR code", "Change the title", "Details", "Delete",
        };
        new Lux.Builder(this).setTitle(title).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                final String pick = items[w];
                if ("Play".equals(pick)) {
                    play(m);
                } else if ("Show the lyrics".equals(pick)) {
                    if (!m.optString("_audio").equals(playingFile)) play(m);
                    showTab(TAB_LYRICS);
                } else if ("New sound, same song".equals(pick)) {
                    if (tokens.length() == 0) {
                        toast("This song has no saved melody data");
                        return;
                    }
                    clearCarry();
                    loadPrompt(m, true);
                    semTokens = tokens;
                    carryName = title;
                    seed.setText("");
                    showBanner();
                    showTab(0);
                } else if ("Edit the score and render again".equals(pick)) {
                    clearCarry();
                    loadPrompt(m, true);
                    showScore(true);
                    lmSeed.setText("");
                    showTab(0);
                    toast("Change the score, then Generate. The song is written again from it.");
                } else if ("Continue from this song".equals(pick)) {
                    if (tokens.length() == 0) {
                        toast("This song has no saved melody data");
                        return;
                    }
                    clearCarry();
                    loadPrompt(m, false);
                    srcTokens = tokens;
                    carryName = title;
                    askSection(tokens.split(",").length / 25);
                } else if ("Use the same style and lyrics".equals(pick)) {
                    clearCarry();
                    loadPrompt(m, false);
                    showTab(0);
                } else if ("Share".equals(pick)) {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    String a = m.optString("_audio");
                    s.setType(a.endsWith(".wav") ? "audio/wav" : "audio/mpeg");
                    s.putExtra(Intent.EXTRA_STREAM, TrackProvider.uri(a));
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Share song"));
                } else if ("Change the title".equals(pick)) {
                    retitle(mf, m);
                } else if ("Details".equals(pick)) {
                    String abc = m.optString("abc");
                    alert(title, "File: " + new File(Store.library(MainActivity.this), m.optString("_audio")).getAbsolutePath()
                            + "\n\nStyle: " + m.optString("style")
                            + "\n\nMelody seed: " + m.optString("lm_seed") + "\nSound seed: " + m.optString("seed")
                            + "\nRender steps: " + m.optString("steps")
                            + "\n\nLyrics:\n" + m.optString("lyrics")
                            + (abc.length() > 0 ? "\n\nScore:\n" + abc : ""));
                } else if ("Other takes of this song".equals(pick)) {
                    showTakes(m);
                } else if ("Make a cover".equals(pick)) {
                    makeCover(m, true);
                } else if ("Share as a recipe".equals(pick)) {
                    shareRecipe(m);
                } else if ("Share with a QR code".equals(pick)) {
                    shareSongQr(m);
                } else if ("Make a lyric video".equals(pick)) {
                    makeLyricVideo(m);
                } else if ("Open in Studio".equals(pick)) {
                    ai.jackknife.killayue.studio.ui.StudioActivity.openSong(MainActivity.this, mf);
                } else if ("Make it a ringtone".equals(pick)) {
                    Ringtones.ask(MainActivity.this, m);
                } else if ("Sheet music and MIDI".equals(pick)) {
                    sheetMusic(m);
                } else if ("Add to an album".equals(pick)) {
                    addToAlbum(mf.getName());
                } else {
                    new Lux.Builder(MainActivity.this).setTitle("Delete this song?")
                            .setMessage(title + "\nThe audio file and its saved melody are removed for good.")
                            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dd, int ww) {
                                    if (m.optString("_audio").equals(playingFile)) stopPlayer();
                                    new File(Store.library(MainActivity.this), m.optString("_audio")).delete();
                                    mf.delete();
                                    refreshLibrary();
                                }
                            }).setNegativeButton("Keep", null).show();
                }
            }
        }).show();
    }

    /** Paints a cover on the NPU in the background, then embeds it in the song and refreshes the list. */
    // ---------- albums: an ordered set of songs with one title, one sound and one cover ----------

    void showAlbums() {
        final ArrayList<JSONObject> list = Albums.all(this);
        final String[] items = new String[list.size() + 1];
        items[0] = "New album";
        for (int i = 0; i < list.size(); i++) {
            JSONObject a = list.get(i);
            int n = a.optJSONArray("tracks") == null ? 0 : a.optJSONArray("tracks").length();
            items[i + 1] = a.optString("title") + "   ·   " + n + (n == 1 ? " song" : " songs");
        }
        new Lux.Builder(this).setTitle("Albums").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) newAlbum(null);
                else albumMenu(list.get(w - 1));
            }
        }).show();
    }

    /** Asks for a title; the album's sound starts as the Create tab's style. addSong, if given, goes in first. */
    void newAlbum(final String addSong) {
        final EditText name = new EditText(this);
        name.setHint("Album title");
        name.setSingleLine(true);
        new Lux.Builder(this).setTitle("New album").setView(name)
                .setMessage("The album's sound starts as the style on the Create tab; songs made for the album use it.")
                .setPositiveButton("Make it", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String t = name.getText().toString().trim();
                        if (t.length() == 0) t = "Untitled album";
                        try {
                            JSONObject a = Albums.create(MainActivity.this, t, val(style));
                            if (addSong != null) Albums.add(MainActivity.this, a.getString("id"), addSong);
                            toast(addSong != null ? "Album made, with this song in it" : "Album made");
                            albumMenu(Albums.get(MainActivity.this, a.getString("id")));
                        } catch (Exception e) {
                            toast("Could not make the album: " + e.getMessage());
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** From a song's menu. songJson is the song's .json name in the library. */
    void addToAlbum(final String songJson) {
        final ArrayList<JSONObject> list = Albums.all(this);
        final String[] items = new String[list.size() + 1];
        items[0] = "New album";
        for (int i = 0; i < list.size(); i++) items[i + 1] = list.get(i).optString("title");
        new Lux.Builder(this).setTitle("Add to an album").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    newAlbum(songJson);
                } else {
                    Albums.add(MainActivity.this, list.get(w - 1).optString("id"), songJson);
                    toast("Added to " + list.get(w - 1).optString("title"));
                }
            }
        }).show();
    }

    void albumMenu(final JSONObject a) {
        if (a == null) return;
        final String id = a.optString("id");
        final String[] items = {
            "Play the album", "Songs in this album", "Add songs from the library", "Make new songs for this album",
            "Paint the album cover", "Use the Create tab's style for this album", "Rename", "Export to Music", "Share with a QR code", "Share the album",
            "Delete the album",
        };
        String sub = a.optString("style").length() > 0 ? "Sound: " + a.optString("style") : "No sound set yet";
        new Lux.Builder(this).setTitle(a.optString("title") + "\n" + sub).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String pick = items[w];
                JSONObject fresh = Albums.get(MainActivity.this, id);
                if (fresh == null) return;
                if ("Play the album".equals(pick)) {
                    ArrayList<JSONObject> songs = Albums.songs(MainActivity.this, fresh);
                    if (songs.size() == 0) {
                        toast("This album has no songs yet");
                        return;
                    }
                    albumOrder = songs;
                    play(songs.get(0));
                } else if ("Songs in this album".equals(pick)) {
                    albumSongs(fresh);
                } else if ("Add songs from the library".equals(pick)) {
                    addSongsTo(fresh);
                } else if ("Make new songs for this album".equals(pick)) {
                    queueAlbumSongs(fresh);
                } else if ("Paint the album cover".equals(pick)) {
                    paintAlbumCover(fresh);
                } else if ("Use the Create tab's style for this album".equals(pick)) {
                    try {
                        fresh.put("style", val(style));
                        Albums.save(MainActivity.this, fresh);
                        toast(val(style).length() > 0 ? "The album's sound is now the Create tab's style" : "The album has no sound set now");
                    } catch (Exception e) {
                        toast("Could not save the album");
                    }
                } else if ("Rename".equals(pick)) {
                    renameAlbum(fresh);
                } else if ("Share with a QR code".equals(pick)) {
                    shareAlbumQr(fresh);
                } else if ("Export to Music".equals(pick)) {
                    exportAlbum(fresh, false);
                } else if ("Share the album".equals(pick)) {
                    exportAlbum(fresh, true);
                } else {
                    final JSONObject gone = fresh;
                    new Lux.Builder(MainActivity.this).setTitle("Delete this album?")
                            .setMessage(gone.optString("title") + "\nThe songs stay in your library; only the album goes.")
                            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d2, int w2) {
                                    Albums.delete(MainActivity.this, gone.optString("id"));
                                    albumOrder = null;
                                    toast("Album deleted");
                                }
                            }).setNegativeButton("Keep", null).show();
                }
            }
        }).show();
    }

    void albumSongs(final JSONObject a) {
        final ArrayList<JSONObject> songs = Albums.songs(this, a);
        if (songs.size() == 0) {
            toast("This album has no songs yet. Add some from the library or make new ones.");
            return;
        }
        String[] items = new String[songs.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = (i + 1) + ".  " + songs.get(i).optString("_title", "Untitled") + "   " + clock(songs.get(i).optInt("_seconds"));
        }
        new Lux.Builder(this).setTitle(a.optString("title")).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int at) {
                final String[] acts = {"Play from here", "Move up", "Move down", "Take out of the album"};
                new Lux.Builder(MainActivity.this).setTitle(songs.get(at).optString("_title"))
                        .setItems(acts, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d2, int w) {
                                try {
                                    // songs deleted from the library are not listed, so find this one's place by name
                                    String name = metaFile(songs.get(at)).getName();
                                    JSONArray t = a.optJSONArray("tracks");
                                    int pos = -1;
                                    for (int i = 0; i < t.length(); i++) if (name.equals(t.optString(i))) pos = i;
                                    if (w == 0) {
                                        albumOrder = songs;
                                        play(songs.get(at));
                                        return;
                                    }
                                    if (pos < 0) return;
                                    if (w == 1) Albums.move(MainActivity.this, a, pos, pos - 1);
                                    else if (w == 2) Albums.move(MainActivity.this, a, pos, pos + 1);
                                    else Albums.remove(MainActivity.this, a, pos);
                                    albumSongs(Albums.get(MainActivity.this, a.optString("id")));
                                } catch (Exception e) {
                                    toast("Could not change the album");
                                }
                            }
                        }).show();
            }
        }).show();
    }

    void addSongsTo(final JSONObject a) {
        if (playlist.size() == 0) {
            toast("No songs in the library yet");
            return;
        }
        final ArrayList<JSONObject> all = new ArrayList<JSONObject>(playlist);
        String[] items = new String[all.size()];
        final boolean[] on = new boolean[all.size()];
        for (int i = 0; i < items.length; i++) items[i] = all.get(i).optString("_title", "Untitled");
        new Lux.Builder(this).setTitle("Add to " + a.optString("title"))
                .setMultiChoiceItems(items, on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int w, boolean c) {
                        on[w] = c;
                    }
                })
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        // oldest first, so the album runs in the order the songs were made
                        for (int i = all.size() - 1; i >= 0; i--) {
                            if (on[i] && Albums.add(MainActivity.this, a.optString("id"), metaFile(all.get(i)).getName())) n++;
                        }
                        toast(n + (n == 1 ? " song" : " songs") + " added to " + a.optString("title"));
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** The album builder: one queued song per saved lyric, all in the album's sound; each joins the album when it is made. */
    void queueAlbumSongs(final JSONObject a) {
        final JSONArray saved = loadSaved("lyrics");
        if (saved.length() == 0) {
            alert("No saved lyrics yet", "Write lyrics on the Create tab and press \"Save these lyrics\" for each song you want on the album. "
                    + "Then come back here and pick them; each becomes a song in the album's sound.");
            return;
        }
        String[] items = new String[saved.length()];
        final boolean[] on = new boolean[saved.length()];
        for (int i = 0; i < items.length; i++) items[i] = saved.optJSONObject(i).optString("name");
        new Lux.Builder(this).setTitle("Songs for " + a.optString("title"))
                .setMultiChoiceItems(items, on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int w, boolean c) {
                        on[w] = c;
                    }
                })
                .setPositiveButton("Queue them", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        JSONObject base = formRequest();
                        if (base == null) return;
                        int n = 0;
                        try {
                            for (int i = 0; i < on.length; i++) {
                                if (!on[i]) continue;
                                JSONObject s = saved.optJSONObject(i);
                                JSONObject req = new JSONObject(base.toString());
                                req.put("lyrics", s.optString("text"));
                                if (a.optString("style").length() > 0) req.put("style", a.optString("style"));
                                req.remove("abc"); // each song writes its own score
                                JSONObject item = new JSONObject();
                                item.put("title", s.optString("name"));
                                item.put("given", s.optString("name"));
                                item.put("req", req);
                                item.put("album", a.optString("id"));
                                queue.add(item);
                                n++;
                            }
                        } catch (Exception e) {
                            toast("Could not queue the songs: " + e.getMessage());
                        }
                        saveQueue();
                        if (n > 0) {
                            alert("Queued for " + a.optString("title"), n + (n == 1 ? " song is" : " songs are")
                                    + " in the queue, in the album's sound and with the Create tab's length and settings. "
                                    + "Start the queue from the Create tab and leave the phone on its charger; each song joins the album when it is made.");
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    void paintAlbumCover(final JSONObject a) {
        if (!Covers.anyReady(this)) {
            alert("One more download needed", PhoneCheck.npuCovers()
                    ? "Covers are painted by a model that runs on this phone's NPU. Get it on the Engine tab (710 MB)."
                    : "Covers are painted on this phone's GPU. Get the GPU cover model on the Engine tab (" + size(Covers.gpuBytes()) + ").");
            return;
        }
        toast("Painting the album cover. " + Covers.howLong(this));
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    JSONObject m = Albums.coverMeta(a, Albums.songs(app, a));
                    File made = Covers.make(app, m, (int) (System.currentTimeMillis() % 100000));
                    File dst = Albums.coverFile(app, a.optString("id"));
                    dst.delete();
                    if (!made.renameTo(dst)) {
                        Store.write(dst, Store.read(made));
                        made.delete();
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String ferr = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (ferr != null) alert("No cover this time", ferr);
                        else toast("Album cover painted. Export the album to put it on every song.");
                    }
                });
            }
        }, "album-cover").start();
    }

    void renameAlbum(final JSONObject a) {
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(a.optString("title"));
        new Lux.Builder(this).setTitle("Rename the album").setView(name)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String t = name.getText().toString().trim();
                        if (t.length() == 0) return;
                        try {
                            a.put("title", t);
                            Albums.save(MainActivity.this, a);
                            toast("Renamed");
                        } catch (Exception e) {
                            toast("Could not rename the album");
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Writes the album to Music/Killa YuE (tagged, with cover and playlist); share also sends it as a zip. */
    void exportAlbum(final JSONObject a, final boolean share) {
        toast("Exporting " + a.optString("title") + "…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File zip = null;
                try {
                    zip = Albums.export(app, a, null);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String ferr = err;
                final File fzip = zip;
                ui.post(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            alert("The album was not exported", ferr);
                            return;
                        }
                        if (share && fzip != null) {
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("application/zip");
                            s.putExtra(Intent.EXTRA_STREAM, TrackProvider.uri(fzip.getName()));
                            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(s, "Share the album"));
                        } else {
                            alert("Album exported", "It is in Music/Killa YuE/" + Albums.safeName(a.optString("title"))
                                    + ": every song tagged with the album, track number, lyrics and cover, plus a playlist. "
                                    + "Music apps on this phone will list it as an album.");
                        }
                    }
                });
            }
        }, "album-export").start();
    }

    // ---------- sharing phone to phone with a QR code ----------

    /** The QR code for a text, black on white with the quiet zone the standard asks for. */
    android.graphics.Bitmap qrBitmap(String text, int px) {
        io.nayuki.qrcodegen.QrCode q = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int n = q.size + 8, scale = Math.max(1, px / n), side = n * scale;
        int[] pix = new int[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                boolean dark = q.getModule(x / scale - 4, y / scale - 4);
                pix[y * side + x] = dark ? 0xFF080909 : 0xFFFFFFFF;
            }
        }
        return android.graphics.Bitmap.createBitmap(pix, side, side, android.graphics.Bitmap.Config.ARGB_8888);
    }

    /**
     * Starts the share server for one thing and shows its QR code with the steps for both people.
     * The server stops when this screen closes, so it stays open until the other phone has its download.
     */
    void showQrShare(String kind, String title, java.util.List<ShareServer.Item> items, File cover, File zip) {
        String url;
        try {
            url = ShareServer.start(getApplicationContext(), kind, title, items, cover, zip);
        } catch (Exception e) {
            alert("Sharing needs Wi-Fi", e.getMessage());
            return;
        }
        LinearLayout v = column();
        v.setPadding(dp(20), dp(8), dp(20), dp(8));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(qrBitmap(url, dp(260)));
        iv.setBackground(box(0xFFFFFFFF, 12));
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(260), dp(260));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        v.addView(iv, lp);
        String what = "app".equals(kind) ? "Killa YuE" : "album".equals(kind) ? "this album" : "this song";
        String third = "models".equals(kind)
                ? "3. A page opens there. They install Killa YuE from it if they need to, then tap Open in Killa YuE: their app copies the models from this phone and checks each one.\n"
                : "3. A page opens there with " + what + ("app".equals(kind) ? "" : ", players, downloads, and Killa YuE itself")
                + ", plus the steps to install the app.\n";
        TextView steps = text("1. Both phones on the same Wi-Fi, or turn on your hotspot and let the other phone join it.\n"
                + "2. On the other phone, open the camera, point it at this code and tap the link.\n"
                + third
                + "Keep this screen open until their download finishes.\n\n" + url, TEXT, 14);
        v.addView(steps, gap(12));
        final TextView status = text("Waiting for the other phone", DIM, 13);
        v.addView(status, gap(8));
        final AlertDialog d = new Lux.Builder(this).setTitle("Share " + ("app".equals(kind) ? "Killa YuE" : "models".equals(kind) ? "the models" : "“" + title + "”"))
                .setView(scroll(v)).setPositiveButton("Done", null).create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) {
                ShareServer.stop();
            }
        });
        d.show();
        ui.post(new Runnable() {
            public void run() {
                if (!d.isShowing()) return;
                ShareServer s = ShareServer.current();
                if (s != null && s.bytesSent > 0) {
                    status.setText("Sent " + size(s.bytesSent) + (s.served > 0 ? ", " + s.served + (s.served == 1 ? " download finished" : " downloads finished") : ""));
                }
                ui.postDelayed(this, 1000);
            }
        });
    }

    /** Shares every complete model file on this phone, so a friend can copy them without mobile data. */
    void shareModelsQr() {
        ArrayList<ShareServer.Item> items = new ArrayList<ShareServer.Item>();
        for (int i = 0; i < Store.FILES.length; i++) {
            String n = Store.FILES[i][0];
            if (Store.have(this, n)) items.add(new ShareServer.Item(n, new File(Store.models(this), n), "application/octet-stream", null));
        }
        if (items.size() == 0) {
            alert("No models to share yet", "Download the models on the Engine tab first; then you can pass them on to friends.");
            return;
        }
        showQrShare("models", "Killa YuE models", items, null, null);
    }

    /** "Open in Killa YuE" on a friend's model page: copy the models this phone is missing from theirs. */
    void handleLink(Intent in) {
        if (in == null || in.getData() == null) return;
        Uri u = in.getData();
        if (!"killayue".equals(u.getScheme()) || !"models".equals(u.getHost())) return;
        in.setData(null); // handle it once
        final String from = u.getQueryParameter("from");
        String host = null;
        try {
            host = Uri.parse(from).getHost();
        } catch (Exception e) {
            // checked below
        }
        // only a phone on the local network, in the form ShareServer gives out
        if (from == null || !from.startsWith("http://") || !from.endsWith("/") || host == null
                || !(host.startsWith("192.168.") || host.startsWith("10.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*"))) {
            alert("That link is not a model share", "Ask your friend to open Engine > Share the models by QR and scan the code they show.");
            return;
        }
        toast("Asking your friend's phone what it has…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                final ArrayList<String> need = new ArrayList<String>();
                long bytes = 0;
                String backbone = null;
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(from + "models.json").openConnection();
                    con.setConnectTimeout(6000);
                    con.setReadTimeout(10000);
                    JSONArray a = new JSONArray(new String(Api.readAll(con.getInputStream()), "UTF-8"));
                    con.disconnect();
                    boolean haveBackbone = false;
                    for (int i = 0; i < Store.QUANTS.length; i++) if (Store.have(app, "YuE2-3B-" + Store.QUANTS[i] + ".gguf")) haveBackbone = true;
                    for (int i = 0; i < a.length(); i++) {
                        String n = a.getJSONObject(i).getString("name");
                        String[] f = Store.info(n);
                        if (f == null || Store.have(app, n)) continue; // files the app does not know are never fetched
                        boolean isBackbone = n.startsWith("YuE2-3B-");
                        if (isBackbone && (haveBackbone || backbone != null)) continue; // one song model is enough
                        if (isBackbone) backbone = n;
                        need.add(n);
                        bytes += Long.parseLong(f[1]);
                    }
                } catch (Exception e) {
                    err = "Could not reach your friend's phone. Are you both on the same Wi-Fi, with their share screen still open? (" + e.getMessage() + ")";
                }
                final String ferr = err, fb = backbone;
                final long fbytes = bytes;
                ui.post(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            alert("No connection", ferr);
                            return;
                        }
                        if (need.size() == 0) {
                            alert("Nothing to copy", "This phone already has every model your friend's phone offers.");
                            return;
                        }
                        new Lux.Builder(MainActivity.this).setTitle("Copy the models from your friend?")
                                .setMessage(need.size() + (need.size() == 1 ? " file, " : " files, ") + size(fbytes)
                                        + ", over Wi-Fi, no mobile data. Each file is checked against its published checksum before it is used, "
                                        + "and an interrupted copy picks up where it stopped. Keep both phones on the same Wi-Fi until the Engine tab says ready.")
                                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        if (fb != null) {
                                            // use the song model the friend has
                                            String q = fb.substring("YuE2-3B-".length(), fb.length() - ".gguf".length());
                                            cfg.edit().putString("quant", q).apply();
                                            if (quantSpin != null) quantSpin.setSelection(Math.max(0, Arrays.asList(Store.QUANTS).indexOf(q)));
                                        }
                                        for (int i = 0; i < need.size(); i++) Store.download(app, need.get(i), from + "m/");
                                        refreshModels();
                                        showTab(TAB_ENGINE);
                                    }
                                }).setNegativeButton("Cancel", null).show();
                    }
                });
            }
        }, "model-link").start();
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handleLink(in);
    }

    void shareAppQr() {
        showQrShare("app", "Killa YuE", new ArrayList<ShareServer.Item>(), null, null);
    }

    void shareSongQr(JSONObject m) {
        File cv = Covers.coverFor(this, m);
        showQrShare("song", m.optString("_title", "Untitled"), ShareServer.songItems(this, m), cv.isFile() ? cv : null, null);
    }

    /** Exports the album first (tagged files and a zip), then shares those. */
    void shareAlbumQr(final JSONObject a) {
        toast("Getting " + a.optString("title") + " ready to share…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File zip = null;
                final ArrayList<ShareServer.Item> items = new ArrayList<ShareServer.Item>();
                File cover = null;
                try {
                    zip = Albums.export(app, a, null);
                    File folder = new File(Albums.get(app, a.optString("id")).optString("exported"));
                    ArrayList<JSONObject> songs = Albums.songs(app, a);
                    File[] fs = folder.listFiles();
                    java.util.Arrays.sort(fs);
                    int k = 0;
                    for (int i = 0; fs != null && i < fs.length; i++) {
                        String n = fs[i].getName().toLowerCase(Locale.US);
                        if (!n.endsWith(".mp3") && !n.endsWith(".wav")) continue;
                        String label = k < songs.size() ? songs.get(k).optString("_title", fs[i].getName()) : fs[i].getName();
                        items.add(new ShareServer.Item(label, fs[i], n.endsWith(".wav") ? "audio/wav" : "audio/mpeg", null));
                        k++;
                    }
                    File cv = new File(folder, "cover.jpg");
                    if (cv.isFile()) cover = cv;
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String ferr = err;
                final File fzip = zip, fcover = cover;
                ui.post(new Runnable() {
                    public void run() {
                        if (ferr != null) alert("The album could not be shared", ferr);
                        else showQrShare("album", a.optString("title"), items, fcover, fzip);
                    }
                });
            }
        }, "album-share").start();
    }

    /** Copies what the project needs to know about this phone, and opens the GitHub form for it. */
    void deviceReport() {
        final String r = PhoneCheck.report(this);
        new Lux.Builder(this).setTitle("Device report").setMessage(r + "\nCopy this and paste it into the device report on GitHub. "
                + "It helps make Killa YuE work on more phones. Nothing is sent by the app.")
                .setPositiveButton("Copy and open GitHub", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("Killa YuE device report", r));
                        toast("Copied. Paste it into the form.");
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/JackKnifeAI/killa-yue/issues/new?template=device-report.yml")));
                        } catch (Exception e) {
                            toast("No browser to open the form");
                        }
                    }
                }).setNegativeButton("Close", null).show();
    }

    /** A vertical lyric video of the song, for TikTok, Reels and Shorts. */
    static File scoreDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "Killa YuE");
    }

    /** Sheet music (PDF), a MIDI file and the ABC score itself, from the score the song was made from. */
    void exportScore(final JSONObject m) {
        final String abc = m.optString("abc");
        if (abc.trim().length() == 0) {
            alert("No score", "This song was made without a score (\"No score\" on the Create tab), so there is nothing to print. "
                    + "Songs made with the full score or the melody only have one.");
            return;
        }
        toast("Writing the sheet music...");
        final String title = m.optString("_title", "Untitled");
        final Abc.Score sc;
        try {
            sc = Abc.parse(abc);
        } catch (Exception e) {
            alert("Could not read the score", String.valueOf(e.getMessage()));
            return;
        }
        String style = m.optString("style").trim();
        if (style.length() > 60) style = style.substring(0, 60) + "\u2026";
        String sub = "Killa YuE  \u00b7  key of " + sc.key + (style.length() > 0 ? "  \u00b7  " + style : "");
        // abcjs lays the music out in a hidden WebView; the pages are drawn here, as a vector PDF
        Engrave.run(this, ScorePrep.prepare(abc, title, sub), new Engrave.Done() {
            public void failed(String why) {
                alert("Could not write the sheet music", why);
            }

            public void laidOut(String svg, String lineStarts) {
                writeScore(abc, sc, title, svg);
            }
        });
    }

    void writeScore(final String abc, final Abc.Score sc, final String title, final String svg) {
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                final File dir = scoreDir();
                final String name = Albums.safeName(title);
                try {
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Could not open Documents/Killa YuE");
                    ScorePages.writePdf(SvgScore.parse(svg), "Killa YuE", new File(dir, name + ".pdf"));
                    MidiFile.write(sc, title, new File(dir, name + ".mid"));
                    Store.write(new File(dir, name + ".abc"), abc.getBytes("UTF-8"));
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                final String noXml = err == null ? musicXml(new File(dir, name + ".musicxml"), abc, title, null) : null;
                final String ferr = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            alert("Could not write the sheet music", ferr);
                            return;
                        }
                        scoreReady("Sheet music ready", "Saved in Documents/Killa YuE:\n\n" + name + ".pdf, the sheet music\n" + name
                                + ".mid, MIDI with the vocal, instrument and chord tracks\n"
                                + (noXml == null ? name + ".musicxml, for MuseScore, Sibelius, Dorico or Finale\n" : "") + name + ".abc, the score itself"
                                + (noXml != null ? "\n\nNo MusicXML this time: " + noXml : ""),
                                noXml == null ? new String[] {name + ".pdf", name + ".mid", name + ".musicxml", name + ".abc"}
                                        : new String[] {name + ".pdf", name + ".mid", name + ".abc"});
                    }
                });
            }
        }, "sheet-music").start();
    }

    /** Saved sheet music: share every file, or open the PDF (the first file). */
    void scoreReady(String title, String message, final String[] files) {
        new Lux.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface x, int w) {
                        SheetActivity.open(MainActivity.this, files[0], files);
                    }
                })
                .setNeutralButton("Share", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface x, int w) {
                        ArrayList<Uri> uris = new ArrayList<Uri>();
                        for (String f : files) uris.add(TrackProvider.uri(f));
                        Intent s = new Intent(Intent.ACTION_SEND_MULTIPLE);
                        s.setType("*/*");
                        s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(s, "Share the sheet music"));
                    }
                }).setNegativeButton("Close", null).show();
    }

    // ---------- instrument parts ----------

    /** Sheet music: the full score with MIDI, or one line written out for an instrument. */
    void sheetMusic(final JSONObject m) {
        if (m.optString("abc").trim().length() == 0) {
            exportScore(m); // explains that this song has no score
            return;
        }
        final ArrayList<String> saved = savedSheets(m.optString("_title", "Untitled"));
        String[] items = saved.isEmpty() ? new String[] {"Full score, with MIDI", "A part for one instrument"}
                : new String[] {"Full score, with MIDI", "A part for one instrument", "Saved sheet music (" + saved.size() + ")"};
        new Lux.Builder(this).setTitle("Sheet music")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) exportScore(m);
                        else if (w == 1) pickFamily(m);
                        else openSaved(saved);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** The song's sheet music already in Documents/Killa YuE: its full score and its parts, newest first. */
    static ArrayList<String> savedSheets(String title) {
        final String base = Albums.safeName(title);
        File[] all = scoreDir().listFiles();
        ArrayList<File> mine = new ArrayList<File>();
        if (all != null) for (File f : all) {
            String n = f.getName();
            if (n.endsWith(".pdf") && (n.equals(base + ".pdf") || n.startsWith(base + " - "))) mine.add(f);
        }
        java.util.Collections.sort(mine, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        ArrayList<String> names = new ArrayList<String>();
        for (File f : mine) names.add(f.getName());
        return names;
    }

    void openSaved(final ArrayList<String> saved) {
        String[] labels = new String[saved.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = saved.get(i).replaceAll("\\.pdf$", "");
        new Lux.Builder(this).setTitle("Saved sheet music").setItems(labels, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String pdf = saved.get(w), base = pdf.substring(0, pdf.length() - 4);
                ArrayList<String> share = new ArrayList<String>();
                share.add(pdf);
                for (String ext : new String[] {".mid", ".musicxml", ".abc"}) if (new File(scoreDir(), base + ext).isFile()) share.add(base + ext);
                SheetActivity.open(MainActivity.this, pdf, share.toArray(new String[0]));
            }
        }).setNegativeButton("Back", null).show();
    }

    void pickFamily(final JSONObject m) {
        final ArrayList<String> groups = new ArrayList<String>();
        for (Instruments.Instrument i : Instruments.ALL) if (!groups.contains(i.group)) groups.add(i.group);
        new Lux.Builder(this).setTitle("Which instrument?")
                .setItems(groups.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        pickInstrument(m, groups.get(w));
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    void pickInstrument(final JSONObject m, String group) {
        final ArrayList<Instruments.Instrument> list = new ArrayList<Instruments.Instrument>();
        ArrayList<String> names = new ArrayList<String>();
        for (Instruments.Instrument i : Instruments.ALL) {
            if (!i.group.equals(group)) continue;
            list.add(i);
            names.add(i.title());
        }
        new Lux.Builder(this).setTitle(group)
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        pickLine(m, list.get(w));
                    }
                }).setNegativeButton("Back", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        pickFamily(m);
                    }
                }).show();
    }

    /** The vocal or the instrument melody; asks only when both have notes. */
    void pickLine(final JSONObject m, final Instruments.Instrument ins) {
        final String abc = m.optString("abc");
        final ArrayList<String> voices = new ArrayList<String>();
        ArrayList<String> labels = new ArrayList<String>();
        for (String v : new String[] {"Vocal", "Ins"}) {
            try {
                if (Transpose.part(abc, v, ins).notes > 0) {
                    voices.add(v);
                    labels.add(v.equals("Vocal") ? "The vocal melody" : "The instrument melody");
                }
            } catch (Exception e) {
                // a voice the score does not have
            }
        }
        final boolean chords = ins.playsChords() && voices.contains("Vocal");
        if (chords) labels.add(0, "Chords to sing along to");
        if (voices.size() == 0) {
            alert("No notes to write out", "This score has no melody with notes in it.");
        } else if (voices.size() == 1 && !chords) {
            exportPart(m, ins, voices.get(0));
        } else {
            new Lux.Builder(this).setTitle(ins.title() + ": which line?")
                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            if (chords && w == 0) exportChords(m, ins);
                            else exportPart(m, ins, voices.get(chords ? w - 1 : w));
                        }
                    }).setNegativeButton("Cancel", null).show();
        }
    }

    /** The vocal line as it sounds, its chord names, and under each line the chords' shapes; diagrams on top. */
    void exportChords(final JSONObject m, final Instruments.Instrument ins) {
        final String title = m.optString("_title", "Untitled");
        final Transpose.Part p;
        try {
            p = Transpose.part(m.optString("abc"), "Vocal", new Instruments.Instrument("Voices", "vocal", "Vocal", "Voc.", "", 0, 0, "auto", 0, 127));
        } catch (Exception e) {
            alert("Could not write the chords", String.valueOf(e.getMessage()));
            return;
        }
        String sub = ins.name + " chords to sing along to  \u00b7  key of " + keyName(p.key) + "  \u00b7  tuning " + Tabs.tuningName(ins);
        toast("Writing the " + ins.name.toLowerCase() + " chords...");
        Engrave.run(this, ScorePrep.prepare(p.abc, title, sub), new Engrave.Done() {
            public void failed(String why) {
                alert("Could not write the chords", why);
            }

            public void laidOut(final String svg, final String lineStarts) {
                new Thread(new Runnable() {
                    public void run() {
                        String err = null, words = null;
                        int count = 0;
                        final String name = Albums.safeName(title + " - " + ins.name + " chords");
                        try {
                            SvgScore s = SvgScore.parse(svg);
                            count = ChordSheet.add(s, ins).size();
                            words = lyricsUnder(s, m, lineStarts);
                            File dir = scoreDir();
                            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Could not open Documents/Killa YuE");
                            ScorePages.writePdf(s, "Killa YuE", new File(dir, name + ".pdf"));
                            String noXml = musicXml(new File(dir, name + ".musicxml"), p.abc, title, null);
                            if (noXml != null) throw new Exception(noXml);
                        } catch (Exception e) {
                            err = String.valueOf(e.getMessage());
                        }
                        final String ferr = err, fwords = words;
                        final int chords = count;
                        ui.post(new Runnable() {
                            public void run() {
                                if (ferr != null) {
                                    alert("Could not write the chords", ferr);
                                    return;
                                }
                                scoreReady("Chords ready", "Saved in Documents/Killa YuE:\n\n" + name + ".pdf\n" + name
                                        + ".musicxml, the vocal line and chord symbols for notation programs\n\nThe vocal line with its "
                                        + chords + (chords == 1 ? " chord" : " chords") + ", each chord's shape on the " + ins.name.toLowerCase()
                                        + " tab under it, " + (fwords == null ? "the words under each line, " : "")
                                        + "and a diagram of every shape at the top." + (fwords != null && fwords.length() > 0 ? "\n\n" + fwords : ""),
                                        new String[] {name + ".pdf", name + ".musicxml"});
                            }
                        });
                    }
                }, "chords").start();
            }
        });
    }

    void exportPart(final JSONObject m, final Instruments.Instrument ins, final String voice) {
        final String title = m.optString("_title", "Untitled");
        final Transpose.Part p;
        try {
            p = Transpose.part(m.optString("abc"), voice, ins);
        } catch (Exception e) {
            alert("Could not write the part", String.valueOf(e.getMessage()));
            return;
        }
        String line = voice.equals("Vocal") ? "vocal melody" : "instrument melody";
        String concert = keyName(abcKey(m.optString("abc")));
        StringBuilder sub = new StringBuilder(ins.title() + "  \u00b7  " + line + "  \u00b7  key of " + keyName(p.key));
        if (ins.semitones % 12 != 0) sub.append(" (sounds in ").append(concert).append(")");
        if (p.octaves != 0) sub.append("  \u00b7  written ").append(Math.abs(p.octaves) == 1 ? "an octave" : Math.abs(p.octaves) + " octaves")
                .append(p.octaves < 0 ? " lower" : " higher").append(" to fit");
        if (ins.tab != null) sub.append("  \u00b7  tab for ").append(Tabs.tuningName(ins));
        toast("Writing the " + ins.name + " part...");
        Engrave.run(this, ScorePrep.prepare(p.abc, title, sub.toString()), Tabs.option(ins), new Engrave.Done() {
            public void failed(String why) {
                alert("Could not write the part", why);
            }

            public void laidOut(final String svg, final String lineStarts) {
                new Thread(new Runnable() {
                    public void run() {
                        String err = null, words = null;
                        final String name = Albums.safeName(title + " - " + ins.name + (voice.equals("Vocal") ? "" : " (instrument)"));
                        try {
                            File dir = scoreDir();
                            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Could not open Documents/Killa YuE");
                            SvgScore s = SvgScore.parse(svg);
                            if (voice.equals("Vocal")) words = lyricsUnder(s, m, lineStarts);
                            ScorePages.writePdf(s, "Killa YuE", new File(dir, name + ".pdf"));
                            Store.write(new File(dir, name + ".abc"), p.abc.getBytes("UTF-8"));
                        } catch (Exception e) {
                            err = String.valueOf(e.getMessage());
                        }
                        final String noXml = err == null ? musicXml(new File(scoreDir(), name + ".musicxml"), p.abc, title, ins) : null;
                        final String ferr = err, fwords = words;
                        ui.post(new Runnable() {
                            public void run() {
                                if (ferr != null) {
                                    alert("Could not write the part", ferr);
                                    return;
                                }
                                String msg = "Saved in Documents/Killa YuE:\n\n" + name + ".pdf, the " + ins.title() + " part\n"
                                        + (noXml == null ? name + ".musicxml, for notation programs\n" : "") + name + ".abc, the part as a score";
                                if (noXml != null) msg += "\n\nNo MusicXML this time: " + noXml;
                                if (fwords == null) msg += "\n\nThe words are printed under each line of music.";
                                else if (fwords.length() > 0) msg += "\n\n" + fwords;
                                if (p.outOfRange > 0) msg += "\n\n" + p.outOfRange + " of its " + p.notes + " notes are outside the "
                                        + ins.name.toLowerCase() + "'s usual range. They are printed exactly as the song has them.";
                                if (p.unplayable > 0) msg += "\n\n" + p.unplayable + (p.unplayable == 1 ? " note is" : " notes are")
                                        + " lower than the " + ins.name.toLowerCase() + "'s lowest string, so the tab marks "
                                        + (p.unplayable == 1 ? "it" : "them") + " with a question mark.";
                                scoreReady("Part ready", msg, noXml == null ? new String[] {name + ".pdf", name + ".musicxml", name + ".abc"}
                                        : new String[] {name + ".pdf", name + ".abc"});
                            }
                        });
                    }
                }, "part").start();
            }
        });
    }

    /**
     * Prints the song's lyric lines under the lines of music they are sung in, from the times lyric sync
     * (or tap-to-time) saved. Null when printed; otherwise what to tell the reader ("" when the song has no words).
     */
    static String lyricsUnder(SvgScore s, JSONObject m, String lineStarts) {
        String lyrics = m.optString("lyrics");
        ArrayList<Karaoke.Line> lines = Karaoke.lines(lyrics);
        if (lines.isEmpty()) return "";
        JSONArray t = m.optJSONArray("_times");
        if (t == null || t.length() != lines.size())
            return "To print the words under each line of music, sync the lyrics first: play the song, then \u201cSync automatically\u201d on the Lyrics tab.";
        ArrayList<String> texts = new ArrayList<String>();
        int[] times = new int[lines.size()];
        for (int i = 0; i < times.length; i++) {
            texts.add(lines.get(i).text);
            times[i] = t.optInt(i);
        }
        LyricLines.add(s, LyricLines.starts(lineStarts), texts, times);
        return null;
    }

    /** Writes the score as MusicXML; null when written, else why not (the file is then not left behind). */
    static String musicXml(File out, String abc, String title, Instruments.Instrument ins) {
        try {
            Store.write(out, MusicXml.write(abc, title, ins).getBytes("UTF-8"));
            return null;
        } catch (Exception e) {
            out.delete();
            return String.valueOf(e.getMessage());
        }
    }

    /** The key from a score's K: line, e.g. "Em". */
    static String abcKey(String abc) {
        for (String row : abc.split("\n")) if (row.startsWith("K:")) return row.substring(2).trim();
        return "C";
    }

    /** "C#m" as a reader says it: C\u266f minor. */
    static String keyName(String key) {
        java.util.regex.Matcher k = java.util.regex.Pattern.compile("^([A-G])([#b]*)\\s*(m(?!aj)(?:in(?:or)?)?)?").matcher(key);
        if (!k.find()) return key;
        String acc = k.group(2).replace("#", "\u266f").replace("b", "\u266d");
        return k.group(1) + acc + (k.group(3) != null ? " minor" : " major");
    }

    void makeLyricVideo(final JSONObject m) {
        LinearLayout v = column();
        v.setPadding(dp(22), dp(6), dp(22), dp(6));
        final TextView what = text("Getting ready", TEXT, 15);
        v.addView(what);
        final android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Skin.ACCENT));
        v.addView(bar, gap(12));
        TextView note = text("1080 x 1920, made on this phone. Long songs take a few minutes; keep the app open.", DIM, 12);
        v.addView(note, gap(10));
        final boolean[] stop = new boolean[1];
        final AlertDialog d = new Lux.Builder(this).setTitle("Lyric video").setView(v)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface x, int w) {
                        stop[0] = true;
                    }
                }).setCancelable(false).create();
        d.show();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File made = null;
                try {
                    made = LyricVideo.make(app, m, new LyricVideo.Progress() {
                        int last = -1;

                        public void at(final int pct, final String s) {
                            if (pct == last) return;
                            last = pct;
                            ui.post(new Runnable() {
                                public void run() {
                                    bar.setProgress(pct);
                                    what.setText(s + "   " + pct + "%");
                                }
                            });
                        }

                        public boolean cancelled() {
                            return stop[0];
                        }
                    });
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String ferr = err;
                final File fmade = made;
                ui.post(new Runnable() {
                    public void run() {
                        if (d.isShowing()) d.dismiss();
                        if (ferr != null) {
                            if (!"Cancelled".equals(ferr)) alert("No video this time", ferr);
                            return;
                        }
                        new Lux.Builder(MainActivity.this).setTitle("Lyric video ready")
                                .setMessage("Saved in Movies/Killa YuE as “" + fmade.getName() + "”. It is in your gallery too.")
                                .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface x, int w) {
                                        Intent s = new Intent(Intent.ACTION_SEND);
                                        s.setType("video/mp4");
                                        s.putExtra(Intent.EXTRA_STREAM, TrackProvider.uri(fmade.getName()));
                                        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                        startActivity(Intent.createChooser(s, "Share the lyric video"));
                                    }
                                })
                                .setNeutralButton("Watch", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface x, int w) {
                                        Intent s = new Intent(Intent.ACTION_VIEW);
                                        s.setDataAndType(TrackProvider.uri(fmade.getName()), "video/mp4");
                                        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                        try {
                                            startActivity(s);
                                        } catch (Exception e) {
                                            toast("No video player found");
                                        }
                                    }
                                }).setNegativeButton("Close", null).show();
                    }
                });
            }
        }, "lyric-video").start();
    }

    /** Times the playing song's lyrics with the speech recogniser. */
    void syncLyrics() {
        if (playMeta == null) {
            toast("Play a song first, then sync its lyrics");
            return;
        }
        if (!LyricSync.ready(this)) {
            new Lux.Builder(this).setTitle("Lyric sync")
                    .setMessage("A speech recogniser listens to the song on this phone and times every line to when it is sung. "
                            + "It needs a one-time download of " + size(Long.parseLong(LyricSync.MODEL[1]))
                            + " (whisper \"small\", MIT licence), checked against its published checksum.")
                    .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            LyricSync.download(getApplicationContext());
                            toast("Downloading the lyric sync model. Progress is on the Engine tab.");
                        }
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        final JSONObject m = playMeta;
        final File mf = metaFile(m);
        // the recogniser's own progress on a minified staff bar, with an honest time left
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), 0);
        TextView about = text("The recogniser is listening to \u201c" + m.optString("_title") + "\u201d and timing each line. "
                + "It works the phone hard, so it may get warm; the bar shows how far it has got.", TEXT, 14);
        box.addView(about);
        final StaffProgress bar = new StaffProgress(this, 36);
        bar.setIndeterminate(true);
        bar.setMax(100);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = (int) (12 * dp);
        box.addView(bar, blp);
        final TextView left = text("Starting\u2026", DIM, 13);
        box.addView(left);
        final AlertDialog d = new Lux.Builder(this).setTitle("Listening to the song").setView(box).setCancelable(false).create();
        d.show();
        final boolean[] heard = {false};  // the recogniser has started reporting, in this sync
        ui.post(new Runnable() {
            public void run() {
                if (!d.isShowing()) return;
                int pc = LyricSync.progress;
                if (pc >= 0) {
                    heard[0] = true;
                    bar.setIndeterminate(false);
                    bar.setProgress(pc);
                    String t = SyncProgress.timeLeft(pc, System.currentTimeMillis() - LyricSync.listeningSince);
                    left.setText(pc + "%" + (t.length() > 0 ? "  \u00b7  " + t : ""));
                } else {
                    left.setText(heard[0] ? "Matching the words to the lyrics\u2026" : "Starting\u2026");
                }
                bar.invalidate();
                ui.postDelayed(this, 250);
            }
        });
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String msg, err = null;
                try {
                    msg = LyricSync.sync(app, m, mf);
                } catch (Exception e) {
                    msg = null;
                    err = e.getMessage();
                }
                final String fmsg = msg, ferr = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (d.isShowing()) d.dismiss();
                        if (ferr != null) {
                            alert("The lyrics were not synced", ferr);
                            return;
                        }
                        if (m == playMeta && player != null) {
                            karaoke = linesFor(playMeta, player.getDuration());
                            timedByHand = true;
                        }
                        karaokeView.invalidate();
                        alert("Lyrics synced", fmsg);
                    }
                });
            }
        }, "lyric-sync").start();
    }

    /** After a new song: sync its lyrics in the background, if the model is installed and the setting is on. */
    void syncNewest() {
        if (!LyricSync.ready(this) || !cfg.getBoolean("auto_sync", true) || playlist.size() == 0) return;
        final JSONObject m = playlist.get(0);
        if (m.optJSONArray("_times") != null || m.optString("lyrics").trim().length() == 0) return;
        final File mf = metaFile(m);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final String msg = LyricSync.sync(app, m, mf);
                    ui.post(new Runnable() {
                        public void run() {
                            toast("Lyrics of “" + m.optString("_title") + "” synced");
                        }
                    });
                } catch (Exception e) {
                    EngineService.addLog("[Sync] " + e.getMessage());
                }
            }
        }, "lyric-sync-auto").start();
    }

    void makeCover(final JSONObject m, final boolean tell) {
        if (!Covers.anyReady(this)) {
            if (tell) {
                alert("One more download needed", PhoneCheck.npuCovers()
                        ? "Covers are painted by a model that runs on this phone's NPU. Get it on the Engine tab (710 MB)."
                        : "Covers are painted on this phone's GPU. Get the GPU cover model on the Engine tab (" + size(Covers.gpuBytes()) + ").");
                showTab(TAB_ENGINE);
            }
            return;
        }
        if (tell) toast("Painting a cover. " + Covers.howLong(this));
        final Context app = getApplicationContext();
        final int variation = Covers.coverFor(this, m).isFile() ? (int) (System.currentTimeMillis() % 100000) : 0;
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    Covers.make(app, m, variation);
                    File audio = new File(Store.library(app), m.optString("_audio"));
                    Tags.write(audio, m.optString("_title", "Untitled"), linesFor(m, m.optInt("_seconds") * 1000));
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String ferr = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (ferr != null && tell) alert("No cover this time", ferr);
                        refreshLibrary();
                        if (m.optString("_audio").equals(playingFile)) NowPlaying.update(MainActivity.this);
                    }
                });
            }
        }, "cover").start();
    }

    /** Songs with the same words and the same score are takes of one song. */
    static String family(JSONObject m) {
        return (m.optString("lyrics").trim() + "\u0001" + m.optString("abc").trim()).hashCode() + "";
    }

    void showTakes(final JSONObject m) {
        final ArrayList<JSONObject> takes = new ArrayList<JSONObject>();
        String fam = family(m);
        for (int i = playlist.size() - 1; i >= 0; i--) if (family(playlist.get(i)).equals(fam)) takes.add(playlist.get(i));
        if (takes.size() < 2) {
            toast("This is the only take. \"New sound, same song\" makes another.");
            return;
        }
        String[] names = new String[takes.size()];
        SimpleDateFormat df = new SimpleDateFormat("d MMM, HH:mm", Locale.US);
        for (int i = 0; i < names.length; i++) {
            JSONObject t = takes.get(i);
            names[i] = "Take " + (i + 1) + "   " + df.format(new Date(t.optLong("_created"))) + "   " + t.optString("steps", "32") + " steps"
                    + (t.optString("_audio").equals(m.optString("_audio")) ? "   (this one)" : "");
        }
        new Lux.Builder(this).setTitle("Takes of " + m.optString("_title", "this song"))
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        play(takes.get(w));
                    }
                }).setNegativeButton("Close", null).show();
    }

    /** A recipe is everything needed to make this exact song again on another phone, without the audio. */
    void shareRecipe(JSONObject m) {
        try {
            JSONObject r = new JSONObject(m.toString());
            r.remove("_audio");
            r.remove("_times");
            r.remove("_synced");
            r.put("_recipe", 1);
            String a = m.optString("_audio");
            int dot = a.lastIndexOf('.');
            File f = new File(Store.library(this), (dot > 0 ? a.substring(0, dot) : a) + ".killa");
            Store.write(f, r.toString().getBytes("UTF-8"));
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("application/octet-stream");
            s.putExtra(Intent.EXTRA_STREAM, TrackProvider.uri(f.getName()));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "Share this song's recipe"));
        } catch (Exception e) {
            toast("Could not write the recipe: " + e.getMessage());
        }
    }

    void openRecipe(Uri u) {
        try {
            InputStream in = getContentResolver().openInputStream(u);
            JSONObject r;
            try {
                r = new JSONObject(new String(Api.readAll(in), "UTF-8"));
            } finally {
                in.close();
            }
            if (r.optString("lyrics").length() == 0 && r.optString("style").length() == 0) throw new Exception("empty");
            clearCarry();
            loadPrompt(r, true);
            songTitle.setText(r.optString("_title"));
            if (r.optString("lm_seed").length() > 0) lmSeed.setText(r.optString("lm_seed"));
            if (r.optString("seed").length() > 0) seed.setText(r.optString("seed"));
            if (r.optString("steps").length() > 0) steps.setText(r.optString("steps"));
            String tokens = r.optString("semantic_tokens");
            if (tokens.length() > 0) {
                // with the melody data the song comes out the same; without it the model writes a new take
                semTokens = tokens;
                carryName = r.optString("_title", "the shared song");
                showBanner();
            }
            settle();
            showTab(TAB_CREATE);
            toast("Recipe loaded. Press Generate to make the song.");
        } catch (Exception e) {
            alert("Not a recipe", "That file is not a Killa YuE song recipe.");
        }
    }

    // ---------- sleep timer ----------

    Runnable sleepStop;

    void askSleepTimer() {
        final int[] minutes = {0, 15, 30, 45, 60, 90};
        String[] names = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "1 hour 30"};
        new Lux.Builder(this).setTitle("Stop the music after").setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (sleepStop != null) ui.removeCallbacks(sleepStop);
                sleepStop = null;
                if (minutes[w] == 0) {
                    toast("Sleep timer off");
                    return;
                }
                sleepStop = new Runnable() {
                    public void run() {
                        sleepStop = null;
                        if (player != null && player.isPlaying()) player.pause();
                        playerView.invalidate();
                        NowPlaying.update(MainActivity.this);
                    }
                };
                ui.postDelayed(sleepStop, minutes[w] * 60000L);
                toast("The music will stop in " + minutes[w] + " minutes");
            }
        }).show();
    }

    void retitle(final File mf, final JSONObject m) {
        final EditText e = edit("Title", 1);
        e.setText(m.optString("_title", ""));
        e.selectAll();
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(e);
        new Lux.Builder(this).setTitle("Song title").setView(f)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            String t = val(e).length() == 0 ? "Untitled" : val(e);
                            long stamp = mf.lastModified();
                            m.put("_title", t);
                            Store.write(mf, m.toString().getBytes("UTF-8"));
                            mf.setLastModified(stamp); // keep the song's place in the list
                            if (m.optString("_audio").equals(playingFile)) playTitle = t;
                            Tags.write(new File(Store.library(MainActivity.this), m.optString("_audio")), t,
                                    linesFor(m, m.optInt("_seconds") * 1000));
                        } catch (Exception ex) {
                            toast("Could not save the new title");
                        }
                        refreshLibrary();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ---------- player ----------

    // ---------- Lyrics tab ----------

    View buildLyrics() {
        LinearLayout c = column();
        karaokeView = new KaraokeView(this);
        c.addView(karaokeView, new LinearLayout.LayoutParams(-1, 0, 1f));
        lyricsChips = new FrameLayout(this);
        lyricsChips.setBackgroundColor(Skin.DECK);
        lyricsChips.setPadding(dp(12), 0, dp(12), dp(8));
        c.addView(lyricsChips);
        lyricsButtons();
        return c;
    }

    void lyricsButtons() {
        lyricsChips.removeAllViews();
        if (timing) {
            lyricsChips.addView(chipRow(new String[] {"Back one line", "Finish and save", "Cancel"}, new Runnable[] {
                new Runnable() {
                    public void run() {
                        backLine();
                    }
                },
                new Runnable() {
                    public void run() {
                        endTiming(true);
                    }
                },
                new Runnable() {
                    public void run() {
                        endTiming(false);
                    }
                },
            }));
        } else {
            lyricsChips.addView(chipRow(new String[] {"Sync automatically", "Time the lyrics by tapping", "Share timed lyrics"}, new Runnable[] {
                new Runnable() {
                    public void run() {
                        syncLyrics();
                    }
                },
                new Runnable() {
                    public void run() {
                        startTiming();
                    }
                },
                new Runnable() {
                    public void run() {
                        shareLyrics();
                    }
                },
            }));
        }
    }

    File metaFile(JSONObject m) {
        String a = m.optString("_audio");
        int dot = a.lastIndexOf('.');
        return new File(Store.library(this), (dot > 0 ? a.substring(0, dot) : a) + ".json");
    }

    /** Hand-set line times when the song has them, the estimate otherwise. */
    static ArrayList<Karaoke.Line> linesFor(JSONObject m, int durationMs) {
        JSONArray t = m.optJSONArray("_times");
        ArrayList<Karaoke.Line> plain = Karaoke.lines(m.optString("lyrics"));
        if (t != null && t.length() == plain.size() && t.length() > 0) {
            int[] starts = new int[t.length()];
            for (int i = 0; i < starts.length; i++) starts[i] = t.optInt(i);
            return Karaoke.timed(m.optString("lyrics"), starts, durationMs);
        }
        return Karaoke.build(m.optString("lyrics"), m.optString("abc"), durationMs);
    }

    void startTiming() {
        if (player == null || playMeta == null) {
            toast("Play a song first, then time its lyrics");
            return;
        }
        if (karaoke.size() == 0) {
            toast("This song has no lyrics to time");
            return;
        }
        new Lux.Builder(this).setTitle("Time the lyrics")
                .setMessage("The song starts from the beginning. Tap the lyrics the moment each line begins. "
                        + "\"Back one line\" lets you redo a line you missed.")
                .setPositiveButton("Start", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        timing = true;
                        timingIndex = 0;
                        timingTimes = new int[karaoke.size()];
                        player.seekTo(0);
                        player.start();
                        lyricsButtons();
                        karaokeView.invalidate();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    void tapLine() {
        if (!timing || player == null) return;
        // a tap lands a moment after the ear hears the line
        timingTimes[timingIndex++] = Math.max(0, player.getCurrentPosition() - 150);
        if (timingIndex >= timingTimes.length) endTiming(true);
        else karaokeView.invalidate();
    }

    void backLine() {
        if (!timing || timingIndex == 0 || player == null) return;
        timingIndex--;
        player.seekTo(Math.max(0, (timingIndex > 0 ? timingTimes[timingIndex - 1] : 0) - 1500));
        karaokeView.invalidate();
    }

    void endTiming(boolean save) {
        if (!timing) return;
        timing = false;
        if (save && timingIndex > 0 && playMeta != null && player != null) {
            try {
                // lines that were not reached keep their estimated times, pushed later if need be
                JSONArray t = new JSONArray();
                int last = 0;
                for (int i = 0; i < karaoke.size(); i++) {
                    int v = i < timingIndex ? timingTimes[i] : Math.max(last, karaoke.get(i).start);
                    t.put(v);
                    last = v;
                }
                playMeta.put("_times", t);
                playMeta.remove("_synced"); // the times are now the ones tapped in
                File mf = metaFile(playMeta);
                long stamp = mf.lastModified();
                Store.write(mf, playMeta.toString().getBytes("UTF-8"));
                mf.setLastModified(stamp);
                karaoke = linesFor(playMeta, player.getDuration());
                timedByHand = true;
                Tags.write(new File(Store.library(this), playMeta.optString("_audio")), playTitle, karaoke);
                toast(timingIndex >= karaoke.size() ? "Timing saved with the song"
                        : "Saved. " + timingIndex + " of " + karaoke.size() + " lines were timed by hand.");
            } catch (Exception e) {
                toast("Could not save the timing: " + e.getMessage());
            }
        }
        lyricsButtons();
        karaokeView.invalidate();
    }

    void shareLyrics() {
        if (playMeta == null || player == null || karaoke.size() == 0) {
            toast("Play a song with lyrics first");
            return;
        }
        try {
            File lrc = Tags.write(new File(Store.library(this), playMeta.optString("_audio")), playTitle, karaoke);
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_STREAM, TrackProvider.uri(lrc.getName()));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "Share timed lyrics (.lrc)"));
        } catch (Exception e) {
            toast("Could not write the lyrics file: " + e.getMessage());
        }
    }

    View buildPlayer() {
        playerView = new PlayerView(this);
        return playerView;
    }

    void stopPlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (timing) {
            timing = false;
            lyricsButtons();
        }
        playingFile = "";
        playTitle = "";
        playMeta = null;
        timedByHand = false;
        karaoke = new ArrayList<Karaoke.Line>();
        if (playerView != null) playerView.invalidate();
        NowPlaying.clear(this);
    }

    void play(JSONObject m) {
        File f = new File(Store.library(this), m.optString("_audio"));
        try {
            stopPlayer();
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(PlayerView.onEnd(this));
            takeFocus();
            player.start();
            playingFile = f.getName();
            playTitle = m.optString("_title", "Untitled");
            playMeta = m;
            karaoke = linesFor(m, player.getDuration());
            timedByHand = m.optJSONArray("_times") != null && m.optJSONArray("_times").length() == karaoke.size();
        } catch (Exception e) {
            stopPlayer();
            toast("Could not play this file");
        }
        playerView.invalidate();
        if (player != null) NowPlaying.update(this);
    }

    /** Asks other apps to stop their sound while ours plays, and stops ours when another app takes over. */
    final android.media.AudioManager.OnAudioFocusChangeListener focus = new android.media.AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int change) {
            if (change < 0 && player != null && player.isPlaying()) { // every kind of loss is negative
                player.pause();
                playerView.invalidate();
                NowPlaying.update(MainActivity.this);
            }
        }
    };

    void takeFocus() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        am.requestAudioFocus(focus, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN);
    }

    /** What next and previous step through: the playing album, or the library. */
    ArrayList<JSONObject> order() {
        return albumOrder != null ? albumOrder : playlist;
    }

    int playingIndex() {
        ArrayList<JSONObject> o = order();
        for (int i = 0; i < o.size(); i++) if (o.get(i).optString("_audio").equals(playingFile)) return i;
        return -1;
    }

    void togglePlay() {
        if (player == null) {
            if (playlist.size() == 0) toast("No songs yet. Make one on the Create tab.");
            else play(playlist.get(0));
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            takeFocus();
            player.start();
        }
        playerView.invalidate();
        NowPlaying.update(this);
    }

    /** Moves to the next (1) or previous (-1) song in library order. False when there is none. */
    boolean playStep(int dir) {
        ArrayList<JSONObject> playlist = order();
        if (playlist.size() == 0) return false;
        int at = playingIndex();
        // previous restarts the current song first, as players do, once it is a few seconds in
        if (dir < 0 && player != null && player.getCurrentPosition() > 3000) {
            player.seekTo(0);
            playerView.invalidate();
            return true;
        }
        int to = at < 0 ? 0 : at + dir;
        if (to < 0 || to >= playlist.size()) return false;
        play(playlist.get(to));
        return true;
    }

    void seekBy(int ms) {
        if (player == null) return;
        int to = Math.max(0, Math.min(player.getDuration() - 500, player.getCurrentPosition() + ms));
        player.seekTo(to);
        playerView.invalidate();
        NowPlaying.update(this);
    }

    void seekTo(float fraction) {
        if (player == null) return;
        player.seekTo((int) (fraction * player.getDuration()));
        playerView.invalidate();
        NowPlaying.update(this);
    }

    // ---------- Engine ----------

    View buildEngine() {
        LinearLayout c = column();
        engineStatus = text("", Skin.ACCENT, 17);
        engineStatus.setTypeface(Skin.text(), Typeface.BOLD);
        engineStatus.setShadowLayer(4, 0, 1, 0x99000000);
        engineStatus.setPadding(0, dp(12), 0, dp(8));
        c.addView(engineStatus);
        engineBtn = button("Start engine", true);
        c.addView(engineBtn);
        engineBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (EngineService.running) {
                    if (jobBusy) {
                        toast("A song is being made. Cancel it first.");
                        return;
                    }
                    EngineService.stop(MainActivity.this);
                } else if (!Store.coreReady(MainActivity.this)) {
                    toast("Download the model and the decoder first");
                } else {
                    EngineService.start(MainActivity.this);
                }
            }
        });

        c.addView(label("Skin"));
        final ArrayList<String> skinIds = Skin.ids(this);
        final Spinner skinSpin = spinner(Skin.names(this).toArray(new String[0]));
        skinSpin.setSelection(Math.max(0, skinIds.indexOf(Skin.id(this))));
        c.addView(skinSpin);
        skinSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!ready || skinIds.get(pos).equals(Skin.id(MainActivity.this))) return;
                useSkin(skinIds.get(pos));
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        c.addView(chipRow(new String[] {"Install a skin", "Remove a skin", "Make your own"}, new Runnable[] {
            new Runnable() {
                public void run() {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, "Pick a skin file"), PICK_SKIN);
                }
            },
            new Runnable() {
                public void run() {
                    removeSkin();
                }
            },
            new Runnable() {
                public void run() {
                    skinTemplate();
                }
            },
        }));

        c.addView(label("Queue: start the next song only when the battery is at least"));
        String[] levels = new String[QUEUE_LEVELS.length];
        int levelAt = 3;
        for (int i = 0; i < levels.length; i++) {
            levels[i] = QUEUE_LEVELS[i] + "%";
            if (QUEUE_LEVELS[i] == cfg.getInt("queue_battery", 75)) levelAt = i;
        }
        final Spinner queueSpin = spinner(levels);
        queueSpin.setSelection(levelAt);
        c.addView(queueSpin);
        queueSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (ready) cfg.edit().putInt("queue_battery", QUEUE_LEVELS[pos]).apply();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        c.addView(label("Wake-up song: an alarm that plays your newest song, the last one the queue made overnight"));
        wakeBtn = button(wakeLabel(), false);
        c.addView(wakeBtn);
        wakeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                askWake();
            }
        });

        c.addView(label("Run on"));
        deviceSpin = spinner(DEVICE_LABELS);
        deviceSpin.setSelection(Math.max(0, Arrays.asList(DEVICES).indexOf(cfg.getString("device", "Vulkan0"))));
        c.addView(deviceSpin);
        c.addView(label("Model quality (larger is closer to the original, and slower to load)"));
        quantSpin = spinner(new String[] {"Small, 2.6 GB (Q5)", "Balanced, 2.9 GB (Q6)", "Best, 3.8 GB (Q8)"});
        quantSpin.setSelection(Math.max(0, Arrays.asList(Store.QUANTS).indexOf(cfg.getString("quant", "Q6_K"))));
        c.addView(quantSpin);
        c.addView(label("Memory for song length"));
        seqSpin = spinner(SEQ_LABELS);
        int seq = cfg.getInt("maxseq", 8192);
        for (int i = 0; i < SEQS.length; i++) if (SEQS[i] == seq) seqSpin.setSelection(i);
        c.addView(seqSpin);
        keepBox = new CheckBox(this);
        keepBox.setText("Keep everything loaded (faster between songs, needs much more memory)");
        keepBox.setTextColor(TEXT);
        keepBox.setShadowLayer(4, 0, 1, 0x99000000);
        keepBox.setChecked(cfg.getBoolean("keep", false));
        c.addView(keepBox, gap(8));
        gentleBox = new CheckBox(this);
        gentleBox.setText("Gentle mode (songs take longer, the phone stays smooth while one is made)");
        gentleBox.setTextColor(TEXT);
        gentleBox.setShadowLayer(4, 0, 1, 0x99000000);
        gentleBox.setChecked(cfg.getBoolean("gentle", true));
        c.addView(gentleBox, gap(8));
        final CheckBox procBox = new CheckBox(this);
        procBox.setText("Run the engine in its own app process (new: Android's limits on child processes no longer apply)");
        procBox.setTextColor(TEXT);
        procBox.setShadowLayer(4, 0, 1, 0x99000000);
        procBox.setChecked(cfg.getBoolean("engine_process", false));
        procBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                cfg.edit().putBoolean("engine_process", on).apply();
                if (EngineService.running && !jobBusy) EngineService.stop(MainActivity.this);
                toast(jobBusy ? "This takes effect with the next song" : "The engine starts this way with the next song");
            }
        });
        c.addView(procBox, gap(8));
        bgStatus = text("", DIM, 13);
        bgStatus.setShadowLayer(4, 0, 1, 0x99000000);
        c.addView(bgStatus, gap(8));
        c.addView(chipRow(new String[] {"Allow in background", "Developer options", "Device report"}, new Runnable[] {
            new Runnable() {
                public void run() {
                    cfg.edit().putBoolean("bg_asked", true).apply();
                    Background.askExemption(MainActivity.this);
                }
            },
            new Runnable() {
                public void run() {
                    Background.openDeveloperOptions(MainActivity.this);
                }
            },
            new Runnable() {
                public void run() {
                    deviceReport();
                }
            },
        }), gap(4));

        AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                saveEngineSettings();
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        };
        deviceSpin.setOnItemSelectedListener(changed);
        quantSpin.setOnItemSelectedListener(changed);
        seqSpin.setOnItemSelectedListener(changed);
        keepBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                saveEngineSettings();
            }
        });
        gentleBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                saveEngineSettings();
            }
        });

        c.addView(label("Downloads"));
        c.addView(chipRow(new String[] {"Share the models by QR"}, new Runnable[] {
            new Runnable() {
                public void run() {
                    shareModelsQr();
                }
            },
        }), gap(4));
        modelBox = column();
        c.addView(modelBox);
        storageNote = text("", DIM, 12);
        c.addView(storageNote, gap(8));
        refreshModels();

        boolean npuPhone = PhoneCheck.npuCovers();
        c.addView(label(npuPhone ? "Album covers (painted on the NPU)" : "Album covers (painted on the GPU)"));
        final Button coverGet = button("Get the cover model (710 MB)", false);
        if (npuPhone) c.addView(coverGet);
        final Button gpuGet = button("Get the GPU cover model (" + size(Covers.gpuBytes()) + ")", false);
        if (npuPhone) c.addView(gpuGet, gap(6));
        else c.addView(gpuGet);
        if (npuPhone) {
            final CheckBox useGpu = new CheckBox(this);
            useGpu.setText("Paint covers on the GPU instead (for testing the version other phones use)");
            useGpu.setTextColor(TEXT);
            useGpu.setChecked(cfg.getBoolean("cover_gpu", false));
            useGpu.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    cfg.edit().putBoolean("cover_gpu", on).apply();
                }
            });
            c.addView(useGpu, gap(4));
        }
        coverStatus = text("", DIM, 12);
        c.addView(coverStatus, gap(6));
        gpuGet.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Covers.gpuReady(MainActivity.this)) {
                    toast("The GPU cover model is already installed");
                    return;
                }
                new Lux.Builder(MainActivity.this).setTitle("GPU cover model")
                        .setMessage("Stable Diffusion 1.5 with the LCM LoRA and the TAESD decoder, " + size(Covers.gpuBytes())
                                + ", for phones without the Snapdragon 8 Elite's NPU. A cover takes about four minutes on a fast phone's GPU, "
                                + "and longer on slower phones. Licensed under CreativeML OpenRAIL-M, which forbids using it to break the law, "
                                + "to harm people, or to spread false information to cause harm. Each file is checked against its published checksum.")
                        .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                Covers.downloadGpu(getApplicationContext());
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        coverGet.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Covers.ready(MainActivity.this)) {
                    toast("The cover model is already installed");
                    return;
                }
                new Lux.Builder(MainActivity.this).setTitle("Cover model")
                        .setMessage("Stable Diffusion 1.5, compiled by Qualcomm for the Snapdragon 8 Elite. 710 MB. "
                                + "It is licensed under CreativeML OpenRAIL-M, which forbids using it to break the law, "
                                + "to harm people, or to spread false information to cause harm. It only runs on phones with this chip.")
                        .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                Covers.download(getApplicationContext());
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });

        c.addView(label("Lyric sync"));
        final Button syncGet = button("Get the lyric sync model (" + size(Long.parseLong(LyricSync.MODEL[1])) + ")", false);
        c.addView(syncGet);
        final CheckBox autoSync = new CheckBox(this);
        autoSync.setText("Sync the lyrics of each new song automatically");
        autoSync.setTextColor(TEXT);
        autoSync.setChecked(cfg.getBoolean("auto_sync", true));
        autoSync.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                cfg.edit().putBoolean("auto_sync", on).apply();
            }
        });
        c.addView(autoSync, gap(4));
        syncStatus = text("", DIM, 12);
        c.addView(syncStatus, gap(6));
        syncGet.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (LyricSync.ready(MainActivity.this)) {
                    toast("The lyric sync model is already installed");
                    return;
                }
                LyricSync.download(getApplicationContext());
            }
        });

        c.addView(label("Style adapters (LoRA)"));
        c.addView(chipRow(new String[] {"Add from Hugging Face", "Add from a file"}, new Runnable[] {
            new Runnable() {
                public void run() {
                    askAdapterRepo();
                }
            },
            new Runnable() {
                public void run() {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, "Pick a .safetensors adapter"), PICK_ADAPTER);
                }
            },
        }));
        adapterStatus = text("", DIM, 12);
        c.addView(adapterStatus, gap(6));
        adapterBox = column();
        c.addView(adapterBox);
        refreshAdapters();

        Button about = button("About and licences", false);
        c.addView(about, gap(18));
        about.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                alert(About.title(versionName()), About.TEXT);
            }
        });

        c.addView(label("Engine log"));
        Button copy = button("Copy log", false);
        c.addView(copy);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Killa YuE log", EngineService.logText()));
                toast("Log copied");
            }
        });
        logView = text("", DIM, 10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setBackground(box(0xF2080909, 10));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        c.addView(logView, gap(8));
        return c;
    }

    void saveEngineSettings() {
        if (!ready) return;
        String dev = DEVICES[deviceSpin.getSelectedItemPosition()];
        String q = Store.QUANTS[quantSpin.getSelectedItemPosition()];
        int seq = SEQS[seqSpin.getSelectedItemPosition()];
        boolean keep = keepBox.isChecked();
        boolean gentle = gentleBox.isChecked();
        boolean same = dev.equals(cfg.getString("device", "Vulkan0")) && q.equals(cfg.getString("quant", "Q6_K"))
                && seq == cfg.getInt("maxseq", 8192) && keep == cfg.getBoolean("keep", false)
                && gentle == cfg.getBoolean("gentle", true);
        if (same) return;
        cfg.edit().putString("device", dev).putString("quant", q).putInt("maxseq", seq).putBoolean("keep", keep)
                .putBoolean("gentle", gentle).apply();
        refreshModels();
        if (EngineService.running) {
            if (jobBusy) {
                toast("The change applies after this song, when the engine restarts");
            } else {
                EngineService.stop(this);
                toast("Engine stopped. It starts with the new settings on the next song.");
            }
        }
    }

    void refreshModels() {
        if (modelBox == null) return;
        modelBox.removeAllViews();
        modelNames = new String[] {Store.backbone(this), Store.VAE, Store.MERT, Store.SHEET, Store.TOKENIZER};
        String[] what = {
            "Song model (required)", "Sound decoder (required)", "Listener, for covers and recordings",
            "Cover pack: reads melody and chords", "Recording pack: continue or re-render audio",
        };
        modelText = new TextView[modelNames.length];
        modelBtn = new Button[modelNames.length];
        for (int i = 0; i < modelNames.length; i++) {
            final String name = modelNames[i];
            LinearLayout r = row();
            r.setBackground(glass(EDGE));
            r.setPadding(dp(14), dp(10), dp(8), dp(10));
            LinearLayout info = column();
            info.addView(text(what[i], TEXT, 14));
            modelText[i] = text("", DIM, 12);
            info.addView(modelText[i]);
            r.addView(info, weight());
            modelBtn[i] = chip("");
            r.addView(modelBtn[i]);
            modelBtn[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    modelAction(name);
                }
            });
            modelBox.addView(r, gap(8));
        }
        storageNote.setText("Files are kept in " + Store.root(this).getAbsolutePath()
                + "\nAdapters you add below are installed for you, in the adapters folder there."
                + "\nModel weights: YuE2 by M-A-P, CC BY-NC 4.0. Engine: yue2.cpp by Serveurperso, MIT.");
    }

    void modelAction(final String name) {
        if (Store.busy(name)) {
            Store.cancel(name);
        } else if (Store.have(this, name)) {
            new Lux.Builder(this).setTitle("Remove this download?")
                    .setMessage(name + "\nYou can download it again later.")
                    .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            if (EngineService.running && !jobBusy) EngineService.stop(MainActivity.this);
                            new File(Store.models(MainActivity.this), name).delete();
                        }
                    }).setNegativeButton("Keep", null).show();
        } else {
            Store.download(getApplicationContext(), name);
        }
    }

    /** How far the phone's heat control has cut the GPU clock, or "" when it is at full speed or unreadable. */
    static String gpuHeat() {
        try {
            String d = "/sys/class/kgsl/kgsl-3d0/";
            long cap = Long.parseLong(new String(Store.read(new File(d + "max_gpuclk")), "UTF-8").trim());
            String[] all = new String(Store.read(new File(d + "gpu_available_frequencies")), "UTF-8").trim().split("\\s+");
            long top = 0;
            for (int i = 0; i < all.length; i++) top = Math.max(top, Long.parseLong(all[i]));
            if (top == 0 || cap >= top) return "";
            return "Phone is hot: GPU limited to " + (cap * 100 / top) + "% speed. Let it cool for faster songs.";
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- style adapters ----------

    /** Every adapter the engine can load: a .safetensors file, or a folder holding one. */
    ArrayList<File> installedAdapters() {
        ArrayList<File> out = new ArrayList<File>();
        File[] all = Store.adapters(this).listFiles();
        if (all == null) return out;
        Arrays.sort(all);
        for (int i = 0; i < all.length; i++) {
            File f = all[i];
            if (f.isFile() && f.getName().endsWith(".safetensors")) out.add(f);
            if (f.isDirectory()) {
                File[] in = f.listFiles();
                for (int j = 0; in != null && j < in.length; j++) {
                    if (in[j].getName().endsWith(".safetensors")) {
                        out.add(f);
                        break;
                    }
                }
            }
        }
        return out;
    }

    static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] in = f.listFiles();
        for (int i = 0; in != null && i < in.length; i++) n += sizeOf(in[i]);
        return n;
    }

    static void deleteAll(File f) {
        File[] in = f.listFiles();
        for (int i = 0; in != null && i < in.length; i++) deleteAll(in[i]);
        f.delete();
    }

    void refreshAdapters() {
        if (adapterBox == null) return;
        adapterBox.removeAllViews();
        ArrayList<File> list = installedAdapters();
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            final File f = list.get(i);
            seen.append(f.getName()).append('|');
            LinearLayout r = row();
            r.setBackground(glass(EDGE));
            r.setPadding(dp(14), dp(10), dp(8), dp(10));
            LinearLayout info = column();
            info.addView(text(f.getName(), TEXT, 14));
            info.addView(text(size(sizeOf(f)), DIM, 12));
            r.addView(info, weight());
            Button use = chip("Use");
            Button del = chip("Remove");
            r.addView(use);
            r.addView(del);
            use.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String cur = val(adaptersBox);
                    if (!cur.contains(f.getName())) adaptersBox.setText(cur.length() == 0 ? f.getName() + ":1.0" : cur + ", " + f.getName() + ":1.0");
                    advBox.setVisibility(View.VISIBLE);
                    showTab(0);
                    toast("Added to this song. Change the number after the colon for more or less of it.");
                }
            });
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new Lux.Builder(MainActivity.this).setTitle("Remove this adapter?").setMessage(f.getName())
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    deleteAll(f);
                                    adapterMsg = "";
                                    dropMissingAdapters();
                                    adaptersChanged();
                                }
                            }).setNegativeButton("Keep", null).show();
                }
            });
            adapterBox.addView(r, gap(8));
        }
        adapterSeen = seen.toString();
        adapterSeenAt = System.currentTimeMillis();
        if (list.size() == 0) {
            adapterBox.addView(text("None installed. An adapter bends the model toward one style or voice.", DIM, 12), gap(4));
        }
    }

    /** Takes adapters that are no longer installed out of the song's adapter box. True if any were dropped. */
    boolean dropMissingAdapters() {
        String cur = val(adaptersBox);
        if (cur.length() == 0) return false;
        ArrayList<File> have = installedAdapters();
        StringBuilder keep = new StringBuilder();
        boolean dropped = false;
        String[] items = cur.split(",");
        for (int i = 0; i < items.length; i++) {
            String it = items[i].trim();
            if (it.length() == 0) continue;
            int k = it.lastIndexOf(':');
            String name = k > 0 ? it.substring(0, k).trim() : it;
            boolean found = false;
            for (int j = 0; j < have.size(); j++) if (have.get(j).getName().equals(name)) found = true;
            if (found) keep.append(keep.length() > 0 ? ", " : "").append(it);
            else dropped = true;
        }
        if (dropped) adaptersBox.setText(keep.toString());
        return dropped;
    }

    /** The engine reads the adapters folder when it starts, so a change means a restart. */
    void adaptersChanged() {
        refreshAdapters();
        if (EngineService.running && !jobBusy) EngineService.stop(this);
    }

    // ---------- community skins ----------

    void useSkin(String id) {
        cfg.edit().putString("skin", id).putInt("tab", TAB_ENGINE).apply();
        recreate(); // everything is rebuilt with the new artwork and colours
    }

    void installSkin(final Uri u) {
        toast("Installing the skin...");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String id = null, err = null;
                try {
                    id = SkinPack.install(app, u);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                final String fid = id, ferr = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            alert("Could not install the skin", ferr);
                            return;
                        }
                        Skin.forget();
                        toast("Installed " + SkinPack.name(MainActivity.this, fid));
                        useSkin(fid);
                    }
                });
            }
        }, "skin-install").start();
    }

    void removeSkin() {
        final ArrayList<String> ids = SkinPack.ids(this);
        if (ids.size() == 0) {
            alert("Remove a skin", "Only skins you installed can be removed, and none are installed. The three built-in skins always stay.");
            return;
        }
        String[] names = new String[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = SkinPack.name(this, ids.get(i));
        new Lux.Builder(this).setTitle("Remove a skin").setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String id = ids.get(w);
                boolean inUse = id.equals(Skin.id(MainActivity.this));
                SkinPack.remove(MainActivity.this, id);
                if (inUse) {
                    Skin.forget();
                    useSkin(Skin.IDS[0]);
                } else {
                    cfg.edit().putInt("tab", TAB_ENGINE).apply();
                    recreate(); // the skin list is rebuilt without it
                }
            }
        }).show();
    }

    /** Writes the skin in use as a skin file to start from, and explains what to change. */
    void skinTemplate() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                File out = null;
                try {
                    File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOCUMENTS), "Killa YuE");
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("Could not open Documents/Killa YuE");
                    out = SkinPack.template(app, new File(dir, "skin-template.zip"));
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                final String ferr = err;
                final File fout = out;
                ui.post(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            alert("Could not write the template", ferr);
                            return;
                        }
                        alert("Make your own skin", "The skin you are using is saved as " + fout.getAbsolutePath() + ".\n\n"
                                + "Unzip it, repaint the pictures (keep their names and sizes), set your colours and a new name in "
                                + "skin.json, zip the files again and choose \"Install a skin\". Share the zip and anyone can install it. "
                                + "SKINS.md in the source repository describes every file.");
                    }
                });
            }
        }, "skin-template").start();
    }

    void importAdapterFile(final Uri u) {
        String name = null;
        try {
            android.database.Cursor cur = getContentResolver().query(u, new String[] {"_display_name"}, null, null, null);
            if (cur != null) {
                if (cur.moveToFirst()) name = cur.getString(0);
                cur.close();
            }
        } catch (Exception e) {
            name = null;
        }
        if (name == null) name = String.valueOf(u.getLastPathSegment());
        name = name.substring(name.lastIndexOf('/') + 1);
        if (!name.endsWith(".safetensors")) {
            alert("Not an adapter file", "Adapters are files ending in .safetensors. This one is called " + name + ".");
            return;
        }
        final File dest = new File(Store.adapters(this), name);
        adapterMsg = "Copying " + name;
        new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = getContentResolver().openInputStream(u);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
                    try {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        out.close();
                        in.close();
                    }
                    adapterMsg = "Installed " + dest.getName();
                } catch (Exception e) {
                    dest.delete();
                    adapterMsg = "Could not copy the file: " + e.getMessage();
                }
            }
        }, "adapter-copy").start();
    }

    void askAdapterRepo() {
        final EditText box = edit("name/repository, or the page address", 1);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(box);
        new Lux.Builder(this).setTitle("Adapter on Hugging Face")
                .setMessage("Paste the repository that holds the adapter. It looks like name/repository.")
                .setView(f)
                .setPositiveButton("Look", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String r = val(box).replace("https://huggingface.co/", "").replace("http://huggingface.co/", "");
                        int cut = r.indexOf("/tree/");
                        if (cut > 0) r = r.substring(0, cut);
                        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
                        if (r.split("/").length != 2) {
                            alert("That does not look right", "A repository is two names with a slash between them.");
                            return;
                        }
                        listAdapterRepo(r);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Finds the adapters in a repository: each folder with a .safetensors, and loose .safetensors files. */
    void listAdapterRepo(final String repo) {
        adapterMsg = "Looking in " + repo;
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(
                            "https://huggingface.co/api/models/" + repo + "/tree/main?recursive=true").openConnection();
                    con.setConnectTimeout(20000);
                    con.setReadTimeout(30000);
                    if (con.getResponseCode() != 200) throw new Exception("Hugging Face answered " + con.getResponseCode());
                    JSONArray tree = new JSONArray(new String(Api.readAll(con.getInputStream()), "UTF-8"));
                    con.disconnect();
                    final ArrayList<String> groups = new ArrayList<String>();   // "" for loose files, else the folder
                    final ArrayList<String> labels = new ArrayList<String>();
                    final ArrayList<ArrayList<String[]>> files = new ArrayList<ArrayList<String[]>>(); // path, size
                    for (int i = 0; i < tree.length(); i++) {
                        JSONObject o = tree.getJSONObject(i);
                        String path = o.optString("path");
                        if (!"file".equals(o.optString("type")) || !path.endsWith(".safetensors")) continue;
                        int slash = path.lastIndexOf('/');
                        String dir = slash < 0 ? "" : path.substring(0, slash);
                        ArrayList<String[]> set = new ArrayList<String[]>();
                        set.add(new String[] {path, String.valueOf(o.optLong("size"))});
                        long total = o.optLong("size");
                        if (dir.length() > 0) {
                            for (int j = 0; j < tree.length(); j++) {
                                JSONObject q = tree.getJSONObject(j);
                                String qp = q.optString("path");
                                if ("file".equals(q.optString("type")) && qp.startsWith(dir + "/") && qp.endsWith(".json")
                                        && qp.lastIndexOf('/') == slash) {
                                    set.add(new String[] {qp, String.valueOf(q.optLong("size"))});
                                }
                            }
                        }
                        groups.add(dir.length() > 0 ? dir : path);
                        labels.add((dir.length() > 0 ? dir : path) + "   " + size(total));
                        files.add(set);
                    }
                    if (groups.size() == 0) throw new Exception("No .safetensors adapters were found in " + repo);
                    adapterMsg = "";
                    ui.post(new Runnable() {
                        public void run() {
                            new Lux.Builder(MainActivity.this).setTitle("Which adapter?")
                                    .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int which) {
                                            downloadAdapter(repo, groups.get(which), files.get(which));
                                        }
                                    }).setNegativeButton("Cancel", null).show();
                        }
                    });
                } catch (Exception e) {
                    adapterMsg = "Could not read that repository: " + e.getMessage();
                }
            }
        }, "adapter-list").start();
    }

    void downloadAdapter(final String repo, final String group, final ArrayList<String[]> files) {
        final File root = Store.adapters(this);
        final String base = repo.substring(repo.indexOf('/') + 1);
        final boolean folder = files.size() > 1 || group.contains("/") || !group.endsWith(".safetensors");
        // a folder adapter keeps its files together under one clear name
        final File dir = folder ? new File(root, base + "-" + group.replace('/', '-')) : root;
        new Thread(new Runnable() {
            public void run() {
                try {
                    dir.mkdirs();
                    for (int i = 0; i < files.size(); i++) {
                        String path = files.get(i)[0];
                        String name = path.substring(path.lastIndexOf('/') + 1);
                        File dest = new File(dir, name);
                        File part = new File(dir, name + ".part");
                        final Store.Dl d = new Store.Dl();
                        d.total = Long.parseLong(files.get(i)[1]);
                        final String shown = folder ? dir.getName() : name;
                        Thread watch = new Thread(new Runnable() {
                            public void run() {
                                while (!"done".equals(d.state) && !"failed".equals(d.state)) {
                                    adapterMsg = "Downloading " + shown + ": " + size(d.done) + " of " + size(d.total);
                                    try {
                                        Thread.sleep(700);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }
                            }
                        });
                        watch.start();
                        try {
                            Store.fetch("https://huggingface.co/" + repo + "/resolve/main/" + path, part, d);
                            if (part.length() != d.total) throw new Exception("The download of " + name + " came up short");
                            if (!part.renameTo(dest)) throw new Exception("Could not move " + name + " into place");
                            d.state = "done";
                        } catch (Exception e) {
                            d.state = "failed";
                            throw e;
                        }
                    }
                    adapterMsg = "Installed " + (folder ? dir.getName() : group);
                } catch (Exception e) {
                    adapterMsg = "Download failed: " + e.getMessage();
                }
            }
        }, "adapter-dl").start();
    }

    static String size(long b) {
        if (b >= 1000000000L) return String.format(Locale.US, "%.2f GB", b / 1e9);
        return (b / 1000000) + " MB";
    }

    // ---------- periodic refresh ----------

    void refreshStatus() {
        boolean core = snapCore;
        String es;
        if (!snapReady) es = "Checking files";
        else if (!core) es = "Model not downloaded";
        else if (!EngineService.running) es = "Engine off";
        else if (!engineHealthy) es = "Engine starting";
        else es = jobBusy ? "Engine busy" : "Engine ready";
        header.setText(es);
        header.setTextColor(engineHealthy && EngineService.running ? 0xFF58D68D : DIM);

        genBtn.setText(jobBusy ? "Cancel" : "Generate song");
        if (jobBusy) {
            String st = jobMsg.length() > 0 ? jobMsg : EngineService.stage;
            if (st.length() == 0) st = "Working";
            int cur = EngineService.progCur, max = EngineService.progMax;
            boolean bar = jobMsg.length() == 0 && max > 0;
            prog.setVisibility(View.VISIBLE);
            prog.setIndeterminate(!bar);
            if (bar) {
                prog.setMax(max);
                prog.setProgress(cur);
                st += "  " + cur + " / " + max;
            } else if (jobMsg.length() == 0 && cur > 0) {
                st += "  " + cur + " notes";
            }
            int el = (int) ((System.currentTimeMillis() - jobStart) / 1000);
            String heat = snapHeat;
            String eta = Eta.text();
            // the estimate gets its own line, so the status never wraps in the middle of a phrase
            progText.setText(st + "   ·   " + clock(el) + " elapsed"
                    + (eta.length() > 0 ? "\n" + Character.toUpperCase(eta.charAt(0)) + eta.substring(1) : "")
                    + (heat.length() > 0 ? "\n" + heat : ""));
        } else {
            prog.setVisibility(View.GONE);
            String idle = jobError.length() > 0 && !"Cancelled".equals(jobError) ? "Last attempt failed: " + jobError : "";
            progText.setText(queueNote.length() > 0 ? queueNote + (idle.length() > 0 ? "\n" + idle : "") : idle);
        }

        if (panels[TAB_ENGINE].getVisibility() == View.VISIBLE) {
            String heat = snapHeat;
            engineStatus.setText(es + (heat.length() > 0 ? "\n" + heat : "")
                    + (EngineService.lastError.length() > 0 && !EngineService.running
                    ? "\n" + EngineService.lastError : ""));
            engineBtn.setText(EngineService.running ? "Stop engine" : "Start engine");
            for (int i = 0; modelNames != null && i < modelNames.length; i++) {
                String n = modelNames[i];
                String[] f = Store.info(n);
                Store.Dl d = Store.status(n);
                String s = size(Long.parseLong(f[1]));
                String btn;
                if (Store.busy(n)) {
                    s = d.state.equals("checking") ? "Checking the file" : size(d.done) + " of " + s;
                    btn = "Stop";
                } else if (is(snapHave, n)) {
                    s += "  ·  ready";
                    btn = "Remove";
                } else {
                    if (d != null && d.state.equals("failed")) s += "  ·  " + d.error;
                    btn = is(snapPart, n) ? "Resume" : "Get";
                }
                modelText[i].setText(s);
                modelBtn[i].setText(btn);
            }
            // a pack that has just arrived only takes effect when the engine starts again
            String packs = "" + is(snapHave, Store.MERT) + is(snapHave, Store.SHEET) + is(snapHave, Store.TOKENIZER);
            if (snapReady) {
                if (packsSeen != null && !packs.equals(packsSeen) && EngineService.running && !jobBusy) EngineService.stop(this);
                packsSeen = packs;
            }
            adapterStatus.setText(adapterMsg);
            if (bgStatus != null && !snapBg.equals(bgStatus.getText().toString())) bgStatus.setText(snapBg);
            if (syncStatus != null) {
                String ss = LyricSync.state.length() > 0 && (LyricSync.busy || !snapSync) ? LyricSync.state
                        : snapSync ? "Installed. Lyrics are timed to the singing after each song, or from the Lyrics tab." : "Not installed.";
                if (!ss.equals(syncStatus.getText().toString())) syncStatus.setText(ss);
            }
            coverStatus.setText(Covers.state.length() > 0 ? Covers.state
                    : snapCovers ? "Installed (" + ("gpu".equals(Covers.painter(this)) ? "GPU" : "NPU") + "). New songs get a cover by themselves; \"Make a cover\" in a song's menu paints another."
                    : "Not installed.");
            // only a list read after the last refresh counts, so a removal is not seen twice
            if (snapReady && snapAt > adapterSeenAt && !snapAdapters.equals(adapterSeen)) adaptersChanged();
            // the log is rebuilt only when it changed, and at most every 1.5 s while lines pour in
            int lv = EngineService.logVersion;
            long t = System.currentTimeMillis();
            if (lv != logSeen && t - logShownAt > 1500) {
                logSeen = lv;
                logShownAt = t;
                logView.setText(EngineService.logText());
            }
        }

        playerView.invalidate();
    }
}
