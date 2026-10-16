package ai.jackknife.killayue.studio;
/** Validated, durable edits for performance work. Each snapshot is one branch in the existing undo tree. */
public final class ClipEdits {
    private ClipEdits() {}
    public static Edit change(Project p, Project next, String label) {
        next.validate();
        return new Edit.Snapshot(label, p.toText(), next.toText());
    }
    private static Clip need(Project p, String id) {
        Clip c = p.clip(id);
        if (c == null)
            throw new IllegalArgumentException("That clip no longer exists.");
        return c;
    }
    public static Edit move(Project p, String id, long at) {
        if (at < 0)
            throw new IllegalArgumentException("A clip cannot start before zero.");
        Project n = p.copy();
        Clip moved = need(n, id);
        moved.start = at;
        for (Track t : n.tracks)
            if (t.clips.contains(moved))
                for (Clip other : t.clips) {
                    if (other == moved)
                        continue;
                    Clip left = moved.start < other.start ? moved : other;
                    Clip right = left == moved ? other : moved;
                    long overlap = left.start + left.length - right.start;
                    if (overlap > 0 && overlap <= Math.min(left.length, right.length)) {
                        left.fadeOut = overlap;
                        right.fadeIn = overlap;
                    }
                }
        return change(p, n, "Move clip");
    }
    public static Edit trim(Project p, String id, long from, long length) {
        Project n = p.copy();
        Clip c = need(n, id);
        if (from < 0 || length <= 0 || from > c.length || length > c.length - from)
            throw new IllegalArgumentException("Trim stays inside the clip.");
        c.start += from;
        c.offset += from;
        c.length = length;
        c.fadeIn = Math.min(c.fadeIn, length);
        c.fadeOut = Math.min(c.fadeOut, length);
        return change(p, n, "Trim clip");
    }
    public static Edit split(Project p, String id, long at) {
        Project n = p.copy();
        Clip c = need(n, id);
        long left = at - c.start;
        if (left <= 0 || left >= c.length)
            throw new IllegalArgumentException("Split inside the clip.");
        for (Track t : n.tracks)
            if (t.clips.contains(c)) {
                Clip right = n.addClip(t, c.file, at, c.offset + left, c.length - left);
                right.gainDb = c.gainDb;
                right.fadeOut = Math.min(c.fadeOut, right.length);
                break;
            }
        c.length = left;
        c.fadeIn = Math.min(c.fadeIn, left);
        c.fadeOut = 0;
        return change(p, n, "Split clip");
    }
    public static Edit duplicate(Project p, String id) {
        Project n = p.copy();
        Clip c = need(n, id);
        for (Track t : n.tracks)
            if (t.clips.contains(c)) {
                Clip d = n.addClip(t, c.file, c.start + c.length, c.offset, c.length);
                d.gainDb = c.gainDb;
                d.fadeIn = c.fadeIn;
                d.fadeOut = c.fadeOut;
                break;
            }
        return change(p, n, "Duplicate clip");
    }
    public static Edit remove(Project p, String id) {
        Project n = p.copy();
        Clip c = need(n, id);
        for (Track t : n.tracks)
            t.clips.remove(c);
        return change(p, n, "Remove clip");
    }
    public static Edit fades(Project p, String id, long in, long out) {
        Project n = p.copy();
        Clip c = need(n, id);
        c.fadeIn = in;
        c.fadeOut = out;
        return change(p, n, "Clip fades");
    }
    public static Edit crossfade(Project p, String first, String second) {
        Project n = p.copy();
        Clip a = need(n, first), b = need(n, second);
        long overlap = a.start + a.length - b.start;
        if (b.start < a.start || overlap <= 0 || overlap > Math.min(a.length, b.length))
            throw new IllegalArgumentException("Select two overlapping clips.");
        a.fadeOut = overlap;
        b.fadeIn = overlap;
        return change(p, n, "Crossfade");
    }
    public static Edit comp(Project p, String id, long from, long to) {
        Project q = p.copy();
        Clip source = need(q, id);
        from = Math.max(from, source.start);
        to = Math.min(to, source.start + source.length);
        if (to <= from)
            throw new IllegalArgumentException("Swipe a range inside the take.");
        Track lane = null;
        for (Track t : q.tracks)
            if ("comp".equals(t.role))
                lane = t;
        if (lane == null) {
            lane = q.addTrack("Comp");
            lane.role = "comp";
        }
        java.util.ArrayList<Clip> old = new java.util.ArrayList<Clip>(lane.clips);
        lane.clips.clear();
        for (Clip c : old) {
            long end = c.start + c.length;
            if (end <= from || c.start >= to) {
                lane.clips.add(c);
                continue;
            }
            if (c.start < from) {
                Clip left = q.addClip(lane, c.file, c.start, c.offset, from - c.start);
                left.gainDb = c.gainDb;
                left.fadeIn = Math.min(c.fadeIn, left.length);
            }
            if (end > to) {
                Clip right = q.addClip(lane, c.file, to, c.offset + to - c.start, end - to);
                right.gainDb = c.gainDb;
                right.fadeOut = Math.min(c.fadeOut, right.length);
            }
        }
        Clip picked = q.addClip(lane, source.file, from, source.offset + from - source.start, to - from);
        picked.gainDb = source.gainDb;
        picked.fadeIn = picked.fadeOut = Math.min(q.rate / 500, picked.length / 2);
        for (Track t : q.tracks)
            if ("take".equals(t.role) || t.clips.contains(source))
                t.mute = true;
        lane.mute = false;
        return change(p, q, "Comp take");
    }
}
