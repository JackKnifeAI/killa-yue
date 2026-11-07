package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Library owns publication and the single persistent audio copy. Nothing publishes on import. */
final class HammerLibrary {
    static final int IMPORT_AUDIO = 847, IMPORT_FOLDER = 848;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    interface Work {
        void run() throws Exception;
    }
    static void work(final Activity a, final String title, final Work action) {
        Ui.toast(a, title);
        worker.execute(new Runnable() {
            public void run() {
                String error = null;
                try {
                    action.run();
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String message = error == null ? title + " — done" : title + ": " + error;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing() || a.isDestroyed())
                            return;
                        Ui.toast(a, message);
                        if (a instanceof MainActivity)
                            ((MainActivity)a).refreshLibrary();
                    }
                });
            }
        });
    }
    static File audio(Context c, JSONObject meta) throws Exception {
        String name = meta.optString("_audio");
        Hammer.require(!name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains(".."),
                       "Invalid Library recording");
        File root = Store.library(c).getCanonicalFile(), f = new File(root, name);
        Hammer.require(!java.nio.file.Files.isSymbolicLink(f.toPath()) &&
                           f.getCanonicalFile().getParentFile().equals(root),
                       "Recording is outside Library");
        return f;
    }
    static android.graphics.Bitmap thumbnail(Context c, JSONObject meta) {
        File f = Covers.coverFor(c, meta);
        if (!meta.has("_hammer_id"))
            return Covers.load(f, 8);
        if (!f.isFile())
            return null;
        try {
            HammerAndroid.store(c);
            android.graphics.Bitmap source = ((HammerCodec)HammerMedia.codec).imagePreview(f).bitmap;
            int width = Math.min(96, source.getWidth());
            int height = Math.max(1, source.getHeight() * width / source.getWidth());
            android.graphics.Bitmap small =
                android.graphics.Bitmap.createScaledBitmap(source, width, height, true);
            if (small != source)
                source.recycle();
            return small;
        } catch (Exception invalidArtwork) {
            return null;
        }
    }
    /** Said wherever a song is shared: hosting is direct, so listeners see this phone's address. */
    static final String ADDRESS_VISIBLE = "People who connect to you can see your internet address.";
    static String state(Context c, JSONObject meta) {
        String hidden = c.getSharedPreferences("hammer-hidden", 0).getString(meta.optString("_audio"), null);
        if (hidden != null)
            return hidden;
        try {
            File f = audio(c, meta);
            for (HammerStore.Hosted h : HammerAndroid.store(c).hosted())
                if (h.source.getCanonicalFile().equals(f))
                    return h.publicSong ? "Public" : "Private circle";
        } catch (Exception ignored) {
        }
        return "Only on this device";
    }
    static void sharing(final Activity a, final JSONObject meta) {
        final LinearLayout view = new LinearLayout(a);
        view.setOrientation(LinearLayout.VERTICAL);
        final Spinner visibility = new Spinner(a);
        visibility.setAdapter(new ArrayAdapter<String>(
            a, android.R.layout.simple_spinner_dropdown_item,
            new String[] {"Only on this device", "Public network", "Private circle", "Hidden artist (experimental)"}));
        String old = state(a, meta);
        visibility.setSelection(old.equals("Public") ? 1 : old.equals("Private circle") ? 2 : old.startsWith("Hidden") ? 3 : 0);
        view.addView(visibility);
        final EditText hiddenName = Ui.edit(a, "Hidden artist name (never your own)", 1);
        hiddenName.setText(a.getSharedPreferences("hammer-hidden", 0).getString("last-name", ""));
        final TextView hiddenText = Ui.text(a, HIDDEN_TEXT, Skin.DIM, 12);
        view.addView(hiddenName);
        view.addView(hiddenText);
        visibility.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, android.view.View v, int at, long id) {
                int show = at == 3 ? android.view.View.VISIBLE : android.view.View.GONE;
                hiddenName.setVisibility(show);
                hiddenText.setVisibility(show);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        final EditText tags = Ui.edit(a, "Public style and tags", 2);
        tags.setText(meta.optString("_hammer_tags"));
        view.addView(tags);
        final CheckBox copies = new CheckBox(a);
        copies.setText("Allow downloads and hosting by others");
        copies.setTextColor(Skin.TEXT);
        copies.setChecked(true);
        view.addView(copies);
        try {
            File f = audio(a, meta);
            for (HammerStore.Hosted h : HammerAndroid.store(a).hosted())
                if (h.source.getCanonicalFile().equals(f)) {
                    tags.setText(h.song.tags);
                    copies.setChecked(h.song.reshare);
                }
        } catch (Exception ignored) {
        }
        if (meta.has("_hammer_id")) {
            copies.setEnabled(false);
            tags.setEnabled(false);
            copies.setText("Original publisher's download permission is preserved");
        }
        new Lux.Builder(a)
            .setTitle("Sharing · " + meta.optString("_title", "Untitled"))
            .setMessage(
                "Share this recording and its cover. Prompts, projects and other files stay on your device. "
                + "Publishing starts hosting. Copies already downloaded cannot be recalled. " + ADDRESS_VISIBLE)
            .setView(view)
            .setNeutralButton(hiddenSongs(a.getSharedPreferences("hammer-hidden", 0)).length() == 0 ? null : "Hidden station",
                              new DialogInterface.OnClickListener() {
                                  public void onClick(DialogInterface d, int which) { hiddenStation(a); }
                              })
            .setPositiveButton(
                "Apply",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final int scope = visibility.getSelectedItemPosition();
                        final boolean allow = copies.isChecked();
                        final String labels = tags.getText().toString();
                        if (scope == 3) {
                            hidden(a, meta, hiddenName.getText().toString().trim(), labels);
                            return;
                        }
                        work(a, "Updating song sharing", new Work() {
                            public void run() throws Exception {
                                HammerStore store = HammerAndroid.store(a);
                                File file = audio(a, meta);
                                HammerStore.Hosted existing = null;
                                for (HammerStore.Hosted h : store.hosted())
                                    if (h.source.getCanonicalFile().equals(file))
                                        existing = h;
                                if (scope == 0) {
                                    if (existing != null) {
                                        store.unpublish(existing.song.id);
                                        HammerService.disconnect();
                                    }
                                    return;
                                }
                                Hammer.Song song = null;
                                if (meta.has("_hammer_id")) {
                                    for (Hammer.Song s : store.downloads())
                                        if (s.id.equals(meta.optString("_hammer_id")))
                                            song = s;
                                    Hammer.require(song != null, "Original publisher record missing");
                                    store.rehost(song, scope == 1);
                                } else {
                                    song = store.publish(file, meta.optString("_title", "Untitled"), labels,
                                                         allow, scope == 1, HammerAndroid.identity());
                                    File cover = Covers.coverFor(a, meta);
                                    if (cover.isFile()) {
                                        byte[] image = ((HammerCodec)HammerMedia.codec).image(cover);
                                        HammerArt art = new HammerArt();
                                        art.song = song.id;
                                        art.image = store.publicObjects.putImage(image);
                                        art.sign(HammerAndroid.identity());
                                        store.publicObjects.cover(song, art);
                                    }
                                    attachLyrics(store, song, file);
                                    if (scope == 1)
                                        Remixes.shared(a, song, meta);  // a public remix links to its original
                                }
                                if (existing != null && existing.publicSong && scope == 2)
                                    HammerService.disconnect();
                                a.runOnUiThread(new Runnable() {
                                    public void run() { HammerService.startHost(a); }
                                });
                            }
                        });
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    /** Said where a song can be published as a hidden artist. */
    static final String HIDDEN_TEXT =
        "Experimental, not yet reviewed by outside security experts. The song goes through the mixnet to three "
        + "helpers, which host it under this name; no helper and no listener learns it came from this phone, and the "
        + "phone never serves it itself. Choose a song you never shared from this phone, and a name that is not "
        + "yours. At most 32 MB (MP3 for long songs). Helpers choose what they keep: if every one drops it, it is gone "
        + "from the network and only your copy remains. Your provider can see that you uploaded a lot through the "
        + "mixnet, not to whom.";

    /** Publishes a Library song as a hidden artist, in the background; its sharing line shows how far it got. */
    static void hidden(final Activity a, final JSONObject meta, final String name, final String labels) {
        final Context app = a.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences("hammer-hidden", 0);
        final String key = meta.optString("_audio");
        if (name.isEmpty() || !Hammer.displayText(name)) {
            Ui.toast(a, "Give the hidden artist a name");
            return;
        }
        if (meta.has("_hammer_id")) {
            Ui.toast(a, "Only your own songs can be published hidden");
            return;
        }
        prefs.edit().putString("last-name", name).putString(key, "Hidden · preparing").apply();
        work(a, "Publishing as " + name, new Work() {
            public void run() throws Exception {
                try {
                    File file = audio(app, meta);
                    for (HammerStore.Hosted h : HammerAndroid.store(app).hosted())
                        Hammer.require(!h.source.getCanonicalFile().equals(file),
                                       "This song is shared from this phone already, which would link it to the "
                                       + "hidden name. Stop sharing it first, or choose another song");
                    HammerAndroid.network(app);
                    String why = HammerMix.unready();
                    for (int i = 0; i < 120 && why != null; i++) {  // the mix nodes are found in the first minute
                        Thread.sleep(500);
                        why = HammerMix.unready();
                    }
                    Hammer.require(why == null, why + " Nothing was sent.");
                    HammerHidden.Prepared p = HammerHidden.prepare(
                        file, new File(app.getFilesDir(), "hidden"), meta.optString("_title", "Untitled"), labels, name,
                        HammerAndroid.hiddenIdentity(name), new Hammer.Job());
                    HammerHidden.Progress shown = new HammerHidden.Progress() {
                        public void progress(int done, int total, List<String> hosting) {
                            prefs.edit().putString(key, "Hidden · uploading " + (100 * done / Math.max(1, total)) + "%")
                                .apply();
                        }
                    };
                    // the helpers that hold this name's other songs first, so one helper can carry its station
                    List<String> before = hiddenHelpers(prefs, name);
                    List<String> hosts = before.size() >= HammerHidden.COPIES
                        ? HammerHidden.publish(p, before, shown, new Hammer.Job()) : new ArrayList<String>();
                    if (hosts.size() < HammerHidden.COPIES)
                        hosts = HammerHidden.publish(p, KillaNet.mixNodes(), shown, new Hammer.Job());
                    prefs.edit().putString(key, "Hidden · hosted by " + hosts.size() +
                                                (hosts.size() == 1 ? " helper" : " helpers")).apply();
                    rememberHidden(prefs, name, p, hosts);
                    Hammer.require(hosts.size() >= HammerHidden.COPIES,
                                   "only " + hosts.size() + " of " + HammerHidden.COPIES + " helpers took it; publish "
                                   + "again later for more copies");
                } catch (Exception e) {
                    if (!prefs.getString(key, "").startsWith("Hidden · hosted"))
                        prefs.edit().remove(key).apply(); // nothing hosted: the song is only on this device
                    throw e;
                } finally {
                    Protection.refresh(app);
                }
            }
        });
    }
    /** The hidden songs sent from this phone: name, song, title, length and the helpers holding each. */
    private static JSONArray hiddenSongs(SharedPreferences prefs) {
        try {
            return new JSONArray(prefs.getString("songs", "[]"));
        } catch (Exception unreadable) {
            return new JSONArray();
        }
    }
    private static synchronized void rememberHidden(SharedPreferences prefs, String name, HammerHidden.Prepared p,
                                                    List<String> hosts) throws Exception {
        JSONArray old = hiddenSongs(prefs), next = new JSONArray();
        for (int i = 0; i < old.length(); i++)
            if (!old.getJSONObject(i).optString("id").equals(p.song.id))
                next.put(old.getJSONObject(i));
        JSONObject s = new JSONObject();
        s.put("name", name).put("id", p.song.id).put("title", p.song.title)
            .put("ms", HammerMedia.codec.frames(p.file) * 1000 / HammerMedia.RATE).put("helpers", new JSONArray(hosts));
        next.put(s);
        prefs.edit().putString("songs", next.toString()).apply();
    }
    /** Every helper holding a song of this hidden name, the ones holding most first. */
    private static List<String> hiddenHelpers(SharedPreferences prefs, String name) {
        final Map<String, Integer> held = new HashMap<String, Integer>();
        JSONArray songs = hiddenSongs(prefs);
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null || !name.equals(s.optString("name")))
                continue;
            JSONArray h = s.optJSONArray("helpers");
            for (int k = 0; h != null && k < h.length(); k++) {
                Integer n = held.get(h.optString(k));
                held.put(h.optString(k), n == null ? 1 : n + 1);
            }
        }
        List<String> out = new ArrayList<String>(held.keySet());
        Collections.sort(out, new Comparator<String>() {
            public int compare(String x, String y) { return held.get(y) - held.get(x); }
        });
        return out;
    }
    /**
     * A station under a hidden name, carried only by helpers: the songs of that name the best-stocked helper holds,
     * signed with the hidden key and handed through the mixnet to every helper that holds them all. This phone never
     * hosts it, so nobody learns where it comes from.
     */
    static void hiddenStation(final Activity a) {
        final SharedPreferences prefs = a.getApplicationContext().getSharedPreferences("hammer-hidden", 0);
        JSONArray songs = hiddenSongs(prefs);
        final List<String> names = new ArrayList<String>();
        for (int i = 0; i < songs.length(); i++) {
            String n = songs.optJSONObject(i) == null ? "" : songs.optJSONObject(i).optString("name");
            if (!n.isEmpty() && !names.contains(n))
                names.add(n);
        }
        if (names.isEmpty()) {
            Ui.toast(a, "Publish a song as a hidden artist first");
            return;
        }
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        final Spinner who = new Spinner(a);
        who.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, names));
        v.addView(who);
        final EditText title = Ui.edit(a, "Station name", 1);
        final EditText note = Ui.edit(a, "A few words for your listeners (optional)", 2);
        v.addView(title);
        v.addView(note);
        title.setText(names.get(0) + " Radio");
        new Lux.Builder(a)
            .setTitle("Hidden station")
            .setMessage("Experimental. Plays this hidden name's songs like radio, carried only by the helpers that hold "
                        + "them, through the mixnet. Going off air also goes through the mixnet and reaches each helper "
                        + "when it answers.")
            .setView(v)
            .setPositiveButton("Go on air", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    sendHidden(a, prefs, names.get(who.getSelectedItemPosition()), title.getText().toString().trim(),
                               note.getText().toString().trim(), false);
                }
            })
            .setNeutralButton("Go off air", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    sendHidden(a, prefs, names.get(who.getSelectedItemPosition()), "", "", true);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private static void sendHidden(final Activity a, final SharedPreferences prefs, final String name, final String title,
                                   final String note, final boolean stop) {
        work(a, stop ? "Going off air" : "Going on air as " + name, new Work() {
            public void run() throws Exception {
                HammerStation s;
                List<String> helpers;
                String saved = prefs.getString("station:" + name, "");
                if (stop) {
                    Hammer.require(!saved.isEmpty(), name + " isn't on air");
                    s = HammerStation.read(new DataInputStream(new ByteArrayInputStream(
                        android.util.Base64.decode(saved, 0))));
                    s.ends = Math.max(s.started + 1, System.currentTimeMillis());
                    helpers = hiddenHelpers(prefs, name);
                } else {
                    Hammer.require(!title.isEmpty(), "Give your station a name");
                    Hammer.require(note.length() <= HammerStation.NOTE, "Keep your words to 140 letters");
                    List<String> ranked = hiddenHelpers(prefs, name);
                    Hammer.require(!ranked.isEmpty(), "No helper holds " + name + "'s songs");
                    String best = ranked.get(0);
                    s = new HammerStation();
                    s.title = title;
                    s.note = note;
                    JSONArray songs = hiddenSongs(prefs);
                    for (int i = 0; i < songs.length(); i++) {
                        JSONObject o = songs.getJSONObject(i);
                        if (name.equals(o.optString("name")) && o.optJSONArray("helpers") != null &&
                            o.getJSONArray("helpers").toString().contains("\"" + best + "\"") &&
                            s.songs.size() < 25) {
                            s.songs.add(o.getString("id"));
                            s.lengths.add((int)o.getLong("ms"));
                        }
                    }
                    s.started = System.currentTimeMillis();
                    s.ends = s.started + HammerStation.LONGEST;
                    helpers = ranked;
                    if (!saved.isEmpty())
                        s.revision = HammerStation.read(new DataInputStream(new ByteArrayInputStream(
                            android.util.Base64.decode(saved, 0)))).revision;
                }
                s.revision = Math.max(System.currentTimeMillis(), s.revision + 1);
                s.sign(HammerAndroid.hiddenIdentity(name));
                HammerAndroid.network(a.getApplicationContext());
                for (int i = 0; i < 120 && HammerMix.unready() != null; i++)
                    Thread.sleep(500);
                int took = 0;  // on air: helpers that kept it; off air: helpers that answered
                for (String h : helpers)
                    try {
                        if (HammerMix.station(h, s, new Hammer.Job()) || stop)
                            took++;
                    } catch (Exception refusedOrGone) {
                    }
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                s.write(new DataOutputStream(b));
                prefs.edit().putString("station:" + name, android.util.Base64.encodeToString(b.toByteArray(), 2)).apply();
                Hammer.require(took > 0, stop ? "No helper answered through the mixnet. Try again later."
                                              : "No helper took the station through the mixnet. Try again later.");
            }
        });
    }
    static void importAudio(Activity a) {
        Intent i =
            new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE);
        a.startActivityForResult(i, IMPORT_AUDIO);
    }
    static void imported(final MainActivity a, final Uri uri) {
        work(a, "Importing into Library", new Work() {
            public void run() throws Exception { importOne(a, uri); }
        });
    }
    /** Copies one recording into the Library, titled by its own tag or file name; returns its Library record. */
    static JSONObject importOne(final Activity a, final Uri uri) throws Exception {
        {
            {
                String label = "Imported recording";
                try (android.database.Cursor c = a.getContentResolver().query(
                         uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst())
                        label = c.getString(0);
                }
                String lower = label.toLowerCase(Locale.ROOT);
                String ext = lower.endsWith(".wav")    ? ".wav"
                             : lower.endsWith(".flac") ? ".flac"
                             : lower.endsWith(".mp3")  ? ".mp3"
                                                       : "";
                Hammer.require(!ext.isEmpty(), "Choose WAV, FLAC or MP3 audio");
                File root = Store.library(a), tmp = File.createTempFile("import-", ".part", root);
                File target = null;
                boolean saved = false;
                try {
                    try (InputStream in = a.getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(tmp)) {
                        Hammer.require(in != null, "Could not open recording");
                        byte[] b = new byte[65536];
                        long total = 0;
                        int n;
                        while ((n = in.read(b)) != -1) {
                            total += n;
                            Hammer.require(total <= Hammer.MAX_AUDIO, "Recording exceeds 1 GiB");
                            Hammer.require(root.getUsableSpace() > 16 * 1024 * 1024,
                                           "Storage is nearly full");
                            out.write(b, 0, n);
                        }
                        out.getFD().sync();
                    }
                    HammerAndroid.store(a);
                    long frames = HammerMedia.codec.frames(tmp);
                    String base = "import-" + UUID.randomUUID().toString();
                    target = new File(root, base + ext);
                    Hammer.require(tmp.renameTo(target), "Could not save recording");
                    JSONObject m = new JSONObject();
                    m.put("_audio", target.getName());
                    m.put("_title", label.substring(0, label.length() - ext.length()));
                    m.put("_created", System.currentTimeMillis());
                    m.put("_seconds", frames / 48000);
                    m.put("style", "Imported audio");
                    m.put("_external_audio", true);
                    try {  // the title, genre and artist inside the file, when it has them
                        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                        r.setDataSource(target.getPath());
                        String t = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
                        String g = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE);
                        if (t != null && !t.trim().isEmpty())
                            m.put("_title", t.trim());
                        if (g != null && !g.trim().isEmpty())
                            m.put("style", g.trim());
                        r.release();
                    } catch (Exception noTags) {
                    }
                    Store.write(new File(root, base + ".json"), m.toString().getBytes("UTF-8"));
                    saved = true;
                    return m;
                } finally {
                    tmp.delete();
                    if (!saved && target != null)
                        target.delete();
                }
            }
        }
    }
    /** Imports several recordings (a folder, or songs shared from another app), offering to share them too. */
    static void importMany(final MainActivity a, final List<Uri> uris) {
        if (uris.isEmpty()) {
            Ui.toast(a, "No WAV, FLAC or MP3 songs there");
            return;
        }
        new Lux.Builder(a)
            .setTitle(uris.size() == 1 ? "Add this song?" : "Add " + uris.size() + " songs?")
            .setMessage(uris.size() == 1
                            ? "It goes into your Library. Share it on the Killa YuE network too, and anyone can find, "
                              + "play and download it from Discover, with the cover and title inside the file. "
                              + "Share only music you made or may share. " + ADDRESS_VISIBLE
                            : "They go into your Library. Share them on the Killa YuE network too, and anyone can find, "
                              + "play and download them from Discover, with the cover and title inside each file. "
                              + "Share only music you made or may share. " + ADDRESS_VISIBLE)
            .setPositiveButton("Add and share", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { importAll(a, uris, true); }
            })
            .setNeutralButton("Just add", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { importAll(a, uris, false); }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private static void importAll(final MainActivity a, final List<Uri> uris, final boolean share) {
        String count = uris.size() == 1 ? "1 song" : uris.size() + " songs";
        work(a, (share ? "Adding and sharing " : "Adding ") + count, new Work() {
            public void run() throws Exception {
                int done = 0, failed = 0;
                for (Uri u : uris) {
                    try {
                        JSONObject m = importOne(a, u);
                        if (share)
                            sharePublicly(a, m);
                        done++;
                    } catch (Exception e) {
                        failed++;
                        if (String.valueOf(e.getMessage()).contains("at most"))
                            break;  // this phone hosts Hammer.MAX_SONGS songs at most
                    }
                }
                if (share && done > 0)
                    a.runOnUiThread(new Runnable() {
                        public void run() { HammerService.startHost(a); }
                    });
                Hammer.require(failed == 0, done + " added; " + failed + " could not be added");
            }
        });
    }
    /** Publishes a Library song publicly with its cover (the Library's, or the art inside the file) and lyrics. */
    static Hammer.Song sharePublicly(Context c, JSONObject meta) throws Exception {
        HammerStore store = HammerAndroid.store(c);
        File file = audio(c, meta);
        Hammer.Song song = store.publish(file, meta.optString("_title", "Untitled"), meta.optString("style", ""),
                                         true, true, HammerAndroid.identity());
        Remixes.shared(c, song, meta);  // a public remix links to its original
        byte[] image = null;
        File cover = Covers.coverFor(c, meta);
        if (cover.isFile())
            image = ((HammerCodec)HammerMedia.codec).image(cover);
        else
            try {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                r.setDataSource(file.getPath());
                byte[] art = r.getEmbeddedPicture();
                r.release();
                if (art != null)
                    image = jpeg(art);
            } catch (Exception noArt) {
            }
        if (image != null && store.publicObjects.localCover(song.id) == null) {
            HammerArt art = new HammerArt();
            art.song = song.id;
            art.image = store.publicObjects.putImage(image);
            art.sign(HammerAndroid.identity());
            store.publicObjects.cover(song, art);
        }
        attachLyrics(store, song, file);
        return song;
    }
    /** Any picture as a JPEG of at most 512 pixels a side, within HAMMER's artwork limit. */
    static byte[] jpeg(byte[] picture) throws Exception {
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(picture, 0, picture.length);
        Hammer.require(b != null, "Unreadable picture");
        for (int side = 512; side >= 160; side -= 96) {
            float k = Math.min(1f, side / (float)Math.max(b.getWidth(), b.getHeight()));
            android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(
                b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            s.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out);
            if (out.size() <= HammerPublic.IMAGE_LIMIT)
                return out.toByteArray();
        }
        throw new Exception("Picture too large");
    }
    /** The song's timed lyrics (the .lrc beside it), signed and offered with it. */
    static void attachLyrics(HammerStore store, Hammer.Song song, File audio) {
        try {
            String name = audio.getName();
            int dot = name.lastIndexOf('.');
            File lrc = new File(audio.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + ".lrc");
            if (!lrc.isFile() || lrc.length() > HammerLyrics.LIMIT)
                return;
            HammerLyrics l = new HammerLyrics();
            l.song = song.id;
            l.text = new String(Store.read(lrc), "UTF-8").replace("\uFEFF", "").trim();
            if (l.text.isEmpty())
                return;
            l.sign(HammerAndroid.identity());
            store.publicObjects.putLyrics(song, l);
        } catch (Exception noLyrics) {
        }
    }
    static void importFolder(Activity a) {
        a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), IMPORT_FOLDER);
    }
    /** Every WAV, FLAC and MP3 in a chosen folder and the folders inside it (three deep). */
    static List<Uri> folderSongs(Context c, Uri tree) {
        List<Uri> out = new ArrayList<Uri>();
        String root = android.provider.DocumentsContract.getTreeDocumentId(tree);
        walk(c, tree, root, out, 0);
        return out;
    }
    private static void walk(Context c, Uri tree, String doc, List<Uri> out, int depth) {
        if (depth > 3 || out.size() >= 256)
            return;
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        try (android.database.Cursor cur = c.getContentResolver().query(children, new String[] {
                 android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                 android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                 android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), name = String.valueOf(cur.getString(1)).toLowerCase(Locale.ROOT);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(2)))
                    walk(c, tree, id, out, depth + 1);
                else if (name.matches(".*\\.(mp3|wav|flac)"))
                    out.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        } catch (Exception unreadable) {
        }
    }
}
