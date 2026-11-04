package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.content.Context;
import java.util.*;

/**
 * Being in a circle's room: reading what's said from its hosts, saying things to all of them, and from what's been
 * said working out the queue, the votes, what's playing and who's here. Every phone in the room works these out
 * the same way from the same messages, so nobody runs the room. When a song is ending, any phone in the room may
 * start the next one, the queue's top; if two do at once, the earlier start wins everywhere.
 */
final class CircleRoom implements HammerService.Next {
    static volatile CircleRoom current;
    /** How long someone counts as here after they last said anything. */
    static final long HERE = 5L * 60 * 1000;

    final Circles.Joined joined;
    final HammerCircle circle;
    private final Context app;
    private final Map<String, HammerCircle.Message> room = new LinkedHashMap<String, HammerCircle.Message>(),
                                                     board = new LinkedHashMap<String, HammerCircle.Message>();
    private long roomSince, boardSince, boardAt, hereAt, startedNext;
    private int hostAt;
    private volatile boolean running = true;
    volatile Runnable changed;
    volatile String trouble;

    private CircleRoom(Context c, Circles.Joined j) {
        app = c.getApplicationContext();
        joined = j;
        circle = j.circle;
    }
    /** Enters a circle's room, leaving any other. */
    static synchronized CircleRoom enter(Context c, Circles.Joined j) {
        CircleRoom r = current;
        if (r != null && r.circle.id.equals(j.circle.id))
            return r;
        if (r != null)
            r.leave();
        final CircleRoom next = new CircleRoom(c, j);
        current = next;
        new Thread(new Runnable() {
            public void run() { next.loop(); }
        }, "killa-circle").start();
        return next;
    }
    synchronized void leave() {
        running = false;
        if (current == this)
            current = null;
        HammerService.leaveCircle();
    }

