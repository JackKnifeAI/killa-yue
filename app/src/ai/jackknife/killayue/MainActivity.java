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
    static final int BG = 0xFF080909, CARD = 0xF20B0C0C, TEXT = 0xFFE9E3D5, DIM = 0xFF9D988C, ACCENT = 0xFFE7BD61;
    static final int EDGE = 0xFF54482D;
    static final int PICK_AUDIO = 7, PICK_ADAPTER = 8;
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

    static final String ABOUT = "Songs made entirely on your phone. Nothing you write or generate leaves the device; "
            + "the only network use is downloading models and adapters you ask for.\n\n"
            + "Needs a phone with a Qualcomm Adreno GPU, Android 9 or later, about 6 GB of free memory for "
            + "full-length songs and 4 GB of storage for the model.\n\n"
            + "App: (c) 2026 JackKnife AI, Apache License 2.0.\n"
            + "Song model: YuE2 by M-A-P, weights under CC BY-NC 4.0 with the authors' creator permission; "
            + "downloaded separately, not part of this app. Companies need a licence from the authors for commercial use.\n"
            + "Engine: yue2.cpp by Serveurperso, MIT. GGML, MIT.\n"
            + "GPU driver: Mesa Turnip, MIT. Vulkan loader, Apache License 2.0.\n"
            + "zlib (zlib licence), Zstandard (BSD), Expat (MIT), libc++ (Apache 2.0 with LLVM exception).\n"
            + "Killa typefaces: SIL Open Font License 1.1.\n"
            + "Adapters you install belong to their makers and carry their own terms.\n\n"
            + "Killa YuE is an independent project and is not affiliated with or endorsed by the YuE2 authors.\n"
            + "Full licence texts are in the source repository: github.com/JackKnifeAI/killa-yue";

    // one job at a time, shared across activity restarts
    static volatile boolean jobBusy;
    static volatile String jobId, jobMsg = "", jobError = "";
    static volatile boolean jobCancel, engineHealthy;
    static volatile long jobStart;
    static MainActivity inst;

    final Handler ui = new Handler();
    SharedPreferences cfg;
    boolean ready;

    TextView header, banner, lengthLabel, progText, engineStatus, logView, storageNote;
    View[] panels = new View[3];
    Button[] tabs = new Button[3];
    EditText style, lyrics, score, steps, variations, lmSeed, seed, cfgScale, bitrate, tempScore, tempSong, adaptersBox;
    SeekBar lengthBar;
    PlayerView playerView;
    KaraokeView karaokeView;
    ArrayList<Karaoke.Line> karaoke = new ArrayList<Karaoke.Line>();
    EditText songTitle;
    static volatile String pendingTitle = "";
    String playTitle = "";
    final ArrayList<JSONObject> playlist = new ArrayList<JSONObject>();
    DialView lengthDial;
    Spinner cotSpin, formatSpin, deviceSpin, quantSpin, seqSpin;
    LinearLayout advBox, libraryBox, modelBox, adapterBox;
    TextView adapterStatus;
    String adapterSeen = "", packsSeen = null;
    Button genBtn, engineBtn, scoreToggle;
    StaffProgress prog;
    CheckBox keepBox;
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
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- small view builders ----------

    GradientDrawable box(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    GradientDrawable glass(int edge) {
        GradientDrawable g = box(CARD, 12);
        g.setStroke(dp(2), edge);
        return g;
    }

    TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(DIM);
        t.setTextSize(13);
        t.setTypeface(Skin.text(), Typeface.BOLD);
        t.setShadowLayer(4, 0, 1, 0x99000000);
        t.setPadding(0, dp(16), 0, dp(5));
        return t;
    }

    TextView text(String s, int color, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        return t;
    }

    EditText edit(String hint, int minLines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF6E6A60);
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setBackground(glass(EDGE));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setGravity(Gravity.TOP | Gravity.START);
        if (minLines > 1) {
            e.setMinLines(minLines);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    EditText numEdit(String hint) {
        EditText e = edit(hint, 1);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return e;
    }

    Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setShadowLayer(5, 0, 2, 0xCC000000);
        b.setStateListAnimator(null);
        if (primary) {
            b.setTypeface(Skin.script());
            b.setTextSize(24);
            b.setTextColor(Skin.GOLD);
            b.setBackground(new Skin.Plate(true));
            b.setPadding(dp(30), 0, dp(30), dp(2));
            b.setMinHeight(dp(64));
            b.setMinimumHeight(dp(64));
        } else {
            b.setTypeface(Skin.text(), Typeface.BOLD);
            b.setTextSize(15);
            b.setTextColor(Skin.CREAM);
            b.setBackground(new Skin.Plate(false));
            b.setPadding(dp(14), 0, dp(14), dp(6));
            b.setMinHeight(dp(50));
            b.setMinimumHeight(dp(50));
        }
        return b;
    }

    Button chip(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Skin.GOLD);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        GradientDrawable pill = box(0xFF0B0C0C, 18);
        pill.setStroke(dp(1), EDGE);
        b.setBackground(pill);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        return lp;
    }

    Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setBackground(glass(EDGE));
        s.setPadding(dp(6), dp(8), dp(6), dp(8));
        return s;
    }

    HorizontalScrollView chipRow(String[] names, final Runnable[] actions) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout l = row();
        for (int i = 0; i < names.length; i++) {
            Button c = chip(names[i]);
            final Runnable r = actions[i];
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    r.run();
                }
            });
            l.addView(c);
        }
        h.addView(l);
        h.setPadding(0, dp(8), 0, 0);
        return h;
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

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    void alert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    // ---------- lifecycle ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        cfg = getSharedPreferences("cfg", 0);
        Skin.load(this);
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != 0) {
            requestPermissions(new String[] {"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
        }

        LinearLayout root = column();
        root.setBackground(Skin.leather(this));
        root.setFitsSystemWindows(true);

        LinearLayout top = row();
        top.setPadding(dp(18), dp(8), dp(18), dp(8));
        top.setBackgroundColor(0xFF080909);
        TextView title = text("Killa YuE", Skin.GOLD, 30);
        title.setTypeface(Skin.script());
        top.addView(title, weight());
        header = text("", DIM, 12);
        top.addView(header);
        root.addView(top);

        LinearLayout tabRow = row();
        tabRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        String[] names = {"Create", "Library", "Engine"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            tabs[i] = button(names[i], false);
            LinearLayout.LayoutParams lp = weight();
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            tabRow.addView(tabs[i], lp);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showTab(idx);
                }
            });
        }
        root.addView(tabRow);

        FrameLayout content = new FrameLayout(this);
        panels[0] = scroll(buildCreate());
        panels[1] = scroll(buildLibrary());
        panels[2] = scroll(buildEngine());
        for (int i = 0; i < 3; i++) content.addView(panels[i]);
        karaokeView = new KaraokeView(this);
        karaokeView.setVisibility(View.GONE);
        content.addView(karaokeView);
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
        showTab(Store.coreReady(this) ? 0 : 2);
        tick.run();
        healthLoop();
    }

    View scroll(View v) {
        ScrollView s = new ScrollView(this);
        v.setPadding(dp(16), dp(4), dp(16), dp(24));
        s.addView(v);
        return s;
    }

    void showKaraoke(boolean show) {
        karaokeView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) karaokeView.invalidate();
    }

    void showTab(int idx) {
        if (karaokeView != null) showKaraoke(false);
        for (int i = 0; i < 3; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean on = i == idx;
            tabs[i].setBackground(new Skin.Plate(true));
            tabs[i].setTextColor(on ? 0xFFFFE2A6 : 0xFF8C7A4E);
            tabs[i].setPadding(dp(18), 0, dp(18), dp(2));
        }
        if (idx == 1) refreshLibrary();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refreshModels();
        refreshLibrary();
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
            ui.postDelayed(this, 600);
        }
    };

    // ---------- Create ----------

    View buildCreate() {
        LinearLayout c = column();

        banner = text("", TEXT, 13);
        banner.setBackground(glass(Skin.GOLD));
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clearCarry();
            }
        });
        c.addView(banner, gap(8));

        c.addView(chipRow(new String[] {"Undo", "Redo", "Clear style and lyrics"}, new Runnable[] {
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
        TextView hint = text("Turn the dial or drag the slider", DIM, 12);
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
        genBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (jobBusy) cancelJob();
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
        lengthLabel.setText("Length: " + clock(lengthSeconds()));
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
        return chipRow(new String[] {"Save this " + word, "My saved " + kind}, new Runnable[] {
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
        new AlertDialog.Builder(this).setTitle("Save this " + word + " as").setView(f)
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
        new AlertDialog.Builder(this).setTitle("My saved " + kind).setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                final int index = names.length - 1 - which;
                final JSONObject item = items.optJSONObject(index);
                new AlertDialog.Builder(MainActivity.this).setTitle(item.optString("name"))
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

    void generate() {
        if (val(style).length() == 0 && val(lyrics).length() == 0 && semTokens.length() == 0) {
            toast("Write a style or some lyrics first");
            return;
        }
        final JSONObject req;
        try {
            req = buildRequest();
        } catch (Exception e) {
            alert("Check the settings", "One of the numbers in Advanced settings is not valid.");
            return;
        }
        final Context app = getApplicationContext();
        pendingTitle = val(songTitle);
        startJob("Starting", new Task() {
            public void run() throws Exception {
                ensureEngine(app);
                jobMsg = "Queued";
                String id = Api.synth(req);
                waitJob(id);
                jobMsg = "Saving";
                int n = saveTracks(app, Api.parts(Api.resultRaw(id)), req);
                if (n == 0) throw new Exception("The engine finished but returned no audio.");
            }

            public void done() {
                showTab(1);
                toast("Song ready");
            }
        });
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
        jobMsg = msg;
        jobId = null;
        jobStart = System.currentTimeMillis();
        EngineService.setStage("", 0, 0);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    t.run();
                } catch (Exception e) {
                    err = jobCancel ? "Cancelled" : String.valueOf(e.getMessage());
                }
                final String ferr = err;
                jobBusy = false;
                jobId = null;
                jobError = ferr == null ? "" : ferr;
                EngineService.setStage("", 0, 0);
                if (ferr == null) EngineService.announce("Finished. Tap to open.");
                else if (!"Cancelled".equals(ferr)) EngineService.announce("That did not work: " + ferr);
                final MainActivity a = inst;
                if (a != null) {
                    a.ui.post(new Runnable() {
                        public void run() {
                            if (ferr == null) t.done();
                            else if (!"Cancelled".equals(ferr)) a.alert("That did not work", ferr);
                        }
                    });
                }
            }
        }, "job").start();
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
                throw new Exception("The engine stopped while working. " + EngineService.lastError
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
        new AlertDialog.Builder(this).setTitle("What should happen with this recording?")
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
            showTab(2);
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
        new AlertDialog.Builder(this).setTitle("Which part starts the new song?")
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

    void refreshLibrary() {
        if (libraryBox == null) return;
        libraryBox.removeAllViews();
        File[] files = Store.library(this).listFiles();
        ArrayList<File> metas = new ArrayList<File>();
        if (files != null) {
            for (int i = 0; i < files.length; i++) if (files[i].getName().endsWith(".json")) metas.add(files[i]);
            java.util.Collections.sort(metas, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
        }
        if (metas.size() == 0) {
            TextView t = text("No songs yet. Make one on the Create tab.", DIM, 16);
            t.setPadding(0, dp(24), 0, 0);
            libraryBox.addView(t);
            return;
        }
        playlist.clear();
        SimpleDateFormat df = new SimpleDateFormat("d MMM, HH:mm", Locale.US);
        for (int i = 0; i < metas.size(); i++) {
            final File mf = metas.get(i);
            final JSONObject m;
            try {
                m = new JSONObject(new String(Store.read(mf), "UTF-8"));
            } catch (Exception e) {
                continue;
            }
            playlist.add(m);
            LinearLayout r = row();
            r.setBackground(glass(EDGE));
            r.setPadding(dp(14), dp(12), dp(8), dp(12));
            LinearLayout info = column();
            TextView t = text(m.optString("_title", "Untitled"), TEXT, 15);
            t.setSingleLine(true);
            info.addView(t);
            String sub = clock(m.optInt("_seconds")) + "  ·  " + df.format(new Date(m.optLong("_created")));
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
        String[] items = {
            "Play", "Lyrics, karaoke style", "New sound, same song", "Edit the score and render again", "Continue from this song",
            "Use the same style and lyrics", "Share", "Change the title", "Details", "Delete",
        };
        new AlertDialog.Builder(this).setTitle(title).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    play(m);
                } else if (w == 1) {
                    if (!m.optString("_audio").equals(playingFile)) play(m);
                    showKaraoke(true);
                } else if (w == 2) {
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
                } else if (w == 3) {
                    clearCarry();
                    loadPrompt(m, true);
                    showScore(true);
                    lmSeed.setText("");
                    showTab(0);
                    toast("Change the score, then Generate. The song is written again from it.");
                } else if (w == 4) {
                    if (tokens.length() == 0) {
                        toast("This song has no saved melody data");
                        return;
                    }
                    clearCarry();
                    loadPrompt(m, false);
                    srcTokens = tokens;
                    carryName = title;
                    askSection(tokens.split(",").length / 25);
                } else if (w == 5) {
                    clearCarry();
                    loadPrompt(m, false);
                    showTab(0);
                } else if (w == 6) {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    String a = m.optString("_audio");
                    s.setType(a.endsWith(".wav") ? "audio/wav" : "audio/mpeg");
                    s.putExtra(Intent.EXTRA_STREAM, TrackProvider.uri(a));
                    s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(s, "Share song"));
                } else if (w == 7) {
                    retitle(mf, m);
                } else if (w == 8) {
                    String abc = m.optString("abc");
                    alert(title, "File: " + new File(Store.library(MainActivity.this), m.optString("_audio")).getAbsolutePath()
                            + "\n\nStyle: " + m.optString("style")
                            + "\n\nMelody seed: " + m.optString("lm_seed") + "\nSound seed: " + m.optString("seed")
                            + "\nRender steps: " + m.optString("steps")
                            + "\n\nLyrics:\n" + m.optString("lyrics")
                            + (abc.length() > 0 ? "\n\nScore:\n" + abc : ""));
                } else {
                    new AlertDialog.Builder(MainActivity.this).setTitle("Delete this song?")
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

    void retitle(final File mf, final JSONObject m) {
        final EditText e = edit("Title", 1);
        e.setText(m.optString("_title", ""));
        e.selectAll();
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(e);
        new AlertDialog.Builder(this).setTitle("Song title").setView(f)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            String t = val(e).length() == 0 ? "Untitled" : val(e);
                            long stamp = mf.lastModified();
                            m.put("_title", t);
                            Store.write(mf, m.toString().getBytes("UTF-8"));
                            mf.setLastModified(stamp); // keep the song's place in the list
                            if (m.optString("_audio").equals(playingFile)) playTitle = t;
                        } catch (Exception ex) {
                            toast("Could not save the new title");
                        }
                        refreshLibrary();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ---------- player ----------

    View buildPlayer() {
        playerView = new PlayerView(this);
        return playerView;
    }

    void stopPlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        playingFile = "";
        playTitle = "";
        karaoke = new ArrayList<Karaoke.Line>();
        if (playerView != null) playerView.invalidate();
    }

    void play(JSONObject m) {
        File f = new File(Store.library(this), m.optString("_audio"));
        try {
            stopPlayer();
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(PlayerView.onEnd(this));
            player.start();
            playingFile = f.getName();
            playTitle = m.optString("_title", "Untitled");
            karaoke = Karaoke.build(m.optString("lyrics"), m.optString("abc"), player.getDuration());
        } catch (Exception e) {
            stopPlayer();
            toast("Could not play this file");
        }
        playerView.invalidate();
    }

    int playingIndex() {
        for (int i = 0; i < playlist.size(); i++) if (playlist.get(i).optString("_audio").equals(playingFile)) return i;
        return -1;
    }

    void togglePlay() {
        if (player == null) {
            if (playlist.size() == 0) toast("No songs yet. Make one on the Create tab.");
            else play(playlist.get(0));
            return;
        }
        if (player.isPlaying()) player.pause();
        else player.start();
        playerView.invalidate();
    }

    /** Moves to the next (1) or previous (-1) song in library order. False when there is none. */
    boolean playStep(int dir) {
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
    }

    void seekTo(float fraction) {
        if (player == null) return;
        player.seekTo((int) (fraction * player.getDuration()));
        playerView.invalidate();
    }

    // ---------- Engine ----------

    View buildEngine() {
        LinearLayout c = column();
        engineStatus = text("", Skin.GOLD, 17);
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

        c.addView(label("Downloads"));
        modelBox = column();
        c.addView(modelBox);
        storageNote = text("", DIM, 12);
        c.addView(storageNote, gap(8));
        refreshModels();

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
                alert("Killa YuE 1.1", ABOUT);
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
        boolean same = dev.equals(cfg.getString("device", "Vulkan0")) && q.equals(cfg.getString("quant", "Q6_K"))
                && seq == cfg.getInt("maxseq", 8192) && keep == cfg.getBoolean("keep", false);
        if (same) return;
        cfg.edit().putString("device", dev).putString("quant", q).putInt("maxseq", seq).putBoolean("keep", keep).apply();
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
            new AlertDialog.Builder(this).setTitle("Remove this download?")
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
                    new AlertDialog.Builder(MainActivity.this).setTitle("Remove this adapter?").setMessage(f.getName())
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    deleteAll(f);
                                    adaptersChanged();
                                }
                            }).setNegativeButton("Keep", null).show();
                }
            });
            adapterBox.addView(r, gap(8));
        }
        adapterSeen = seen.toString();
        if (list.size() == 0) {
            adapterBox.addView(text("None installed. An adapter bends the model toward one style or voice.", DIM, 12), gap(4));
        }
    }

    /** The engine reads the adapters folder when it starts, so a change means a restart. */
    void adaptersChanged() {
        refreshAdapters();
        if (EngineService.running && !jobBusy) EngineService.stop(this);
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
        new AlertDialog.Builder(this).setTitle("Adapter on Hugging Face")
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
                            new AlertDialog.Builder(MainActivity.this).setTitle("Which adapter?")
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
        boolean core = Store.coreReady(this);
        String es;
        if (!core) es = "Model not downloaded";
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
            String heat = gpuHeat();
            progText.setText(st + "   ·   " + clock(el) + " elapsed" + (heat.length() > 0 ? "\n" + heat : ""));
        } else {
            prog.setVisibility(View.GONE);
            progText.setText(jobError.length() > 0 && !"Cancelled".equals(jobError) ? "Last attempt failed: " + jobError : "");
        }

        if (panels[2].getVisibility() == View.VISIBLE) {
            String heat = gpuHeat();
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
                } else if (Store.have(this, n)) {
                    s += "  ·  ready";
                    btn = "Remove";
                } else {
                    if (d != null && d.state.equals("failed")) s += "  ·  " + d.error;
                    File part = new File(Store.models(this), n + ".part");
                    btn = part.isFile() ? "Resume" : "Get";
                }
                modelText[i].setText(s);
                modelBtn[i].setText(btn);
            }
            // a pack that has just arrived only takes effect when the engine starts again
            String packs = "" + Store.have(this, Store.MERT) + Store.have(this, Store.SHEET) + Store.have(this, Store.TOKENIZER);
            if (packsSeen != null && !packs.equals(packsSeen) && EngineService.running && !jobBusy) EngineService.stop(this);
            packsSeen = packs;
            adapterStatus.setText(adapterMsg);
            StringBuilder now = new StringBuilder();
            ArrayList<File> have = installedAdapters();
            for (int i = 0; i < have.size(); i++) now.append(have.get(i).getName()).append('|');
            if (!now.toString().equals(adapterSeen)) adaptersChanged();
            String lt = EngineService.logText();
            if (lt.length() != logView.getText().length()) logView.setText(lt);
        }

        playerView.invalidate();
    }
}
