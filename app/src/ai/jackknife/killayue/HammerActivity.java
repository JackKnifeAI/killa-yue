package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit sharing, on-device catalog search and peer playback. No account or bootstrap service. */
public final class HammerActivity extends Activity {
    private LinearLayout body, results, playerPanel;
    private TextView status;
    private EditText query;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Hammer.Job job;
    private boolean busy, closed;
    private boolean settingsMode, discoveryPending;
    private String artistFilter;
    private final Map<String, ProfileHit> profiles = new LinkedHashMap<String, ProfileHit>();
    private final Map<String, HammerStore.Peer> discovered =
        new java.util.concurrent.ConcurrentHashMap<String, HammerStore.Peer>();
    private HammerNearby nearby;
    private static final class ProfileHit {
        final HammerStore.Peer peer;
        final HammerProfile profile;
        ProfileHit(HammerStore.Peer p, HammerProfile a) {
            peer = p;
            profile = a;
        }
    }
    private int streamMode = HammerBuffer.AUTO;
    private HammerStore store;
    private final List<Hit> hits = new ArrayList<Hit>();
    private static final class Hit {
        final HammerStore.Peer peer;
        final Hammer.Song song;
        Hit(HammerStore.Peer p, Hammer.Song s) {
            peer = p;
            song = s;
        }
    }
    private static final class AlbumHit {
        final HammerStore.Peer peer;
        final HammerAlbum album;
        AlbumHit(HammerStore.Peer p, HammerAlbum a) {
            peer = p;
            album = a;
        }
    }
    /** Each peer's albums and playlists, kept like its songs. */
    private static final Map<String, List<AlbumHit>> albumsByPeer =
        new java.util.concurrent.ConcurrentHashMap<String, List<AlbumHit>>();
    private AlbumHit albumFilter;  // one album open, its songs in order
    private int chart;             // 0 all, 1 top voted, 2 most played, 3 most downloaded
    /** Plays and downloads each song's host has served, by song and audience. */
    private static final Map<String, long[]> served = new java.util.concurrent.ConcurrentHashMap<String, long[]>();
    private volatile List<HammerLyrics.Line> lyricLines;  // the playing song's, when it has timed lyrics
    private volatile HammerLyrics playingLyrics;
    private interface Work {
        void run() throws Exception;
    }
    static final int ALL = 0, HOSTING = 1, CIRCLES = 2, PEERS = 3, TRANSFERS = 4, COMPUTER = 5;
    static void open(Context c) { c.startActivity(new Intent(c, HammerActivity.class)); }
    static void settings(Context c) { settings(c, ALL); }
    /** Sharing settings, grouped; section is the group to scroll to. The Settings tab opens these. */
    static void settings(Context c, int section) {
        c.startActivity(new Intent(c, HammerActivity.class).putExtra("settings", true).putExtra("section", section));
    }
    static void publish(Context c, org.json.JSONObject song) {
        c.startActivity(new Intent(c, HammerActivity.class).putExtra("publish", song.toString()));
    }
    private ScrollView scroll;
    private Button hostToggle;
    private ImageView ownPortrait;
    private TextView ownName;
    private Hit playing, bound;
    // the Discover player: one tap to vote or keep a song, and the list plays on like a radio
    private DiscoverDeck deck;
    private final List<Hit> shown = new ArrayList<Hit>();
    private String advancedFor;
    private boolean userStopped;
    /** Up, down and this phone's own vote for each song, kept for the life of the app. */
    private static final Map<String, int[]> tallies = new java.util.concurrent.ConcurrentHashMap<String, int[]>();
    private static final Map<String, Long> counted = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private final ExecutorService voting = Executors.newFixedThreadPool(2);
    // covers and portraits, fetched one at a time so a peer's admission limit is never hit
    private final ExecutorService art = Executors.newSingleThreadExecutor();
    private final Map<String, android.graphics.Bitmap> pictures = new java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>();
    private final Set<String> asked = java.util.Collections.synchronizedSet(new HashSet<String>());
    private final Map<String, List<ImageView>> waiting = new HashMap<String, List<ImageView>>();
    @Override
    public void onCreate(Bundle state) {
        setTheme(Skin.themeFor(this, Skin.id(this)));
        super.onCreate(state);
        Skin.load(this);
        LinearLayout root = Ui.column(this);
        root.setBackground(Skin.background(this));
        root.setFitsSystemWindows(true);
        scroll = new ScrollView(this);
        body = Ui.column(this);
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        settingsMode = getIntent().getBooleanExtra("settings", false);
        artistFilter = getIntent().getStringExtra("artist");
        if (settingsMode)
            buildSettings(getIntent().getIntExtra("section", ALL));
        else
            buildDiscover(root);
        setContentView(root);
        work("Opening HAMMER", new Work() {
            public void run() throws Exception {
                store = HammerAndroid.store(HammerActivity.this);
                HammerAndroid.identity();
                if (!settingsMode) {
                    ui.post(new Runnable() {
                        public void run() {
                            if (closed)
                                return;
                            collect();  // artists found earlier in this session show straight away
                            render();
                        }
                    });
                    loadOwn();
                    try {
                        HammerAndroid.network(HammerActivity.this);
                        KillaNet.lookup(); // ask the world for public hosts now
                    } catch (Exception e) {
                        EngineService.addLog("[Network] " + e.getMessage());
                    }
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        String browse = getIntent().getStringExtra("browse_peer");
                        if (browse != null)
                            try {
                                Hammer.Card card = Hammer.Card.parse(browse);
                                discovered.put(HammerStore.peerId(card),
                                               new HammerStore.Peer(card, "Artist", false));
                            } catch (Exception ignored) {
                            }
                        String incoming = getIntent().getStringExtra("invite");
                        if (incoming != null)
                            addPeer(incoming);
                        String publish = getIntent().getStringExtra("publish");
                        if (publish != null)
                            publishDialog(publish);
                        if (!settingsMode) {
                            nearby = new HammerNearby(HammerActivity.this, new HammerNearby.Found() {
                                public void peer(final Hammer.Card card) {
                                    ui.post(new Runnable() {
                                        public void run() {
                                            if (closed || discovered.size() >= 64)
                                                return;
                                            try {
                                                route(card);
                                                if (discovered.containsKey(HammerStore.peerId(card)))
                                                    return; // already known; this Wi-Fi route is kept beside it
                                                discovered.put(
                                                    HammerStore.peerId(card),
                                                    new HammerStore.Peer(card, "Nearby artist", false));
                                                discoveryPending = true;
                                                if (!busy)
                                                    refresh();
                                            } catch (Exception ignored) {
                                            }
                                        }
                                    });
                                }
                            });
                            nearby.start();
                            getIntent().putExtra("refresh", true);
                        }
                    }
                });
            }
        });
        ui.post(tick);
    }
    /** Discover: your own artist page on top, then artists as medallions, then songs as cards; the player sits below. */
    private void buildDiscover(LinearLayout root) {
        LinearLayout top = Ui.row(this);
        top.addView(Ui.title(this, artistFilter == null ? "Discover" : "Artist"), Ui.weight());
        Button again = Ui.chip(this, "↻ Refresh");
        top.addView(again);
        again.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (store != null)
                    refresh();
            }
        });
        body.addView(top);
        TextView intro = Ui.text(this, "Songs and artists shared on the Killa YuE network, around the world. Tap a song to hear it.",
                                 Skin.DIM, 13);
        intro.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10));
        body.addView(intro);
        // your page, as the other artists see it
        LinearLayout mine = Ui.row(this);
        mine.setBackground(new LuxFrame(16, 8));
        int in = Ui.dp(this, 14);
        mine.setPadding(in, in, in, in);
        ownPortrait = new ImageView(this);
        ownPortrait.setImageBitmap(Ui.medallion(this, null, "", 56));
        mine.addView(ownPortrait, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        LinearLayout words = Ui.column(this);
        words.setPadding(Ui.dp(this, 14), 0, 0, 0);
        ownName = Ui.text(this, "Your artist page", Skin.ACCENT, 17);
        ownName.setTypeface(Skin.text(), android.graphics.Typeface.BOLD);
        words.addView(ownName);
        words.addView(Ui.text(this, "Portrait, name and bio · share songs from the Library", Skin.DIM, 12));
        mine.addView(words, Ui.weight());
        mine.addView(Ui.text(this, "›", Skin.ACCENT, 28));
        mine.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(HammerActivity.this, HammerArtistActivity.class));
            }
        });
        if (artistFilter == null)
            body.addView(mine, Ui.gap(this, 2));
        query = Ui.edit(this, "Search artists, songs and styles", 1);
        query.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { render(); }
        });
        body.addView(query, Ui.gap(this, 14));
        status = Ui.text(this, "Opening…", Skin.DIM, 12);
        status.setPadding(0, Ui.dp(this, 6), 0, 0);
        body.addView(status);
        results = Ui.column(this);
        body.addView(results);
        // now playing: the gold deck under the list, with the song's votes and a download
        playerPanel = Ui.column(this);
        playerPanel.setBackground(new LuxFrame(16, 8));
        playerPanel.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 4));
        deck = new DiscoverDeck(this, new DiscoverDeck.Listener() {
            public void press(int b) {
                if (b == DiscoverDeck.UP)
                    tapVote(1);
                else if (b == DiscoverDeck.DOWN)
                    tapVote(-1);
                else if (b == DiscoverDeck.PREV)
                    skip(-1);
                else if (b == DiscoverDeck.NEXT)
                    skip(1);
                else if (b == DiscoverDeck.PLAY) {
                    if (HammerService.playback.startsWith("Finished") && playing != null)
                        stream(playing, streamMode);  // play the finished song again
                    else
                        HammerService.pause();
                } else if (b == DiscoverDeck.KEEP && playing != null) {
                    if (playing.song.reshare)
                        download(playing);
                    else
                        Ui.toast(HammerActivity.this, "The artist shares this song for streaming only");
                } else if (b == DiscoverDeck.MORE && playing != null)
                    song(playing);
            }
            public void seek(int ms) { HammerService.seek(ms); }
        });
        playerPanel.addView(deck, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.leftMargin = plp.rightMargin = Ui.dp(this, 8);
        plp.bottomMargin = Ui.dp(this, 8);
        root.addView(playerPanel, plp);
    }
    /** Sharing settings in five groups, the same five the Settings tab names. */
    private void buildSettings(final int section) {
        body.addView(Ui.title(this, "Sharing and hosting"));
        label("Your Library decides which songs are shared: open a song's sharing line there. Everything else is here.");
        status = Ui.text(this, "Opening local storage…", Skin.DIM, 13);
        body.addView(status);
        final View hosting = group("Hosting", "Serve the songs you share to people who follow you or find you nearby.");
        hostToggle = Ui.button(this, "Start hosting", true);
        body.addView(hostToggle, Ui.gap(this, 6));
        hostToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (HammerService.hosting())
                    HammerService.stopHost(HammerActivity.this);
                else
                    HammerService.startHost(HammerActivity.this);
            }
        });
        button(body, "My hosted songs", new Runnable() {
            public void run() { hosted(); }
        });
        final CheckBox spread = new CheckBox(this);
        spread.setText("Keep songs I download alive: host them too, with the artist's signature");
        spread.setTextColor(Skin.TEXT);
        spread.setChecked(getSharedPreferences("hammer", MODE_PRIVATE).getBoolean("spread", true));
        spread.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                getSharedPreferences("hammer", MODE_PRIVATE).edit().putBoolean("spread", on).apply();
            }
        });
        body.addView(spread);
        final View circles = group("Circles and invitations", "A public card lets anyone follow you. A private circle invitation opens your private songs to the people you send it to.");
        button(body, "Public peer card", new Runnable() {
            public void run() { invite(false); }
        });
        button(body, "Private circle invitation", new Runnable() {
            public void run() { invite(true); }
        });
        button(body, "Revoke private invitations", new Runnable() {
            public void run() {
                new Lux.Builder(HammerActivity.this)
                    .setTitle("Replace the circle invitation?")
                    .setMessage("All current private invitations stop working. Copies people already "
                                + "downloaded remain theirs.")
                    .setPositiveButton("Revoke",
                                       new DialogInterface.OnClickListener() {
                                           public void onClick(DialogInterface d, int w) {
                                               work("Revoking invitations", new Work() {
                                                   public void run() throws Exception {
                                                       store.revoke();
                                                       HammerService.disconnect();
                                                   }
                                               });
                                           }
                                       })
                    .setNegativeButton("Cancel", null)
                    .show();
            }
        });
        final View peers = group("Peers and artists", "People you follow, and the artists who tell you about their new songs.");
        button(body, "Add a peer or invitation", new Runnable() {
            public void run() { addPeer(""); }
        });
        button(body, "Followed peers", new Runnable() {
            public void run() { peers(); }
        });
        button(body, "Followed artists", new Runnable() {
            public void run() { artists(); }
        });
        button(body, "Live Follow on / off", new Runnable() {
            public void run() { liveFollow(); }
        });
        final View transfers = group("Downloads", "Verified copies you downloaded, ready to save to your Library or host again.");
        button(body, "Downloaded songs", new Runnable() {
            public void run() { downloads(); }
        });
        button(body, "Cancel current transfer", new Runnable() {
            public void run() {
                if (job != null)
                    job.cancel();
            }
        });
        final View computer = group("Computer", "A small server for a computer that hosts your songs while the phone sleeps.");
        button(body, "Save desktop companion", new Runnable() {
            public void run() {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                               .setType("application/zip")
                               .addCategory(Intent.CATEGORY_OPENABLE)
                               .putExtra(Intent.EXTRA_TITLE, "hammer-server.zip");
                startActivityForResult(i, 840);
            }
        });
        group("Community headcount", "An optional monthly count of participating installations.");
        button(body, "Community headcount settings", new Runnable() {
            public void run() { startActivity(new Intent(HammerActivity.this, CommunityCountActivity.class)); }
        });
        group("Privacy", null);
        TextView fine = Ui.text(this,
            "Streams are encrypted, but encryption does not hide addresses: people who connect to you can see "
            + "your internet address, and you see theirs. Shared songs leave without hidden data: only what "
            + "describes the music, like title, your artist name, album, lyrics and cover, stays in the file. Titles, tags and "
            + "profiles you publish are public. Search words stay here.", Skin.DIM, 12);
        body.addView(fine);
        if (section != ALL)
            scroll.post(new Runnable() {
                public void run() {
                    View[] at = {null, hosting, circles, peers, transfers, computer};
                    if (section < at.length && at[section] != null)
                        scroll.smoothScrollTo(0, at[section].getTop());
                }
            });
    }
    /** A group heading in the settings, with one line on what the group is for. Returns the heading. */
    private View group(String name, String what) {
        TextView h = Ui.label(this, name.toUpperCase());
        h.setTextColor(Skin.ACCENT);
        h.setLetterSpacing(0.12f);
        h.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 2));
        body.addView(h);
        if (what != null) {
            TextView t = Ui.text(this, what, Skin.DIM, 12);
            t.setPadding(0, 0, 0, Ui.dp(this, 4));
            body.addView(t);
        }
        return h;
    }
    private TextView label(String text) {
        TextView v = Ui.text(this, text, Skin.TEXT, 14);
        v.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        body.addView(v);
        return v;
    }
    private void button(LinearLayout parent, String title, final Runnable action) {
        Button b = Ui.button(this, title, false);
        parent.addView(b, Ui.gap(this, 6));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (store == null) {
                    Ui.toast(HammerActivity.this, "HAMMER is still opening");
                    return;
                }
                action.run();
            }
        });
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (closed)
                return;
            if (hostToggle != null)
                hostToggle.setText(HammerService.hosting() ? "Stop hosting" : "Start hosting");
            if (!settingsMode && ++ticks % 6 == 0)
                networkPeers();
            // no configuration: keep asking the world for hosts, and re-check everyone now and then
            if (!settingsMode && ticks % 60 == 0)
                try {
                    KillaNet.lookup();
                } catch (Exception ignored) {
                }
            if (!settingsMode && ticks % 120 == 0 && !busy && store != null)
                refresh();
            if (playerPanel != null) {
                boolean stopped = "Stopped".equals(HammerService.playback);
                playerPanel.setVisibility(stopped ? View.GONE : View.VISIBLE);
                if (!stopped) {
                    if (playing != bound)
                        bindPlayer();
                    deck.title = playing == null ? "Now playing" : playing.song.title;
                    String state = HammerService.playback;
                    deck.line = busy && job != null && job.total > 0
                                    ? "Downloading " + (job.done * 100 / job.total) + "%"
                                    : playing == null ? state
                                    : state.startsWith("Playing")
                                        ? (sungNow(HammerService.position()) != null ? "♪ " + sungNow(HammerService.position())
                                                                                     : artistName(playing.song.key))
                                    : artistName(playing.song.key) + " · " + state.replaceFirst(":.*", "");
                    deck.playing = state.startsWith("Playing");
                    deck.position = HammerService.position();
                    deck.duration = HammerService.duration();
                    if (playing != null && deck.cover == null)
                        deck.cover = pictures.get(coverKey(playing));
                    deck.invalidate();
                }
                // like a radio: when a song ends by itself, the next one in Discover plays
                if (playing != null && HammerService.playback.startsWith("Finished") && !userStopped &&
                    !playing.song.id.equals(advancedFor)) {
                    advancedFor = playing.song.id;
                    skip(1);
                }
            }
            if (busy && job != null && job.total > 0)
                status.setText("Downloading " + (job.done * 100 / job.total) +
                               "% — Cancel keeps the partial copy");
            ui.postDelayed(this, 500);
        }
    };
    private int ticks;
    /**
     * Killa YuE peers the network node found, on this Wi-Fi or in the worldwide public host list, join Discover as
     * PeerId cards, replacing address cards.
     */
    private void networkPeers() {
        boolean added = false;
        for (KillaNet.Peer p : KillaNet.peers()) {
            if (discovered.size() >= 64)
                break;
            try {
                Hammer.Card card = Hammer.Card.p2p(p.id, p.pin, "");
                String key = HammerStore.peerId(card);
                if (!(p.local || p.host))
                    continue;
                route(card);  // a second way to the same artist, kept beside the direct one
                if (!discovered.containsKey(key)) {
                    discovered.put(key, new HammerStore.Peer(card, p.local ? "Nearby artist" : "Network artist", false));
                    added = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (added) {
            discoveryPending = true;
            if (!busy)
                refresh();
        }
    }
    /** Your published profile, for the card on top of Discover. Runs on the worker. */
    private void loadOwn() {
        try {
            final HammerProfile own = store.publicObjects.own();
            if (own == null)
                return;
            android.graphics.Bitmap face = null;
            if (!own.image.isEmpty())
                try {
                    face = picture(store.publicObjects.localImage(own.image));
                } catch (Exception optional) {
                }
            final android.graphics.Bitmap f = face;
            ui.post(new Runnable() {
                public void run() {
                    if (closed)
                        return;
                    ownName.setText(own.name.isEmpty() ? "Your artist page" : own.name);
                    ownPortrait.setImageBitmap(Ui.medallion(HammerActivity.this, f, own.name, 56));
                }
            });
        } catch (Exception none) {
        }
    }
    /** The bitmap of artwork bytes, decoded by the isolated codec, never in this process. */
    private android.graphics.Bitmap picture(byte[] bytes) throws Exception {
        File f = File.createTempFile("discover-", ".image", getCacheDir());
        try {
            HammerStore.atomic(f, bytes);
            return ((HammerCodec)HammerMedia.codec).imagePreview(f).bitmap;
        } finally {
            f.delete();
        }
    }
    /** Fetches a cover or portrait once, in the background, then shows it in the view if it is still on screen. */
    private void fetch(final String key, final ImageView into, final int sizeDp, final String name, final Fetch how) {
        android.graphics.Bitmap have = pictures.get(key);
        if (have != null) {
            into.setImageBitmap(sizeDp > 0 ? Ui.medallion(this, have, name, sizeDp) : have);
            return;
        }
        into.setTag(key);
        // Discover redraws its list as artists answer: every view waiting for this picture gets it when it
        // arrives, not just the one that asked first
        List<ImageView> list = waiting.get(key);
        if (list == null)
            waiting.put(key, list = new ArrayList<ImageView>());
        list.add(into);
        if (!asked.add(key) || asked.size() > 300)
            return;
        art.execute(new Runnable() {
            public void run() {
                try {
                    if (closed)
                        return;
                    byte[] bytes = how.get(new Hammer.Job());
                    if (bytes == null)
                        return;
                    final android.graphics.Bitmap b = picture(bytes);
                    pictures.put(key, b);
                    ui.post(new Runnable() {
                        public void run() {
                            List<ImageView> views = waiting.remove(key);
                            if (closed || views == null)
                                return;
                            for (ImageView v : views)
                                if (key.equals(v.getTag()))
                                    v.setImageBitmap(sizeDp > 0 ? Ui.medallion(HammerActivity.this, b, name, sizeDp) : b);
                        }
                    });
                } catch (Exception noArt) {
                    asked.remove(key);  // try again on a later redraw
                }
            }
        });
    }
    private interface Fetch {
        byte[] get(Hammer.Job job) throws Exception;
    }
    private String coverKey(Hit hit) { return "s:" + HammerStore.peerId(hit.peer.card) + hit.song.id; }
    private static String audience(Hit hit) {
        try {
            return hit.peer.card.isPrivate() ? Hammer.hash(hit.peer.card.token.getBytes("US-ASCII")) : "public";
        } catch (Exception e) {
            return "public";
        }
    }
    private static String tallyKey(Hit hit) { return hit.song.id + "/" + audience(hit); }
    /** Shows the playing song in the deck: its cover, and its votes with this phone's own lit up. */
    private void bindPlayer() {
        bound = playing;
        deck.cover = playing == null ? null : pictures.get(coverKey(playing));
        deck.votable = playing != null;
        deck.keepable = playing != null && playing.song.reshare;
        deck.up = deck.down = -1;
        deck.mine = 0;
        if (playing == null)
            return;
        showVotes();
        tally(playing, null);
        lyricLines = null;
        playingLyrics = null;
        final Hit hit = playing;
        voting.execute(new Runnable() {
            public void run() {
                try {
                    HammerLyrics l = Hammer.lyrics(hit.peer.card, hit.song, new Hammer.Job());
                    if (l != null && hit == playing) {
                        playingLyrics = l;
                        List<HammerLyrics.Line> lines = l.lines();
                        lyricLines = !lines.isEmpty() && lines.get(0).ms >= 0 ? lines : null;
                    }
                } catch (Exception noLyricsOrOlderHost) {
                }
            }
        });
    }
    /** The line being sung now, or null between lines and for untimed lyrics. */
    private String sungNow(int ms) {
        List<HammerLyrics.Line> lines = lyricLines;
        if (lines == null)
            return null;
        String now = null;
        for (HammerLyrics.Line l : lines)
            if (l.ms <= ms + 150)
                now = l.words;
            else
                break;
        return now;
    }
    private void showVotes() {
        if (playing == null || deck == null)
            return;
        int[] t = tallies.get(tallyKey(playing));
        deck.up = t == null ? -1 : t[0];
        deck.down = t == null ? -1 : t[1];
        deck.mine = t == null ? 0 : t[2];
        deck.invalidate();
    }
    /** Plays the song before or after this one in Discover, as listed now. */
    private void skip(int by) {
        if (shown.isEmpty())
            return;
        int at = -1;
        for (int i = 0; playing != null && i < shown.size(); i++)
            if (shown.get(i).song.id.equals(playing.song.id))
                at = i;
        int next = at + by;
        if (next < 0 || next >= shown.size()) {
            if (by > 0 && at >= 0)
                Ui.toast(this, "That was the last song in Discover");
            return;
        }
        stream(shown.get(next), streamMode);
    }
    /**
     * Counts a song's signed votes in the background (never blocking a download), and remembers whether this phone
     * voted. {@code line} is a song card's vote line, or null for the player.
     */
    private void tally(final Hit hit, final TextView line) {
        counted.put(tallyKey(hit), System.currentTimeMillis());
        voting.execute(new Runnable() {
            public void run() {
                try {
                    if (closed)
                        return;
                    List<Hammer.Vote> list = Hammer.votes(hit.peer.card, hit.song.id, new Hammer.Job());
                    int up = 0, down = 0;
                    for (Hammer.Vote v : list) {
                        if (v.value == 1)
                            up++;
                        if (v.value == -1)
                            down++;
                    }
                    int mine = (int)getSharedPreferences("hammer-votes", 0).getLong("mine." + tallyKey(hit), 0);
                    tallies.put(tallyKey(hit), new int[] {up, down, mine});
                    try {
                        long[] n = Hammer.stats(hit.peer.card, hit.song.id, new Hammer.Job());
                        if (n != null)
                            served.put(tallyKey(hit), n);
                    } catch (Exception olderHost) {
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            if (closed)
                                return;
                            if (line != null && hit.song.id.equals(line.getTag()))
                                voteLine(line, hit);
                            if (playing != null && tallyKey(playing).equals(tallyKey(hit)))
                                showVotes();
                        }
                    });
                } catch (Exception olderHostOrOffline) {
                }
            }
        });
    }
    private void voteLine(TextView line, Hit hit) {
        int[] t = tallies.get(tallyKey(hit));
        long[] n = served.get(tallyKey(hit));
        line.setText(t == null ? "" : "▲ " + t[0] + "   ▼ " + t[1] +
                                      (n == null ? "" : "   ▶ " + n[0] + "   ⬇ " + n[1]));
        line.setVisibility(t == null ? View.GONE : View.VISIBLE);
    }
    /** One tap: vote, or tap the lit one again to take the vote back. Shows at once; signs and sends behind. */
    private void tapVote(int value) {
        final Hit hit = playing;
        if (hit == null)
            return;
        int[] t = tallies.get(tallyKey(hit));
        int up = t == null ? 0 : t[0], down = t == null ? 0 : t[1], mine = t == null ? 0 : t[2];
        final int now = mine == value ? 0 : value;
        if (mine == 1) up--;
        if (mine == -1) down--;
        if (now == 1) up++;
        if (now == -1) down++;
        tallies.put(tallyKey(hit), new int[] {Math.max(0, up), Math.max(0, down), now});
        showVotes();
        voting.execute(new Runnable() {
            public void run() {
                try {
                    sendVote(hit, now, Hammer.votes(hit.peer.card, hit.song.id, new Hammer.Job()), new Hammer.Job());
                    tally(hit, null);  // the host's count, now including this vote
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            if (closed)
                                return;
                            Ui.toast(HammerActivity.this, "Vote not sent: the artist is out of reach right now");
                            tally(hit, null);
                        }
                    });
                }
            }
        });
    }
    /** Signs a vote with this song's own key (no profile name) and sends it to every host of the song. */
    private void sendVote(Hit hit, int value, List<Hammer.Vote> list, Hammer.Job job) throws Exception {
        Hammer.Vote v = new Hammer.Vote();
        v.song = hit.song.id;
        v.audience = audience(hit);
        v.value = value;
        java.security.KeyPair key = HammerAndroid.voteIdentity(v.song, v.audience);
        String pub = Hammer.publicKey(key.getPublic());
        String seq = "vote." + v.song + "." + v.audience;
        long last = getSharedPreferences("hammer-votes", 0).getLong(seq, 0);
        for (Hammer.Vote old : list)
            if (old.key.equals(pub) && old.audience.equals(v.audience))
                last = Math.max(last, old.sequence);
        v.sequence = last + 1;
        v.sign(key);
        Hammer.vote(hit.peer.card, v, job);
        getSharedPreferences("hammer-votes", 0)
            .edit()
            .putLong(seq, v.sequence)
            .putLong("mine." + tallyKey(hit), value)
            .apply();
        if (!hit.peer.card.isPrivate())
            for (Hammer.Card p : hosts(hit)) {
                job.check();
                if (p == hit.peer.card)
                    continue;
                try {
                    Hammer.vote(p, v, job);
                } catch (Exception ignored) {
                }
            }
    }
    private void stream(Hit hit, int mode) {
        streamMode = mode;
        playing = hit;
        userStopped = false;
        advancedFor = null;
        try {
            HammerService.play(HammerActivity.this, hosts(hit), hit.song, streamMode);
            Ui.toast(this, "Opening “" + hit.song.title + "”");
        } catch (Exception e) {
            Ui.toast(HammerActivity.this, "Could not open stream");
        }
    }
    private void work(final String title, final Work action) {
        if (busy) {
            Ui.toast(this, "Finish or cancel the current operation first");
            return;
        }
        busy = true;
        job = new Hammer.Job();
        status.setText(title);
        worker.execute(new Runnable() {
            public void run() {
                String error = null;
                try {
                    action.run();
                } catch (Exception e) {
                    error = job.cancelled ? "Cancelled" : e.getMessage();
                    if (error == null || error.isEmpty())
                        error = "Peer unavailable or access denied";
                }
                final String message = error;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (closed)
                            return;
                        if (title.equals("Looking for music"))
                            status.setText(found());  // who is out there, never a list of failures
                        else
                            status.setText(message == null ? title + " — done" : title + ": " + message);
                        if (!settingsMode)
                            render(); // the empty list stops saying it is still looking
                        if (message == null && title.equals("Opening HAMMER") &&
                            getIntent().getBooleanExtra("refresh", false)) {
                            getIntent().removeExtra("refresh");
                            refresh();
                        } else if (discoveryPending && !settingsMode) {
                            refresh();
                        }
                    }
                });
            }
        });
    }
    private void publishDialog(String raw) {
        try {
            final org.json.JSONObject meta = new org.json.JSONObject(raw);
            final String name = meta.optString("_audio");
            if (name.contains("/") || name.contains("\\") || name.contains(".."))
                throw new IOException("Invalid library audio");
            final File file = new File(Store.library(this), name);
            final String title = meta.optString("_title", "Untitled");
            LinearLayout v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            final EditText tags = Ui.edit(this, "Public tags / style (optional)", 2);
            v.addView(tags);
            final CheckBox pub = new CheckBox(this);
            pub.setText("Publish publicly (off = private circle)");
            pub.setTextColor(Skin.TEXT);
            v.addView(pub);
            final CheckBox reshare = new CheckBox(this);
            reshare.setText("Allow downloads and hosting by other people");
            reshare.setTextColor(Skin.TEXT);
            reshare.setChecked(true);
            v.addView(reshare);
            new Lux.Builder(this)
                .setTitle("Host “" + title + "”")
                .setMessage(
                    "Only this audio, title and the tags you enter are shared; hidden data in the file is removed "
                    + "first. Downloaded copies can outlive this host. " + HammerLibrary.ADDRESS_VISIBLE)
                .setView(v)
                .setPositiveButton("Publish",
                                   new DialogInterface.OnClickListener() {
                                       public void onClick(DialogInterface d, int w) {
                                           final String text = tags.getText().toString();
                                           final boolean publicSong = pub.isChecked(),
                                                         allow = reshare.isChecked();
                                           work("Indexing original audio", new Work() {
                                               public void run() throws Exception {
                                                   store.publish(file, title, text, allow, publicSong,
                                                                 HammerAndroid.identity(), job);
                                               }
                                           });
                                       }
                                   })
                .setNegativeButton("Cancel", null)
                .show();
        } catch (Exception e) {
            Ui.toast(this, "Could not open that library song");
        }
    }
    private void invite(final boolean privateCircle) {
        final EditText address = Ui.edit(this, "Address other peers can reach (computers only)", 1);
        String local = ShareServer.localAddress();
        address.setText(local == null ? "" : local);
        new Lux.Builder(this)
            .setTitle(privateCircle ? "Private circle invitation" : "Public peer card")
            .setMessage("A network card finds this phone by its Killa YuE network identity, on any Wi-Fi. Start "
                        + "hosting before someone connects. An address card is for the desktop companion, which "
                        + "can also join the network with --network. Address cards need a reachable port 8784.")
            .setView(address)
            .setPositiveButton("Network card", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { makeCard(null, privateCircle); }
            })
            .setNeutralButton("Address card", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    makeCard(address.getText().toString().trim(), privateCircle);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    /** host null: a card naming this phone's PeerId on the Killa YuE network. */
    private void makeCard(final String host, final boolean privateCircle) {
        work("Creating peer card", new Work() {
            public void run() throws Exception {
                String token = privateCircle ? store.token() : "";
                final Hammer.Card card =
                    host == null ? Hammer.Card.p2p(HammerAndroid.network(HammerActivity.this), HammerAndroid.pin(), token)
                                 : new Hammer.Card(host, Hammer.PORT, HammerAndroid.pin(), token);
                ui.post(new Runnable() {
                    public void run() {
                        if (!closed)
                            showCard(card);
                    }
                });
            }
        });
    }
    private void showCard(final Hammer.Card card) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        ImageView image = new ImageView(this);
        io.nayuki.qrcodegen.QrCode qr =
            io.nayuki.qrcodegen.QrCode.encodeText(card.encode(), io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int scale = 4, side = (qr.size + 8) * scale;
        int[] pixels = new int[side * side];
        for (int y = 0; y < side; y++)
            for (int x = 0; x < side; x++)
                pixels[y * side + x] = qr.getModule(x / scale - 4, y / scale - 4) ? 0xff000000 : 0xffffffff;
        image.setImageBitmap(android.graphics.Bitmap.createBitmap(pixels, side, side,
                                                                  android.graphics.Bitmap.Config.ARGB_8888));
        v.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
        TextView text = Ui.text(this,
                                (card.isPrivate()
                                     ? "Anyone holding this invitation can read your private circle. Send it "
                                           + "through a trusted channel.\n\n"
                                     : "Anyone with this public card can read your public catalog.\n\n") +
                                    card.encode(),
                                Skin.TEXT, 12);
        text.setTextIsSelectable(true);
        v.addView(text);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(v);
        AlertDialog d =
            new Lux.Builder(this)
                .setTitle(card.isPrivate() ? "Private invitation" : "Public card")
                .setView(scroll)
                .setPositiveButton(
                    "Copy card",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("HAMMER peer", card.encode()));
                        }
                    })
                .setNegativeButton("Done", null)
                .create();
        if (card.isPrivate())
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        d.show();
    }
    private void addPeer(String initial) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final EditText card = Ui.edit(this, "hammer:// peer card", 3);
        card.setText(initial);
        v.addView(card);
        final EditText name = Ui.edit(this, "Name for this peer", 1);
        v.addView(name);
        final CheckBox introduce = new CheckBox(this);
        introduce.setText("Introduce this PUBLIC peer to visitors (off by default)");
        introduce.setTextColor(Skin.TEXT);
        v.addView(introduce);
        new Lux.Builder(this)
            .setTitle("Follow a peer")
            .setMessage(
                "Accept cards from people you trust. The card pins their identity. An invitation also grants "
                + "access to their private circle. Connections start when you refresh, play, or enable Live "
                + "Follow. If Live Follow is already on, this peer connects immediately.")
            .setView(v)
            .setPositiveButton(
                "Follow",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String raw = card.getText().toString(), label = name.getText().toString();
                        final boolean share = introduce.isChecked();
                        work("Saving peer", new Work() {
                            public void run() throws Exception {
                                store.follow(new HammerStore.Peer(Hammer.Card.parse(raw),
                                                                  label.isEmpty() ? "Peer" : label, share));
                                HammerFollowService.changed(HammerActivity.this);
                            }
                        });
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void peers() {
        final List<HammerStore.Peer> list = store.followed();
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = list.get(i).label + (list.get(i).card.isPrivate() ? " · private" : " · public");
        new Lux.Builder(this)
            .setTitle("Followed peers")
            .setItems(
                labels,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final HammerStore.Peer p = list.get(which);
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(p.label)
                            .setMessage(
                                "Identity: " + p.card.pin +
                                ("\nIntroductions are public cards only; adding one still requires your "
                                 + "choice."))
                            .setPositiveButton(
                                "Find introductions",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        work("Fetching introductions", new Work() {
                                            public void run() throws Exception {
                                                final List<Hammer.Card> ps = Hammer.peers(p.card, job);
                                                ui.post(new Runnable() {
                                                    public void run() {
                                                        if (closed)
                                                            return;
                                                        String[] names = new String[ps.size()];
                                                        for (int i = 0; i < names.length; i++)
                                                            names[i] = ps.get(i).pin.substring(0, 16) +
                                                                       " · " + ps.get(i).host;
                                                        new Lux.Builder(HammerActivity.this)
                                                            .setTitle("Public peers")
                                                            .setItems(names,
                                                                      new DialogInterface.OnClickListener() {
                                                                          public void onClick(
                                                                              DialogInterface d, int n) {
                                                                              addPeer(ps.get(n).encode());
                                                                          }
                                                                      })
                                                            .show();
                                                    }
                                                });
                                            }
                                        });
                                    }
                                })
                            .setNeutralButton(
                                "Forget peer",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        work("Removing peer", new Work() {
                                            public void run() throws Exception {
                                                store.forget(p.card);
                                                HammerFollowService.changed(HammerActivity.this);
                                                ui.post(new Runnable() {
                                                    public void run() {
                                                        for (Iterator<Hit> it = hits.iterator();
                                                             it.hasNext();)
                                                            if (HammerStore.peerId(it.next().peer.card)
                                                                    .equals(HammerStore.peerId(p.card)))
                                                                it.remove();
                                                        render();
                                                    }
                                                });
                                            }
                                        });
                                    }
                                })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                })
            .setNegativeButton("Close", null)
            .show();
    }
    /**
     * Every way to reach a peer, by its identity: the network card (PeerId) and the direct address card from this
     * Wi-Fi, whichever were found. Discover tries the one that last answered first, then the others.
     */
    private static final Map<String, List<Hammer.Card>> routes = new java.util.concurrent.ConcurrentHashMap<String, List<Hammer.Card>>();
    /** What each peer last offered, kept for the life of the app: a refresh that cannot reach a peer keeps them. */
    private static final Map<String, List<Hit>> songsByPeer = new java.util.concurrent.ConcurrentHashMap<String, List<Hit>>();
    private static final Map<String, Map<String, ProfileHit>> artistsByPeer =
        new java.util.concurrent.ConcurrentHashMap<String, Map<String, ProfileHit>>();
    private static final Map<String, Long> lastSeen = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    static void route(Hammer.Card card) {
        String key = HammerStore.peerId(card);
        List<Hammer.Card> known = routes.get(key);
        List<Hammer.Card> next = known == null ? new ArrayList<Hammer.Card>() : new ArrayList<Hammer.Card>(known);
        for (Hammer.Card c : next)
            if (c.encode().equals(card.encode()))
                return;
        next.add(card);
        routes.put(key, next);
    }
    private static void worked(Hammer.Card card) {
        String key = HammerStore.peerId(card);
        List<Hammer.Card> known = routes.get(key);
        if (known == null)
            return;
        List<Hammer.Card> next = new ArrayList<Hammer.Card>(known);
        for (int i = 0; i < next.size(); i++)
            if (next.get(i).encode().equals(card.encode())) {
                next.add(0, next.remove(i));
                break;
            }
        routes.put(key, next);
    }
    /** Shows everything known: songs and artists from every peer that has answered. */
    private void collect() {
        hits.clear();
        Set<String> seen = new HashSet<String>();
        for (List<Hit> list : songsByPeer.values())
            for (Hit h : list)
                if (seen.add(h.song.key + "/" + h.song.id))
                    hits.add(h);
        profiles.clear();
        for (Map<String, ProfileHit> m : artistsByPeer.values())
            for (Map.Entry<String, ProfileHit> e : m.entrySet()) {
                ProfileHit old = profiles.get(e.getKey());
                if (old == null || e.getValue().profile.revision > old.profile.revision)
                    profiles.put(e.getKey(), e.getValue());
            }
    }
    private void refresh() {
        if (busy) {
            discoveryPending = true;
            return;
        }
        discoveryPending = false;
        work("Looking for music", new Work() {
            public void run() throws Exception {
                // live peers (found on the network or this Wi-Fi just now) first, then the ones you follow
                final List<HammerStore.Peer> peers = new ArrayList<HammerStore.Peer>(discovered.values());
                for (HammerStore.Peer p : store.followed()) peers.add(p);
                for (HammerStore.Peer p : peers) route(p.card);
                final Set<String> visited = java.util.Collections.synchronizedSet(new HashSet<String>());
                final java.util.concurrent.atomic.AtomicInteger answered = new java.util.concurrent.atomic.AtomicInteger();
                final java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(6);
                final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
                final Hammer.Job whole = job;
                try {
                    List<java.util.concurrent.Future<?>> tasks = new ArrayList<java.util.concurrent.Future<?>>();
                    for (final HammerStore.Peer peer : peers) {
                        final String key = HammerStore.peerId(peer.card);
                        if (!visited.add(key) || visited.size() > 48)
                            continue;
                        tasks.add(pool.submit(new Runnable() {
                            public void run() {
                                List<Hammer.Card> cards = routes.get(key);
                                if (cards == null)
                                    cards = java.util.Collections.singletonList(peer.card);
                                for (Hammer.Card card : cards) {
                                    if (whole.cancelled)
                                        return;
                                    // each route gets its own twelve seconds; a dead peer never holds up a live one
                                    final Hammer.Job one = new Hammer.Job();
                                    java.util.concurrent.ScheduledFuture<?> stop = timer.schedule(new Runnable() {
                                        public void run() { one.cancel(); }
                                    }, 12, TimeUnit.SECONDS);
                                    try {
                                        HammerStore.Peer via = new HammerStore.Peer(card, peer.label, peer.introduce);
                                        List<Hit> songs = new ArrayList<Hit>();
                                        for (Hammer.Song s : Hammer.catalog(card, one))
                                            songs.add(new Hit(via, s));
                                        Map<String, ProfileHit> artists = new LinkedHashMap<String, ProfileHit>();
                                        try {
                                            for (HammerProfile profile : Hammer.profiles(card, one))
                                                artists.put(profile.id(), new ProfileHit(via, profile));
                                        } catch (Exception older) { /* older peers still serve music */
                                        }
                                        List<AlbumHit> albums = new ArrayList<AlbumHit>();
                                        try {
                                            for (HammerAlbum a : Hammer.albums(card, one))
                                                albums.add(new AlbumHit(via, a));
                                        } catch (Exception olderHost) {
                                        }
                                        if (!card.isPrivate())
                                            try {
                                                List<HammerPresence> hints = Hammer.directory(card, one);
                                                Collections.shuffle(hints);
                                                for (int i = 0; i < hints.size() && i < 8; i++) {
                                                    final HammerPresence hint = hints.get(i);
                                                    route(hint.card);
                                                    ui.post(new Runnable() {
                                                        public void run() {
                                                            String k = HammerStore.peerId(hint.card);
                                                            if (!discovered.containsKey(k) && discovered.size() < 64)
                                                                try {
                                                                    discovered.put(k, new HammerStore.Peer(hint.card, "Network artist", false));
                                                                    discoveryPending = true;
                                                                } catch (Exception ignored) {
                                                                }
                                                        }
                                                    });
                                                }
                                            } catch (Exception unsupported) {
                                            }
                                        songsByPeer.put(key, songs);
                                        artistsByPeer.put(key, artists);
                                        albumsByPeer.put(key, albums);
                                        lastSeen.put(key, System.currentTimeMillis());
                                        worked(card);
                                        answered.incrementAndGet();
                                        ui.post(new Runnable() {
                                            public void run() {
                                                if (closed)
                                                    return;
                                                collect();
                                                render();  // each artist appears the moment it answers
                                            }
                                        });
                                        return;
                                    } catch (Exception tryTheNextRoute) {
                                    } finally {
                                        stop.cancel(false);
                                    }
                                }
                            }
                        }));
                    }
                    for (java.util.concurrent.Future<?> t : tasks)
                        try {
                            t.get(40, TimeUnit.SECONDS);
                        } catch (Exception late) {
                        }
                } finally {
                    pool.shutdownNow();
                    timer.shutdownNow();
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        collect();
                        render();
                        String wanted = getIntent().getStringExtra("song");
                        if (wanted != null) {
                            getIntent().removeExtra("song");
                            for (Hit h : hits)
                                if (h.song.id.equals(wanted)) {
                                    song(h);
                                    break;
                                }
                        }
                    }
                });
            }
        });
    }
    /** The status line under the search: who is out there right now, without numbers about failures. */
    private String found() {
        int artists = 0;
        for (List<Hit> l : songsByPeer.values())
            if (!l.isEmpty())
                artists++;
        return hits.isEmpty() ? "Looking for artists on the Killa YuE network…"
                              : artists + (artists == 1 ? " artist · " : " artists · ") + hits.size() +
                                    (hits.size() == 1 ? " song" : " songs");
    }
    private void render() {
        if (closed || results == null)
            return;
        results.removeAllViews();
        String q = query.getText().toString();
        int count = 0;
        if (artistFilter == null && albumFilter == null) {
            LinearLayout strip = Ui.row(this);
            strip.setGravity(android.view.Gravity.TOP);
            for (final ProfileHit profile : profiles.values())
                if (profile.profile.matches(q))
                    strip.addView(artistTile(profile));
            if (strip.getChildCount() > 0) {
                results.addView(heading("Artists"));
                HorizontalScrollView h = new HorizontalScrollView(this);
                h.setHorizontalScrollBarEnabled(false);
                h.addView(strip);
                results.addView(h);
            }
            LinearLayout shelf = Ui.row(this);
            shelf.setGravity(android.view.Gravity.TOP);
            Set<String> albumsShown = new HashSet<String>();
            for (List<AlbumHit> list : albumsByPeer.values())
                for (AlbumHit a : list)
                    try {
                        if ((a.album.title.toLowerCase(java.util.Locale.ROOT).contains(q.toLowerCase(java.util.Locale.ROOT))
                             || q.trim().isEmpty()) && albumsShown.add(a.album.id()))
                            shelf.addView(albumTile(a));
                    } catch (Exception badAlbum) {
                    }
            if (shelf.getChildCount() > 0) {
                results.addView(heading("Albums and playlists"));
                HorizontalScrollView h = new HorizontalScrollView(this);
                h.setHorizontalScrollBarEnabled(false);
                h.addView(shelf);
                results.addView(h);
            }
            // charts, counted by the hosts themselves: no central ranking
            LinearLayout charts = Ui.row(this);
            String[] names = {"All", "Top voted", "Most played", "Most downloaded"};
            for (int i = 0; i < names.length; i++) {
                final int which = i;
                Button c = Ui.chip(this, names[i]);
                if (chart == i) {
                    c.setTextColor(Skin.HI);
                    android.graphics.drawable.GradientDrawable lit = Ui.box(this, Skin.accent(70), 18);
                    lit.setStroke(Ui.dp(this, 1), Skin.ACCENT);
                    c.setBackground(lit);
                }
                c.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        chart = which;
                        render();
                    }
                });
                charts.addView(c);
            }
            HorizontalScrollView ch = new HorizontalScrollView(this);
            ch.setHorizontalScrollBarEnabled(false);
            ch.addView(charts);
            results.addView(ch, Ui.gap(this, 12));
        }
        List<Hit> list = new ArrayList<Hit>(hits);
        if (albumFilter != null) {
            // the album: its title, a way back, play it all; its songs in the artist's order
            LinearLayout bar = Ui.row(this);
            Button back = Ui.chip(this, "◀ All music");
            back.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    albumFilter = null;
                    render();
                }
            });
            bar.addView(back);
            Button all = Ui.chip(this, "▶ Play album");
            bar.addView(all);
            results.addView(bar, Ui.gap(this, 10));
            results.addView(heading(albumFilter.album.title));
            List<Hit> ordered = new ArrayList<Hit>();
            for (String id : albumFilter.album.songs)
                for (Hit h : hits)
                    if (h.song.id.equals(id) && h.song.key.equals(albumFilter.album.key)) {
                        ordered.add(h);
                        break;
                    }
            list = ordered;
            final List<Hit> first = ordered;
            all.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!first.isEmpty())
                        stream(first.get(0), streamMode);  // ⏭ and the end of each song go through the album
                }
            });
        } else if (chart > 0) {
            Collections.sort(list, new java.util.Comparator<Hit>() {
                public int compare(Hit a, Hit b) { return Long.compare(score(b), score(a)); }
            });
        }
        Set<String> displayed = new HashSet<String>();
        shown.clear();
        boolean first = true;
        for (final Hit hit : list)
            if ((artistFilter == null || artistFilter.equals(shortAuthorFull(hit.song.key))) &&
                (albumFilter != null || hit.song.matches(q) || artistMatches(hit.song.key, q)) &&
                displayed.add(hit.song.id + hit.song.sig)) {
                if (++count > 100)
                    break;
                if (first) {
                    results.addView(heading("Songs"));
                    first = false;
                }
                shown.add(hit);
                results.addView(songCard(hit), Ui.gap(this, 8));
            }
        TextView n = Ui.text(this,
                             count > 100 ? "Showing the first 100 matches. Narrow your search."
                                         : count == 0 ? "" : count + " songs in reachable catalogs",
                             Skin.DIM, 12);
        n.setPadding(0, Ui.dp(this, 10), 0, 0);
        results.addView(n);
        if (hits.isEmpty() && profiles.isEmpty()) {
            TextView empty = Ui.text(
                this,
                busy ? "Looking for artists on the Killa YuE network…"
                     : "No one is sharing that this phone can see yet. Artists who share publicly appear here by "
                       + "themselves, from this Wi-Fi or anywhere in the world, as the network finds them; Discover "
                       + "keeps looking while it is open.",
                Skin.DIM, 14);
            empty.setPadding(0, Ui.dp(this, 24), 0, 0);
            empty.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            results.addView(empty);
        }
    }
    /** A song's place in the chosen chart. */
    private long score(Hit h) {
        int[] t = tallies.get(tallyKey(h));
        long[] n = served.get(tallyKey(h));
        return chart == 1 ? (t == null ? Long.MIN_VALUE / 2 : t[0] - t[1])
             : chart == 2 ? (n == null ? -1 : n[0])
             : (n == null ? -1 : n[1]);
    }
    /** An album as a framed cover with its title; tap to open it. */
    private View albumTile(final AlbumHit a) {
        LinearLayout t = Ui.column(this);
        t.setPadding(0, 0, Ui.dp(this, 14), 0);
        final ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.box(this, Skin.PILL, 10));
        art.setClipToOutline(true);
        t.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        for (final Hit h : hits)
            if (h.song.id.equals(a.album.cover) && h.song.key.equals(a.album.key)) {
                fetch(coverKey(h), art, 0, null, new Fetch() {
                    public byte[] get(Hammer.Job j) throws Exception {
                        HammerArt c = Hammer.cover(h.peer.card, h.song, j);
                        return c == null ? null : Hammer.image(h.peer.card, h.song.id, c.image, false, j);
                    }
                });
                break;
            }
        PanText n = new PanText(this);
        n.setText(a.album.title);
        n.setTextColor(Skin.TEXT);
        n.setTextSize(13);
        n.setTypeface(Skin.text(), android.graphics.Typeface.BOLD);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
        t.addView(n, new LinearLayout.LayoutParams(Ui.dp(this, 96), -2));
        TextView c = Ui.text(this, a.album.songs.size() + " songs · " + artistName(a.album.key), Skin.DIM, 11);
        c.setSingleLine(true);
        c.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(c, new LinearLayout.LayoutParams(Ui.dp(this, 96), -2));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                albumFilter = a;
                render();
                if (scroll != null)
                    scroll.smoothScrollTo(0, 0);
            }
        });
        t.setContentDescription("Album " + a.album.title);
        return t;
    }
    private TextView heading(String name) {
        TextView h = Ui.label(this, name.toUpperCase());
        h.setTextColor(Skin.ACCENT);
        h.setLetterSpacing(0.12f);
        h.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 6));
        return h;
    }
    /** An artist medallion with the name under it; tap for the artist's page. */
    private View artistTile(final ProfileHit profile) {
        LinearLayout t = Ui.column(this);
        t.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, 0, Ui.dp(this, 14), 0);
        final ImageView face = new ImageView(this);
        final String name = profile.profile.name;
        face.setImageBitmap(Ui.medallion(this, null, name, 84));
        t.addView(face, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84)));
        TextView n = Ui.text(this, name, Skin.TEXT, 13);
        n.setTypeface(Skin.text(), android.graphics.Typeface.BOLD);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
        t.addView(n, new LinearLayout.LayoutParams(Ui.dp(this, 92), -2));
        try {
            final String id = profile.profile.id();
            if (!profile.profile.image.isEmpty())
                fetch("a:" + id + profile.profile.image, face, 84, name, new Fetch() {
                    public byte[] get(Hammer.Job j) throws Exception {
                        return Hammer.image(profile.peer.card, id, profile.profile.image, true, j);
                    }
                });
        } catch (Exception badKey) {
        }
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(HammerActivity.this, HammerArtistActivity.class)
                                      .putExtra("peer", profile.peer.card.encode())
                                      .putExtra("artist", profile.profile.id()));
                } catch (Exception ignored) {
                }
            }
        });
        // the bell: followed artists ring when they publish; hold the medallion to follow or stop
        String id = "";
        try {
            id = profile.profile.id();
        } catch (Exception badKey) {
        }
        final String artistId = id;
        final boolean following = HammerFollowService.prefs(this).contains("artist." + artistId);
        if (following) {
            TextView bell = Ui.text(this, "🔔 Following", Skin.ACCENT, 11);
            bell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            t.addView(bell, new LinearLayout.LayoutParams(Ui.dp(this, 92), -2));
        }
        t.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                work(following ? "Unfollowing " + name : "Following " + name, new Work() {
                    public void run() throws Exception {
                        if (following) {
                            HammerFollowService.prefs(HammerActivity.this).edit().remove("artist." + artistId).apply();
                            store.forget(profile.peer.card);
                        } else {
                            store.follow(new HammerStore.Peer(profile.peer.card, name, false));
                            HammerFollowService.prefs(HammerActivity.this).edit().putString("artist." + artistId, name).apply();
                            HammerFollowService.start(HammerActivity.this);  // so new songs ring
                        }
                        HammerFollowService.changed(HammerActivity.this);
                    }
                });
                return true;
            }
        });
        t.setContentDescription("Artist " + name + (following ? ", following" : "") + ". Hold to " +
                                (following ? "stop following" : "follow and hear about new songs"));
        return t;
    }
    /** A song in a gold frame: cover, title, artist and style; tap to listen, ⋯ for everything else. */
    private View songCard(final Hit hit) {
        LinearLayout card = Ui.row(this);
        card.setBackground(new LuxFrame(14, 8));
        int in = Ui.dp(this, 12);
        card.setPadding(in, in, in, in);
        // the cover takes its place only when there is one, so a song without art starts at the left
        final ImageView cover = new ImageView(this) {
            @Override
            public void setImageBitmap(android.graphics.Bitmap b) {
                super.setImageBitmap(b);
                setVisibility(b == null ? View.GONE : View.VISIBLE);
            }
        };
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Ui.box(this, Skin.PILL, 8));
        cover.setClipToOutline(true);
        cover.setVisibility(View.GONE);
        LinearLayout.LayoutParams coverAt = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        coverAt.rightMargin = Ui.dp(this, 12);
        card.addView(cover, coverAt);
        fetch(coverKey(hit), cover, 0, null, new Fetch() {
            public byte[] get(Hammer.Job j) throws Exception {
                HammerArt a = Hammer.cover(hit.peer.card, hit.song, j);
                return a == null ? null : Hammer.image(hit.peer.card, hit.song.id, a.image, false, j);
            }
        });
        LinearLayout words = Ui.column(this);
        words.setPadding(0, 0, Ui.dp(this, 6), 0);
        PanText title = new PanText(this);  // long titles glide to and fro instead of ending in "…"
        title.setText(hit.song.title);
        title.setTextColor(Skin.TEXT);
        title.setTextSize(16);
        title.setTypeface(Skin.text(), android.graphics.Typeface.BOLD);
        words.addView(title);
        TextView by = Ui.text(this, artistName(hit.song.key) + (hit.peer.card.isPrivate() ? " · private circle" : ""),
                              Skin.ACCENT, 13);
        by.setSingleLine(true);
        by.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(by);
        if (hit.song.tags != null && !hit.song.tags.isEmpty()) {
            TextView tags = Ui.text(this, hit.song.tags, Skin.DIM, 12);
            tags.setSingleLine(true);
            tags.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(tags);
        }
        TextView votes = Ui.text(this, "", Skin.DIM, 12);
        votes.setTag(hit.song.id);
        voteLine(votes, hit);
        words.addView(votes);
        Long when = counted.get(tallyKey(hit));
        if (when == null || System.currentTimeMillis() - when > 5 * 60 * 1000)
            tally(hit, votes);  // counted at most every five minutes per song
        card.addView(words, Ui.weight());
        Button play = Ui.chip(this, "▶");
        card.addView(play);
        play.setContentDescription("Play " + hit.song.title);
        play.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stream(hit, streamMode); }
        });
        Button more = Ui.chip(this, "⋯");
        ((LinearLayout.LayoutParams)more.getLayoutParams()).rightMargin = 0;
        card.addView(more);
        more.setContentDescription("More for " + hit.song.title);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { song(hit); }
        });
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stream(hit, streamMode); }
        });
        return card;
    }
    private String shortAuthorFull(String key) {
        try {
            return Hammer.hash(java.util.Base64.getDecoder().decode(key));
        } catch (Exception e) {
            return "";
        }
    }
    private String artistName(String key) {
        ProfileHit p = profiles.get(shortAuthorFull(key));
        return p == null ? "Artist " + shortAuthor(key) : p.profile.name;
    }
    private boolean artistMatches(String key, String query) {
        ProfileHit p = profiles.get(shortAuthorFull(key));
        return p != null && p.profile.matches(query);
    }
    private List<Hammer.Card> hosts(Hit hit) {
        List<Hammer.Card> ps = new ArrayList<Hammer.Card>();
        ps.add(hit.peer.card);
        Set<String> known = new HashSet<String>();
        known.add(HammerStore.peerId(hit.peer.card));
        for (Hit h : hits)
            if (h.song.id.equals(hit.song.id) && h.song.sig.equals(hit.song.sig) &&
                known.add(HammerStore.peerId(h.peer.card)))
                ps.add(h.peer.card);
        return ps;
    }
    private void song(final Hit hit) {
        new Lux.Builder(this)
            .setTitle(hit.song.title)
            .setNeutralButton(
                "Track details",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(hit.song.title)
                            .setMessage(hit.song.tags + "\n" + (hit.song.size / 1048576) + " MiB · " +
                                        (hit.peer.card.isPrivate() ? "Private circle" : "Public catalog") +
                                        "\nPublisher: " + shortAuthor(hit.song.key))
                            .setPositiveButton("Done", null)
                            .show();
                    }
                })
            .setItems(new String[] {"Listen · choose the quality",
                                    hit.song.reshare ? "Download / resume" : "Streaming only", "Comments",
                                    "Up / down votes", "Follow this artist", "Artist profile", "Song conversation / remixes",
                                    "Lyrics"},
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, int which) {
                              if (which == 7) {
                                  showLyrics(hit);
                              } else if (which == 6) {
                                  BackstageActivity.open(HammerActivity.this, hit.peer.card, "track", hit.song.id, false);
                              } else if (which == 0) {
                                  new Lux.Builder(HammerActivity.this)
                                      .setTitle("Stream quality")
                                      .setItems(
                                          new String[] {"Auto · adaptive Opus",
                                                        "Original · lossless when source is lossless",
                                                        "Data saver · 48 kbps Opus"},
                                          new DialogInterface.OnClickListener() {
                                              public void onClick(DialogInterface d, int mode) {
                                                  stream(hit, mode);
                                              }
                                          })
                                      .setNegativeButton("Cancel", null)
                                      .show();
                              } else if (which == 1 && hit.song.reshare)
                                  download(hit);
                              else if (which == 2)
                                  comments(hit);
                              else if (which == 3)
                                  votes(hit);
                              else if (which == 4)
                                  followArtist(hit);
                              else if (which == 5)
                                  startActivity(new Intent(HammerActivity.this, HammerArtistActivity.class)
                                                    .putExtra("peer", hit.peer.card.encode())
                                                    .putExtra("artist", shortAuthorFull(hit.song.key)));
                          }
                      })
            .setNegativeButton("Close", null)
            .show();
    }
    private void showLyrics(final Hit hit) {
        work("Reading lyrics", new Work() {
            public void run() throws Exception {
                HammerLyrics l = null;
                try {
                    l = Hammer.lyrics(hit.peer.card, hit.song, job);
                } catch (Exception olderHost) {
                }
                final StringBuilder text = new StringBuilder();
                if (l != null)
                    for (HammerLyrics.Line line : l.lines())
                        text.append(line.words).append('\n');
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(hit.song.title)
                            .setMessage(text.length() == 0 ? "The artist has not shared lyrics for this song." : text.toString())
                            .setPositiveButton("Done", null)
                            .show();
                    }
                });
            }
        });
    }
    private String shortAuthor(String key) {
        try {
            return Hammer.hash(java.util.Base64.getDecoder().decode(key)).substring(0, 16);
        } catch (Exception e) {
            return "Unknown";
        }
    }
    private void download(final Hit hit) {
        work("Downloading verified audio", new Work() {
            public void run() throws Exception {
                File target = store.downloadFile(hit.song), part =
                                                                new File(store.root, hit.song.id + ".part");
                if (!target.isFile()) {
                    Exception failure = null;
                    for (Hammer.Card p : hosts(hit)) {
                        job.check();
                        try {
                            Hammer.download(p, hit.song, part, target, job);
                            failure = null;
                            break;
                        } catch (Exception e) {
                            failure = e;
                        }
                    }
                    if (failure != null)
                        throw failure;
                }
                Hammer.require(target.length() == hit.song.size && Hammer.hash(target).equals(hit.song.id),
                               "Local copy does not match");
                store.rememberDownload(hit.song);
                try {
                    ProfileHit artist = profiles.get(shortAuthorFull(hit.song.key));
                    if (artist != null) {
                        store.publicObjects.rememberProfile(artist.profile);
                        if (!artist.profile.image.isEmpty())
                            store.publicObjects.putImage(Hammer.image(artist.peer.card, artist.profile.id(),
                                                                      artist.profile.image, true, job));
                    }
                    HammerArt art = Hammer.cover(hit.peer.card, hit.song, job);
                    if (art != null) {
                        store.publicObjects.putImage(
                            Hammer.image(hit.peer.card, art.song, art.image, false, job));
                        store.publicObjects.cover(hit.song, art);
                    }
                } catch (Exception optionalArtwork) { /* The verified recording remains usable. */
                }
                saveLibrary(hit.song);
                // keep it alive: a public song the artist lets people rehost is hosted from here too, with the
                // artist's own signature, so it plays on when the artist's phone is off
                if (!hit.peer.card.isPrivate() && hit.song.reshare &&
                    getSharedPreferences("hammer", MODE_PRIVATE).getBoolean("spread", true))
                    try {
                        store.rehost(hit.song, true);
                        ui.post(new Runnable() {
                            public void run() { HammerService.startHost(HammerActivity.this); }
                        });
                    } catch (Exception full) {  /* at most 256 songs */
                    }
                if (!hit.peer.card.isPrivate())
                    try {
                        store.importPublicMetadata(hit.song, Hammer.comments(hit.peer.card, hit.song.id, job),
                                                   Hammer.votes(hit.peer.card, hit.song.id, job));
                        store.community.importPublic(hit.song, HammerCommunity.list(hit.peer.card, "track", hit.song.id, job));
                    } catch (Exception e) {
                        ui.post(new Runnable() {
                            public void run() {
                                if (!closed)
                                    Ui.toast(HammerActivity.this,
                                             "Audio verified; public annotations can be synced later");
                            }
                        });
                    }
            }
        });
    }
    private void downloads() {
        work("Reading downloads", new Work() {
            public void run() throws Exception {
                final List<Hammer.Song> songs = store.downloads();
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        String[] names = new String[songs.size()];
                        for (int i = 0; i < names.length; i++)
                            names[i] = songs.get(i).title;
                        new Lux.Builder(HammerActivity.this)
                            .setTitle("Verified downloads")
                            .setItems(
                                names,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int n) {
                                        final Hammer.Song s = songs.get(n);
                                        new Lux.Builder(HammerActivity.this)
                                            .setTitle(s.title)
                                            .setItems(
                                                new String[] {"Save to Library", "Host in private circle",
                                                              "Publish publicly"},
                                                new DialogInterface.OnClickListener() {
                                                    public void onClick(DialogInterface d, final int action) {
                                                        if (action == 0)
                                                            work("Saving to Library", new Work() {
                                                                public void run() throws Exception {
                                                                    saveLibrary(s);
                                                                }
                                                            });
                                                        else
                                                            new Lux.Builder(HammerActivity.this)
                                                                .setTitle(
                                                                    action == 2
                                                                        ? "Publish this download publicly?"
                                                                        : "Host this download privately?")
                                                                .setMessage(
                                                                    "The original signed title and publisher "
                                                                    + "stay attached. You are offering your "
                                                                    + "verified copy from this device.")
                                                                .setPositiveButton(
                                                                    "Host copy",
                                                                    new DialogInterface.OnClickListener() {
                                                                        public void onClick(DialogInterface d,
                                                                                            int w) {
                                                                            work("Hosting downloaded copy",
                                                                                 new Work() {
                                                                                     public void run()
                                                                                         throws Exception {
                                                                                         store.rehost(
                                                                                             s, action == 2);
                                                                                     }
                                                                                 });
                                                                        }
                                                                    })
                                                                .setNegativeButton("Cancel", null)
                                                                .show();
                                                    }
                                                })
                                            .show();
                                    }
                                })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                });
            }
        });
    }
    private void saveLibrary(Hammer.Song song) throws Exception {
        String base = "hammer-" + song.id;
        File out = new File(Store.library(this), base + song.extension()), source = store.downloadFile(song);
        if (!out.exists()) {
            File tmp = File.createTempFile("hammer-", ".part", Store.library(this));
            try {
                try (InputStream in = new FileInputStream(source);
                     FileOutputStream file = new FileOutputStream(tmp)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) != -1) {
                        job.check();
                        file.write(b, 0, n);
                    }
                    file.getFD().sync();
                }
                Hammer.require(Hammer.hash(tmp).equals(song.id), "Copy verification failed");
                Hammer.require(tmp.renameTo(out), "Could not save song");
            } finally {
                tmp.delete();
            }
        }
        Hammer.require(Hammer.hash(out).equals(song.id), "Existing Library file differs");
        store.relocateDownload(song, out);
        if (!source.getCanonicalFile().equals(out.getCanonicalFile()))
            Hammer.require(source.delete(), "Library copy saved; old download could not be removed");
        Store.write(new File(Store.library(this), base + ".hammer"), store.publicMetadata(song));
        try {
            HammerArt cover = store.publicObjects.localCover(song.id);
            if (cover != null && cover.key.equals(song.key)) {
                File raw = File.createTempFile("download-cover-", ".image", getCacheDir());
                try {
                    HammerStore.atomic(raw, store.publicObjects.localImage(cover.image));
                    Store.write(new File(Store.library(this), base + ".cover.jpg"),
                                ((HammerCodec)HammerMedia.codec).image(raw));
                } finally {
                    raw.delete();
                }
            }
        } catch (Exception optionalArtwork) { /* A bad cover must not hide verified audio from Library. */
        }
        File meta = new File(Store.library(this), base + ".json");
        if (!meta.exists()) {
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("_title", song.title);
            m.put("_audio", out.getName());
            m.put("style", song.tags);
            m.put("_created", System.currentTimeMillis());
            m.put("_hammer_id", song.id);
            m.put("_hammer_publisher", song.key);
            Store.write(meta, m.toString().getBytes("UTF-8"));
        }
    }
    private void hosted() {
        final List<HammerStore.Hosted> list = store.hosted();
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            String counted = "";
            try {  // what this phone has served: plays and whole downloads, never who
                long[] n = store.stats(list.get(i).song.id, list.get(i).publicSong ? "" : store.token());
                counted = " · ▶ " + n[0] + " · ⬇ " + n[1];
            } catch (Exception unavailable) {
            }
            names[i] = list.get(i).song.title + (list.get(i).publicSong ? " · public" : " · private") + counted;
        }
        new Lux.Builder(this)
            .setTitle("Hosted songs")
            .setItems(
                names,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int n) {
                        final HammerStore.Hosted h = list.get(n);
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(h.song.title)
                            .setItems(
                                new String[] {h.publicSong ? "Move to private circle" : "Publish publicly",
                                              "Unpublish", "Read / moderate comments", "Song conversation / remixes"},
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, final int action) {
                                        if (action == 3) {
                                            BackstageActivity.open(HammerActivity.this, null, "track", h.song.id, !h.publicSong);
                                            return;
                                        }
                                        if (action == 2) {
                                            localComments(h.song);
                                            return;
                                        }
                                        new Lux.Builder(HammerActivity.this)
                                            .setTitle(action == 1 ? "Stop offering this song?"
                                                                  : "Change who can discover this song?")
                                            .setMessage(
                                                "Existing downloads cannot be recalled. Public publishing "
                                                + "makes the title and tags available to any visitor.")
                                            .setPositiveButton(
                                                "Apply",
                                                new DialogInterface.OnClickListener() {
                                                    public void onClick(DialogInterface d, int w) {
                                                        work("Updating publication", new Work() {
                                                            public void run() throws Exception {
                                                                if (action == 1)
                                                                    store.unpublish(h.song.id);
                                                                else
                                                                    store.visibility(h.song.id,
                                                                                     !h.publicSong);
                                                                if (action == 1 || h.publicSong)
                                                                    HammerService.disconnect();
                                                            }
                                                        });
                                                    }
                                                })
                                            .setNegativeButton("Cancel", null)
                                            .show();
                                    }
                                })
                            .show();
                    }
                })
            .setNegativeButton("Close", null)
            .show();
    }
    private void comments(final Hit hit) {
        work("Reading signed comments", new Work() {
            public void run() throws Exception {
                final List<Hammer.Comment> cs = Hammer.comments(hit.peer.card, hit.song.id, job);
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        StringBuilder b = new StringBuilder();
                        for (Hammer.Comment c : cs)
                            b.append(c.name)
                                .append(" [")
                                .append(shortAuthor(c.key))
                                .append("]\n")
                                .append(c.body)
                                .append("\n\n");
                        new Lux.Builder(HammerActivity.this)
                            .setTitle("Comments on this peer")
                            .setMessage(b.length() == 0 ? "No comments on this host yet." : b.toString())
                            .setPositiveButton("Write comment",
                                               new DialogInterface.OnClickListener() {
                                                   public void onClick(DialogInterface d, int w) {
                                                       writeComment(hit);
                                                   }
                                               })
                            .setNeutralButton(
                                "Sync public track metadata",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        work("Syncing signed comments", new Work() {
                                            public void run() throws Exception {
                                                Hammer.require(
                                                    !hit.peer.card.isPrivate(),
                                                    "Private comments stay in their original circle");
                                                store.importPublicMetadata(
                                                    hit.song, cs,
                                                    Hammer.votes(hit.peer.card, hit.song.id, job));
                                            }
                                        });
                                    }
                                })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                });
            }
        });
    }
    private void writeComment(final Hit hit) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final EditText name = Ui.edit(this, "Display name (identity shown beside it)", 1);
        final EditText text = Ui.edit(this, "Your comment", 4);
        v.addView(name);
        v.addView(text);
        new Lux.Builder(this)
            .setTitle("Post signed comment")
            .setMessage("This peer stores your comment. Public song comments can be copied to other hosts; "
                        + "private song comments are visible to the host's invited circle.")
            .setView(v)
            .setPositiveButton(
                "Post",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String who = name.getText().toString(), body = text.getText().toString();
                        work("Posting comment", new Work() {
                            public void run() throws Exception {
                                Hammer.Comment c = new Hammer.Comment();
                                c.song = hit.song.id;
                                c.audience = hit.peer.card.isPrivate()
                                                 ? Hammer.hash(hit.peer.card.token.getBytes("US-ASCII"))
                                                 : "public";
                                c.id = Hammer.random();
                                c.time = System.currentTimeMillis();
                                c.name = who;
                                c.body = body;
                                c.sign(HammerAndroid.identity());
                                Hammer.comment(hit.peer.card, c, job);
                            }
                        });
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void localComments(final Hammer.Song song) {
        work("Reading hosted comments", new Work() {
            public void run() throws Exception {
                final List<Hammer.Comment> cs = store.comments(song.id, store.token());
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        String[] names = new String[cs.size()];
                        for (int i = 0; i < names.length; i++)
                            names[i] =
                                cs.get(i).name + " [" + shortAuthor(cs.get(i).key) + "]: " + cs.get(i).body;
                        new Lux.Builder(HammerActivity.this)
                            .setTitle("Tap an author to moderate")
                            .setItems(names,
                                      new DialogInterface.OnClickListener() {
                                          public void onClick(DialogInterface d, final int n) {
                                              new Lux.Builder(HammerActivity.this)
                                                  .setTitle("Block this author on your host?")
                                                  .setMessage(
                                                      "Their comments disappear from your host. Other "
                                                      + "peers control their own copies.")
                                                  .setPositiveButton(
                                                      "Block author",
                                                      new DialogInterface.OnClickListener() {
                                                          public void onClick(DialogInterface d, int w) {
                                                              work("Blocking comment author", new Work() {
                                                                  public void run() throws Exception {
                                                                      store.block(cs.get(n).key);
                                                                  }
                                                              });
                                                          }
                                                      })
                                                  .setNegativeButton("Cancel", null)
                                                  .show();
                                          }
                                      })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                });
            }
        });
    }
    private void liveFollow() {
        if (HammerFollowService.prefs(this).getBoolean("live", false)) {
            HammerFollowService.stop(this);
            return;
        }
        if (store.followed().isEmpty()) {
            Ui.toast(this, "Follow a peer first");
            return;
        }
        if (!HammerFollowService.hasArtists(this)) {
            Ui.toast(this, "Open a song and follow its artist first");
            return;
        }
        new Lux.Builder(this)
            .setTitle("Enable Live Follow?")
            .setMessage("Keep encrypted connections to up to sixteen followed peers for immediate release "
                        + "events while connected. This uses extra battery and a persistent notification. "
                        + "Android or network restrictions can still interrupt delivery; reconnect catches "
                        + "up. Hosting does not need to be on.")
            .setPositiveButton(
                "Enable",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        android.app.NotificationManager manager =
                            (android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                        android.app.NotificationChannel releases =
                            manager.getNotificationChannel("hammer-releases");
                        if (!manager.areNotificationsEnabled() ||
                            (releases != null &&
                             releases.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE)) {
                            Ui.toast(HammerActivity.this,
                                     "Allow release notifications, then enable Live Follow");
                            Intent settings =
                                new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                            startActivity(settings);
                            return;
                        }
                        HammerFollowService.start(HammerActivity.this);
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void followArtist(final Hit hit) {
        final EditText name = Ui.edit(this, "Name for this artist", 1);
        name.setText(artistName(hit.song.key));
        new Lux.Builder(this)
            .setTitle("Follow the signed artist identity")
            .setMessage("Followed hosts can introduce copies by this artist. Their signing key, not a "
                        + "profile name, identifies a new release. Enable Live Follow for notifications.")
            .setView(name)
            .setPositiveButton(
                "Follow artist",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String artistLabel = name.getText().toString();
                        work("Following artist", new Work() {
                            public void run() throws Exception {
                                store.follow(new HammerStore.Peer(
                                    hit.peer.card, artistLabel.isEmpty() ? "Artist" : artistLabel, false));
                                String id = Hammer.hash(java.util.Base64.getDecoder().decode(hit.song.key));
                                HammerFollowService.prefs(HammerActivity.this)
                                    .edit()
                                    .putString("artist." + id, artistLabel)
                                    .apply();
                                HammerFollowService.changed(HammerActivity.this);
                            }
                        });
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void artists() {
        final List<String> ids = new ArrayList<String>(), names = new ArrayList<String>();
        for (java.util.Map.Entry<String, ?> e : HammerFollowService.prefs(this).getAll().entrySet())
            if (e.getKey().startsWith("artist.")) {
                ids.add(e.getKey());
                names.add(String.valueOf(e.getValue()));
            }
        new Lux.Builder(this)
            .setTitle("Tap an artist to unfollow")
            .setItems(
                names.toArray(new String[0]),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        HammerFollowService.prefs(HammerActivity.this).edit().remove(ids.get(w)).apply();
                        HammerFollowService.changed(HammerActivity.this);
                    }
                })
            .setNegativeButton("Close", null)
            .show();
    }
    private void votes(final Hit hit) {
        work("Reading signed vote metadata", new Work() {
            public void run() throws Exception {
                final List<Hammer.Vote> list = Hammer.votes(hit.peer.card, hit.song.id, job);
                int up = 0, down = 0;
                for (Hammer.Vote v : list) {
                    if (v.value == 1)
                        up++;
                    if (v.value == -1)
                        down++;
                }
                final String count = up + " up · " + down + " down";
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(count)
                            .setNeutralButton(
                                "About votes",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int which) {
                                        new Lux.Builder(HammerActivity.this)
                                            .setTitle("About votes")
                                            .setMessage(
                                                "Votes show no profile name. A separate signing key "
                                                + "per track and "
                                                + "circle lets hosts merge votes without double-counting "
                                                + "replicas. "
                                                + "Keys are not proof of unique people.")
                                            .setPositiveButton("Done", null)
                                            .show();
                                    }
                                })
                            .setItems(
                                new String[] {"Upvote", "Downvote", "Clear my vote"},
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int n) {
                                        final int value = n == 0 ? 1 : n == 1 ? -1 : 0;
                                        work("Saving anonymous-display vote", new Work() {
                                            public void run() throws Exception {
                                                sendVote(hit, value, list, job);
                                                tally(hit, null);
                                            }
                                        });
                                    }
                                })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                });
            }
        });
    }
    @Override
    protected void onActivityResult(int request, int result, final Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 840 && result == RESULT_OK && data != null && data.getData() != null) {
            work("Saving desktop server", new Work() {
                public void run() throws Exception {
                    try (InputStream in = getAssets().open("hammer-server.zip");
                         OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                        Hammer.require(out != null, "Cannot write selected file");
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) != -1) {
                            job.check();
                            out.write(b, 0, n);
                        }
                    }
                }
            });
        }
    }
    @Override
    protected void onDestroy() {
        closed = true;
        if (nearby != null)
            nearby.close();
        ui.removeCallbacksAndMessages(null);
        if (job != null)
            job.cancel();
        worker.shutdownNow();
        art.shutdownNow();
        voting.shutdownNow();
        super.onDestroy();
    }
}
