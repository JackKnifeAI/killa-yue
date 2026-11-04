package ai.jackknife.killayue.peer;

import java.io.*;
import java.util.*;

/**
 * The circles a host carries: their records and the sealed envelopes said in them, which it can't read. The room
 * lane keeps a day (at most 1000 envelopes a circle) and lives in memory; the board keeps 30 days (at most 500) and
 * is kept on disk, so a phone that was away catches up. Every envelope paid its stamp, and a circle takes at most
 * 20 room envelopes in ten seconds, so nobody floods it. A helper's owner can turn carrying circles off.
 */
public final class HammerCircles {
    public static final int CIRCLES = 64, ROOM_KEPT = 1000, BOARD_KEPT = 500, READ = 200;
    public static final long ROOM_AGE = 24L * 3600 * 1000, BOARD_AGE = 30L * 24 * 3600 * 1000;
    public volatile boolean enabled = true;
    /** When set, the only circles carried here: a phone carries just the ones its owner started or joined. */
    public volatile Set<String> only;
    private final File dir;
    private final Map<String, HammerCircle> records = new LinkedHashMap<String, HammerCircle>();
    private final Map<String, List<HammerCircle.Envelope>> room = new HashMap<String, List<HammerCircle.Envelope>>(),
                                                           board = new HashMap<String, List<HammerCircle.Envelope>>();
    private final Map<String, long[]> pace = new HashMap<String, long[]>();

