package ai.jackknife.killayue.studio;
import java.io.*;
import java.util.*;
/** Small durable receipt written before opening the recorder, then updated with the native clock anchors. */
public final class TakeJournal {
    public String file, track;
    public long start, latency, first = -1, anchor = -1;
    public boolean loop, punch;
    public long loopStart, loopEnd, punchStart, punchEnd;
    public TakeJournal copy() {
        TakeJournal j = new TakeJournal();
        j.file = file;
        j.track = track;
        j.start = start;
        j.latency = latency;
        j.first = first;
        j.anchor = anchor;
        j.loop = loop;
        j.loopStart = loopStart;
        j.loopEnd = loopEnd;
        j.punch = punch;
        j.punchStart = punchStart;
        j.punchEnd = punchEnd;
        return j;
    }
    public File receipt(File dir) { return new File(dir, file + ".take"); }
    public void save(File dir) throws IOException {
        LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
        j.put("file", file);
        j.put("track", track);
        j.put("start", start);
        j.put("latency", latency);
        j.put("first", first);
        j.put("anchor", anchor);
        j.put("loop", loop);
        j.put("loopStart", loopStart);
        j.put("loopEnd", loopEnd);
        j.put("punch", punch);
        j.put("punchStart", punchStart);
        j.put("punchEnd", punchEnd);
        File f = receipt(dir), tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            o.write(Json.write(j).getBytes("UTF-8"));
            o.getFD().sync();
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f))
            throw new IOException("Cannot save the take receipt.");
    }
    public static TakeJournal read(File f) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] bytes = new byte[4096];
            for (int n; (n = in.read(bytes)) > 0;) {
                if (b.size() > 65536)
                    throw new IOException("Invalid take receipt.");
                b.write(bytes, 0, n);
            }
        } finally {
            in.close();
        }
  Map<?,?> j=(Map<?,?>)Json.parse(new String(b.toByteArray(),"UTF-8"));
  TakeJournal t = new TakeJournal();
  t.file = (String)j.get("file");
  t.track = (String)j.get("track");
  if (!Project.insideProject(t.file))
      throw new IOException("Invalid take path.");
  t.start = n(j, "start");
  t.latency = n(j, "latency");
  t.first = n(j, "first");
  t.anchor = n(j, "anchor");
  t.loop = Boolean.TRUE.equals(j.get("loop"));
  t.loopStart = n(j, "loopStart");
  t.loopEnd = n(j, "loopEnd");
  t.punch = Boolean.TRUE.equals(j.get("punch"));
  t.punchStart = n(j, "punchStart");
  t.punchEnd = n(j, "punchEnd");
  return t;
    }
    static long n(Map<?, ?> m, String k) { return ((Number)m.get(k)).longValue(); }
    public Edit add(Project p, long frames) {
        Project q = p.copy();
        for (Track t : q.tracks)
            for (Clip c : t.clips)
                if (c.file.equals(file))
                    return null;
        long at = first >= 0 && anchor >= 0 ? TakePlacement.start(start, anchor, first, latency) : start;
        // Discard captured count-in/pre-roll even when recording starts in the middle of a song.
        long preRoll = Math.min(frames, Math.max(0, start - at));
        at += preRoll;
        frames -= preRoll;
        java.util.List<long[]> parts;
        if (loop)
            parts = TakePlacement.passes(at, frames, loopStart, loopEnd);
        else {
            parts = new ArrayList<long[]>();
            long skip = Math.max(0, -at);
            if (frames > skip)
                parts.add(new long[] {Math.max(0, at), skip, frames - skip});
        }
        int take = 0;
        Track archive = null;
        long archiveEnd = 0;
        for (long[] v : parts) {
            long a = v[0], off = v[1] + preRoll, len = v[2];
            if (punch) {
                long s = Math.max(a, punchStart), e = Math.min(a + len, punchEnd);
                off += s - a;
                a = s;
                len = e - s;
            }
            if (len <= 0)
                continue;
            Track target = take == 0 ? q.track(track) : null;
            if (target == null) {
                if (archive == null && q.tracks.size() >= 63) {
                    if (q.tracks.size() >= 64)
                        throw new IllegalArgumentException("Free a track to recover the remaining takes; their WAV is retained.");
                    archive = q.addTrack("More takes (in order)");
                    archive.role = "take";
                    archive.mute = true;
                }
                if (archive != null) {
                    target = archive;
                    a = archiveEnd;
                    archiveEnd += len;
                } else {
                    target = q.addTrack("Take " + (take + 1));
                    target.role = "take";
                    target.mute = take > 0;
                }
            }
            Clip c = q.addClip(target, file, a, off, len);
            c.fadeIn = c.fadeOut = Math.min(q.rate / 500, len / 2);
            take++;
        }
        return take == 0 ? null : ClipEdits.change(p, q, "Record " + take + (take == 1 ? " take" : " takes"));
    }
}
