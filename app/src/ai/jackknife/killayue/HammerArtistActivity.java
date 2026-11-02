package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * An artist page is native UI built from signed plain text and bounded artwork, never a web page. Listeners and the
 * artist see the same page: who they are, what they have, then what to do. The artist's editing sits behind one button.
 */
public final class HammerArtistActivity extends Activity {
    private static final int SONGS_SHOWN = 10, COVERS_FETCHED = 40;
    private LinearLayout body, songs, editor;
    private EditText name, bio, tags;
    private TextView status, shownName, shownTags, shownBio, figureSongs, figurePlays, figureDownloads, figureNote;
    private LinearLayout figures, about;
    private Button followButton;
    private int paintings, coversAsked;
    private ImageView portrait;
    private String image = "", artist, via;
    private boolean own, linked;
    private Hammer.Card peer;
    private HammerProfile profile;
    private HammerStore store;
    private boolean closed, busy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Hammer.Job job = new Hammer.Job();
    private interface Work {
        void run() throws Exception;
    }
    @Override
    public void onCreate(Bundle saved) {
        setTheme(Skin.themeFor(this, Skin.id(this)));
        super.onCreate(saved);
        Skin.load(this);
        artist = getIntent().getStringExtra("artist");
        Uri link = getIntent().getData();
        if (link != null) {  // killayue://a/<artist>?via=<a host that has the page>
            linked = true;
            artist = link.getLastPathSegment();
            via = link.getQueryParameter("via");
            if (artist == null || !Hammer.digest(artist)) {
                Ui.toast(this, "That artist link is not valid");
                finish();
                return;
            }
        }
        try {
            own = linked ? artist.equals(HammerAndroid.pin()) : getIntent().getStringExtra("peer") == null;
        } catch (Exception noIdentityYet) {
            own = false;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Skin.background(this));
        scroll.setFitsSystemWindows(true);
        body = Ui.column(this);
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 28));
        scroll.addView(body);
        setContentView(scroll);
        hero();
        actions();
        if (own)
            editorSection();
        about = plate();
        about.setVisibility(View.GONE);
        shownBio = Ui.text(this, "", Skin.TEXT, 15);
        // an artist's links open in the browser; nothing is fetched until tapped
        shownBio.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        shownBio.setLinkTextColor(Skin.ACCENT);
        shownBio.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        shownBio.setLineSpacing(0, 1.15f);
        about.addView(shownBio);
        body.addView(about, Ui.gap(this, 14));
        songs = Ui.column(this);
        body.addView(songs);
        load();
    }
    /** Who this is: the portrait in its gold ring, the name, the styles, and what they have done. */
    private void hero() {
        portrait = new ImageView(this);
        portrait.setImageBitmap(Ui.medallion(this, null, "", 148));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(this, 148), Ui.dp(this, 148));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(portrait, plp);
        shownName = Ui.text(this, "", Skin.ACCENT, Skin.SCRIPT_TITLES ? 36 : 26);
        shownName.setTypeface(Skin.script(), Skin.SCRIPT_TITLES ? Typeface.NORMAL : Typeface.BOLD);
        shownName.setGravity(Gravity.CENTER_HORIZONTAL);
        shownName.setShadowLayer(8, 0, 2, 0xCC000000);
        body.addView(shownName, Ui.gap(this, 10));
        shownTags = Ui.text(this, "", Skin.DIM, 13);
        shownTags.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(shownTags);
        status = Ui.text(this, "Loading artist…", Skin.DIM, 12);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, Ui.dp(this, 6), 0, 0);
        body.addView(status);
        figures = Ui.row(this);
        figures.setBackground(new LuxFrame(14, 8));
        int in = Ui.dp(this, 12);
        figures.setPadding(in, in, in, in);
        figureSongs = figure("SONGS");
        figurePlays = figure("PLAYS");
        figureDownloads = figure("DOWNLOADS");
        figures.setVisibility(View.GONE);
        body.addView(figures, Ui.gap(this, 16));
        figureNote = Ui.text(this, "", Skin.DIM, 11);
        figureNote.setGravity(Gravity.CENTER_HORIZONTAL);
        figureNote.setPadding(0, Ui.dp(this, 4), 0, 0);
        body.addView(figureNote);
    }
    private TextView figure(String label) {
        LinearLayout cell = Ui.column(this);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView n = Ui.text(this, "–", Skin.TEXT, 22);
        n.setTypeface(Skin.text(), Typeface.BOLD);
        n.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.addView(n);
        TextView l = Ui.text(this, label, Skin.DIM, 10);
        l.setLetterSpacing(0.1f);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.addView(l);
        figures.addView(cell, Ui.weight());
        return n;
    }
    private LinearLayout plate() {
        LinearLayout p = Ui.column(this);
        p.setBackground(new LuxFrame(14, 8));
        int in = Ui.dp(this, 14);
        p.setPadding(in, in, in, in);
        return p;
    }
    /** What to do here: one row, the main thing first. */
    private void actions() {
        LinearLayout row = Ui.row(this);
        if (own) {
            action(row, "Edit my page", true, new Runnable() {
                public void run() { editor.setVisibility(editor.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
            });
        } else {
            followButton = action(row, "Follow", true, new Runnable() {
                public void run() { if (ready()) follow(); }
            });
        }
        action(row, "Share", false, new Runnable() {
            public void run() { if (ready()) share(); }
        });
        action(row, "Board", false, new Runnable() {
            public void run() { if (ready()) BackstageActivity.open(HammerArtistActivity.this, peer, "board", artist, false); }
        });
        body.addView(row, Ui.gap(this, 14));
    }
    private Button action(LinearLayout row, String label, boolean primary, final Runnable run) {
        Button b = Ui.button(this, label, primary);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { run.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, primary ? 1.4f : 1f);
        if (row.getChildCount() > 0)
            lp.leftMargin = Ui.dp(this, 8);
        row.addView(b, lp);
        return b;
    }
    /** The artist's own fields, out of the way of the page they make. Open until there is a page to look at. */
    private void editorSection() {
        editor = plate();
        name = Ui.edit(this, "Artist name", 1);
        bio = Ui.edit(this, "About your music, and your links: website, Bandcamp, socials", 5);
        tags = Ui.edit(this, "Genres and search tags", 2);
        // the name under the portrait follows the field as it is typed
        name.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            public void onTextChanged(CharSequence t, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable t) {
                shownName.setText(t.length() == 0 ? "Unnamed artist" : t.toString());
            }
        });
        LinearLayout pics = Ui.row(this);
        pics.setGravity(Gravity.CENTER_HORIZONTAL);
        pics.addView(chip("✦ Paint with AI", new Runnable() {
            public void run() { askPaint(); }
        }));
        pics.addView(chip("Upload a picture", new Runnable() {
            public void run() {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                           .setType("image/*")
                                           .addCategory(Intent.CATEGORY_OPENABLE),
                                       1);
            }
        }));
        pics.addView(chip("Use a song cover", new Runnable() {
            public void run() { chooseCover(); }
        }));
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(pics);
        editor.addView(h);
        editor.addView(Ui.label(this, "Artist name"));
        editor.addView(name);
        editor.addView(Ui.label(this, "About your music"));
        editor.addView(bio);
        editor.addView(Ui.label(this, "Genres and search tags"));
        editor.addView(tags);
        Button go = Ui.button(this, "Publish my page", true);
        editor.addView(go, Ui.gap(this, 16));
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ready())
                    publish();
            }
        });
        add("Withdraw my page", new Runnable() {
            public void run() {
                work("Withdrawing profile", new Work() {
                    public void run() throws Exception {
                        store.publicObjects.withdrawProfile();
                        profile = null;
                        HammerService.disconnect();
                    }
                });
            }
        });
        TextView note = Ui.text(this,
                             "Your name, bio, tags and picture become public when you publish. "
                                 + "Share songs one by one from the Library. Paint with AI runs on this phone, "
                                 + "with the cover model.",
                             Skin.DIM, 12);
        note.setPadding(0, Ui.dp(this, 10), 0, 0);
        editor.addView(note);
        editor.setVisibility(View.GONE);
        body.addView(editor, Ui.gap(this, 14));
    }
    /** Finds a host for a shared link: the one it names, then those we follow, then whoever the network has met. */
    private void findHost() throws Exception {
        List<Hammer.Card> tries = new ArrayList<Hammer.Card>();
        try {
            if (via != null)
                tries.add(publicCard(Hammer.Card.parse(via)));
        } catch (Exception badHint) {
        }
        for (HammerStore.Peer p : store.followed())
            if (!p.card.isPrivate())
                tries.add(p.card);
        HammerAndroid.identity();
        HammerAndroid.network(this);
        Set<String> asked = new HashSet<String>();
        long end = System.currentTimeMillis() + 30000;
        KillaNet.lookup();
        while (!closed) {
            for (Hammer.Card card : tries) {
                if (!asked.add(card.pin))
                    continue;
                try {
                    for (HammerProfile p : Hammer.profiles(card, job))
                        if (p.id().equals(artist)) {  // the page is signed: any host's copy is the artist's own
                            peer = card;
                            profile = p;
                            return;
                        }
                } catch (Exception unreachable) {
                }
            }
            if (System.currentTimeMillis() > end)
                throw new IOException("Nobody who has this artist's page is reachable right now");
            Thread.sleep(1500);
            tries.clear();
            for (KillaNet.Peer p : KillaNet.peers())
                if (p.host)
                    tries.add(Hammer.Card.p2p(p.id, p.pin, ""));
        }
    }
    /** A card as it may leave this phone: a network identity, never an address or a private invitation. */
    private static Hammer.Card publicCard(Hammer.Card c) throws Exception {
        Hammer.require(c.isP2p(), "Only network cards are shared");
        return Hammer.Card.p2p(c.p2pId(), c.pin, "");
    }
    private void load() {
        work("Loading artist", new Work() {
            public void run() throws Exception {
                store = HammerAndroid.store(HammerArtistActivity.this);
                List<Hammer.Song> tracks;
                if (own) {
                    profile = store.publicObjects.own();
                    artist = HammerAndroid.pin();
                    // your page shows everything you host, private circle songs too
                    tracks = new ArrayList<Hammer.Song>();
                    for (HammerStore.Hosted h : store.hosted())
                        tracks.add(h.song);
                } else {
                    Hammer.require(Hammer.digest(artist), "Invalid artist identity");
                    if (linked)
                        findHost();
                    else {
                        peer = Hammer.Card.parse(getIntent().getStringExtra("peer"));
                        for (HammerProfile p : Hammer.profiles(peer, job))
                            if (p.id().equals(artist))
                                profile = p;
                    }
                    tracks = Hammer.catalog(peer, job);
                }
                // the artist's own songs first, then the copies of other artists' songs this host serves
                final List<Hammer.Song> selected = new ArrayList<Hammer.Song>();
                final List<Hammer.Song> carried = new ArrayList<Hammer.Song>();
                for (Hammer.Song s : tracks)
                    if (Hammer.hash(Base64.getDecoder().decode(s.key)).equals(artist))
                        selected.add(s);
                    else
                        carried.add(s);
                // what this host has served, read in one go so an artist with hundreds of songs opens as fast
                Map<String, long[]> served = null;
                try {
                    served = own ? store.allStats(store.token()) : Hammer.allStats(peer, job);
                } catch (Exception olderHost) {
                }
                final Map<String, long[]> counts = served;
                if (counts != null)
                    Collections.sort(selected, new Comparator<Hammer.Song>() {
                        public int compare(Hammer.Song a, Hammer.Song b) { return Long.compare(plays(counts, b), plays(counts, a)); }
                    });
                final List<HammerAlbum> albums = new ArrayList<HammerAlbum>();
                try {
                    for (HammerAlbum a : own ? store.publicObjects.albums(store.token()) : Hammer.albums(peer, job))
                        if (Hammer.hash(Base64.getDecoder().decode(a.key)).equals(artist))
                            albums.add(a);
                } catch (Exception olderHostOrNone) {
                }
                final HammerProfile p = profile;
                final Bitmap avatar;
                if (p != null && !p.image.isEmpty()) {
                    image = p.image;
                    Bitmap picture = null;
                    try {
                        picture = picture(own ? store.publicObjects.localImage(p.image)
                                              : Hammer.image(peer, artist, p.image, true, job));
                    } catch (Exception optional) {
                    }
                    avatar = picture;
                } else
                    avatar = null;
                boolean following = false;
                if (!own)
                    for (HammerStore.Peer f : store.followed())
                        following |= f.card.pin.equals(peer.pin);
                final boolean followed = following;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!closed)
                            show(p, avatar, selected, carried, counts, albums, followed);
                    }
                });
            }
        });
    }
    private static long plays(Map<String, long[]> counts, Hammer.Song s) {
        long[] n = counts.get(s.id);
        return n == null ? 0 : n[0];
    }
    private void show(HammerProfile p, Bitmap avatar, List<Hammer.Song> selected, List<Hammer.Song> carried,
                      Map<String, long[]> counts, List<HammerAlbum> albums, boolean followed) {
        String shown = "", words = "", styles = "";
        if (p != null) {
            shown = p.name;
            words = p.bio;
            styles = p.tags;
        } else if (!own)
            shown = "Artist " + artist.substring(0, 12);
        if (own) {
            name.setText(shown);
            bio.setText(words);
            tags.setText(styles);
            if (p == null)
                editor.setVisibility(View.VISIBLE);
        }
        showIdentity(shown, words, styles);
        portrait.setImageBitmap(Ui.medallion(this, avatar, shown, 148));
        if (followButton != null && followed)
            followButton.setText("Following");
        figureSongs.setText(String.valueOf(selected.size()));
        if (counts != null) {
            long plays = 0, downloads = 0;
            for (Hammer.Song s : selected) {
                long[] n = counts.get(s.id);
                if (n != null) {
                    plays += n[0];
                    downloads += n[1];
                }
            }
            figurePlays.setText(String.valueOf(plays));
            figureDownloads.setText(String.valueOf(downloads));
            figureNote.setText(own ? "Counted by this phone, the plays and downloads it served" : "As counted by this host");
        }
        figures.setVisibility(View.VISIBLE);
        if (own && counts != null)
            week(counts, selected);
        if (!albums.isEmpty())
            albums(albums, selected);
        if (!selected.isEmpty())
            songs.addView(heading(own ? "YOUR SONGS ON THE NETWORK" : "SONGS"));
        else if (own)
            songs.addView(Ui.text(this, "No published songs yet. In the Library, tap a song's sharing line and choose Public.",
                                  Skin.DIM, 13), Ui.gap(this, 18));
        songList(songs, selected, counts, SONGS_SHOWN);
        if (!carried.isEmpty()) {
            songs.addView(heading(own ? "OTHER ARTISTS' SONGS YOU HOST" : "ALSO HOSTING"));
            songList(songs, carried, counts, 0);
        }
    }
    private void showIdentity(String shown, String words, String styles) {
        shownName.setText(shown.isEmpty() && own ? "Unnamed artist" : shown);
        shownTags.setText(styles);
        shownTags.setVisibility(styles.isEmpty() ? View.GONE : View.VISIBLE);
        shownBio.setText(words);
        about.setVisibility(words.trim().isEmpty() ? View.GONE : View.VISIBLE);
    }
    /** Shows the first few songs and a button for the rest, so a long catalogue stays one screen until asked. */
    private void songList(final LinearLayout into, final List<Hammer.Song> list, final Map<String, long[]> counts, int first) {
        int shown = Math.min(first, list.size());
        for (int k = 0; k < shown; k++)
            into.addView(songRow(list.get(k), counts), Ui.gap(this, 8));
        if (shown == list.size())
            return;
        final int from = shown;
        final Button more = Ui.button(this, "Show " + (shown == 0 ? "" : "all ") + list.size() + " songs", false);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int at = into.indexOfChild(more);
                into.removeView(more);
                for (int k = from; k < list.size(); k++)
                    into.addView(songRow(list.get(k), counts), at++, Ui.gap(HammerArtistActivity.this, 8));
            }
        });
        into.addView(more, Ui.gap(this, 10));
    }
    private View songRow(final Hammer.Song song, Map<String, long[]> counts) {
        final Context me = this;
        LinearLayout row = Ui.row(me);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(new LuxFrame(14, 8));
        int in = Ui.dp(me, 12);
        row.setPadding(in, in, in, in);
        ImageView art = new ImageView(me);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.box(me, Skin.PILL, 8));
        art.setClipToOutline(true);
        row.addView(art, new LinearLayout.LayoutParams(Ui.dp(me, 56), Ui.dp(me, 56)));
        LinearLayout words = Ui.column(me);
        words.setPadding(Ui.dp(me, 12), 0, Ui.dp(me, 8), 0);
        TextView t = Ui.text(me, song.title, Skin.TEXT, 16);
        t.setTypeface(Skin.text(), Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(t);
        if (song.tags != null && !song.tags.isEmpty()) {
            TextView g = Ui.text(me, song.tags, Skin.DIM, 12);
            g.setSingleLine(true);
            g.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(g);
        }
        long[] n = counts == null ? null : counts.get(song.id);
        if (n != null && (n[0] > 0 || n[1] > 0))
            words.addView(Ui.text(me, "▶ " + n[0] + "   ⬇ " + n[1], Skin.ACCENT, 12));
        row.addView(words, Ui.weight());
        if (!own)
            row.addView(Ui.text(me, "▶", Skin.ACCENT, 20));
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (own) {
                    Ui.toast(HammerArtistActivity.this, "Play or manage this song in Library");
                    return;
                }
                startActivity(new Intent(HammerArtistActivity.this, HammerActivity.class)
                                  .putExtra("browse_peer", peer.encode())
                                  .putExtra("song", song.id)
                                  .putExtra("refresh", true));
            }
        });
        cover(song, art);
        return row;
    }
    private void cover(final Hammer.Song song, final ImageView art) {
        if (coversAsked++ >= COVERS_FETCHED)
            return;
        worker.execute(new Runnable() {
            public void run() {
                try {
                    HammerArt attachment = own ? store.publicObjects.cover(song.id, "") : Hammer.cover(peer, song, job);
                    if (attachment == null)
                        return;
                    final Bitmap bitmap = picture(own ? store.publicObjects.localImage(attachment.image)
                                                      : Hammer.image(peer, song.id, attachment.image, false, job));
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!closed)
                                art.setImageBitmap(bitmap);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }
    private void albums(List<HammerAlbum> albums, List<Hammer.Song> list) {
        songs.addView(heading("ALBUMS"));
        LinearLayout shelf = Ui.row(this);
        for (final HammerAlbum a : albums) {
            LinearLayout tile = Ui.column(this);
            tile.setPadding(0, 0, Ui.dp(this, 14), 0);
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(Ui.box(this, Skin.PILL, 10));
            art.setClipToOutline(true);
            tile.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
            for (Hammer.Song s : list)
                if (s.id.equals(a.cover)) {
                    cover(s, art);
                    break;
                }
            TextView t = Ui.text(this, a.title, Skin.TEXT, 13);
            t.setTypeface(Skin.text(), Typeface.BOLD);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(0, Ui.dp(this, 6), 0, 0);
            tile.addView(t, new LinearLayout.LayoutParams(Ui.dp(this, 96), -2));
            tile.addView(Ui.text(this, a.songs.size() + " songs", Skin.DIM, 11));
            tile.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (own) {
                        Ui.toast(HammerArtistActivity.this, "Play or manage this album in Library");
                        return;
                    }
                    try {
                        startActivity(new Intent(HammerArtistActivity.this, HammerActivity.class)
                                          .putExtra("browse_peer", peer.encode())
                                          .putExtra("album", a.id())
                                          .putExtra("refresh", true));
                    } catch (Exception unreadable) {
                    }
                }
            });
            shelf.addView(tile);
        }
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(shelf);
        songs.addView(h);
    }
    /**
     * Your week: what this phone served since about a week ago. A note of the counts is kept here each day the page
     * opens, so nothing about listeners is stored and nothing leaves the phone.
     */
    private void week(Map<String, long[]> now, List<Hammer.Song> list) {
        SharedPreferences prefs = getSharedPreferences("artist-week", 0);
        long today = System.currentTimeMillis() / 86400000L;
        StringBuilder note = new StringBuilder();
        for (Hammer.Song s : list) {
            long[] n = now.get(s.id);
            if (n != null)
                note.append(s.id).append(' ').append(n[0]).append(' ').append(n[1]).append('\n');
        }
        SharedPreferences.Editor edit = prefs.edit();
        if (!prefs.contains("day." + today))
            edit.putString("day." + today, note.toString());
        long weekOld = -1, oldest = today;  // the latest note at least a week old, or else the oldest there is
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("day."))
                continue;
            long day = Long.parseLong(key.substring(4));
            if (today - day > 60) {
                edit.remove(key);
                continue;
            }
            oldest = Math.min(oldest, day);
            if (today - day >= 7)
                weekOld = Math.max(weekOld, day);
        }
        long baseDay = weekOld >= 0 ? weekOld : oldest;
        edit.apply();
        Map<String, long[]> before = new HashMap<String, long[]>();
        for (String line : prefs.getString("day." + baseDay, "").split("\n")) {
            String[] f = line.split(" ");
            if (f.length == 3)
                before.put(f[0], new long[] {Long.parseLong(f[1]), Long.parseLong(f[2])});
        }
        long plays = 0, downloads = 0, rise = 0;
        String rising = null;
        for (Hammer.Song s : list) {
            long[] n = now.get(s.id), b = before.get(s.id);
            if (n == null)
                continue;
            long p = n[0] - (b == null ? 0 : b[0]);
            plays += p;
            downloads += n[1] - (b == null ? 0 : b[1]);
            if (p > rise) {
                rise = p;
                rising = s.title;
            }
        }
        songs.addView(heading("YOUR WEEK"));
        LinearLayout card = plate();
        if (baseDay == today)
            card.addView(Ui.text(this, "Counting starts today. Come back in a few days to see what's moving.", Skin.TEXT, 14));
        else {
            String since = new java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(baseDay * 86400000L));
            card.addView(Ui.text(this, "▶ " + plays + " plays   ⬇ " + downloads + " downloads since " + since, Skin.TEXT, 15));
            if (rising != null)
                card.addView(Ui.text(this, "Rising: " + rising + ", +" + rise + " plays", Skin.ACCENT, 13), Ui.gap(this, 4));
        }
        songs.addView(card);
    }
    /** The page as a link and a QR code. It names the artist, and a network identity that has the page, no address. */
    private void share() {
        final String shown = shownName.getText().toString();
        if (own && profile == null) {
            Ui.toast(this, "Publish your page first");
            return;
        }
        work("Making your link", new Work() {
            public void run() throws Exception {
                Hammer.Card host = own ? Hammer.Card.p2p(HammerAndroid.network(HammerArtistActivity.this), HammerAndroid.pin(), "")
                                 : peer.isP2p() ? publicCard(peer) : null;
                final String url = "killayue://a/" + artist + (host == null ? "" : "?via=" + Uri.encode(host.encode()));
                final Bitmap qr = qr(url);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!closed)
                            showShare(shown, url, qr);
                    }
                });
            }
        });
    }
    private static Bitmap qr(String text) {
        io.nayuki.qrcodegen.QrCode q = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int scale = 6, side = (q.size + 8) * scale;
        int[] pixels = new int[side * side];
        for (int y = 0; y < side; y++)
            for (int x = 0; x < side; x++)
                pixels[y * side + x] = q.getModule(x / scale - 4, y / scale - 4) ? 0xff000000 : 0xffffffff;
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888);
    }
    private void showShare(String shown, final String url, Bitmap qr) {
        LinearLayout v = Ui.column(this);
        ImageView code = new ImageView(this);
        code.setImageBitmap(qr);
        v.addView(code, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
        TextView words = Ui.text(this, "Point a phone's camera here, or send the link. It opens this page in Killa YuE.", Skin.DIM, 13);
        words.setPadding(0, Ui.dp(this, 10), 0, 0);
        v.addView(words);
        new Lux.Builder(this)
            .setTitle(shown)
            .setView(v)
            .setPositiveButton("Send link", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                                                           .putExtra(Intent.EXTRA_TEXT, url), "Share artist page"));
                }
            })
            .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("Artist page", url));
                    Ui.toast(HammerArtistActivity.this, "Link copied");
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }
    private TextView heading(String name) {
        TextView h = Ui.label(this, name);
        h.setTextColor(Skin.ACCENT);
        h.setLetterSpacing(0.12f);
        h.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 4));
        return h;
    }
    private void follow() {
        work("Following artist", new Work() {
            public void run() throws Exception {
                Hammer.require(Hammer.digest(artist), "Invalid artist");
                String label = profile == null ? "Artist" : profile.name;
                if (label.length() > 80) {
                    int end = Character.isHighSurrogate(label.charAt(79)) ? 79 : 80;
                    label = label.substring(0, end);
                }
                store.follow(new HammerStore.Peer(peer, label, false));
                HammerFollowService.prefs(HammerArtistActivity.this)
                    .edit()
                    .putString("artist." + artist, profile == null ? "Artist" : profile.name)
                    .apply();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        followButton.setText("Following");
                        NotificationManager nm =
                            (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                        if (nm.areNotificationsEnabled())
                            HammerFollowService.start(HammerArtistActivity.this);
                        else
                            startActivity(
                                new Intent(
                                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,
                                              getPackageName()));
                    }
                });
            }
        });
    }
    private boolean ready() {
        if (store == null || busy) {
            Ui.toast(this, "Please wait for the current operation");
            return false;
        }
        return true;
    }
    private Button chip(String label, final Runnable action) {
        Button b = Ui.chip(this, label);
        b.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                if (ready())
                    action.run();
            }
        });
        return b;
    }
    /** Paint with AI: a portrait from the artist's own words, on this phone, then shown for approval. */
    private void askPaint() {
        if (MainActivity.jobBusy) {
            Ui.toast(this, "A song is being made. Paint your portrait when it is done.");
            return;
        }
        if (!Covers.anyReady(this)) {
            new Lux.Builder(this).setTitle("One more download needed")
                .setMessage("Portraits are painted on this phone by the cover model. Get it on the Settings tab, under Downloads.")
                .setPositiveButton("OK", null).show();
            return;
        }
        final EditText what = Ui.edit(this, "e.g. silhouette in gold light, smoke, vintage microphone", 3);
        String seed = tags.getText().toString().trim();
        if (seed.length() > 0) what.setText(seed);
        new Lux.Builder(this).setTitle("Paint your portrait")
            .setMessage("Describe the picture. " + Covers.howLong(this))
            .setView(what)
            .setPositiveButton("Paint", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    final String words = (what.getText().toString().trim() + ", " + name.getText().toString().trim())
                                             .replaceAll("^, |, $", "");
                    final int variation = paintings++;
                    work("Painting your portrait", new Work() {
                        public void run() throws Exception {
                            selectImage(Covers.portrait(getApplicationContext(), words, variation));
                        }
                    });
                }
            })
            .setNegativeButton("Cancel", null).show();
    }
    private void add(String label, final Runnable action) {
        Button b = Ui.button(this, label, false);
        editor.addView(b, Ui.gap(this, 8));
        b.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                if (store == null || busy) {
                    Ui.toast(HammerArtistActivity.this, "Please wait for the current operation");
                    return;
                }
                action.run();
            }
        });
    }
    private void work(final String title, final Work action) {
        if (busy || closed)
            return;
        busy = true;
        status.setText(title);
        status.setVisibility(android.view.View.VISIBLE);
        worker.execute(new Runnable() {
            public void run() {
                String error = null;
                try {
                    action.run();
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String result = error == null ? (title.equals("Loading artist") || title.equals("Making your link") ? "" : title + " — done")
                                                    : title + ": " + error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        if (!closed)
                            status.setText(result);
                        status.setVisibility(result.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                    }
                });
            }
        });
    }
    private Bitmap picture(byte[] bytes) throws Exception {
        File f = File.createTempFile("artist-", ".image", getCacheDir());
        try {
            HammerStore.atomic(f, bytes);
            return ((HammerCodec)HammerMedia.codec).imagePreview(f).bitmap;
        } finally {
            f.delete();
        }
    }
    private void selectImage(File f) throws Exception {
        HammerCodec.Image clean = ((HammerCodec)HammerMedia.codec).imagePreview(f);
        image = store.publicObjects.putImage(clean.jpeg);
        final Bitmap bitmap = clean.bitmap;
        runOnUiThread(new Runnable() {
            public void run() {
                if (!closed)
                    portrait.setImageBitmap(Ui.medallion(HammerArtistActivity.this, bitmap, name.getText().toString(), 148));
            }
        });
    }
    private void chooseCover() {
        work("Reading Library covers", new Work() {
            public void run() throws Exception {
                final MainActivity.LibData data = MainActivity.loadLibrary(HammerArtistActivity.this);
                final List<File> covers = new ArrayList<File>();
                final List<String> titles = new ArrayList<String>();
                for (org.json.JSONObject m : data.loaded)
                    if (m != null) {
                        File f = Covers.coverFor(HammerArtistActivity.this, m);
                        if (f.isFile()) {
                            covers.add(f);
                            titles.add(m.optString("_title", "Untitled"));
                        }
                    }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        new Lux.Builder(HammerArtistActivity.this)
                            .setTitle("Choose a song cover")
                            .setItems(titles.toArray(new String[0]),
                                      new DialogInterface.OnClickListener() {
                                          public void onClick(DialogInterface d, final int n) {
                                              work("Preparing profile image", new Work() {
                                                  public void run() throws Exception {
                                                      selectImage(covers.get(n));
                                                  }
                                              });
                                          }
                                      })
                            .setNegativeButton("Cancel", null)
                            .show();
                    }
                });
            }
        });
    }
    private void publish() {
        final String n = name.getText().toString(), b = bio.getText().toString(),
                     t = tags.getText().toString();
        work("Publishing artist profile", new Work() {
            public void run() throws Exception {
                HammerProfile p = new HammerProfile();
                p.name = n;
                p.bio = b;
                p.tags = t;
                p.image = image;
                HammerProfile old = store.publicObjects.own();
                p.revision = Math.max(System.currentTimeMillis(), old == null ? 1 : old.revision + 1);
                p.sign(HammerAndroid.identity());
                store.publicObjects.publishProfile(p);
                profile = p;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        showIdentity(n, b, t);
                        editor.setVisibility(android.view.View.GONE);
                        HammerService.startHost(HammerArtistActivity.this);
                    }
                });
            }
        });
    }
    @Override
    protected void onActivityResult(int request, int result, final Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null)
            return;
        work("Preparing profile image", new Work() {
            public void run() throws Exception {
                File f = File.createTempFile("profile-", ".image", getCacheDir());
                try {
                    try (InputStream in = getContentResolver().openInputStream(data.getData());
                         FileOutputStream out = new FileOutputStream(f)) {
                        Hammer.require(in != null, "Could not open image");
                        byte[] b = new byte[8192];
                        int n, total = 0;
                        while ((n = in.read(b)) != -1) {
                            total += n;
                            Hammer.require(total <= 16 * 1024 * 1024, "Image exceeds 16 MiB");
                            out.write(b, 0, n);
                        }
                    }
                    selectImage(f);
                } finally {
                    f.delete();
                }
            }
        });
    }
    @Override
    protected void onDestroy() {
        closed = true;
        job.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
}
