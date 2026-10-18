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
    private TextView status;
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
        super.onCreate(saved);
        Skin.load(this);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(24, 20, 24, 24);
        body.setBackgroundColor(Skin.DECK);
        scroll.addView(body);
        setContentView(scroll);
        body.addView(Ui.title(this, "Artist profile"));
        status = Ui.text(this, "Loading artist…", Skin.DIM, 14);
        body.addView(status);
        portrait = new ImageView(this);
        portrait.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        body.addView(portrait, new LinearLayout.LayoutParams(-1, Ui.dp(this, 220)));
        name = Ui.edit(this, "Artist name", 1);
        body.addView(name);
        bio = Ui.edit(this, "About your music", 5);
        body.addView(bio);
        tags = Ui.edit(this, "Genres and search tags", 2);
        body.addView(tags);
        artist = getIntent().getStringExtra("artist");
        final boolean own = getIntent().getStringExtra("peer") == null;
        if (own) {
            add("Choose profile image", new Runnable() {
                public void run() {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                               .setType("image/*")
                                               .addCategory(Intent.CATEGORY_OPENABLE),
                                           1);
                }
            });
            add("Use a generated song cover", new Runnable() {
                public void run() { chooseCover(); }
            });
            add("Publish artist profile", new Runnable() {
                public void run() { publish(); }
            });
            add("Withdraw profile", new Runnable() {
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
            body.addView(Ui.text(this,
                                 "Your name, bio, tags and selected image become public when you publish. "
                                     + "Share songs individually from Library. Your generated covers are " +
                                       "reused without generating them again.",
                                 Skin.DIM, 14));
        } else {
            name.setEnabled(false);
            bio.setEnabled(false);
            tags.setEnabled(false);
            add("Follow artist & notify", new Runnable() {
                public void run() {
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
            });
        }
        songs = new LinearLayout(this);
        songs.setOrientation(LinearLayout.VERTICAL);
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
                        if (avatar != null)
                            portrait.setImageBitmap(avatar);
                        songs.addView(Ui.text(HammerArtistActivity.this, "Published songs", Skin.TEXT, 20));
                        for (final Hammer.Song song : selected) {
                            final LinearLayout row = new LinearLayout(HammerArtistActivity.this);
                            row.setOrientation(LinearLayout.VERTICAL);
                            final ImageView art = new ImageView(HammerArtistActivity.this);
                            art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                            row.addView(art, new LinearLayout.LayoutParams(
                                                 -1, Ui.dp(HammerArtistActivity.this, 160)));
                            Button b = Ui.button(HammerArtistActivity.this, song.title, false);
                            row.addView(b);
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
                            songs.addView(row);
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
                                                    if (!closed)
                                                        art.setImageBitmap(bitmap);
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
    private void add(String label, final Runnable action) {
        Button b = Ui.button(this, label, false);
        body.addView(b);
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
                    portrait.setImageBitmap(bitmap);
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
