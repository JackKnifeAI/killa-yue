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
import org.json.JSONObject;

/** Library owns publication and the single persistent audio copy. Nothing publishes on import. */
final class HammerLibrary {
    static final int IMPORT_AUDIO = 847;
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
    static String state(Context c, JSONObject meta) {
        try {
            File f = audio(c, meta);
            for (HammerStore.Hosted h : HammerAndroid.store(c).hosted())
                if (h.file.getCanonicalFile().equals(f))
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
            new String[] {"Only on this device", "Public network", "Private circle"}));
        String old = state(a, meta);
        visibility.setSelection(old.equals("Public") ? 1 : old.equals("Private circle") ? 2 : 0);
        view.addView(visibility);
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
                if (h.file.getCanonicalFile().equals(f)) {
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
                + "Publishing starts hosting. Copies already downloaded cannot be recalled.")
            .setView(view)
            .setPositiveButton(
                "Apply",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final int scope = visibility.getSelectedItemPosition();
                        final boolean allow = copies.isChecked();
                        final String labels = tags.getText().toString();
                        work(a, "Updating song sharing", new Work() {
                            public void run() throws Exception {
                                HammerStore store = HammerAndroid.store(a);
                                File file = audio(a, meta);
                                HammerStore.Hosted existing = null;
                                for (HammerStore.Hosted h : store.hosted())
                                    if (h.file.getCanonicalFile().equals(file))
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
    static void importAudio(Activity a) {
        Intent i =
            new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE);
        a.startActivityForResult(i, IMPORT_AUDIO);
    }
    static void imported(final MainActivity a, final Uri uri) {
        work(a, "Importing into Library", new Work() {
            public void run() throws Exception {
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
                    Store.write(new File(root, base + ".json"), m.toString().getBytes("UTF-8"));
                    saved = true;
                } finally {
                    tmp.delete();
                    if (!saved && target != null)
                        target.delete();
                }
            }
        });
    }
}
