package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A circle's room: what's playing for everyone, reactions, the queue with its votes, the chat and the board.
 * Opened from Discover with the circle's id, or from an invitation link killayue://c/<id>?k=<key>&via=<host>.
 *
 * The top stays put (the record, the circle's name, what's on, the queue) and the talk scrolls under it, newest at
 * the bottom as in any chat, with the field to write in held at the foot. Tap a message to reply to it; hold it to
 * mute its sender. Names show bare; the short tag of someone's key shows only when two people share a name.
 */
public final class CircleActivity extends Activity {
    private static final String[] MARKS = {"🔥", "❤️", "😂", "🙌", "😮"};
    private static final int SHOWN_QUEUE = 3;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private LinearLayout page, top, extras, queue, said, replyBar;
    private ScrollView talk;
    private RecordView record;
    private TextView title, about, people, status, nowTitle, nowBy, nowTime, replyText, upNext;
    private Progress progress;
    private Knob listen;
    private TextView listenLabel;
    private Button chatTab, boardTab, skipButton;
    private EditText line;
    private boolean onBoard, closed, queueOpen, keyboard;
    private String replyTo = "", shownTalk = "", shownQueue = "";
    private CircleRoom room;
    private final Set<String> floated = new HashSet<String>();
    private final long openedAt = System.currentTimeMillis() - 5000;
    private java.text.DateFormat clock;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (closed)
                return;
            showNow();
            if (listen != null)
                showListening();
            floatReactions();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(Bundle saved) {
        setTheme(Skin.themeFor(this, Skin.id(this)));
        super.onCreate(saved);
        Skin.load(this);
        clock = android.text.format.DateFormat.getTimeFormat(this);
        root = new FrameLayout(this);
        root.setBackground(Skin.background(this));
        root.setFitsSystemWindows(true);
        page = Ui.column(this);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // the circle's name beside its record
        top = Ui.column(this);
        int pad = Ui.dp(this, 16);
        top.setPadding(pad, Ui.dp(this, 10), pad, 0);
        page.addView(top);
        LinearLayout head = Ui.row(this);
        record = new RecordView(this);
        head.addView(record, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        LinearLayout names = Ui.column(this);
        names.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0);
        title = Ui.title(this, "");
        title.setTextSize(Skin.SCRIPT_TITLES ? 26 : 19);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);
        people = Ui.text(this, "", Skin.DIM, 12);
        people.setSingleLine(true);
        people.setEllipsize(TextUtils.TruncateAt.END);
        people.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { whoIsHere(); }
        });
        names.addView(people);
        head.addView(names, Ui.weight());
        Button more = Ui.chip(this, "•••");
        more.setTextSize(11);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { menu(); }
        });
        ((LinearLayout.LayoutParams) more.getLayoutParams()).rightMargin = 0;
        head.addView(more);
        top.addView(head);
        about = Ui.text(this, "", Skin.TEXT, 13);
        about.setMaxLines(2);
        about.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(about, Ui.gap(this, 6));
        status = Ui.text(this, "Opening the circle…", Skin.DIM, 12);
        top.addView(status, Ui.gap(this, 4));

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
            .setView(padded(name))
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
        status.setText("");
        status.setVisibility(View.GONE);

        // what's on: the song, who it's by, how long it has left, and the knob to listen along
        LinearLayout now = plate();
        LinearLayout nowRow = Ui.row(this);
        LinearLayout nowText = Ui.column(this);
        nowTitle = Ui.text(this, "", Skin.ACCENT, 17);
        nowTitle.setTypeface(Skin.text(), Typeface.BOLD);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowText.addView(nowTitle);
        nowBy = Ui.text(this, "", Skin.TEXT, 13);
        nowBy.setSingleLine(true);
        nowBy.setEllipsize(TextUtils.TruncateAt.END);
        nowText.addView(nowBy, Ui.gap(this, 2));
        progress = new Progress(this);
        nowText.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 10)));
        ((LinearLayout.LayoutParams) progress.getLayoutParams()).topMargin = Ui.dp(this, 8);
        nowTime = Ui.text(this, "", Skin.DIM, 11);
        nowTime.setSingleLine(true);
        nowTime.setEllipsize(TextUtils.TruncateAt.END);
        nowText.addView(nowTime, Ui.gap(this, 2));
        nowRow.addView(nowText, Ui.weight());
        LinearLayout knob = Ui.column(this);
        knob.setGravity(Gravity.CENTER_HORIZONTAL);
        listen = new Knob(this, Knob.PLAY);
        listen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleListening(); }
        });
        listen.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Ui.toast(CircleActivity.this, HammerService.playback);
                return true;
            }
        });
        knob.addView(listen, new LinearLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 62)));
        listenLabel = Ui.text(this, "", Skin.DIM, 10);
        listenLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        knob.addView(listenLabel);
        nowRow.addView(knob, new LinearLayout.LayoutParams(Ui.dp(this, 78), -2));
        now.addView(nowRow);
        top.addView(now, Ui.gap(this, 10));
        showListening();

        extras = Ui.column(this);
        top.addView(extras);
        // reactions: they rise over the room for everyone
        LinearLayout marks = Ui.row(this);
        for (final String mark : MARKS) {
            final TextView b = new TextView(this);
            b.setText(mark);
            b.setTextSize(20);
            b.setGravity(Gravity.CENTER);
            GradientDrawable o = new GradientDrawable();
            o.setShape(GradientDrawable.OVAL);
            o.setColor(Skin.CARD);
            o.setStroke(Ui.dp(this, 1), Skin.EDGE);
            b.setBackground(o);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(90).withEndAction(new Runnable() {
                        public void run() { b.animate().scaleX(1f).scaleY(1f).setDuration(140).start(); }
                    }).start();
                    rise(mark, v);
                    HammerCircle.Message m = new HammerCircle.Message();
                    m.kind = "react";
                    m.body = mark;
                    send(m, null);
                }
            });
            FrameLayout cell = new FrameLayout(this);
            cell.addView(b, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));
            marks.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        }
        extras.addView(marks, Ui.gap(this, 8));

        // up next
        LinearLayout qhead = Ui.row(this);
        upNext = heading("UP NEXT");
        qhead.addView(upNext, Ui.weight());
        if (j.started) {
            final Button skip = Ui.chip(this, "Skip ▸▸");
            skipButton = skip;
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
            qhead.addView(skip);
        }
        Button add = Ui.chip(this, "+ Add a song");
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addSong(); }
        });
        ((LinearLayout.LayoutParams) add.getLayoutParams()).rightMargin = 0;
        qhead.addView(add);
        extras.addView(qhead, Ui.gap(this, 4));
        queue = Ui.column(this);
        extras.addView(queue);

        // chat or the board, as two plates side by side, the chosen one pressed in
        LinearLayout tabs = Ui.row(this);
        chatTab = tab("Chat", false);
        boardTab = tab("Board", true);
        tabs.addView(chatTab, tabParams(false));
        tabs.addView(boardTab, tabParams(true));
        top.addView(tabs, Ui.gap(this, 10));

        talk = new ScrollView(this);
        talk.setFillViewport(true);
        talk.setVerticalFadingEdgeEnabled(true);
        talk.setFadingEdgeLength(Ui.dp(this, 18));
        said = Ui.column(this);
        said.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        talk.addView(said);
        page.addView(talk, new LinearLayout.LayoutParams(-1, 0, 1f));

        // replying to: shown over the field while a reply is being written
        replyBar = Ui.row(this);
        replyBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        replyBar.setBackground(Ui.box(this, Skin.accent(0x1E), 0));
        View bar = new View(this);
        bar.setBackgroundColor(Skin.ACCENT);
        replyBar.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 3), Ui.dp(this, 30)));
        replyText = Ui.text(this, "", Skin.TEXT, 12);
        replyText.setMaxLines(2);
        replyText.setEllipsize(TextUtils.TruncateAt.END);
        replyText.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        replyBar.addView(replyText, Ui.weight());
        Button cancel = Ui.chip(this, "✕");
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { replyTo(null); }
        });
        replyBar.addView(cancel);
        replyBar.setVisibility(View.GONE);
        page.addView(replyBar);

        LinearLayout input = Ui.row(this);
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 8));
        line = Ui.edit(this, "Say something", 1);
        GradientDrawable field = Ui.box(this, Skin.CARD, 22);
        field.setStroke(Ui.dp(this, 1), Skin.EDGE);
        line.setBackground(field);
        line.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
        line.setImeOptions(EditorInfo.IME_ACTION_SEND);
        line.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int action, android.view.KeyEvent e) {
                say();
                return true;
            }
        });
        input.addView(line, Ui.weight());
        Knob sendKnob = new Knob(this, Knob.SEND);
        sendKnob.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { say(); }
        });
        input.addView(sendKnob, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        page.addView(input);

        // with the keyboard up the talk needs the room: the reactions and the queue step aside
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                android.graphics.Rect r = new android.graphics.Rect();
                root.getWindowVisibleDisplayFrame(r);
                boolean up = root.getRootView().getHeight() - r.height() > Ui.dp(CircleActivity.this, 200);
                if (up != keyboard) {
                    keyboard = up;
                    extras.setVisibility(up ? View.GONE : View.VISIBLE);
                    about.setVisibility(up || room.circle.about.isEmpty() ? View.GONE : View.VISIBLE);
                    if (up && !onBoard)
                        toBottom();
                }
            }
        });

        room.changed = new Runnable() {
            public void run() {
                ui.post(new Runnable() {
                    public void run() { if (!closed) render(); }
                });
            }
        };
        showTab();
        ui.post(tick);
    }
    private Button tab(String name, final boolean board) {
        Button b = Ui.button(this, name, false);
        b.setTextSize(14);
        b.setMinHeight(Ui.dp(this, 42));
        b.setMinimumHeight(Ui.dp(this, 42));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (onBoard == board)
                    return;
                onBoard = board;
                replyTo(null);
                showTab();
            }
        });
        return b;
    }
    private LinearLayout.LayoutParams tabParams(boolean right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f);
        if (right)
            lp.leftMargin = Ui.dp(this, 8);
        return lp;
    }
    private void showTab() {
        Button[] both = {chatTab, boardTab};
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == onBoard;
            both[i].setBackground(new Skin.Plate(true, !on));  // the chosen one stands up bright; the other sits back
            both[i].setTextColor(on ? Skin.HI : Skin.LO);
            both[i].setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 1));
        }
        shownTalk = "";
        render();
        if (!onBoard)
            toBottom();
        else
            talk.post(new Runnable() {
                public void run() { talk.scrollTo(0, 0); }
            });
    }

    private void render() {
        if (room == null)
            return;
        Map<String, String> here = room.here();
        String state = room.trouble != null ? room.trouble : room.circle.open ? "Open circle" : "Invite-only";
        String count = here.isEmpty() ? "nobody else here yet" : here.size() + " here";
        if (room.circle.open && !here.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String n : here.values())
                if (!n.isEmpty() && b.length() < 60)
                    b.append(b.length() == 0 ? "" : ", ").append(n);
            if (b.length() > 0)
                count += ": " + b;
        }
        people.setText(state + "  ·  " + count);
        people.setTextColor(room.trouble != null ? Skin.HI : Skin.DIM);
        showNow();
        showQueue();
        showTalk();
    }

    private void showQueue() {
        List<CircleRoom.Queued> q = room.queue();
        StringBuilder sig = new StringBuilder(queueOpen ? "open" : "shut");
        for (CircleRoom.Queued item : q)
            sig.append(item.add.id).append(item.votes.size()).append(room.voted(item.add.id));
        if (sig.toString().equals(shownQueue))
            return;
        shownQueue = sig.toString();
        if (skipButton != null)
            skipButton.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        upNext.setText(q.isEmpty() ? "UP NEXT" : "UP NEXT  ·  " + q.size());
        queue.removeAllViews();
        if (q.isEmpty()) {
            TextView t = Ui.text(this, "Nothing waiting. Add a song and the room plays it.", Skin.DIM, 13);
            queue.addView(t, Ui.gap(this, 2));
            return;
        }
        int n = 0;
        for (final CircleRoom.Queued item : q) {
            if (!queueOpen && n == SHOWN_QUEUE)
                break;
            n++;
            LinearLayout r = Ui.row(this);
            r.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            r.setBackground(Ui.box(this, Skin.accent(n == 1 ? 0x18 : 0x0A), 12));
            TextView place = Ui.text(this, String.valueOf(n), n == 1 ? Skin.ACCENT : Skin.DIM, 15);
            place.setTypeface(Skin.text(), Typeface.BOLD);
            place.setMinWidth(Ui.dp(this, 22));
            r.addView(place);
            LinearLayout words = Ui.column(this);
            TextView t = Ui.text(this, item.add.song.title, Skin.TEXT, 14);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(t);
            String artist = HammerActivity.artistOf(item.add.song.key);
            TextView by = Ui.text(this, (artist.isEmpty() ? "" : artist + "  ·  ") + "added by " + shown(item.add), Skin.DIM, 11);
            by.setSingleLine(true);
            by.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(by);
            r.addView(words, Ui.weight());
            final boolean mine = room.voted(item.add.id);
            Button v = Ui.chip(this, "▲ " + item.votes.size());
            if (mine) {
                GradientDrawable lit = Ui.box(this, Skin.accent(0x55), 18);
                lit.setStroke(Ui.dp(this, 1), Skin.ACCENT);
                v.setBackground(lit);
                v.setTextColor(Skin.HI);
            } else
                v.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View x) {
                        HammerCircle.Message m = new HammerCircle.Message();
                        m.kind = "vote";
                        m.ref = item.add.id;
                        send(m, null);
                    }
                });
            ((LinearLayout.LayoutParams) v.getLayoutParams()).rightMargin = 0;
            r.addView(v);
            queue.addView(r, Ui.gap(this, 4));
        }
        if (q.size() > SHOWN_QUEUE) {
            TextView more = Ui.text(this, queueOpen ? "Show fewer" : "Show all " + q.size(), Skin.ACCENT, 12);
            more.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), 0, Ui.dp(this, 2));
            more.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    queueOpen = !queueOpen;
                    showQueue();
                }
            });
            queue.addView(more);
        }
    }

    private void showTalk() {
        List<HammerCircle.Message> list = onBoard ? room.posts() : room.chat();
        String sig = (onBoard ? "b" : "c") + list.size() + (list.isEmpty() ? "" : list.get(list.size() - 1).id) + muteMark();
        if (sig.equals(shownTalk))
            return;
        shownTalk = sig;
        boolean atBottom = !talk.canScrollVertically(1);
        said.removeAllViews();
        line.setHint(onBoard ? (replyTo.isEmpty() ? "Write a post for the board" : "Write a reply") : "Say something");
        Set<String> twins = twins(list);
        Map<String, HammerCircle.Message> byId = new HashMap<String, HammerCircle.Message>();
        for (HammerCircle.Message m : list)
            byId.put(m.id, m);
        if (onBoard) {
            if (list.isEmpty())
                said.addView(empty("The board is empty. Posts here stay for 30 days."));
            Map<String, List<HammerCircle.Message>> replies = new HashMap<String, List<HammerCircle.Message>>();
            for (HammerCircle.Message p : list)
                if (!p.ref.isEmpty()) {
                    if (!replies.containsKey(p.ref))
                        replies.put(p.ref, new ArrayList<HammerCircle.Message>());
                    replies.get(p.ref).add(p);
                }
            for (int i = list.size() - 1; i >= 0; i--) {
                final HammerCircle.Message p = list.get(i);
                if (!p.ref.isEmpty() && byId.containsKey(p.ref))
                    continue;  // shown under its post
                LinearLayout card = Ui.column(this);
                card.setBackground(Ui.glass(this, Skin.EDGE));
                card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 10));
                card.addView(whoLine(p, twins, true));
                TextView body = Ui.text(this, p.body, Skin.TEXT, 15);
                body.setLineSpacing(0, 1.15f);
                card.addView(body, Ui.gap(this, 4));
                if (replies.containsKey(p.id))
                    for (HammerCircle.Message r : replies.get(p.id)) {
                        LinearLayout rr = Ui.column(this);
                        rr.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), 0, 0);
                        rr.addView(whoLine(r, twins, false));
                        rr.addView(Ui.text(this, r.body, Skin.TEXT, 14), Ui.gap(this, 2));
                        longPress(rr, r);
                        card.addView(rr);
                    }
                TextView reply = Ui.text(this, replies.containsKey(p.id) ? "Reply  ·  " + replies.get(p.id).size()
                                                                           : "Reply", Skin.ACCENT, 12);
                reply.setTypeface(Skin.text(), Typeface.BOLD);
                reply.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 2));
                reply.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { replyTo(p); }
                });
                card.addView(reply);
                card.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { replyTo(p); }
                });
                longPress(card, p);
                said.addView(card, Ui.gap(this, 10));
            }
        } else {
            if (list.isEmpty())
                said.addView(empty("Nothing said yet. Say hello."));
            String me = Hammer.publicKey(room.joined.me.getPublic()), lastKey = "";
            long lastTime = 0;
            for (final HammerCircle.Message m : list) {
                if (m.kind.equals("add")) {
                    TextView t = Ui.text(this, "♪  " + shown(m, twins) + " added " + m.song.title, Skin.DIM, 12);
                    t.setGravity(Gravity.CENTER_HORIZONTAL);
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    said.addView(t, Ui.gap(this, 8));
                    lastKey = "";
                    continue;
                }
                boolean mine = m.key.equals(me);
                // one name over a run of lines from the same person, as messengers do
                boolean run = m.key.equals(lastKey) && m.time - lastTime < 5 * 60 * 1000 && m.ref.isEmpty();
                lastKey = m.key;
                lastTime = m.time;
                LinearLayout bubble = Ui.column(this);
                GradientDrawable g = Ui.box(this, mine ? Skin.accent(0x2A) : Skin.CARD, 16);
                g.setStroke(Ui.dp(this, 1), mine ? Skin.accent(0x88) : Skin.EDGE);
                bubble.setBackground(g);
                bubble.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
                if (!run && !mine)
                    bubble.addView(whoLine(m, twins, true));
                HammerCircle.Message quoted = m.ref.isEmpty() ? null : byId.get(m.ref);
                if (quoted != null) {
                    TextView q = Ui.text(this, shown(quoted, twins) + ": " + quoted.body, Skin.DIM, 12);
                    q.setMaxLines(2);
                    q.setEllipsize(TextUtils.TruncateAt.END);
                    q.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), 0, Ui.dp(this, 2));
                    q.setBackground(new Quote());
                    bubble.addView(q, Ui.gap(this, 3));
                }
                TextView body = Ui.text(this, m.body, Skin.TEXT, 15);
                body.setLineSpacing(0, 1.1f);
                bubble.addView(body, Ui.gap(this, 2));
                TextView when = Ui.text(this, clock.format(new Date(m.time)), Skin.FAINT, 10);
                when.setGravity(Gravity.END);
                bubble.addView(when);
                bubble.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { replyTo(m); }
                });
                longPress(bubble, m);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = Ui.dp(this, run ? 3 : 10);
                lp.gravity = mine ? Gravity.END : Gravity.START;
                if (mine)
                    lp.leftMargin = Ui.dp(this, 48);
                else
                    lp.rightMargin = Ui.dp(this, 48);
                said.addView(bubble, lp);
            }
        }
        if (!onBoard && atBottom)
            toBottom();
    }
    private void toBottom() {
        talk.post(new Runnable() {
            public void run() { talk.scrollTo(0, Math.max(0, said.getHeight() - talk.getHeight())); }
        });
    }
    private String muteMark() {
        return String.valueOf(getSharedPreferences("circles", 0).getStringSet("muted", new HashSet<String>()).hashCode())
            + room.removed().hashCode();
    }
    private TextView empty(String s) {
        TextView t = Ui.text(this, s, Skin.DIM, 13);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, Ui.dp(this, 24), 0, 0);
        return t;
    }
    /** The sender's name in gold, the starter marked with a star, and how long ago. */
    private TextView whoLine(HammerCircle.Message m, Set<String> twins, boolean big) {
        String when = onBoard ? "  ·  " + ago(m.time) : "";
        TextView t = Ui.text(this, shown(m, twins) + when, Skin.ACCENT, big ? 12 : 11);
        t.setTypeface(Skin.text(), Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
    private void replyTo(HammerCircle.Message m) {
        replyTo = m == null ? "" : m.id;
        if (m == null) {
            replyBar.setVisibility(View.GONE);
        } else {
            replyText.setText("Replying to " + shown(m) + "\n" + m.body);
            replyBar.setVisibility(View.VISIBLE);
            line.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(line, 0);
        }
        line.setHint(onBoard ? (replyTo.isEmpty() ? "Write a post for the board" : "Write a reply")
                             : replyTo.isEmpty() ? "Say something" : "Write a reply");
    }

    private void showNow() {
        if (room == null || nowTitle == null)
            return;
        long t = System.currentTimeMillis();
        HammerCircle.Message on = room.playing(t);
        record.setSpinning(on != null && t >= on.start);
        if (on == null) {
            nowTitle.setText("Quiet for now");
            nowTitle.setTextColor(Skin.DIM);
            nowBy.setText(room.queue().isEmpty() ? "Add a song and the room plays it" : "The next song starts in a moment");
            progress.set(0);
            progress.setVisibility(View.GONE);
            nowTime.setText("");
            nowTime.setVisibility(View.GONE);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        nowTime.setVisibility(View.VISIBLE);
        nowTitle.setText(on.song.title);
        nowTitle.setTextColor(Skin.ACCENT);
        String artist = HammerActivity.artistOf(on.song.key);
        nowBy.setText(artist.isEmpty() ? "chosen by " + shown(on) : artist);
        long gone = Math.max(0, t - on.start) / 1000, all = on.length / 1000;
        progress.set(on.length <= 0 ? 0 : Math.min(1f, (t - on.start) / (float) on.length));
        nowTime.setText(t < on.start ? "Starting…"
                                     : String.format(Locale.ROOT, "%d:%02d / %d:%02d", gone / 60, gone % 60, all / 60, all % 60)
                                           + (artist.isEmpty() ? "" : "  ·  chosen by " + shown(on)));
    }
    private void showListening() {
        boolean on = HammerService.inCircle();
        String state = HammerService.playback == null ? "" : HammerService.playback;
        boolean sounding = on && state.startsWith("Playing");
        listen.set(on ? Knob.PAUSE : Knob.PLAY, sounding);
        listenLabel.setText(!on ? "Listen along" : sounding ? "Listening"
                            : state.startsWith("Buffering") || state.startsWith("Connecting") ? "Tuning in…"
                            : state.startsWith("Waiting") ? "Waiting" : "Can't reach\nthe song");
        listenLabel.setTextColor(sounding ? Skin.ACCENT : on ? Skin.HI : Skin.DIM);
    }

    /** New reactions rise over the room for a moment, for everyone; your own rose already when you tapped. */
    private void floatReactions() {
        if (room == null)
            return;
        String me = Hammer.publicKey(room.joined.me.getPublic());
        for (HammerCircle.Message m : room.reactions(openedAt))
            if (floated.add(m.id) && !m.key.equals(me) && System.currentTimeMillis() - m.time < 30000)
                rise(m.body, null);
    }
    private void rise(String mark, View from) {
        final TextView f = Ui.text(this, mark, Skin.TEXT, 38);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        Random rnd = new Random();
        int[] at = new int[2], base = new int[2];
        root.getLocationInWindow(base);
        float startY;
        if (from != null) {
            from.getLocationInWindow(at);
            lp.leftMargin = at[0] - base[0] + from.getWidth() / 2 - Ui.dp(this, 22);
            startY = at[1] - base[1] - Ui.dp(this, 10);
        } else {
            lp.leftMargin = Ui.dp(this, 30 + rnd.nextInt(Math.max(1, (int) (root.getWidth() / getResources().getDisplayMetrics().density) - 90)));
            startY = root.getHeight() * 0.62f;
        }
        root.addView(f, lp);
        f.setTranslationY(startY);
        f.setScaleX(0.6f);
        f.setScaleY(0.6f);
        float sway = (rnd.nextFloat() - 0.5f) * Ui.dp(this, 70);
        f.animate().translationY(startY - root.getHeight() * 0.5f).translationX(sway).scaleX(1.25f).scaleY(1.25f).alpha(0f)
            .setDuration(3000).setInterpolator(new android.view.animation.DecelerateInterpolator())
            .setListener(new AnimatorListenerAdapter() {
                public void onAnimationEnd(Animator a) { root.removeView(f); }
            }).start();
    }

    // ---------- doing things ----------

    private void say() {
        final String text = line.getText().toString().trim();
        if (text.isEmpty())
            return;
        HammerCircle.Message m = new HammerCircle.Message();
        m.kind = onBoard ? "post" : "chat";
        m.body = text;
        m.ref = replyTo;
        line.setText("");
        replyTo(null);
        send(m, new Runnable() {
            public void run() {
                if (!onBoard)
                    toBottom();
            }
        });
        if (onBoard)
            toast("Posting…");  // a board post pays a larger stamp: a second or two
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
        if (HammerService.inCircle())
            HammerService.leaveCircle();
        else
            try {
                HammerService.tuneCircle(this, room);
            } catch (Exception e) {
                Ui.toast(this, "Couldn't listen along: " + e.getMessage());
            }
        showListening();
    }
    private void menu() {
        if (room == null)
            return;
        final String[] items = {"Invite people", "Who's here", "Your name in this circle", "Leave the circle"};
        new Lux.Builder(this).setTitle(room.circle.title)
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int i) {
                    if (i == 0)
                        invite();
                    else if (i == 1)
                        whoIsHere();
                    else if (i == 2)
                        rename();
                    else
                        leave();
                }
            })
            .setNegativeButton("Close", null).show();
    }
    private void whoIsHere() {
        if (room == null)
            return;
        Map<String, String> here = room.here();
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : here.entrySet()) {
            String n = e.getValue().isEmpty() ? "someone" : e.getValue();
            String tag;
            try {
                tag = Hammer.hash(java.util.Base64.getDecoder().decode(e.getKey())).substring(0, 6);
            } catch (Exception bad) {
                tag = "?";
            }
            b.append(n).append(e.getKey().equals(room.circle.key) ? " ★" : "").append("   #").append(tag).append('\n');
        }
        new Lux.Builder(this).setTitle(here.isEmpty() ? "Nobody else here yet" : here.size() + " here")
            .setMessage((b.length() == 0 ? "" : b + "\n")
                        + "Anyone who said anything in the last five minutes. The tag after a name is part of that "
                        + "person's key for this circle, so two people with the same name can be told apart. ★ started the circle.")
            .setPositiveButton("Close", null).show();
    }
    private void rename() {
        final EditText name = Ui.edit(this, "Your name in this circle", 1);
        name.setText(Circles.name(this));
        new Lux.Builder(this).setTitle("Your name in this circle").setView(padded(name))
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { Circles.name(CircleActivity.this, name.getText().toString().trim()); }
            })
            .setNegativeButton("Cancel", null).show();
    }
    /** Picks a song from your own shared ones and those Discover has seen, and adds it to the queue. */
    private void addSong() {
        final List<Hammer.Song> songs = new ArrayList<Hammer.Song>();
        final List<List<Hammer.Card>> hosts = new ArrayList<List<Hammer.Card>>();
        final List<java.io.File> files = new ArrayList<java.io.File>();
        Map<Hammer.Song, List<Hammer.Card>> seen = HammerActivity.songsSeen();
        try {
            // others in the room can't always reach this phone, and its own player can't stream from itself:
            // name the public hosts Discover has seen carrying the song first, this phone last
            Hammer.Card own = Hammer.Card.p2p(HammerAndroid.network(this), HammerAndroid.pin(), "");
            for (HammerStore.Hosted h : HammerAndroid.store(this).hosted())
                if (h.publicSong) {
                    List<Hammer.Card> where = new ArrayList<Hammer.Card>();
                    for (Map.Entry<Hammer.Song, List<Hammer.Card>> e : seen.entrySet())
                        if (e.getKey().id.equals(h.song.id))
                            for (Hammer.Card c : e.getValue())
                                if (where.size() < 2 && !c.pin.equals(own.pin))
                                    where.add(c);
                    where.add(own);
                    songs.add(h.song);
                    hosts.add(where);
                    files.add(h.file);
                }
        } catch (Exception noOwn) {
        }
        final int yours = songs.size();
        for (Map.Entry<Hammer.Song, List<Hammer.Card>> e : seen.entrySet()) {
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
        final List<Integer> shown = new ArrayList<Integer>();
        for (int i = 0; i < songs.size(); i++)
            shown.add(i);
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        final EditText search = Ui.edit(this, "Find a song or an artist", 1);
        box.addView(search);
        if (seen.isEmpty()) {
            TextView hint = Ui.text(this, "Only your own songs for now. Open Discover once and the songs it finds show here too.",
                                    Skin.DIM, 12);
            Lux.keep(hint);
            hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
            box.addView(hint);
        }
        final ListView list = new ListView(this);
        list.setDivider(null);
        final BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return shown.get(i); }
            public View getView(int i, View old, ViewGroup parent) {
                int k = shown.get(i);
                LinearLayout row = old instanceof LinearLayout ? (LinearLayout) old : songRow();
                TextView t = (TextView) row.getChildAt(0), a = (TextView) row.getChildAt(1);
                t.setText(songs.get(k).title);
                String artist = k < yours ? "Yours" : HammerActivity.artistOf(songs.get(k).key);
                a.setText(artist.isEmpty() ? "An artist on the network" : artist);
                return row;
            }
        };
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.5f)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (int i = 0; i < songs.size(); i++)
                    if (q.isEmpty() || songs.get(i).title.toLowerCase(Locale.ROOT).contains(q)
                        || HammerActivity.artistOf(songs.get(i).key).toLowerCase(Locale.ROOT).contains(q))
                        shown.add(i);
                adapter.notifyDataSetChanged();
            }
        });
        final android.app.AlertDialog d = new Lux.Builder(this).setTitle("Add a song").setView(box)
            .setNegativeButton("Cancel", null).create();
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                d.dismiss();
                final int i = shown.get(pos);
                toast("Adding " + songs.get(i).title + "…");
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
        });
        d.show();
    }
    private LinearLayout songRow() {
        LinearLayout row = Ui.column(this);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        TextView t = Ui.text(this, "", Skin.TEXT, 15);
        t.setTypeface(Skin.text());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        Lux.keep(t);
        row.addView(t);
        TextView a = Ui.text(this, "", Skin.DIM, 12);
        a.setTypeface(Skin.text());
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        Lux.keep(a);
        row.addView(a);
        return row;
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
                String tag;
                try {
                    tag = "  #" + m.who();
                } catch (Exception bad) {
                    tag = "";
                }
                Lux.Builder b = new Lux.Builder(CircleActivity.this);
                b.setTitle(shown(m) + tag)
                    .setPositiveButton(muted ? "Unmute" : "Mute on this phone", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            Circles.mute(CircleActivity.this, m.key, !muted);
                            shownTalk = "";
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

    /** Names that two or more different keys have used here: only those get the tag of the key beside them. */
    private Set<String> twins(List<HammerCircle.Message> list) {
        Map<String, String> first = new HashMap<String, String>();
        Set<String> out = new HashSet<String>();
        List<HammerCircle.Message> all = new ArrayList<HammerCircle.Message>(list);
        all.addAll(onBoard ? room.chat() : room.posts());
        for (Map.Entry<String, String> e : room.here().entrySet()) {
            HammerCircle.Message h = new HammerCircle.Message();
            h.key = e.getKey();
            h.name = e.getValue();
            all.add(h);
        }
        for (HammerCircle.Message m : all) {
            String n = m.name.trim().toLowerCase(Locale.ROOT);
            String k = first.get(n);
            if (k == null)
                first.put(n, m.key);
            else if (!k.equals(m.key))
                out.add(n);
        }
        return out;
    }
    private String shown(HammerCircle.Message m) { return shown(m, twins(onBoard ? room.posts() : room.chat())); }
    private String shown(HammerCircle.Message m, Set<String> twins) {
        String who = m.name.isEmpty() ? "someone" : m.name;
        if (twins.contains(m.name.trim().toLowerCase(Locale.ROOT)))
            try {
                who += " #" + m.who();
            } catch (Exception badKey) {
            }
        return who + (m.key.equals(room.circle.key) ? " ★" : "");
    }
    private static String ago(long t) {
        long m = Math.max(0, System.currentTimeMillis() - t) / 60000;
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 1440 ? (m / 60) + " h ago" : (m / 1440) + " d ago";
    }
    private LinearLayout plate() {
        LinearLayout p = Ui.column(this);
        p.setBackground(new LuxFrame(14, 8));
        int in = Ui.dp(this, 20), down = Ui.dp(this, 14);
        p.setPadding(in, down, Ui.dp(this, 12), down);
        return p;
    }
    private View padded(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(Ui.dp(this, 22), Ui.dp(this, 6), Ui.dp(this, 22), 0);
        f.addView(v);
        return f;
    }
    private TextView heading(String name) {
        TextView h = Ui.label(this, name);
        h.setTextColor(Skin.ACCENT);
        h.setLetterSpacing(0.12f);
        h.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return h;
    }
    private void toast(final String s) {
        ui.post(new Runnable() {
            public void run() { if (!closed) Ui.toast(CircleActivity.this, s); }
        });
    }

    /** A thin gold line showing how far the song has got. */
    private static final class Progress extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float at;
        Progress(Context c) { super(c); }
        void set(float f) {
            if (f != at) {
                at = f;
                invalidate();
            }
        }
        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), y = h / 2f, t = Math.max(2f, h * 0.28f);
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(0, y - t / 2, w, y + t / 2), t, t, p);
            if (at > 0) {
                p.setShader(new android.graphics.LinearGradient(0, 0, w * at, 0, Skin.LO, Skin.ACCENT,
                                                                android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(new RectF(0, y - t / 2, w * at, y + t / 2), t, t, p);
                p.setShader(null);
                p.setColor(Skin.HI);
                c.drawCircle(w * at, y, t * 1.3f, p);
            }
        }
    }
    /** The gold bar down the side of a quoted line in a reply. */
    private static final class Quote extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            p.setColor(Skin.accent(0x18));
            c.drawRect(b, p);
            p.setColor(Skin.ACCENT);
            c.drawRect(b.left, b.top, b.left + Math.max(2, b.height() / 12f), b.bottom, p);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
