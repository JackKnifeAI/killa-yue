package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.LuxFrame;
import ai.jackknife.killayue.MainActivity;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Store;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.AiJob;
import ai.jackknife.killayue.studio.ClipEdits;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.StudioModels;
import ai.jackknife.killayue.studio.TempoMap;
import ai.jackknife.killayue.studio.Track;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI: beats, a speaking voice and a singing voice, made on the phone and dropped into the project at the playhead.
 * Beats are Stable Audio Open Small (a beat from words, or a recording restyled); Speak is Kokoro, 54 voices in
 * eight languages; Sing hands the words to the song engine (YuE), whose vocals the Splitter brings back in. One
 * model at a time, in the ":ai" process, and never while recording or while a song is being made.
 */
final class AiScreen implements Screen {
    final StudioActivity a;
    final ScrollView view;
    final TextView status, beatState, voiceState;
    final EditText beatPrompt, speakText, singWords, singVoice;
    int bars = 4, voice = 3, quality = 0, grooveStyle = 1, grooveLength = 4;
    float speed = 1f, busy = 1f, feel = 1f;
    static ai.jackknife.killayue.studio.GrooveModel grooveModel;
    boolean restyle;
    long lastSeed;
    String watching;

    static final String[] VOICES = {"af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole",
        "af_nova", "af_river", "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael",
        "am_onyx", "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable",
        "bm_george", "bm_lewis", "ef_dora", "em_alex", "ff_siwis", "hf_alpha", "hf_beta", "hm_omega", "hm_psi", "if_sara",
        "im_nicola", "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo", "pf_dora", "pm_alex", "pm_santa",
        "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi", "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
        "em_santa"};
    static String language(String v) {
        switch (v.charAt(0)) {
            case 'a': return "American English";
            case 'b': return "British English";
            case 'e': return "Spanish";
            case 'f': return "French";
            case 'h': return "Hindi";
            case 'i': return "Italian";
            case 'j': return "Japanese";
            case 'p': return "Brazilian Portuguese";
            default: return "Mandarin Chinese";
        }
    }
    static String lang(String v) {
        switch (v.charAt(0)) {
            case 'a': return "en-us";
            case 'b': return "en-gb";
            case 'e': return "es";
            case 'f': return "fr-fr";
            case 'h': return "hi";
            case 'i': return "it";
            case 'j': return "ja";
            case 'p': return "pt-br";
            default: return "cmn";
        }
    }
    static String label(String v) {
        String name = v.substring(3);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " · " + (v.charAt(1) == 'f' ? "woman" : "man")
            + " · " + language(v);
    }

    static final String[] GENRES = {"Trap", "Boom bap", "House", "Drum & bass", "Afrobeats", "Lo-fi", "Reggaeton",
                                    "Techno", "Rock drums", "Funk", "Drill", "Ambient"};
    static final String[] GENRE_PROMPTS = {
        "hard trap beat, booming 808 bass, crisp hi-hat rolls, snappy snare",
        "boom bap hip hop drum loop, dusty vinyl drums, punchy kick and snare, swing",
        "deep house groove, four on the floor kick, open hi-hats, warm bassline",
        "drum and bass breakbeat, fast amen break, rolling sub bass",
        "afrobeats groove, log drums, shakers, syncopated percussion",
        "lo-fi hip hop beat, soft dusty drums, mellow keys, vinyl crackle",
        "reggaeton dembow rhythm, punchy kick, rim clicks, percussion",
        "driving techno drum loop, pounding kick, metallic percussion, rumble bass",
        "live rock drum kit loop, roomy snare, crash cymbals, tight kick",
        "funky drum break, ghost notes, tight snare, groovy bassline",
        "UK drill beat, sliding 808 bass, skippy hi-hats, dark",
        "ambient textured beat, soft pads, gentle percussion, evolving"};

