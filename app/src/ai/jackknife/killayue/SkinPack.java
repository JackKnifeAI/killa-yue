package ai.jackknife.killayue;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Community skins: a skin someone made, installed from a file.
 *
 * A skin file is a zip holding the same pictures as a built-in skin (app/assets/skins/<id>/)
 * and a skin.json with its name and colours; SKINS.md in the repository describes it. Installed
 * skins live in the app's private storage under skins/<id>/. Every picture is checked to be one
 * the skin needs and a real image of a sensible size before anything is kept.
 */
public class SkinPack {
    static final String[] IMAGES = {"btnA.png", "btnB.png", "staff.png", "dial.png", "round.png",
        "note1.png", "note2.png", "note3.png", "note4.png", "note5.png", "note6.png"};
    static final String[] COLOURS = {"accent", "hi", "lo", "glow", "text", "dim", "faint", "edge", "off", "deck", "card", "pill"};
    private static final long MAX_FILE = 12L << 20, MAX_TOTAL = 40L << 20;
    private static final int MAX_SIDE = 4096;

    static File root(Context c) {
        return new File(c.getFilesDir(), "skins");
    }

    static File dir(Context c, String id) {
        return new File(root(c), id);
    }

    static boolean installed(Context c, String id) {
        return id != null && id.startsWith("x-") && new File(dir(c, id), "skin.json").isFile();
    }

    /** Installed skins' ids, in name order. Community ids start with "x-" so they never clash with built-in ones. */
    static ArrayList<String> ids(Context c) {
        ArrayList<String> out = new ArrayList<String>();
        File[] d = root(c).listFiles();
        if (d == null) return out;
        for (int i = 0; i < d.length; i++) if (installed(c, d[i].getName())) out.add(d[i].getName());
        Collections.sort(out);
        return out;
    }

