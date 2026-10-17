package ai.jackknife.killayue;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Songs that carry on where they stopped.
 *
 * Every song is made with a checkpoint folder (KillaYuE/checkpoints/<time>): the engine saves the
 * composition there as soon as it is written (composed-0.json) and the render after every step
 * (render-0.bin, see engine/yue2-resume.patch). The request itself is kept beside them. When a song
 * fails or is stopped, its folder stays, and resuming sends the same request with the saved
 * composition: the engine skips the composing and the finished render steps. A finished song's
 * folder is deleted.
 *
 * A song that stopped while it was being rendered can also be heard as far as it got: the same
 * request with "preview" makes the engine decode the saved render, its unfinished part carried to
 * the solver's guess of the end (engine/yue2-preview.patch). The checkpoint is left as it was.
 */
public class Resume {
    static File root(Context c) {
        return Store.dir(c, "checkpoints");
    }

    /** Gives a new song its checkpoint folder and keeps the request there. */
    static void prepare(Context c, JSONObject req, String title, String album) {
        try {
            req.remove("preview"); // a song is always made in full; only listen() asks for a preview
            if (req.has("checkpoint")) return; // a resumed song keeps its folder
            File d = new File(root(c), new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()));
            d.mkdirs();
            req.put("checkpoint", d.getAbsolutePath());
            // songs always render in one piece: chunked rendering is heard at every boundary
            req.remove("max_chunk"); // a song re-rendered from the library may carry it from a test build
            JSONObject keep = new JSONObject(req.toString());
            keep.put("_title", title == null ? "" : title);
            keep.put("_album", album == null ? "" : album);
            Store.write(new File(d, "request.json"), keep.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            // without a checkpoint the song is made as before
        }
    }

    /** The song finished: its checkpoint is no longer needed. */
    static void finished(JSONObject req) {
        String p = req.optString("checkpoint");
        if (p.length() > 0) delete(new File(p));
    }

    static void delete(File d) {
        File[] f = d.listFiles();
        if (f != null) for (File x : f) x.delete();
        d.delete();
    }

    /** One song that stopped part way. */
    static class Unfinished {
        File dir;
        JSONObject request, composed;
        String title;
        int stepsDone, stepsTotal; // render steps, across all chunks
        int heardFrames, totalFrames; // how much of the song a preview has to play (25 frames a second)
        long when;

        String describe() {
            String what = composed == null ? "nothing saved yet"
                    : stepsDone == 0 ? "composed, not rendered yet"
                    : stepsDone + " of " + stepsTotal + " render steps done";
            return (title.length() > 0 ? title : "Untitled") + "  ·  " + what;
        }

        boolean resumable() {
            return composed != null;
        }

        /** Something was rendered, so a preview has audio to give. */
        boolean listenable() {
            return composed != null && heardFrames > 0;
        }

        /** "2:10 of 4:15" */
        String howFar() {
            return clock(heardFrames / 25) + " of " + clock(totalFrames / 25);
        }
    }

    /** Stopped songs, newest first. Folders with nothing worth keeping are removed. */
    static ArrayList<Unfinished> list(Context c, String runningCheckpoint) {
        ArrayList<Unfinished> out = new ArrayList<Unfinished>();
        File[] ds = root(c).listFiles();
        if (ds == null) return out;
        Arrays.sort(ds);
        for (int i = ds.length - 1; i >= 0; i--) {
            File d = ds[i];
            if (!d.isDirectory() || d.getAbsolutePath().equals(runningCheckpoint)) continue;
            try {
                Unfinished u = new Unfinished();
                u.dir = d;
                u.when = d.lastModified();
                u.request = new JSONObject(new String(Store.read(new File(d, "request.json")), "UTF-8"));
                u.title = u.request.optString("_title");
                File comp = new File(d, "composed-0.json");
                if (comp.isFile()) u.composed = new JSONObject(new String(Store.read(comp), "UTF-8"));
                progress(u, new File(d, "render-0.bin"));
                if (!u.resumable() && System.currentTimeMillis() - u.when > 24L * 3600 * 1000) {
                    delete(d); // stopped before anything was saved, a day ago
                    continue;
                }
                out.add(u);
            } catch (Exception e) {
                // not a checkpoint folder we can read
            }
        }
        return out;
    }

    /** Reads where the render stands from the header of render-0.bin (CpRenderHeader in the engine patch). */
    static void progress(Unfinished u, File bin) {
        int steps = u.request.optInt("steps", 32);
        u.stepsTotal = steps;
        if (!bin.isFile()) return;
        try {
            RandomAccessFile f = new RandomAccessFile(bin, "r");
            byte[] h = new byte[40];
            f.readFully(h);
            f.close();
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            if (b.get(0) != 'K' || b.get(1) != 'Y' || b.get(2) != 'R' || b.get(3) != '1') return;
            int tLat = b.getInt(4), chunk = b.getInt(16), st = b.getInt(20), start = b.getInt(24), done = b.getInt(28);
            int chunks = Math.max(1, (tLat + chunk - 1) / chunk);
            u.stepsTotal = chunks * st;
            // A completed render stores start == tLat and done == 0 while decoding is still pending.
            u.stepsDone = start >= tLat ? u.stepsTotal
                    : Math.min(u.stepsTotal, (start / Math.max(1, chunk)) * st + done);
            // as the engine's preview counts it: the finished chunks, and the one being worked on once it has steps
            u.totalFrames = tLat;
            u.heardFrames = Math.min(tLat, start + (done > 0 ? Math.min(chunk, tLat - start) : 0));
        } catch (Exception e) {
            // no progress to show
        }
    }

    static String clock(int s) {
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** The request that plays the song as far as it got, leaving its checkpoint as it was. */
    static JSONObject previewRequest(Unfinished u) throws Exception {
        JSONObject r = request(u);
        r.put("preview", true);
        return r;
    }

    /** The request that carries the song on: the same settings, the saved composition and seed. */
    static JSONObject request(Unfinished u) throws Exception {
        JSONObject r = new JSONObject(u.request.toString());
        r.remove("_title");
        r.remove("_album");
        r.put("checkpoint", u.dir.getAbsolutePath());
        r.put("semantic_tokens", u.composed.getString("semantic_tokens"));
        r.put("seed", u.composed.getLong("seed"));
        String abc = u.composed.optString("abc");
        if (abc.length() > 0) {
            r.put("abc", abc);
            r.put("abc_tokens", u.composed.optString("abc_tokens"));
        }
        return r;
    }
}