    AiScreen(final StudioActivity a) {
        this.a = a;
        view = new ScrollView(a);
        LinearLayout col = Ui.column(a);
        int pad = Ui.dp(a, 14);
        col.setPadding(pad, Ui.dp(a, 4), pad, pad);
        view.addView(col);
        status = Ui.text(a, "", Skin.DIM, 13);
        col.addView(status);

        // ---- Groove: the drum model that runs on the phone
        LinearLayout groove = card(col, "Groove", "A drummer in your phone: real-feel grooves in 18 styles, as notes "
            + "for Killa Drums you can edit and run through the MIDI effects.");
        HorizontalScrollView ss = new HorizontalScrollView(a);
        ss.setHorizontalScrollBarEnabled(false);
        LinearLayout styles = Ui.row(a);
        ss.addView(styles);
        final Button[] styleB = new Button[ai.jackknife.killayue.studio.GrooveModel.STYLES.length];
        for (int i = 0; i < styleB.length; i++) {
            final int s = i;
            styleB[i] = Ui.chip(a, ai.jackknife.killayue.studio.GrooveModel.STYLE_NAMES[i]);
            styleB[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    grooveStyle = s;
                    for (int k = 0; k < styleB.length; k++) FxDialog.mark(a, styleB[k], k == s);
                }
            });
            FxDialog.mark(a, styleB[i], i == grooveStyle);
            styles.addView(styleB[i]);
        }
        groove.addView(ss, Ui.gap(a, 8));
        final TextView busyL = Ui.text(a, "Busy: as the drummers played", Skin.TEXT, 13);
        groove.addView(busyL, Ui.gap(a, 6));
        SeekBar busyBar = new SeekBar(a);
        busyBar.setMax(20);
        busyBar.setProgress(10);
        busyBar.setContentDescription("How busy the groove is");
        busyBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                busy = .5f + v * .05f;
                busyL.setText(v == 10 ? "Busy: as the drummers played" : v < 10 ? "Busy: sparser" : "Busy: busier");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        groove.addView(busyBar);
        final TextView feelL = Ui.text(a, "Feel: human", Skin.TEXT, 13);
        groove.addView(feelL, Ui.gap(a, 6));
        SeekBar feelBar = new SeekBar(a);
        feelBar.setMax(15);
        feelBar.setProgress(10);
        feelBar.setContentDescription("How loose the timing is");
        feelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                feel = v / 10f;
                feelL.setText(v == 0 ? "Feel: on the grid" : v < 10 ? "Feel: tight" : v == 10 ? "Feel: human" : "Feel: laid back and loose");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        groove.addView(feelBar);
        final Button grooveBars = Ui.chip(a, "4 bars");
        grooveBars.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                grooveLength = grooveLength == 4 ? 8 : grooveLength == 8 ? 2 : 4;
                grooveBars.setText(grooveLength + " bars");
            }
        });
        groove.addView(grooveBars, Ui.gap(a, 6));
        Button play = Ui.button(a, "Play me a groove", true);
        groove.addView(play, Ui.gap(a, 8));
        play.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { makeGroove(); }
        });
        help(groove, "groove");

        // ---- Beat Lab
        LinearLayout beat = card(col, "Beat Lab", "A beat as audio from a few words, at your tempo, looped into the project. "
            + "Stable Audio Open Small · Powered by Stability AI.");
        beatPrompt = Ui.edit(a, "Describe the beat: genre, drums, bass, mood", 2);
        beat.addView(beatPrompt, Ui.gap(a, 6));
        HorizontalScrollView gs = new HorizontalScrollView(a);
        gs.setHorizontalScrollBarEnabled(false);
        LinearLayout genres = Ui.row(a);
        gs.addView(genres);
        for (int i = 0; i < GENRES.length; i++) {
            final int g = i;
            Button b = Ui.chip(a, GENRES[i]);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { beatPrompt.setText(GENRE_PROMPTS[g]); }
            });
            genres.addView(b);
        }
        beat.addView(gs, Ui.gap(a, 6));
        LinearLayout opts = Ui.row(a);
        final Button barsB = Ui.chip(a, "4 bars"), qualityB = Ui.chip(a, "Quick (8 steps)"), restyleB = Ui.chip(a, "From words");
        barsB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bars = bars == 4 ? 2 : bars == 2 ? 1 : 4;
                barsB.setText(bars + (bars == 1 ? " bar" : " bars"));
            }
        });
        qualityB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                quality = (quality + 1) % 2;
                qualityB.setText(quality == 0 ? "Quick (8 steps)" : "Fine (16 steps)");
            }
        });
        restyleB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                restyle = !restyle;
                restyleB.setText(restyle ? "Restyle the selected track" : "From words");
            }
        });
        opts.addView(barsB);
        opts.addView(qualityB);
        opts.addView(restyleB);
        HorizontalScrollView os = new HorizontalScrollView(a);
        os.addView(opts);
        beat.addView(os, Ui.gap(a, 4));
        Button make = Ui.button(a, "Make the beat", true);
        beat.addView(make, Ui.gap(a, 8));
        make.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { makeBeat(false); }
        });
        Button again = Ui.button(a, "Another take (same words, new seed)", false);
        beat.addView(again, Ui.gap(a, 6));
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { makeBeat(true); }
        });
        beatState = Ui.text(a, "", Skin.DIM, 12);
        beat.addView(beatState, Ui.gap(a, 4));
        help(beat, "beats");

        // ---- Speak
        LinearLayout speak = card(col, "Speak", "Spoken words in 54 voices and eight languages, for intros, hooks and skits.");
        speakText = Ui.edit(a, "What should the voice say?", 3);
        speak.addView(speakText, Ui.gap(a, 6));
        final Button voiceB = Ui.button(a, "Voice: " + label(VOICES[voice]), false);
        speak.addView(voiceB, Ui.gap(a, 6));
        voiceB.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String[] labels = new String[VOICES.length];
                for (int i = 0; i < labels.length; i++) labels[i] = label(VOICES[i]);
                new Lux.Builder(a).setTitle("Voice").setSingleChoiceItems(labels, voice, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        voice = w;
                        voiceB.setText("Voice: " + label(VOICES[w]));
                        d.dismiss();
                    }
                }).show();
            }
        });
        final TextView speedL = Ui.text(a, "Speed 1.0×", Skin.TEXT, 13);
        speak.addView(speedL, Ui.gap(a, 6));
        SeekBar speedBar = new SeekBar(a);
        speedBar.setMax(14);
        speedBar.setProgress(5);
        speedBar.setContentDescription("Speaking speed");
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                speed = .5f + p * .1f;
                speedL.setText(String.format(java.util.Locale.US, "Speed %.1f×", speed));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        speak.addView(speedBar);
        Button say = Ui.button(a, "Speak it into the project", true);
        speak.addView(say, Ui.gap(a, 8));
        say.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { speak(); }
        });
        voiceState = Ui.text(a, "", Skin.DIM, 12);
        speak.addView(voiceState, Ui.gap(a, 4));
        help(speak, "voice");

        // ---- Sing
        LinearLayout sing = card(col, "Sing", "Sung lines by the song engine, then its vocals into this project.");
        singWords = Ui.edit(a, "[Verse]\nThe words to sing", 4);
        sing.addView(singWords, Ui.gap(a, 6));
        singVoice = Ui.edit(a, "The voice: e.g. soulful female vocal, R&B, warm, slow", 1);
        sing.addView(singVoice, Ui.gap(a, 6));
        Button singIt = Ui.button(a, "Sing it (on Create)", true);
        sing.addView(singIt, Ui.gap(a, 8));
        singIt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String words = singWords.getText().toString().trim();
                if (words.isEmpty()) {
                    Ui.toast(a, "Write the words to sing first.");
                    return;
                }
                Intent i = new Intent(a, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                i.putExtra(MainActivity.SING_LYRICS, words);
                i.putExtra(MainActivity.SING_STYLE, singVoice.getText().toString().trim());
                a.startActivity(i);
            }
        });
        Button bring = Ui.button(a, "Bring a finished song into this project", false);
        sing.addView(bring, Ui.gap(a, 6));
        bring.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { bringSong(); }
        });
        help(sing, "sing");
    }

    LinearLayout card(LinearLayout col, String title, String about) {
        LinearLayout c = Ui.column(a);
        c.setBackground(new LuxFrame(16, 8));
        c.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, 16));
        TextView t = Ui.text(a, title, Skin.HI, 26);
        t.setTypeface(Skin.script());
        t.setShadowLayer(Ui.dp(a, 6), 0, 0, Skin.glow(160));
        c.addView(t);
        c.addView(Ui.text(a, about, Skin.DIM, 13));
        col.addView(c, Ui.gap(a, 12));
        return c;
    }
    void help(LinearLayout card, final String topic) {
        Button h = Ui.chip(a, "How to get the best from it");
        card.addView(h, Ui.gap(a, 8));
        h.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new Lux.Builder(a).setTitle("Getting the best").setMessage(advice(topic)).setPositiveButton("OK", null).show();
            }
        });
    }
    static String advice(String topic) {
        if ("groove".equals(topic))
            return "Groove is a drum model trained on 13.6 hours of real drummers (Magenta's Groove MIDI Dataset), so "
                + "it plays with a drummer's timing and dynamics, not a drum machine's.\n\n"
                + "• Pick a style: each one learned from players of that style. Funk, Soul, Hip-hop and Rock have the most "
                + "playing behind them; Jazz swings; Latin, Afrobeat and Afro-Cuban are full of syncopation.\n"
                + "• Busy makes the drummer play less or more; in the middle it plays as it learned.\n"
                + "• Feel sets how far the hits sit off the grid: on the grid for electronic music, human in the middle, "
                + "loose for a laid-back pocket.\n"
                + "• Each press plays a new groove; keep the one you like and undo the rest.\n"
                + "• It lands at the playhead's bar as notes on a Killa Drums track: open Keys to edit a hit, add Note "
                + "repeat for hat rolls or Swing for more groove, or choose another sound for the track.\n"
                + "• It plays at the project's tempo, so set the tempo first (Play Along).";
        if ("beats".equals(topic))
            return "The beat model (Stable Audio Open Small) listens to words about sound, not instructions.\n\n"
                + "• Name the genre first, then the drums, then the bass and the mood: \"boom bap hip hop drum loop, dusty "
                + "drums, punchy snare, swing\".\n"
                + "• The tempo is added for you from the project, so the loop fits your grid. Pick 1, 2 or 4 bars; up to "
                + "11 seconds is made at once.\n"
                + "• Words that help: drum loop, groove, kick, snare, hi-hats, 808, percussion, bassline, crisp, punchy, "
                + "dusty, warm, dark, wide.\n"
                + "• It cannot sing or speak, and English prompts work best.\n"
                + "• Not quite right? Another take keeps your words and tries a new seed. Fine (16 steps) is cleaner but "
                + "takes twice as long.\n"
                + "• Restyle turns the selected track's sound into the style you describe; it follows the recording's rhythm.\n"
                + "• Beats land on a new track at the playhead, repeated to fill four times the length. Slice them in the "
                + "Sampler, or tidy them with the Mixer's EQ and compressor.";
        if ("voice".equals(topic))
            return "Speak uses Kokoro, a small, natural voice model, on the phone.\n\n"
                + "• Choose a voice in the language you write: Spanish voices for Spanish, and so on. American and British "
                + "English voices read English best.\n"
                + "• Punctuation is direction: commas make short pauses, full stops longer ones, a question mark lifts the "
                + "end, and an exclamation mark adds energy.\n"
                + "• Write numbers and names the way they are said (\"twenty twenty-six\", \"Killa You-Ee\").\n"
                + "• For a hook or ad-lib, keep it short and try two or three voices; Heart, Bella and Michael are "
                + "favourites.\n"
                + "• Slower (0.8×) sounds more dramatic; faster (1.2×) suits hype intros.\n"
                + "• The voice lands on a new track at the playhead. Add reverb or a delay in the Mixer to sit it in the song.";
        return "Sing uses the song engine itself (YuE), the best singing voice the app has.\n\n"
            + "• Write the words with section tags, like [Verse] and [Chorus]; one or two short sections sing best.\n"
            + "• Describe the voice in the style: \"female vocal, soulful, R&B, warm\" or \"male rap vocal, trap, aggressive\". "
            + "Genre and mood words matter as much as the voice.\n"
            + "• Sing it opens Create with your words and voice filled in; make the song as usual.\n"
            + "• When it is done, Bring a finished song into this project puts it on a track; then open the Splitter and "
            + "split it to keep the vocals (mute or delete the other stems).\n"
            + "• Line the vocal up on the Arrange timeline, then use the Mixer to blend it.";
    }

    public String name() { return "AI"; }
    public View view() { return view; }
    public void shown() { refresh(); }
    public void hidden() {}

    void refresh() {
        boolean beats = StudioModels.available("beats"), beatsHere = beats && AiJobs.installed(a, "beats");
        beatState.setText(!beats ? "Beats as audio arrive in an update; Groove above makes drum beats now."
                          : beatsHere ? "Ready." : "First use downloads the beat model (" + VaultScreen.size(StudioModels.bytes("beats")) + ").");
        voiceState.setText(AiJobs.installed(a, "voice") ? "Ready."
                           : "First use downloads the voice (" + VaultScreen.size(StudioModels.bytes("voice")) + ").");
    }

    public void frame(NativeEngine.Status s) {
        AiJob j = watchedJob();
        if (j == null) {
            if (AiJobs.busy())
                status.setText("An AI job is running.");
            return;  // otherwise the last message stays
        }
        String state = j.state();
        if ("running".equals(state) || "waiting".equals(state))
            status.setText(("beats".equals(j.kind()) ? "Making the beat: " : "Speaking: ") + Math.round(j.progress() * 100) + "%");
        else if ("done".equals(state)) {
            watching = null;
            AiJobs.finished(a);
            bringIn(j);
        } else {
            watching = null;
            AiJobs.finished(a);
            status.setText("It stopped: " + j.error());
        }
    }
    AiJob watchedJob() {
        if (watching == null)
            return null;
        return AiJob.open(new File(watching));
    }

    // ------------------------------------------------------------------ jobs

    /** A groove from the drum model, as Killa Drums notes at the playhead's bar. */
    void makeGroove() {
        final Project p = a.session.project();
        if (p == null)
            return;
        if (p.tracks.size() >= 64) {
            Ui.toast(a, "The project already has 64 tracks.");
            return;
        }
        final int style = grooveStyle, length = grooveLength;
        final float b = busy, f = feel;
        NativeEngine.Status s = a.session.status();
        TempoMap map = new TempoMap(p.rate, p.performance);
        double perBar = p.performance.beats * 4.0 / p.performance.beatUnit;
        final double at = s == null ? 0 : Math.floor(map.beatAt(s.position) / perBar + 1e-9) * perBar;
        status.setText("The drummer is playing…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                java.util.List<ai.jackknife.killayue.studio.MidiNotes.Note> notes = null;
                try {
                    synchronized (AiScreen.class) {
                        if (grooveModel == null) {
                            java.io.InputStream in = a.getAssets().open("studio/killa-groove-v2.bin");
                            try {
                                grooveModel = ai.jackknife.killayue.studio.GrooveModel.read(new java.io.BufferedInputStream(in));
                            } finally {
                                in.close();
                            }
                        }
                    }
                    notes = grooveModel.play(style, length, b, f, System.nanoTime());
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String why = err;
                final java.util.List<ai.jackknife.killayue.studio.MidiNotes.Note> played = notes;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (why != null || a.session.project() != p) {
                            status.setText(why == null ? "" : "The groove did not play: " + why);
                            return;
                        }
                        Project q = p.copy();
                        Track t = q.addTrack("Groove · " + ai.jackknife.killayue.studio.GrooveModel.STYLE_NAMES[style]);
                        t.role = "instrument:7";
                        t.notes.instrument = 7;
                        for (ai.jackknife.killayue.studio.MidiNotes.Note n : played) {
                            if (t.notes.notes.size() >= ai.jackknife.killayue.studio.MidiNotes.MAX)
                                break;
                            ai.jackknife.killayue.studio.MidiNotes.Note c = t.notes.add(at + n.beat, n.key);
                            c.length = n.length;
                            c.velocity = n.velocity;
                        }
                        try {
                            q.validate();
                            a.session.run(ClipEdits.change(p, q, "AI groove"));
                            status.setText(played.size() + " hits of " + ai.jackknife.killayue.studio.GrooveModel.STYLE_NAMES[style]
                                + " on a new Killa Drums track. Press ▶ to hear it; open Keys to shape it.");
                        } catch (Exception e) {
                            status.setText(e.getMessage());
                        }
                    }
                });
            }
        }, "studio-groove").start();
    }

    void makeBeat(boolean again) {
        final Project p = a.session.project();
        if (p == null)
            return;
        if (!StudioModels.available("beats")) {
            new Lux.Builder(a).setTitle("Beat Lab").setMessage("Beats as audio arrive in an update. For beats now, Groove "
                + "above plays a real-feel drum groove in 18 styles, ready to edit in Keys.").setPositiveButton("OK", null).show();
            return;
        }
        String words = beatPrompt.getText().toString().trim();
        if (words.isEmpty()) {
            Ui.toast(a, "Describe the beat, or tap a genre.");
            return;
        }
        double bpm = p.performance.tempo, beatsPerBar = p.performance.beats * 4.0 / p.performance.beatUnit;
        int b = bars;
        while (b > 1 && b * beatsPerBar * 60 / bpm > 11) b /= 2;
        final double seconds = Math.min(11, b * beatsPerBar * 60 / bpm);
        final String prompt = words + ", " + Math.round(bpm) + " BPM";
        lastSeed = again || lastSeed == 0 ? new Random().nextInt(1 << 30) : lastSeed;
        final int steps = quality == 0 ? 8 : 16;
        File input = null;
        if (restyle) {
            Track t = selectedAudio(p);
            if (t == null) {
                Ui.toast(a, "Select an audio track on Arrange to restyle.");
                return;
            }
            input = new File(a.session.dir(), t.clips.get(0).file);
        }
        final File in = input;
        ensureThen("beats", new Runnable() {
            public void run() {
                try {
                    startJob(AiJobs.beat(a, a.session.dir(), prompt, seconds, lastSeed, steps, in, .7));
                } catch (Exception e) {
                    Ui.toast(a, e.getMessage());
                }
            }
        });
    }
    Track selectedAudio(Project p) {
        for (Track t : p.tracks)
            if (!t.clips.isEmpty() && t.notes.instrument < 0)
                return t;
        return null;
    }
    void speak() {
        if (a.session.project() == null)
            return;
        final String text = speakText.getText().toString().trim();
        if (text.isEmpty()) {
            Ui.toast(a, "Write what the voice should say.");
            return;
        }
        ensureThen("voice", new Runnable() {
            public void run() {
                try {
                    startJob(AiJobs.speak(a, a.session.dir(), text, voice, lang(VOICES[voice]), speed));
                } catch (Exception e) {
                    Ui.toast(a, e.getMessage());
                }
            }
        });
    }
    void startJob(AiJob j) {
        String why = AiJobs.start(a, j);
        if (why != null) {
            Ui.toast(a, why);
            return;
        }
        watching = j.dir().getAbsolutePath();
        status.setText("Starting…");
    }

    /** Downloads a feature's model the first time, with progress, then runs next. */
    void ensureThen(final String feature, final Runnable next) {
        if (AiJobs.installed(a, feature)) {
            next.run();
            return;
        }
        final AtomicBoolean cancel = new AtomicBoolean();
        final TextView msg = Ui.text(a, "Downloading…", Skin.TEXT, 15);
        msg.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8));
        final AlertDialog d = new Lux.Builder(a).setTitle("beats".equals(feature) ? "The beat model" : "The voice")
                                  .setView(msg).setCancelable(false)
                                  .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                      public void onClick(DialogInterface di, int w) { cancel.set(true); }
                                  }).show();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    AiJobs.download(a, feature, new AiJobs.Progress() {
                        public void at(final long done, final long total) {
                            a.runOnUiThread(new Runnable() {
                                public void run() {
                                    msg.setText("Downloading: " + VaultScreen.size(done) + " of " + VaultScreen.size(total));
                                }
                            });
                        }
                    }, cancel);
                } catch (Exception e) {
                    err = cancel.get() ? null : e.getMessage();
                }
                final String why = err;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        d.dismiss();
                        refresh();
                        if (why != null)
                            new Lux.Builder(a).setTitle("Could not download it").setMessage(why).setPositiveButton("OK", null).show();
                        else if (!cancel.get())
                            next.run();
                    }
                });
            }
        }, "studio-ai-download").start();
    }

    /** A finished beat or voice: into the project's audio at its rate, on a new track at the playhead. */
    void bringIn(final AiJob j) {
        final Project p = a.session.project();
        final File dir = a.session.dir();
        if (p == null || dir == null)
            return;
        final File made = new File((String) j.get("out"));
        final boolean beat = "beats".equals(j.kind());
        NativeEngine.Status s = a.session.status();
        final long at = s == null ? 0 : beat ? new TempoMap(p.rate, p.performance).snap(s.position, 1) : s.position;
        status.setText("Adding it to the project…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                String rel = "audio/" + (beat ? "beat-" : "voice-") + j.dir().getName() + ".wav";
                long frames = 0;
                try {
                    frames = ImportJob.run(a, made, new File(dir, rel), p.rate, new ImportJob.Progress() {
                        public void at(float f) {}
                    }, new AtomicBoolean());
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String why = err, file = rel;
                final long length = frames;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (why != null) {
                            status.setText("Could not add it: " + why);
                            return;
                        }
                        Project now = a.session.project();
                        if (now != p)
                            return;
                        Project q = p.copy();
                        Track t = q.addTrack(beat ? "Beat · " + j.subject() : "Voice · " + j.subject());
                        t.role = beat ? "ai:beat" : "ai:voice";
                        int repeats = beat ? 4 : 1;
                        // a beat's exact length in frames at the project's tempo, so the repeats stay on the grid
                        long loop = length;
                        if (beat) {
                            long want = Math.round(((Number) j.get("seconds")).doubleValue() * q.rate);
                            loop = Math.max(1, Math.min(length, want));
                        }
                        for (int r = 0; r < repeats; r++) q.addClip(t, file, at + r * loop, 0, loop);
                        try {
                            q.validate();
                            a.session.run(ClipEdits.change(p, q, beat ? "AI beat" : "AI voice"));
                            status.setText(beat ? "The beat is on a new track at the playhead, looped four times."
                                                : "The voice is on a new track at the playhead.");
                        } catch (Exception e) {
                            status.setText("Could not add it: " + e.getMessage());
                        }
                    }
                });
            }
        }, "studio-ai-bring").start();
    }

    /** A library song as a new track in this project (then the Splitter takes its vocals). */
    void bringSong() {
        File[] metas = Store.library(a).listFiles();
        final java.util.ArrayList<File> audio = new java.util.ArrayList<File>();
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        if (metas != null) {
            java.util.Arrays.sort(metas, new java.util.Comparator<File>() {
                public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
            });
            for (File m : metas) {
                if (!m.getName().endsWith(".json"))
                    continue;
                try {
                    org.json.JSONObject j = new org.json.JSONObject(new String(Store.read(m), "UTF-8"));
                    if (j.optString("_audio").length() == 0)
                        continue;
                    audio.add(new File(Store.library(a), j.optString("_audio")));
                    names.add(j.optString("_title", m.getName()));
                } catch (Exception e) {
                    // not a song
                }
            }
        }
        if (audio.isEmpty()) {
            Ui.toast(a, "There are no songs in your library yet.");
            return;
        }
        final java.util.ArrayList<String> titles = names;
        new Lux.Builder(a).setTitle("Which song?").setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, final int w) {
                final Project p = a.session.project();
                final File dir = a.session.dir();
                if (p == null)
                    return;
                status.setText("Bringing in “" + titles.get(w) + "”…");
                new Thread(new Runnable() {
                    public void run() {
                        String err = null;
                        final String rel = "audio/song-" + System.currentTimeMillis() + ".wav";
                        long frames = 0;
                        try {
                            frames = ImportJob.run(a, audio.get(w), new File(dir, rel), p.rate, new ImportJob.Progress() {
                                public void at(float f) {}
                            }, new AtomicBoolean());
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                        final String why = err;
                        final long length = frames;
                        a.runOnUiThread(new Runnable() {
                            public void run() {
                                if (why != null || a.session.project() != p) {
                                    status.setText(why == null ? "" : "Could not bring it in: " + why);
                                    return;
                                }
                                Project q = p.copy();
                                Track t = q.addTrack(titles.get(w));
                                t.role = "song";
                                q.addClip(t, rel, 0, 0, length);
                                try {
                                    q.validate();
                                    a.session.run(ClipEdits.change(p, q, "Bring in a song"));
                                    status.setText("“" + titles.get(w) + "” is on a new track. Open the Splitter to keep its vocals.");
                                } catch (Exception e) {
                                    status.setText(e.getMessage());
                                }
                            }
                        });
                    }
                }, "studio-bring-song").start();
            }
        }).show();
    }
}