    public HammerCircles(File dir) throws Exception {
        this.dir = dir;
        Hammer.require(dir.isDirectory() || dir.mkdirs(), "Cannot open circles");
        File[] fs = dir.listFiles();
        if (fs != null)
            for (File f : fs)
                try {
                    if (!f.getName().matches("[0-9a-f]{64}\\.circle"))
                        continue;
                    HammerCircle c = HammerCircle.read(new DataInputStream(new ByteArrayInputStream(HammerStore.read(f, 16384))), "");
                    records.put(c.id, c);
                    List<HammerCircle.Envelope> posts = new ArrayList<HammerCircle.Envelope>();
                    File b = new File(dir, c.id + ".board");
                    if (b.isFile())
                        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(b)))) {
                            while (true)
                                posts.add(HammerCircle.Envelope.read(in));
                        } catch (EOFException end) {
                        } catch (Exception damagedTail) {
                        }
                    board.put(c.id, posts);
                    trim(posts, BOARD_AGE, BOARD_KEPT);
                } catch (Exception damaged) {
                }
    }

    /**
     * Carries a circle, or a newer revision of one carried here. Only its starter's key may change it, so another
     * record claiming the same id is refused. True when this copy was new here.
     */
    public synchronized boolean carry(HammerCircle c) throws Exception {
        Hammer.require(enabled, "This host doesn't carry circles");
        c.validate();
        Set<String> mine = only;
        Hammer.require(mine == null || mine.contains(c.id), "This phone carries only its owner's circles");
        HammerCircle old = records.get(c.id);
        if (old != null) {
            Hammer.require(old.key.equals(c.key), "Only the person who started this circle can change it");
            if (c.revision <= old.revision)
                return false;
        } else {
            if (records.size() >= CIRCLES)
                forgetQuiet();
            Hammer.require(records.size() < CIRCLES, "This host carries at most " + CIRCLES + " circles");
        }
        HammerCircle kept = c.carried();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        kept.write(new DataOutputStream(b));
        HammerStore.atomic(new File(dir, c.id + ".circle"), b.toByteArray());
        records.put(c.id, kept);
        if (!board.containsKey(c.id))
            board.put(c.id, new ArrayList<HammerCircle.Envelope>());
        return true;
    }
    /** Makes room by letting go of the circle quiet longest, once it has said nothing for 30 days. */
    private void forgetQuiet() {
        String quietest = null;
        long last = Long.MAX_VALUE, now = System.currentTimeMillis();
        for (HammerCircle c : records.values()) {
            long t = c.revision;
            for (List<HammerCircle.Envelope> l : Arrays.asList(room.get(c.id), board.get(c.id)))
                if (l != null && !l.isEmpty())
                    t = Math.max(t, l.get(l.size() - 1).time);
            if (t < last) {
                last = t;
                quietest = c.id;
            }
        }
        if (quietest != null && now - last > BOARD_AGE)
            forget(quietest);
    }
    public synchronized void forget(String id) {
        records.remove(id);
        room.remove(id);
        board.remove(id);
        new File(dir, id + ".circle").delete();
        new File(dir, id + ".board").delete();
    }
    public synchronized HammerCircle record(String id) { return records.get(id); }
    /** The open circles carried here, for Discover. */
    public synchronized List<HammerCircle> open() {
        List<HammerCircle> out = new ArrayList<HammerCircle>();
        for (HammerCircle c : records.values())
            if (c.open)
                out.add(c);
        return out;
    }
    public synchronized List<HammerCircle> all() { return new ArrayList<HammerCircle>(records.values()); }

    /** Keeps a sealed envelope for a circle carried here. True when it was new. */
    public boolean post(HammerCircle.Envelope e) throws Exception { return post(e, true); }
    /** {@code paced}: a member's post counts against the circle's pace; one caught up from another host doesn't. */
    synchronized boolean post(HammerCircle.Envelope e, boolean paced) throws Exception {
        e.validate();
        Hammer.require(records.containsKey(e.circle), "This host doesn't carry that circle");
        long now = System.currentTimeMillis();
        long age = e.lane == HammerCircle.BOARD ? BOARD_AGE : ROOM_AGE;
        Hammer.require(e.time <= now + 120000 && e.time > now - age, "That message is too old or from the future");
        Map<String, List<HammerCircle.Envelope>> lanes = e.lane == HammerCircle.BOARD ? board : room;
        List<HammerCircle.Envelope> l = lanes.get(e.circle);
        if (l == null)
            lanes.put(e.circle, l = new ArrayList<HammerCircle.Envelope>());
        for (HammerCircle.Envelope x : l)
            if (x.id.equals(e.id))
                return false;
        if (paced && e.lane == HammerCircle.ROOM) {
            long[] p = pace.get(e.circle);
            if (p == null || now - p[0] > 10000)
                pace.put(e.circle, p = new long[] {now, 0});
            Hammer.require(++p[1] <= 20, "This circle is busy; try again in a moment");
        }
        int at = l.size();
        while (at > 0 && l.get(at - 1).time > e.time)
            at--;
        l.add(at, e);
        int before = l.size();
        trim(l, age, e.lane == HammerCircle.BOARD ? BOARD_KEPT : ROOM_KEPT);
        if (e.lane == HammerCircle.BOARD) {
            if (l.size() == before && at == l.size() - 1)
                try (DataOutputStream o = new DataOutputStream(new FileOutputStream(new File(dir, e.circle + ".board"), true))) {
                    e.write(o);
                }
            else
                saveBoard(e.circle, l);
        }
        return true;
    }
    private void saveBoard(String id, List<HammerCircle.Envelope> l) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        for (HammerCircle.Envelope e : l)
            e.write(o);
        HammerStore.atomic(new File(dir, id + ".board"), b.toByteArray());
    }
    private static void trim(List<HammerCircle.Envelope> l, long age, int kept) {
        long now = System.currentTimeMillis();
        while (!l.isEmpty() && (l.size() > kept || now - l.get(0).time > age))
            l.remove(0);
    }
    /** Envelopes of one lane from {@code since} on, oldest first, at most READ: ask again from the last one's time. */
    public synchronized List<HammerCircle.Envelope> read(String id, int lane, long since) throws Exception {
        Hammer.require(records.containsKey(id), "This host doesn't carry that circle");
        List<HammerCircle.Envelope> l = (lane == HammerCircle.BOARD ? board : room).get(id);
        List<HammerCircle.Envelope> out = new ArrayList<HammerCircle.Envelope>();
        if (l != null)
            for (HammerCircle.Envelope e : l)
                if (e.time >= since && out.size() < READ)
                    out.add(e);
        return out;
    }
    /** Catches up from another host of the same circle: the last ten minutes of the room, and the board since its newest here. */
    public int sync(Hammer.Card from, String id, Hammer.Job job) throws Exception {
        long boardSince;
        synchronized (this) {
            List<HammerCircle.Envelope> b = board.get(id);
            boardSince = b == null || b.isEmpty() ? 0 : b.get(b.size() - 1).time;
        }
        int n = 0;
        for (int lane : new int[] {HammerCircle.ROOM, HammerCircle.BOARD})
            for (HammerCircle.Envelope e : Hammer.circleRead(from, id, lane,
                                                             lane == HammerCircle.ROOM ? System.currentTimeMillis() - 600000 : boardSince, job))
                try {
                    if (post(e, false))
                        n++;
                } catch (Exception refused) {
                }
        return n;
    }
}