    private void loop() {
        roomSince = System.currentTimeMillis() - HammerCircles.ROOM_AGE;
        while (running) {
            try {
                boolean news = read(HammerCircle.ROOM, roomSince);
                long now = System.currentTimeMillis();
                if (now - boardAt > 30000) {
                    news |= read(HammerCircle.BOARD, boardSince);
                    boardAt = now;
                }
                if (now - hereAt > 120000) {
                    hereAt = now;
                    HammerCircle.Message m = new HammerCircle.Message();
                    m.kind = "here";
                    m.name = Circles.name(app);
                    send(m);
                    news = true;
                }
                driveQueue(now);
                trouble = null;
                Runnable r = changed;
                if (news && r != null)
                    r.run();
            } catch (Exception e) {
                trouble = "Can't reach this circle's hosts right now";
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException stop) {
                return;
            }
        }
    }
    /** Reads a lane from the first host that answers, each host in turn after a failure. */
    private boolean read(int lane, long since) throws Exception {
        List<Hammer.Card> hosts = circle.hostCards();
        Exception last = null;
        for (int i = 0; i < hosts.size(); i++) {
            Hammer.Card h = hosts.get((hostAt + i) % hosts.size());
            try {
                List<HammerCircle.Envelope> got = Hammer.circleRead(h, circle.id, lane, Math.max(0, since - 5000), new Hammer.Job());
                hostAt = (hostAt + i) % hosts.size();
                boolean news = false;
                for (HammerCircle.Envelope e : got)
                    news |= take(e);
                return news;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null)
            throw last;
        return false;
    }
    private synchronized boolean take(HammerCircle.Envelope e) {
        Map<String, HammerCircle.Message> lane = e.lane == HammerCircle.BOARD ? board : room;
        if (lane.containsKey(e.id))
            return false;
        HammerCircle.Message m = HammerCircle.Message.open(circle, e);
        if (m == null)
            return false;
        lane.put(e.id, m);
        if (e.lane == HammerCircle.BOARD)
            boardSince = Math.max(boardSince, e.time);
        else
            roomSince = Math.max(roomSince, e.time);
        return true;
    }
    /** Signs, seals and leaves a message with every host of the circle; it counts once one host has it. */
    void send(HammerCircle.Message m) throws Exception {
        HammerCircle.Envelope e = m.seal(circle, joined.me);
        int took = 0;
        Exception why = null;
        for (Hammer.Card h : circle.hostCards())
            try {
                Hammer.circlePost(h, e, new Hammer.Job());
                took++;
            } catch (Exception refused) {
                why = refused;
            }
        if (took == 0)
            throw why != null ? why : new java.io.IOException("This circle has no host");
        take(e);
        Runnable r = changed;
        if (r != null)
            r.run();
    }

    // ---------- what everyone works out the same way ----------

    synchronized Set<String> removed() {
        Set<String> out = new HashSet<String>();
        for (HammerCircle.Message m : board.values())
            if (m.kind.equals("remove") && m.key.equals(circle.key))
                out.add(m.ref);
        return out;
    }
    private boolean heard(HammerCircle.Message m, Set<String> removed) {
        return !removed.contains(m.key) && !Circles.muted(app, m.key);
    }
    synchronized List<HammerCircle.Message> chat() {
        Set<String> gone = removed();
        List<HammerCircle.Message> out = new ArrayList<HammerCircle.Message>();
        for (HammerCircle.Message m : room.values())
            if ((m.kind.equals("chat") || m.kind.equals("add")) && heard(m, gone))
                out.add(m);
        Collections.sort(out, BY_TIME);
        return out.size() > 200 ? out.subList(out.size() - 200, out.size()) : out;
    }
    synchronized List<HammerCircle.Message> posts() {
        Set<String> gone = removed();
        List<HammerCircle.Message> out = new ArrayList<HammerCircle.Message>();
        for (HammerCircle.Message m : board.values())
            if (m.kind.equals("post") && heard(m, gone))
                out.add(m);
        Collections.sort(out, BY_TIME);
        return out;
    }
    synchronized List<HammerCircle.Message> reactions(long since) {
        Set<String> gone = removed();
        List<HammerCircle.Message> out = new ArrayList<HammerCircle.Message>();
        for (HammerCircle.Message m : room.values())
            if (m.kind.equals("react") && m.time >= since && heard(m, gone))
                out.add(m);
        Collections.sort(out, BY_TIME);
        return out;
    }
    /** Who's here: anyone who said anything in the last five minutes, by their key, with their latest name. */
    synchronized Map<String, String> here() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        long now = System.currentTimeMillis();
        Set<String> gone = removed();
        for (HammerCircle.Message m : room.values())
            if (now - m.time < HERE && !gone.contains(m.key))
                out.put(m.key, m.name);
        return out;
    }
    /**
     * What has played and what's playing: "now" messages in order of their start, each taken only once the one
     * before it is nearly over (the starter may skip at any time), so two phones starting the next song together
     * agree on the earlier one.
     */
    synchronized List<HammerCircle.Message> played() {
        Set<String> gone = removed();
        List<HammerCircle.Message> nows = new ArrayList<HammerCircle.Message>();
        for (HammerCircle.Message m : room.values())
            if (m.kind.equals("now") && !gone.contains(m.key))
                nows.add(m);
        Collections.sort(nows, new Comparator<HammerCircle.Message>() {
            public int compare(HammerCircle.Message a, HammerCircle.Message b) {
                return a.start != b.start ? Long.compare(a.start, b.start) : a.id.compareTo(b.id);
            }
        });
        List<HammerCircle.Message> out = new ArrayList<HammerCircle.Message>();
        Set<String> used = new HashSet<String>();
        for (HammerCircle.Message m : nows) {
            HammerCircle.Message last = out.isEmpty() ? null : out.get(out.size() - 1);
            boolean after = last == null || m.start >= last.start + last.length - 5000;
            boolean skip = last != null && m.key.equals(circle.key) && m.start > last.start;
            if ((after || skip) && used.add(m.ref))
                out.add(m);
        }
        return out;
    }
    synchronized HammerCircle.Message playing(long now) {
        HammerCircle.Message on = null;
        for (HammerCircle.Message m : played())
            if (m.start <= now + 1500)
                on = m;
        return on != null && now < on.start + on.length ? on : null;
    }
    /** A song in the queue, with who voted for it. */
    static final class Queued {
        final HammerCircle.Message add;
        final Set<String> votes = new HashSet<String>();
        Queued(HammerCircle.Message add) {
            this.add = add;
            votes.add(add.key);
        }
    }
    /** The songs added and not yet played, most votes first, then the earliest added. */
    synchronized List<Queued> queue() {
        Set<String> gone = removed(), done = new HashSet<String>();
        for (HammerCircle.Message m : played())
            done.add(m.ref);
        Map<String, Queued> q = new LinkedHashMap<String, Queued>();
        for (HammerCircle.Message m : room.values())
            if (m.kind.equals("add") && !done.contains(m.id) && !gone.contains(m.key))
                q.put(m.id, new Queued(m));
        for (HammerCircle.Message m : room.values())
            if (m.kind.equals("vote") && q.containsKey(m.ref) && !gone.contains(m.key))
                q.get(m.ref).votes.add(m.key);
        List<Queued> out = new ArrayList<Queued>(q.values());
        Collections.sort(out, new Comparator<Queued>() {
            public int compare(Queued a, Queued b) {
                return a.votes.size() != b.votes.size() ? b.votes.size() - a.votes.size() : Long.compare(a.add.time, b.add.time);
            }
        });
        return out;
    }
    synchronized boolean voted(String add) {
        String me = Hammer.publicKey(joined.me.getPublic());
        for (Queued q : queue())
            if (q.add.id.equals(add))
                return q.votes.contains(me);
        return false;
    }
    /** When nothing is on, or the song is ending, start the queue's top: after a short random wait, if nobody did. */
    private void driveQueue(long now) throws Exception {
        HammerCircle.Message on = playing(now);
        List<Queued> q = queue();
        if (q.isEmpty() || now - startedNext < 15000)
            return;
        List<HammerCircle.Message> all = played();
        HammerCircle.Message last = all.isEmpty() ? null : all.get(all.size() - 1);
        if (last != null && last.start > now)
            return;  // the next one is chosen already
        if (on != null && now < on.start + on.length - 4000)
            return;
        Thread.sleep(new Random().nextInt(2000));
        read(HammerCircle.ROOM, roomSince);
        all = played();
        if (!all.isEmpty() && all.get(all.size() - 1) != last)
            return;  // someone else started it
        q = queue();
        if (q.isEmpty())
            return;
        HammerCircle.Message pick = q.get(0).add, m = new HammerCircle.Message();
        m.kind = "now";
        m.name = Circles.name(app);
        m.ref = pick.id;
        m.song = pick.song;
        m.cards.addAll(pick.cards);
        m.length = pick.length;
        long end = on == null ? 0 : on.start + on.length;
        m.start = Math.max(System.currentTimeMillis() + 2500, end);
        startedNext = System.currentTimeMillis();
        send(m);
    }
    /** The starter skips to the queue's top now. */
    void skip() throws Exception {
        Hammer.require(joined.started, "Only the person who started the circle can skip");
        List<Queued> q = queue();
        Hammer.require(!q.isEmpty(), "Nothing is waiting in the queue");
        HammerCircle.Message pick = q.get(0).add, m = new HammerCircle.Message();
        m.kind = "now";
        m.name = Circles.name(app);
        m.ref = pick.id;
        m.song = pick.song;
        m.cards.addAll(pick.cards);
        m.length = pick.length;
        m.start = System.currentTimeMillis() + 2500;
        startedNext = System.currentTimeMillis();
        send(m);
    }

    /** The player asks: the song on at this moment, or the next one chosen, from the room's clock. */
    public HammerService.Slot next(long now) {
        if (!running)
            return null;
        HammerCircle.Message pick = null;
        for (HammerCircle.Message m : played())
            if (m.start + m.length > now) {
                pick = m;
                break;
            }
        if (pick == null)
            return null;
        List<Hammer.Card> hosts = new ArrayList<Hammer.Card>();
        for (String c : pick.cards)
            try {
                hosts.add(Hammer.Card.parse(c));
            } catch (Exception unreadable) {
            }
        return new HammerService.Slot(circle.title, pick.song, hosts, pick.start, pick.length);
    }

    static final Comparator<HammerCircle.Message> BY_TIME = new Comparator<HammerCircle.Message>() {
        public int compare(HammerCircle.Message a, HammerCircle.Message b) { return Long.compare(a.time, b.time); }
    };
}