    static JSONObject info(Context c, String id) {
        try {
            return new JSONObject(new String(Store.read(new File(dir(c, id), "skin.json")), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    static String name(Context c, String id) {
        JSONObject j = info(c, id);
        return j == null ? id : j.optString("name", id);
    }

    /** "#RRGGBB" or "#AARRGGBB". */
    static int colour(JSONObject colours, String key, int fallback) {
        String s = colours == null ? "" : colours.optString(key).trim();
        if (!s.startsWith("#") || (s.length() != 7 && s.length() != 9)) return fallback;
        try {
            long v = Long.parseLong(s.substring(1), 16);
            return s.length() == 7 ? (int) (0xFF000000L | v) : (int) v;
        } catch (Exception e) {
            return fallback;
        }
    }

    static void remove(Context c, String id) {
        if (!installed(c, id)) return;
        File d = dir(c, id);
        File[] f = d.listFiles();
        if (f != null) for (int i = 0; i < f.length; i++) f[i].delete();
        d.delete();
    }

    /** Installs a skin from a zip and returns its id. Throws with a message for the user when the file is not a usable skin. */
    static String install(Context c, Uri u) throws Exception {
        java.util.HashMap<String, byte[]> files = new java.util.HashMap<String, byte[]>();
        long total = 0;
        InputStream raw = c.getContentResolver().openInputStream(u);
        if (raw == null) throw new Exception("Could not open the file");
        ZipInputStream zin = new ZipInputStream(raw);
        try {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                // only the file's own name counts: a skin may sit at the top of the zip or inside one folder
                String n = e.getName().replace('\\', '/');
                n = n.substring(n.lastIndexOf('/') + 1);
                if (!wanted(n)) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int r;
                while ((r = zin.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    total += r;
                    if (out.size() > MAX_FILE) throw new Exception(n + " is larger than " + (MAX_FILE >> 20) + " MB");
                    if (total > MAX_TOTAL) throw new Exception("The skin is larger than " + (MAX_TOTAL >> 20) + " MB");
                }
                files.put(n, out.toByteArray());
            }
        } catch (java.util.zip.ZipException e) {
            throw new Exception("This is not a skin file. Skins are zip files with a skin.json inside.");
        } finally {
            zin.close();
        }

        byte[] json = files.get("skin.json");
        if (json == null) throw new Exception("This is not a skin file: it has no skin.json inside.");
        JSONObject j;
        try {
            j = new JSONObject(new String(json, "UTF-8"));
        } catch (Exception e) {
            throw new Exception("The skin's skin.json could not be read: " + e.getMessage());
        }
        String name = j.optString("name").trim();
        if (name.length() == 0) throw new Exception("The skin's skin.json has no name");
        if (name.length() > 40) name = name.substring(0, 40);
        JSONObject colours = j.optJSONObject("colors");
        if (colours == null) colours = j.optJSONObject("colours");
        if (colours == null || colour(colours, "accent", 0) == 0) throw new Exception("The skin's skin.json needs colors, with at least an accent colour");
        boolean tiled = j.optBoolean("tiledBackground", false);
        String bg = tiled ? "bg.png" : "wall.jpg";
        ArrayList<String> missing = new ArrayList<String>();
        for (int i = 0; i < IMAGES.length; i++) if (!files.containsKey(IMAGES[i])) missing.add(IMAGES[i]);
        if (!files.containsKey(bg)) missing.add(bg);
        if (missing.size() > 0) throw new Exception("The skin is missing " + join(missing));
        for (String n : files.keySet()) {
            if (n.equals("skin.json")) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            byte[] b = files.get(n);
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception(n + " is not a picture");
            if (o.outWidth > MAX_SIDE || o.outHeight > MAX_SIDE) throw new Exception(n + " is larger than " + MAX_SIDE + " pixels on a side");
        }
        JSONArray staff = j.optJSONArray("staff");
        if (staff != null && staff.length() != 15) throw new Exception("\"staff\" in skin.json needs 15 numbers (see SKINS.md)");

        // what is kept: the pictures, and a skin.json written by us with only what the app reads
        String id = "x-" + slug(j.optString("id", name));
        JSONObject keep = new JSONObject();
        keep.put("name", name);
        keep.put("author", j.optString("author"));
        keep.put("base", Skin.builtin(j.optString("base")));
        keep.put("scriptTitles", j.optBoolean("scriptTitles", false));
        keep.put("tiledBackground", tiled);
        JSONObject cs = new JSONObject();
        for (int i = 0; i < COLOURS.length; i++) if (colours.has(COLOURS[i])) cs.put(COLOURS[i], colours.optString(COLOURS[i]));
        keep.put("colors", cs);
        if (staff != null) {
            JSONArray st = new JSONArray();
            for (int i = 0; i < 15; i++) st.put(staff.getDouble(i));
            keep.put("staff", st);
        }
        files.put("skin.json", keep.toString(2).getBytes("UTF-8"));

        File tmp = new File(root(c), ".installing");
        File[] old = tmp.listFiles();
        if (old != null) for (int i = 0; i < old.length; i++) old[i].delete();
        tmp.mkdirs();
        for (String n : files.keySet()) {
            if (!n.equals("skin.json") && !n.equals(bg) && java.util.Arrays.asList(IMAGES).indexOf(n) < 0) continue;
            Store.write(new File(tmp, n), files.get(n));
        }
        remove(c, id); // a newer version of the same skin replaces the old one
        if (!tmp.renameTo(dir(c, id))) throw new Exception("Could not install the skin");
        return id;
    }

    private static boolean wanted(String n) {
        if (n.equals("skin.json") || n.equals("bg.png") || n.equals("wall.jpg")) return true;
        for (int i = 0; i < IMAGES.length; i++) if (IMAGES[i].equals(n)) return true;
        return false;
    }

    private static String slug(String s) {
        String out = s.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (out.length() > 32) out = out.substring(0, 32);
        return out.length() == 0 ? "skin" : out;
    }

    private static String join(ArrayList<String> a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.size(); i++) b.append(i == 0 ? "" : i == a.size() - 1 ? " and " : ", ").append(a.get(i));
        return b.toString();
    }

    private static String hex(int c) {
        return String.format(java.util.Locale.US, "#%08X", c);
    }

    /**
     * Writes the skin in use as a skin file, to start a new skin from: its pictures and a
     * skin.json with its colours and staff measurements filled in.
     */
    static File template(Context c, File out) throws Exception {
        String id = Skin.id(c);
        boolean community = installed(c, id);
        JSONObject j = new JSONObject();
        j.put("name", (community ? name(c, id) : Skin.builtinName(id)) + " (my version)");
        j.put("author", "");
        j.put("base", Skin.builtin(community ? info(c, id).optString("base") : id));
        j.put("scriptTitles", Skin.SCRIPT_TITLES);
        j.put("tiledBackground", Skin.tiledBackground());
        JSONObject cs = new JSONObject();
        int[] v = {Skin.ACCENT, Skin.HI, Skin.LO, 0xFF000000 | Skin.GLOW, Skin.TEXT, Skin.DIM, Skin.FAINT, Skin.EDGE, Skin.OFF,
            Skin.DECK, Skin.CARD, Skin.PILL};
        for (int i = 0; i < COLOURS.length; i++) cs.put(COLOURS[i], hex(v[i]));
        j.put("colors", cs);
        JSONArray st = new JSONArray();
        for (int i = 0; i < Skin.STAFF.length; i++) st.put((double) Skin.STAFF[i]);
        j.put("staff", st);

        ArrayList<String> names = new ArrayList<String>(java.util.Arrays.asList(IMAGES));
        names.add(Skin.tiledBackground() ? "bg.png" : "wall.jpg");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out));
        try {
            z.putNextEntry(new ZipEntry("skin.json"));
            z.write(j.toString(2).getBytes("UTF-8"));
            z.closeEntry();
            byte[] buf = new byte[1 << 16];
            for (int i = 0; i < names.size(); i++) {
                InputStream in = community ? new java.io.FileInputStream(new File(dir(c, id), names.get(i)))
                        : c.getAssets().open("skins/" + id + "/" + names.get(i));
                try {
                    z.putNextEntry(new ZipEntry(names.get(i)));
                    int r;
                    while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
                    z.closeEntry();
                } finally {
                    in.close();
                }
            }
        } finally {
            z.close();
        }
        return out;
    }
}
