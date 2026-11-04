package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A circle's room: what's playing for everyone, reactions, the queue with its votes, the chat and the board.
 * Opened from Discover with the circle's id, or from an invitation link killayue://c/<id>?k=<key>&via=<host>.
 */
public final class CircleActivity extends Activity {
    private static final String[] MARKS = {"🔥", "❤️", "😂", "🙌", "😮"};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private LinearLayout body, now, queue, said;
    private TextView title, about, people, status;
    private Button listen;
    private EditText line;
    private boolean onBoard, closed;
    private String replyTo = "";
    private CircleRoom room;
    private long reactedUpTo = System.currentTimeMillis() - 5000;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (closed)
                return;
            showNow();
            floatReactions();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(Bundle saved) {
        setTheme(Skin.themeFor(this, Skin.id(this)));
        super.onCreate(saved);
        Skin.load(this);
        root = new FrameLayout(this);
        root.setBackground(Skin.background(this));
        root.setFitsSystemWindows(true);
        ScrollView scroll = new ScrollView(this);
        body = Ui.column(this);
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, pad, pad, Ui.dp(this, 28));
        scroll.addView(body);
        root.addView(scroll);
        setContentView(root);
        title = Ui.text(this, "", Skin.ACCENT, Skin.SCRIPT_TITLES ? 32 : 24);
        title.setTypeface(Skin.script(), Skin.SCRIPT_TITLES ? Typeface.NORMAL : Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(title);
        about = Ui.text(this, "", Skin.TEXT, 14);
        about.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(about, Ui.gap(this, 4));
        people = Ui.text(this, "", Skin.DIM, 12);
        people.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(people, Ui.gap(this, 4));
        status = Ui.text(this, "Opening the circle…", Skin.DIM, 12);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(status, Ui.gap(this, 4));

        Uri link = getIntent().getData();
        if (link != null)
            openInvitation(link);
        else {
            Circles.Joined j = Circles.get(this, getIntent().getStringExtra("circle"));
            if (j == null) {
                Ui.toast(this, "You're not in that circle");
                finish();
                return;
            }
            enter(j);
        }
    }
    @Override
    protected void onDestroy() {
        closed = true;
        if (room != null) {
            room.changed = null;
            if (!HammerService.inCircle())
                room.leave();  // nobody's listening: stop asking the hosts
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------- joining ----------

    /** An invitation: fetch the record from the host it names, check it against the key, and ask before joining. */
    private void openInvitation(final Uri link) {
        final String id = link.getLastPathSegment(), key = link.getQueryParameter("k"), via = link.getQueryParameter("via");
        if (id == null || !Hammer.digest(id) || key == null || !Hammer.digest(key)) {
            Ui.toast(this, "That circle invitation is not valid");
            finish();
            return;
        }
        Circles.Joined already = Circles.get(this, id);
        if (already != null) {
            enter(already);
            return;
        }
        worker.submit(new Runnable() {
            public void run() {
                String why = null;
                HammerCircle found = null;
                try {
                    Hammer.require(via != null, "The invitation names no host");
                    found = Hammer.circle(Hammer.Card.parse(via), id, key, new Hammer.Job());
                    Hammer.require(found != null, "That host no longer carries this circle");
                } catch (Exception e) {
                    why = e.getMessage();
                }
                final HammerCircle circle = found;
                final String said = why;
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        if (circle == null) {
                            status.setText("Couldn't open the invitation: " + said);
                            return;
                        }
                        askToJoin(circle);
                    }
                });
            }
        });
    }
    private void askToJoin(final HammerCircle circle) {
        title.setText(circle.title);
        about.setText(circle.about);
        final EditText name = Ui.edit(this, "Your name in this circle", 1);
        name.setText(Circles.name(this));
        new Lux.Builder(this).setTitle("Join " + circle.title + "?")
            .setMessage((circle.open ? "An open circle: anyone can join, and people in it see who's here."
                                     : "An invite-only circle: only people with the invitation can find it or read it.")
                        + " What's said is sealed with the circle's key; its hosts carry it without reading it.")
            .setView(name)
            .setPositiveButton("Join", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    try {
                        Circles.name(CircleActivity.this, name.getText().toString().trim());
                        enter(Circles.join(CircleActivity.this, circle));
                    } catch (Exception e) {
                        Ui.toast(CircleActivity.this, "Couldn't join: " + e.getMessage());
                        finish();
                    }
                }
            })
            .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { finish(); }
            })
            .show();
    }

    // ---------- the room ----------

    private void enter(Circles.Joined j) {
        room = CircleRoom.enter(this, j);
        title.setText(j.circle.title);
        about.setText(j.circle.about);
        about.setVisibility(j.circle.about.isEmpty() ? View.GONE : View.VISIBLE);
        status.setText(j.circle.open ? "Open circle" : "Invite-only circle");
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        listen = Ui.button(this, HammerService.inCircle() ? "Stop listening" : "Listen along", true);
        listen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleListening(); }
        });
        row.addView(listen);
        Button invite = Ui.chip(this, "Invite");
        invite.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { invite(); }
        });
        row.addView(invite);
        Button leave = Ui.chip(this, "Leave");
        leave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { leave(); }
        });
        row.addView(leave);
        body.addView(row, Ui.gap(this, 12));

        now = plate();
        body.addView(now, Ui.gap(this, 14));
        LinearLayout marks = Ui.row(this);
        marks.setGravity(Gravity.CENTER_HORIZONTAL);
        for (final String mark : MARKS) {
            Button b = Ui.chip(this, mark);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    HammerCircle.Message m = new HammerCircle.Message();
                    m.kind = "react";
                    m.body = mark;
                    send(m, null);
                }
            });
            marks.addView(b);
        }
        body.addView(marks, Ui.gap(this, 8));

        body.addView(heading("UP NEXT"));
        queue = Ui.column(this);
        body.addView(queue);
        LinearLayout qrow = Ui.row(this);
        Button add = Ui.chip(this, "Add a song");
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addSong(); }
        });
        qrow.addView(add);
        if (j.started) {
            Button skip = Ui.chip(this, "Skip to next");
            skip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    worker.submit(new Runnable() {
                        public void run() {
                            try {
                                room.skip();
                            } catch (final Exception e) {
                                toast(e.getMessage());
                            }
                        }
                    });
                }
            });
            qrow.addView(skip);
        }
        body.addView(qrow, Ui.gap(this, 8));

        LinearLayout tabs = Ui.row(this);
        final Button chat = Ui.chip(this, "Chat"), board = Ui.chip(this, "Board");
        chat.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBoard = false;
                replyTo = "";
                render();
            }
        });
        board.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBoard = true;
                replyTo = "";
                render();
            }
        });
        tabs.addView(chat);
        tabs.addView(board);
        body.addView(tabs, Ui.gap(this, 18));
        LinearLayout input = Ui.row(this);
        line = Ui.edit(this, "Say something", 1);
        input.addView(line, Ui.weight());
        Button send = Ui.chip(this, "Send");
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { say(); }
        });
        input.addView(send);
        body.addView(input, Ui.gap(this, 8));
        said = Ui.column(this);
        body.addView(said, Ui.gap(this, 8));

        room.changed = new Runnable() {
            public void run() {
                ui.post(new Runnable() {
                    public void run() { if (!closed) render(); }
                });
            }
        };
        render();
        ui.post(tick);
    }
    private void render() {
        if (room == null)
            return;
        Map<String, String> here = room.here();
        people.setText(here.isEmpty() ? "Nobody else here yet" : here.size() == 1 ? "1 here" : here.size() + " here");
        if (room.circle.open && !here.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String n : here.values())
                if (!n.isEmpty() && b.length() < 120)
                    b.append(b.length() == 0 ? "" : ", ").append(n);
            if (b.length() > 0)
                people.setText(people.getText() + ": " + b);
        }
        if (room.trouble != null)
            status.setText(room.trouble);
        showNow();
        queue.removeAllViews();
        List<CircleRoom.Queued> q = room.queue();
        if (q.isEmpty())
            queue.addView(Ui.text(this, "Nothing waiting. Add a song and the room plays it.", Skin.DIM, 13));
        for (final CircleRoom.Queued item : q) {
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = Ui.text(this, item.add.song.title + "  ·  added by " + shown(item.add), Skin.TEXT, 14);
            r.addView(t, Ui.weight());
            final boolean mine = room.voted(item.add.id);
            Button v = Ui.chip(this, "▲ " + item.votes.size());
            v.setEnabled(!mine);
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    HammerCircle.Message m = new HammerCircle.Message();
                    m.kind = "vote";
                    m.ref = item.add.id;
                    send(m, null);
                }
            });
            r.addView(v);
            queue.addView(r, Ui.gap(this, 4));
        }
        said.removeAllViews();
        line.setHint(onBoard ? (replyTo.isEmpty() ? "Write a post for the board" : "Write a reply") : "Say something");
        if (onBoard) {
            List<HammerCircle.Message> posts = room.posts();
            if (posts.isEmpty())
                said.addView(Ui.text(this, "The board is empty. Posts here stay for 30 days.", Skin.DIM, 13));
            Map<String, List<HammerCircle.Message>> replies = new HashMap<String, List<HammerCircle.Message>>();
            for (HammerCircle.Message p : posts)
                if (!p.ref.isEmpty()) {
                    if (!replies.containsKey(p.ref))
                        replies.put(p.ref, new ArrayList<HammerCircle.Message>());
                    replies.get(p.ref).add(p);
                }
            for (int i = posts.size() - 1; i >= 0; i--) {
                final HammerCircle.Message p = posts.get(i);
                if (!p.ref.isEmpty())
                    continue;
                LinearLayout card = plate();
                card.addView(Ui.text(this, shown(p) + "  ·  " + ago(p.time), Skin.ACCENT, 12));
                card.addView(Ui.text(this, p.body, Skin.TEXT, 15), Ui.gap(this, 4));
                if (replies.containsKey(p.id))
                    for (HammerCircle.Message r : replies.get(p.id)) {
                        TextView t = Ui.text(this, "↳ " + shown(r) + ": " + r.body, Skin.TEXT, 13);
                        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), 0, 0);
                        longPress(t, r);
                        card.addView(t);
                    }
                Button reply = Ui.chip(this, "Reply");
                reply.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        replyTo = p.id;
                        render();
                        line.requestFocus();
                    }
                });
                card.addView(reply, Ui.gap(this, 6));
                longPress(card, p);
                said.addView(card, Ui.gap(this, 8));
            }
        } else {
            List<HammerCircle.Message> lines = room.chat();
            if (lines.isEmpty())
                said.addView(Ui.text(this, "Nothing said yet.", Skin.DIM, 13));
            for (int i = lines.size() - 1; i >= 0; i--) {
                HammerCircle.Message m = lines.get(i);
                TextView t = Ui.text(this, m.kind.equals("add") ? shown(m) + " added " + m.song.title
                                                                : shown(m) + ": " + m.body,
                                     m.kind.equals("add") ? Skin.DIM : Skin.TEXT, 14);
                longPress(t, m);
                said.addView(t, Ui.gap(this, 6));
            }
        }
    }
    private void showNow() {
        if (room == null || now == null)
            return;
        now.removeAllViews();
        long t = System.currentTimeMillis();
        HammerCircle.Message on = room.playing(t);
        if (on == null) {
            now.addView(Ui.text(this, "Quiet for now", Skin.DIM, 15));
            return;
        }
        TextView n = Ui.text(this, "♪ " + on.song.title, Skin.ACCENT, 18);
        n.setTypeface(Skin.text(), Typeface.BOLD);
        now.addView(n);
        long left = Math.max(0, on.start + on.length - t) / 1000;
        now.addView(Ui.text(this, (t < on.start ? "Starting…" : String.format(Locale.ROOT, "%d:%02d left", left / 60, left % 60))
                                       + "  ·  chosen by " + shown(on), Skin.DIM, 13), Ui.gap(this, 4));
    }
    /** New reactions rise over the room for a moment, for everyone. */
    private void floatReactions() {
        if (room == null)
            return;
        for (HammerCircle.Message m : room.reactions(reactedUpTo)) {
            reactedUpTo = Math.max(reactedUpTo, m.time + 1);
            final TextView f = Ui.text(this, m.body, Skin.TEXT, 34);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                                                                       FrameLayout.LayoutParams.WRAP_CONTENT,
                                                                       Gravity.BOTTOM | Gravity.START);
            lp.leftMargin = Ui.dp(this, 40 + new Random().nextInt(240));
            lp.bottomMargin = Ui.dp(this, 60);
            root.addView(f, lp);
            f.animate().translationY(-Ui.dp(this, 320)).alpha(0f).setDuration(2600)
                .setListener(new AnimatorListenerAdapter() {
                    public void onAnimationEnd(Animator a) { root.removeView(f); }
                }).start();
        }
    }

    // ---------- doing things ----------

    private void say() {
        final String text = line.getText().toString().trim();
        if (text.isEmpty())
            return;
        HammerCircle.Message m = new HammerCircle.Message();
        m.kind = onBoard ? "post" : "chat";
        m.body = text;
        m.ref = onBoard ? replyTo : "";
        send(m, new Runnable() {
            public void run() {
                line.setText("");
                replyTo = "";
            }
        });
    }
    /** Signs, seals (a moment's work, a second for a board post) and sends, off the main thread. */
    private void send(final HammerCircle.Message m, final Runnable done) {
        if (room == null)
            return;
        String name = Circles.name(this);
        m.name = name.length() > 40 ? name.substring(0, 40) : name;
        worker.submit(new Runnable() {
            public void run() {
                try {
                    room.send(m);
                    if (done != null)
                        ui.post(done);
                } catch (Exception e) {
                    toast("Not sent: " + e.getMessage());
                }
            }
        });
    }
    private void toggleListening() {
        if (HammerService.inCircle()) {
            HammerService.leaveCircle();
            listen.setText("Listen along");
            return;
        }
        try {
            HammerService.tuneCircle(this, room);
            listen.setText("Stop listening");
        } catch (Exception e) {
            Ui.toast(this, "Couldn't listen along: " + e.getMessage());
        }
    }
    /** Picks a song from your own shared ones and those Discover has seen, and adds it to the queue. */
    private void addSong() {
        final List<Hammer.Song> songs = new ArrayList<Hammer.Song>();
        final List<List<Hammer.Card>> hosts = new ArrayList<List<Hammer.Card>>();
        final List<java.io.File> files = new ArrayList<java.io.File>();
        try {
            Hammer.Card own = Hammer.Card.p2p(HammerAndroid.network(this), HammerAndroid.pin(), "");
            for (HammerStore.Hosted h : HammerAndroid.store(this).hosted())
                if (h.publicSong) {
                    songs.add(h.song);
                    hosts.add(Collections.singletonList(own));
                    files.add(h.file);
                }
        } catch (Exception noOwn) {
        }
        for (Map.Entry<Hammer.Song, List<Hammer.Card>> e : HammerActivity.songsSeen().entrySet()) {
            boolean dup = false;
            for (Hammer.Song s : songs)
                dup |= s.id.equals(e.getKey().id);
            if (!dup && songs.size() < 400) {
                songs.add(e.getKey());
                hosts.add(e.getValue());
                files.add(null);
            }
        }
        if (songs.isEmpty()) {
            Ui.toast(this, "No songs to add yet: open Discover first, or share a song publicly");
            return;
        }
        String[] names = new String[songs.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = songs.get(i).title;
        new Lux.Builder(this).setTitle("Add a song")
            .setItems(names, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, final int i) {
                    worker.submit(new Runnable() {
                        public void run() {
                            try {
                                HammerCircle.Message m = new HammerCircle.Message();
                                m.kind = "add";
                                m.song = songs.get(i);
                                for (Hammer.Card c : hosts.get(i))
                                    m.cards.add(c.encode());
                                long frames = files.get(i) != null ? HammerMedia.codec.frames(files.get(i))
                                                                   : Hammer.frames(hosts.get(i).get(0), m.song.id, new Hammer.Job());
                                m.length = (int)(frames * 1000 / HammerMedia.RATE);
                                String name = Circles.name(CircleActivity.this);
                                m.name = name.length() > 40 ? name.substring(0, 40) : name;
                                room.send(m);
                            } catch (Exception e) {
                                toast("Couldn't add it: " + e.getMessage());
                            }
                        }
                    });
                }
            })
            .setNegativeButton("Cancel", null).show();
    }
    private void invite() {
        try {
            final String link = room.circle.invitation();
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Join my circle \"" + room.circle.title + "\" in Killa YuE: " + link);
            startActivity(Intent.createChooser(send, "Invite to " + room.circle.title));
        } catch (Exception e) {
            Ui.toast(this, "Couldn't make the invitation: " + e.getMessage());
        }
    }
    private void leave() {
        new Lux.Builder(this).setTitle("Leave " + room.circle.title + "?")
            .setMessage(room.joined.started
                            ? "You started this circle and this phone carries it. If you leave, this phone stops carrying it; its other hosts keep it."
                            : "You can join again with an invitation" + (room.circle.open ? " or from Discover." : "."))
            .setPositiveButton("Leave", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    try {
                        room.leave();
                        Circles.leave(CircleActivity.this, room.circle.id);
                    } catch (Exception ignored) {
                    }
                    finish();
                }
            })
            .setNegativeButton("Stay", null).show();
    }
    /** Hold a message: mute its sender on this phone, or, for the circle's starter, remove them from the circle. */
    private void longPress(View v, final HammerCircle.Message m) {
        v.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View x) {
                final boolean muted = Circles.muted(CircleActivity.this, m.key);
                final boolean mine = m.key.equals(Hammer.publicKey(room.joined.me.getPublic()));
                if (mine)
                    return true;
                Lux.Builder b = new Lux.Builder(CircleActivity.this);
                b.setTitle(shown(m))
                    .setPositiveButton(muted ? "Unmute" : "Mute on this phone", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            Circles.mute(CircleActivity.this, m.key, !muted);
                            render();
                        }
                    })
                    .setNegativeButton("Cancel", null);
                if (room.joined.started)
                    b.setNeutralButton("Remove from circle", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            HammerCircle.Message r = new HammerCircle.Message();
                            r.kind = "remove";
                            r.ref = m.key;
                            r.body = "removed " + m.name;
                            send(r, null);
                        }
                    });
                b.setMessage("Muting hides what they say on this phone only."
                             + (room.joined.started ? " Removing hides them for everyone in the circle." : ""));
                b.show();
                return true;
            }
        });
    }

    // ---------- small things ----------

    private String shown(HammerCircle.Message m) {
        try {
            String who = m.name.isEmpty() ? "someone" : m.name;
            return who + " #" + m.who() + (m.key.equals(room.circle.key) ? " (started it)" : "");
        } catch (Exception badKey) {
            return "someone";
        }
    }
    private static String ago(long t) {
        long m = Math.max(0, System.currentTimeMillis() - t) / 60000;
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? (m / 60) + " h ago" : (m / 1440) + " d ago";
    }
    private LinearLayout plate() {
        LinearLayout p = Ui.column(this);
        p.setBackground(new LuxFrame(14, 8));
        int in = Ui.dp(this, 24), down = Ui.dp(this, 18);
        p.setPadding(in, down, in, down);
        return p;
    }
    private TextView heading(String name) {
        TextView h = Ui.label(this, name);
        h.setTextColor(Skin.ACCENT);
        h.setLetterSpacing(0.12f);
        h.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 4));
        return h;
    }
    private void toast(final String s) {
        ui.post(new Runnable() {
            public void run() { if (!closed) Ui.toast(CircleActivity.this, s); }
        });
    }
}
