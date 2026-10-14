package ai.jackknife.killayue;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Albums: an ordered set of library songs with a title, an artist, a sound (style) and a cover.
 * Each album is one JSON file in /sdcard/KillaYuE/albums; its tracks are the songs' .json names.
 * Export writes a tagged album folder to Music (so music apps see it) and a zip for sharing.
 */
public class Albums {
    static File dir(Context c) {
        return Store.dir(c, "albums");
    }

    static File file(Context c, String id) {
        return new File(dir(c), id + ".json");
    }

    static File coverFile(Context c, String id) {
        return new File(dir(c), id + ".cover.jpg");
    }

    /** All albums, newest first. */
    static ArrayList<JSONObject> all(Context c) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        for (int i = 0; i < fs.length; i++) {
            if (!fs[i].getName().endsWith(".json")) continue;
            try {
                out.add(new JSONObject(new String(Store.read(fs[i]), "UTF-8")));
            } catch (Exception e) {
                // a damaged album file is skipped, not fatal
            }
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("created"), a.optLong("created"));
            }
        });
        return out;
    }

    static JSONObject get(Context c, String id) {
        try {
            return new JSONObject(new String(Store.read(file(c, id)), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized void save(Context c, JSONObject a) throws Exception {
        Store.write(file(c, a.getString("id")), a.toString(1).getBytes("UTF-8"));
    }

    static JSONObject create(Context c, String title, String style) throws Exception {
        JSONObject a = new JSONObject();
        a.put("id", "a" + System.currentTimeMillis());
        a.put("title", title);
        a.put("artist", "Killa YuE");
        a.put("style", style == null ? "" : style);
        a.put("tracks", new JSONArray());
        a.put("created", System.currentTimeMillis());
        save(c, a);
        return a;
    }

    static void delete(Context c, String id) {
        file(c, id).delete();
        coverFile(c, id).delete();
    }

    /** Adds a song (its .json name in the library) to the end of the album, once. */
    static synchronized boolean add(Context c, String id, String songJson) {
        try {
            JSONObject a = get(c, id);
            if (a == null) return false;
            JSONArray t = a.optJSONArray("tracks");
            for (int i = 0; i < t.length(); i++) if (songJson.equals(t.optString(i))) return true;
            t.put(songJson);
            save(c, a);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized void move(Context c, JSONObject a, int from, int to) throws Exception {
        JSONArray t = a.optJSONArray("tracks");
        if (from < 0 || to < 0 || from >= t.length() || to >= t.length()) return;
        ArrayList<String> l = new ArrayList<String>();
        for (int i = 0; i < t.length(); i++) l.add(t.optString(i));
        l.add(to, l.remove(from));
        a.put("tracks", new JSONArray(l));
        save(c, a);
    }

    static synchronized void remove(Context c, JSONObject a, int at) throws Exception {
        JSONArray t = a.optJSONArray("tracks");
        JSONArray n = new JSONArray();
        for (int i = 0; i < t.length(); i++) if (i != at) n.put(t.optString(i));
        a.put("tracks", n);
        save(c, a);
    }

    /** The album's songs (their library metadata) in album order; songs deleted from the library are left out. */
    static ArrayList<JSONObject> songs(Context c, JSONObject a) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        JSONArray t = a.optJSONArray("tracks");
        for (int i = 0; t != null && i < t.length(); i++) {
            try {
                File f = new File(Store.library(c), t.optString(i));
                if (f.isFile()) out.add(new JSONObject(new String(Store.read(f), "UTF-8")));
            } catch (Exception e) {
                // skip a damaged song file
            }
        }
        return out;
    }

    static String safeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.length() == 0 ? "Untitled" : n;
    }

    /** The cover for the whole album: its own if painted, else the first song's. */
    static File cover(Context c, JSONObject a, ArrayList<JSONObject> songs) {
        File own = coverFile(c, a.optString("id"));
        if (own.isFile()) return own;
        for (int i = 0; i < songs.size(); i++) {
            File f = Covers.coverFor(c, songs.get(i));
            if (f.isFile()) return f;
        }
        return null;
    }

    interface Progress {
        void at(String what);
    }

    /**
     * Writes Music/Killa YuE/<album>/ with "01 Title.mp3" files tagged as one album (title, artist,
     * track n/N, lyrics, timed lyrics, the album cover), an .lrc per song, cover.jpg and an .m3u8
     * playlist, then a zip of the same in the library folder for sharing. Returns the zip.
     */
    static File export(Context c, JSONObject a, Progress p) throws Exception {
        ArrayList<JSONObject> songs = songs(c, a);
        if (songs.size() == 0) throw new Exception("This album has no songs yet.");
        String album = a.optString("title", "Untitled album");
        String artist = a.optString("artist", "Killa YuE");
        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        File out = new File(new File(music, "Killa YuE"), safeName(album));
        out.mkdirs();
        if (!out.isDirectory()) throw new Exception("Could not make the folder " + out.getAbsolutePath());
        File coverSrc = cover(c, a, songs);
        byte[] coverBytes = coverSrc != null ? Store.read(coverSrc) : null;
        ArrayList<File> written = new ArrayList<File>();
        StringBuilder m3u = new StringBuilder("#EXTM3U\n#PLAYLIST:" + album + "\n");
        int n = songs.size();
        for (int i = 0; i < n; i++) {
            JSONObject m = songs.get(i);
            String title = m.optString("_title", "Untitled");
            if (p != null) p.at("Exporting " + (i + 1) + " of " + n + ": " + title);
            String audioName = m.optString("_audio");
            File src = new File(Store.library(c), audioName);
            if (!src.isFile()) continue;
            boolean mp3 = audioName.toLowerCase(Locale.US).endsWith(".mp3");
            String base = String.format(Locale.US, "%02d %s", i + 1, safeName(title));
            File dst = new File(out, base + (mp3 ? ".mp3" : ".wav"));
            int ms = m.optInt("_seconds") * 1000;
            ArrayList<Karaoke.Line> lines = MainActivity.linesFor(m, ms);
            byte[] d = Store.read(src);
            FileOutputStream o = new FileOutputStream(dst);
            try {
                if (mp3) {
                    int skip = Tags.existingTag(d);
                    o.write(Tags.id3(title, lines, coverBytes, album, artist, i + 1, n));
                    o.write(d, skip, d.length - skip);
                } else {
                    o.write(d);
                }
            } finally {
                o.close();
            }
            written.add(dst);
            if (lines.size() > 0) {
                File lrc = new File(out, base + ".lrc");
                Store.write(lrc, Tags.lrc(title, lines).getBytes("UTF-8"));
                written.add(lrc);
            }
            m3u.append("#EXTINF:").append(m.optInt("_seconds")).append(',').append(artist).append(" - ").append(title).append('\n');
            m3u.append(dst.getName()).append('\n');
        }
        if (coverBytes != null) {
            File cv = new File(out, "cover.jpg");
            Store.write(cv, coverBytes);
            written.add(cv);
        }
        File list = new File(out, safeName(album) + ".m3u8");
        Store.write(list, m3u.toString().getBytes("UTF-8"));
        written.add(list);

        // let music apps find the new files
        String[] paths = new String[written.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = written.get(i).getAbsolutePath();
        try {
            MediaScannerConnection.scanFile(c, paths, null, null);
        } catch (Exception e) {
            // the files are there either way; a music app will find them on its next scan
        }

        // and one zip of the same, for sharing (audio is stored, not compressed again)
        if (p != null) p.at("Packing the album to share");
        File zip = new File(Store.library(c), "album-" + a.optString("id") + ".zip");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip));
        try {
            String folder = safeName(album) + "/";
            for (int i = 0; i < written.size(); i++) {
                File f = written.get(i);
                byte[] b = Store.read(f);
                ZipEntry e = new ZipEntry(folder + f.getName());
                boolean stored = f.getName().endsWith(".mp3") || f.getName().endsWith(".jpg");
                if (stored) {
                    CRC32 crc = new CRC32();
                    crc.update(b);
                    e.setMethod(ZipEntry.STORED);
                    e.setSize(b.length);
                    e.setCompressedSize(b.length);
                    e.setCrc(crc.getValue());
                }
                z.putNextEntry(e);
                z.write(b);
                z.closeEntry();
            }
        } finally {
            z.close();
        }
        a.put("exported", out.getAbsolutePath());
        save(c, a);
        return zip;
    }

    /** A cover prompt for the whole album, in the same form Covers uses for one song. */
    static JSONObject coverMeta(JSONObject a, ArrayList<JSONObject> songs) throws Exception {
        JSONObject m = new JSONObject();
        m.put("_title", a.optString("title"));
        String style = a.optString("style");
        if (style.length() == 0 && songs.size() > 0) style = songs.get(0).optString("style");
        m.put("style", style);
        m.put("_audio", "album-" + a.optString("id") + ".mp3"); // only names the output and seeds the picture
        return m;
    }
}
