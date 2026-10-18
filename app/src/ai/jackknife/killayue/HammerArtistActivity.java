package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** An artist page is native UI built from signed plain text and bounded artwork, never a web page. */
public final class HammerArtistActivity extends Activity {
    private LinearLayout body, songs;
    private EditText name, bio, tags;
    private TextView status, shownName, shownTags, shownBio;
    private int paintings;
    private ImageView portrait;
    private String image = "", artist;
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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Skin.background(this));
        scroll.setFitsSystemWindows(true);
        body = Ui.column(this);
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 24));
        scroll.addView(body);
        setContentView(scroll);
        artist = getIntent().getStringExtra("artist");
        final boolean own = getIntent().getStringExtra("peer") == null;
        body.addView(Ui.title(this, own ? "Your artist page" : "Artist"));
        // the portrait: a gold medallion, centred, as the artists strip in Discover shows it
        portrait = new ImageView(this);
        portrait.setImageBitmap(Ui.medallion(this, null, "", 148));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(this, 148), Ui.dp(this, 148));
        plp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        plp.topMargin = Ui.dp(this, 12);
        body.addView(portrait, plp);
        shownName = Ui.text(this, "", Skin.ACCENT, Skin.SCRIPT_TITLES ? 34 : 24);
        shownName.setTypeface(Skin.script(), Skin.SCRIPT_TITLES ? Typeface.NORMAL : Typeface.BOLD);
        shownName.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        shownName.setShadowLayer(8, 0, 2, 0xCC000000);
        body.addView(shownName, Ui.gap(this, 8));
        shownTags = Ui.text(this, "", Skin.DIM, 13);
        shownTags.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        body.addView(shownTags);
        status = Ui.text(this, "Loading artist…", Skin.DIM, 12);
        status.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, Ui.dp(this, 6), 0, 0);
        body.addView(status);
        name = Ui.edit(this, "Artist name", 1);
        bio = Ui.edit(this, "About your music", 5);
        tags = Ui.edit(this, "Genres and search tags", 2);
        if (own) {
            LinearLayout pics = Ui.row(this);
            pics.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
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
            h.setPadding(0, Ui.dp(this, 12), 0, 0);
            h.addView(pics);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
            hlp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            body.addView(h, hlp);
            body.addView(Ui.label(this, "Artist name"));
            body.addView(name);
            body.addView(Ui.label(this, "About your music"));
            body.addView(bio);
            body.addView(Ui.label(this, "Genres and search tags"));
            body.addView(tags);
            Button go = Ui.button(this, "Publish my page", true);
            body.addView(go, Ui.gap(this, 16));
            go.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
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
            body.addView(note);
        } else {
            shownBio = Ui.text(this, "", Skin.TEXT, 15);
            shownBio.setPadding(0, Ui.dp(this, 14), 0, 0);
            body.addView(shownBio);
            Button follow = Ui.button(this, "Follow & get new songs", true);
            body.addView(follow, Ui.gap(this, 16));
            follow.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    if (ready())
                        follow();
                }
            });
        }
        songs = Ui.column(this);
        body.addView(songs);
        work("Loading artist", new Work() {
            public void run() throws Exception {
                store = HammerAndroid.store(HammerArtistActivity.this);
                List<Hammer.Song> tracks;
                if (own) {
                    profile = store.publicObjects.own();
                    artist = HammerAndroid.pin();
                    tracks = store.catalog("");
                } else {
                    peer = Hammer.Card.parse(getIntent().getStringExtra("peer"));
                    Hammer.require(Hammer.digest(artist), "Invalid artist identity");
                    for (HammerProfile p : Hammer.profiles(peer, job))
                        if (p.id().equals(artist))
                            profile = p;
                    tracks = Hammer.catalog(peer, job);
                }
                final List<Hammer.Song> selected = new ArrayList<Hammer.Song>();
                for (Hammer.Song s : tracks)
                    if (Hammer.hash(Base64.getDecoder().decode(s.key)).equals(artist))
                        selected.add(s);
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
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        if (p != null) {
                            name.setText(p.name);
                            bio.setText(p.bio);
                            tags.setText(p.tags);
                        } else if (!own)
                            name.setText("Artist " + artist.substring(0, 12));
                        String shown = name.getText().toString();
                        shownName.setText(shown.isEmpty() ? (own ? "Unnamed artist" : "") : shown);
                        shownTags.setText(tags.getText().toString());
                        if (shownBio != null)
                            shownBio.setText(bio.getText().toString());
                        portrait.setImageBitmap(Ui.medallion(HammerArtistActivity.this, avatar, shown, 148));
                        if (!selected.isEmpty()) {
                            TextView h = Ui.label(HammerArtistActivity.this, (own ? "YOUR PUBLISHED SONGS" : "SONGS"));
                            h.setTextColor(Skin.ACCENT);
                            h.setLetterSpacing(0.12f);
                            h.setPadding(0, Ui.dp(HammerArtistActivity.this, 22), 0, Ui.dp(HammerArtistActivity.this, 4));
                            songs.addView(h);
                        } else if (own)
                            songs.addView(Ui.text(HammerArtistActivity.this,
                                "No published songs yet. In the Library, tap a song's sharing line and choose Public.", Skin.DIM, 13),
                                Ui.gap(HammerArtistActivity.this, 18));
                        for (final Hammer.Song song : selected) {
                            final Context me = HammerArtistActivity.this;
                            final LinearLayout row = Ui.row(me);
                            row.setBackground(new LuxFrame(14, 8));
                            int in = Ui.dp(me, 12);
                            row.setPadding(in, in, in, in);
                            final ImageView art = new ImageView(me);
                            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            art.setBackground(Ui.box(me, Skin.PILL, 8));
                            art.setClipToOutline(true);
                            row.addView(art, new LinearLayout.LayoutParams(Ui.dp(me, 64), Ui.dp(me, 64)));
                            LinearLayout words = Ui.column(me);
                            words.setPadding(Ui.dp(me, 12), 0, 0, 0);
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
                            row.addView(words, Ui.weight());
                            if (!own)
                                row.addView(Ui.text(me, "▶", Skin.ACCENT, 20));
                            final android.view.View b = row;
                            b.setOnClickListener(new android.view.View.OnClickListener() {
                                public void onClick(android.view.View v) {
                                    if (own) {
                                        Ui.toast(HammerArtistActivity.this,
                                                 "Play or manage this song in Library");
                                        return;
                                    }
                                    startActivity(new Intent(HammerArtistActivity.this, HammerActivity.class)
                                                      .putExtra("browse_peer", peer.encode())
                                                      .putExtra("song", song.id)
                                                      .putExtra("refresh", true));
                                }
                            });
                            songs.addView(row, Ui.gap(me, 8));
                            if (songs.getChildCount() <= 21)
                                worker.execute(new Runnable() {
                                    public void run() {
                                        try {
                                            HammerArt attachment =
                                                own ? store.publicObjects.cover(song.id, "")
                                                    : Hammer.cover(peer, song, job);
                                            if (attachment == null)
                                                return;
                                            final Bitmap bitmap =
                                                picture(own ? store.publicObjects.localImage(attachment.image)
                                                            : Hammer.image(peer, song.id, attachment.image,
                                                                           false, job));
                                            runOnUiThread(new Runnable() {
                                                public void run() {
                                                    if (!closed) {
                                                        art.setImageBitmap(bitmap);
                                                    }
                                                }
                                            });
                                        } catch (Exception ignored) {
                                        }
                                    }
                                });
                        }
                    }
                });
            }
        });
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
                .setMessage("Portraits are painted on this phone by the cover model. Get it on the Engine tab, under Downloads.")
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
        body.addView(b, Ui.gap(this, 8));
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
        worker.execute(new Runnable() {
            public void run() {
                String error = null;
                try {
                    action.run();
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String result = error == null ? title + " — done" : title + ": " + error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        if (!closed)
                            status.setText(result);
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
                        if (!closed)
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
