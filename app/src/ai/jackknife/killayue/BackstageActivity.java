package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Artist boards and track conversations use the same signed host-side metadata on Android and desktop. */
public final class BackstageActivity extends Activity {
    private LinearLayout body, posts;
    private TextView status;
    private Button newPost;
    private HammerStore store;
    private Hammer.Card peer;
    private String scope, target, token = "", ownerKey;
    private boolean local, closed, busy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Hammer.Job job = new Hammer.Job();
    private interface Task {
        void run() throws Exception;
    }
    static void open(Context c, Hammer.Card peer, String scope, String target, boolean privateCircle) {
        Intent i = new Intent(c, BackstageActivity.class)
                       .putExtra("scope", scope)
                       .putExtra("target", target)
                       .putExtra("private", privateCircle);
        if (peer != null)
            i.putExtra("peer", peer.encode());
        c.startActivity(i);
    }
    public void onCreate(Bundle saved) {
        setTheme(Skin.themeFor(BackstageActivity.this, Skin.id(BackstageActivity.this)));
        super.onCreate(saved);
        Skin.load(BackstageActivity.this);
        ScrollView scroll = new ScrollView(BackstageActivity.this);
        body = Ui.column(BackstageActivity.this);
        int pad = Ui.dp(BackstageActivity.this, 16);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
        scroll.setFitsSystemWindows(true);
        scroll.setBackground(Skin.background(BackstageActivity.this));
        setContentView(scroll);
        scope = getIntent().getStringExtra("scope");
        target = getIntent().getStringExtra("target");
        local = getIntent().getStringExtra("peer") == null;
        body.addView(
            Ui.title(BackstageActivity.this, "board".equals(scope) ? "Backstage · artist board" : "Song conversation"));
        body.addView(Ui.text(BackstageActivity.this,
                             "Posts live on the host beside the music. Public posts can be copied. "
                                 + "Private conversations stay in the invited circle. A remix link points " +
                                   "to another host; open it only if you trust it.",
                             Skin.DIM, 13));
        status = Ui.text(BackstageActivity.this, "Opening…", Skin.DIM, 13);
        body.addView(status);
        button(body, "Refresh", new Runnable() {
            public void run() { load(); }
        });
        newPost = button(body, "New post", new Runnable() {
            public void run() { compose(null, null); }
        });
        if ("track".equals(scope))
            button(body, "Link one of my remixes", new Runnable() {
                public void run() { chooseRemix(); }
            });
        if ("board".equals(scope)) {
            if (local)
                button(body, "Followers · invite to listening circle", new Runnable() {
                    public void run() { followers(); }
                });
            else {
                button(body, "Receive circle invitations", new Runnable() {
                    public void run() { register(); }
                });
                button(body, "Check my invitation", new Runnable() {
                    public void run() { inbox(); }
                });
                button(body, "Stop receiving invitations", new Runnable() {
                    public void run() { unfollow(); }
                });
            }
        }
        posts = Ui.column(BackstageActivity.this);
        body.addView(posts);
        task("Opening host", new Task() {
            public void run() throws Exception {
                store = HammerAndroid.store(BackstageActivity.this);
                Hammer.require(Hammer.digest(target), "Invalid track or artist");
                if (!local) {
                    peer = Hammer.Card.parse(getIntent().getStringExtra("peer"));
                    if (peer.isP2p())
                        HammerAndroid.network(BackstageActivity.this);
                    token = peer.token;
                } else if (getIntent().getBooleanExtra("private", false))
                    token = store.token();
                refresh();
            }
        });
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button b = Ui.button(BackstageActivity.this, text, false);
        parent.addView(b, Ui.gap(BackstageActivity.this, 6));
        b.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                if (!busy && store != null)
                    action.run();
            }
        });
        return b;
    }
    private void task(String label, Task task) {
        if (busy || closed)
            return;
        busy = true;
        status.setText(label);
        worker.execute(new Runnable() {
            public void run() {
                String error = null;
                try {
                    task.run();
                } catch (Exception e) {
                    error = e.getMessage();
                    if (error == null || error.isEmpty())
                        error = "Host unavailable or this feature is not supported yet";
                }
                final String failure = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        if (!closed)
                            status.setText(failure == null ? "Up to date on this host" : failure);
                    }
                });
            }
        });
    }
    private void load() {
        task("Refreshing", new Task() {
            public void run() throws Exception { refresh(); }
        });
    }
    private void refresh() throws Exception {
        ownerKey = null;
        if (scope.equals("track")) {
            List<Hammer.Song> songs = local ? store.catalog(token) : Hammer.catalog(peer, job);
            for (Hammer.Song song : songs)
                if (song.id.equals(target))
                    ownerKey = song.key;
        } else {
            List<HammerProfile> ps = local ? store.publicObjects.profiles() : Hammer.profiles(peer, job);
            for (HammerProfile p : ps)
                if (p.id().equals(target))
                    ownerKey = p.key;
        }
        Hammer.require(ownerKey != null, "The track or public artist page is unavailable on this host");
        List<HammerCommunity.Post> all = local ? store.community.list(scope, target, token)
                                               : HammerCommunity.list(peer, scope, target, job);
        final List<HammerCommunity.Post> shown = HammerCommunity.visible(all, store);
        final boolean artist = ownerKey.equals(Hammer.publicKey(HammerAndroid.identity().getPublic()));
        final List<Hammer.Comment> legacy =
            scope.equals("track")
                ? (local ? store.comments(target, token) : Hammer.comments(peer, target, job))
                : Collections.<Hammer.Comment>emptyList();
        runOnUiThread(new Runnable() {
            public void run() {
                if (closed)
                    return;
                posts.removeAllViews();
                newPost.setEnabled(artist || scope.equals("track"));
                if (shown.isEmpty() && legacy.isEmpty())
                    posts.addView(Ui.text(BackstageActivity.this,
                                          scope.equals("board")
                                              ? "The artist can start the first thread here."
                                              : "Start a conversation about this track.",
                                          Skin.DIM, 14));
                for (HammerCommunity.Post p : shown) {
                    LinearLayout row = Ui.column(BackstageActivity.this);
                    row.setPadding(0, Ui.dp(BackstageActivity.this, 14), 0, Ui.dp(BackstageActivity.this, 8));
                    String replyTo = "";
                    if (!p.parent.isEmpty()) {
                        replyTo = " · reply to an earlier post";
                        for (HammerCommunity.Post parent : shown)
                            if (parent.id.equals(p.parent))
                                replyTo = " · reply to " + (parent.name.isEmpty() ? "Listener" : parent.name);
                        row.setPadding(Ui.dp(BackstageActivity.this, 12), Ui.dp(BackstageActivity.this, 8), 0, Ui.dp(BackstageActivity.this, 8));
                    }
                    String label = p.name.isEmpty() ? "Listener" : p.name;
                    row.addView(Ui.text(BackstageActivity.this, label + (p.key.equals(ownerKey) ? " · artist" : "") + replyTo,
                                        Skin.ACCENT, 14));
                    TextView text = Ui.text(BackstageActivity.this, p.body, Skin.TEXT, 16);
                    text.setTextIsSelectable(true);
                    row.addView(text);
                    if (p.position >= 0)
                        button(row,
                               "Listen at " + (p.position / 60000) + ":" +
                                   String.format(Locale.US, "%02d", p.position / 1000 % 60),
                               new Runnable() {
                                   public void run() { listen(p.position); }
                               });
                    if (p.remix != null)
                        button(
                            row,
                            (p.kind.equals("source") ? "Original track · " : "Open remix · ") + p.remix.title,
                            new Runnable() {
                                public void run() {
                                    new Lux.Builder(BackstageActivity.this)
                                        .setTitle("Connect to remix host?")
                                        .setMessage("This connects to the host named by the remix author. " +
                                                    "The link is not an endorsement by the original artist.")
                                        .setPositiveButton("Open",
                                                           new DialogInterface.OnClickListener() {
                                                               public void onClick(DialogInterface d, int w) {
                                                                   startActivity(
                                                                       new Intent(BackstageActivity.this, HammerActivity.class)
                                                                           .putExtra("browse_peer", p.host)
                                                                           .putExtra("song", p.remix.id)
                                                                           .putExtra("refresh", true));
                                                               }
                                                           })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                                }
                            });
                    button(row, "Reply", new Runnable() {
                        public void run() { compose(p, null); }
                    });
                    button(row, "Mute author on this device", new Runnable() {
                        public void run() {
                            task("Muting author", new Task() {
                                public void run() throws Exception {
                                    store.block(p.key);
                                    refresh();
                                }
                            });
                        }
                    });
                    if (artist) {
                        button(row, "Pin", new Runnable() {
                            public void run() { moderate(p, "pin"); }
                        });
                        button(row, "Hide", new Runnable() {
                            public void run() { moderate(p, "hide"); }
                        });
                        if (p.parent.isEmpty())
                            button(row, "Lock replies", new Runnable() {
                                public void run() { moderate(p, "lock"); }
                            });
                    }
                    posts.addView(row);
                }
                for (Hammer.Comment c : legacy)
                    if (!c.body.isEmpty())
                        posts.addView(Ui.text(BackstageActivity.this, c.name + " · earlier comment\n" + c.body, Skin.DIM, 14),
                                      Ui.gap(BackstageActivity.this, 8));
            }
        });
    }
    private void compose(HammerCommunity.Post parent, Hammer.Song remix) {
        LinearLayout fields = Ui.column(BackstageActivity.this);
        EditText name = Ui.edit(BackstageActivity.this, "Display name", 1), text = Ui.edit(BackstageActivity.this, "Your message", 4),
                 seconds = Ui.edit(BackstageActivity.this, "Optional timestamp in seconds", 1);
        name.setText(getPreferences(0).getString("name", ""));
        fields.addView(name);
        fields.addView(text);
        seconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (scope.equals("track") && remix == null)
            fields.addView(seconds);
        new Lux.Builder(BackstageActivity.this)
            .setTitle(remix == null ? "Post to this host" : "Link remix · " + remix.title)
            .setView(fields)
            .setMessage(token.isEmpty() ? "Your display name, signing identity and message will be public."
                                        : "The host and invited circle can read this message.")
            .setPositiveButton(
                "Post",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String who = name.getText().toString(), body = text.getText().toString(),
                               at = seconds.getText().toString().trim();
                        getPreferences(0).edit().putString("name", who).apply();
                        task("Posting", new Task() {
                            public void run() throws Exception {
                                HammerCommunity.Post p = base();
                                p.name = who;
                                p.body = body;
                                if (parent != null)
                                    p.parent = parent.id;
                                if (!at.isEmpty())
                                    p.position = Math.multiplyExact(Long.parseLong(at), 1000);
                                if (remix != null) {
                                    p.kind = "remix";
                                    p.remix = remix;
                                    p.host =
                                        Hammer.Card.p2p(HammerAndroid.network(BackstageActivity.this), HammerAndroid.pin(), "")
                                            .encode();
                                }
                                if (remix != null) {
                                    List<Hammer.Song> originals =
                                        local ? store.catalog("") : Hammer.catalog(peer, job);
                                    for (Hammer.Song original : originals)
                                        if (original.id.equals(target)) {
                                            HammerCommunity.Post source = new HammerCommunity.Post();
                                            source.target = remix.id;
                                            source.kind = "source";
                                            source.remix = original;
                                            source.name = who;
                                            source.body = "Remix source: " + original.title;
                                            source.host = local ? p.host : peer.encode();
                                            source.sign(HammerAndroid.identity());
                                            store.community.post(source, "");
                                        }
                                }
                                send(p);
                                refresh();
                            }
                        });
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private HammerCommunity.Post base() throws Exception {
        HammerCommunity.Post p = new HammerCommunity.Post();
        p.scope = scope;
        p.target = target;
        p.audience = scope.equals("board") ? "public" : HammerCommunity.audience(token);
        return p;
    }
    private void send(HammerCommunity.Post p) throws Exception {
        p.sign(HammerAndroid.identity());
        if (local)
            store.community.post(p, token);
        else
            HammerCommunity.post(peer, p, job);
    }
    private void moderate(HammerCommunity.Post original, String action) {
        new Lux.Builder(BackstageActivity.this)
            .setTitle(action + " this post?")
            .setMessage("This affects this host's thread. Other people may retain copies.")
            .setPositiveButton("Apply",
                               new DialogInterface.OnClickListener() {
                                   public void onClick(DialogInterface d, int w) {
                                       task("Updating thread", new Task() {
                                           public void run() throws Exception {
                                               HammerCommunity.Post p = base();
                                               p.kind = action;
                                               p.parent = original.id;
                                               p.body = action;
                                               send(p);
                                               refresh();
                                           }
                                       });
                                   }
                               })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void chooseRemix() {
        task("Reading your public tracks", new Task() {
            public void run() throws Exception {
                Hammer.require(token.isEmpty(),
                               "Remix references are public; open the public track conversation");
                Hammer.require(HammerService.hosting(), "Start hosting your published remix first");
                List<Hammer.Song> songs = new ArrayList<Hammer.Song>();
                String key = Hammer.publicKey(HammerAndroid.identity().getPublic());
                for (HammerStore.Hosted h : store.hosted())
                    if (h.publicSong && h.song.key.equals(key) && !h.song.id.equals(target))
                        songs.add(h.song);
                String[] labels = new String[songs.size()];
                for (int i = 0; i < labels.length; i++)
                    labels[i] = songs.get(i).title;
                Hammer.require(labels.length > 0, "Publish your remix publicly from the Library first");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!closed)
                            new Lux.Builder(BackstageActivity.this)
                                .setTitle("Choose your remix")
                                .setItems(labels,
                                          new DialogInterface.OnClickListener() {
                                              public void onClick(DialogInterface d, int n) {
                                                  compose(null, songs.get(n));
                                              }
                                          })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                });
            }
        });
    }
    private void listen(long position) {
        task("Opening track at timestamp", new Task() {
            public void run() throws Exception {
                Hammer.Card p = peer;
                if (local) {
                    Hammer.require(HammerService.hosting(), "Start hosting to listen here");
                    p = new Hammer.Card("127.0.0.1", Hammer.PORT, HammerAndroid.pin(), token);
                }
                for (Hammer.Song song : Hammer.catalog(p, job))
                    if (song.id.equals(target)) {
                        final Hammer.Card route = p;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                try {
                                    HammerService.play(BackstageActivity.this, Collections.singletonList(route), song,
                                                       HammerBuffer.AUTO, (int)position);
                                } catch (Exception e) {
                                    status.setText("Could not start playback");
                                }
                            }
                        });
                        return;
                    }
                throw new java.io.IOException("Track unavailable");
            }
        });
    }
    private HammerCommunity.Follow request(String action, String name) throws Exception {
        Hammer.require(peer != null && !peer.isPrivate() && target.equals(peer.pin),
                       "Open the artist's own public host for invitations");
        HammerCommunity.Follow f = new HammerCommunity.Follow();
        f.host = peer.pin;
        f.action = action;
        f.name = name;
        f.sign(HammerAndroid.identity());
        return f;
    }
    private void register() {
        EditText name = Ui.edit(BackstageActivity.this, "Name the artist will see", 1);
        new Lux.Builder(BackstageActivity.this)
            .setTitle("Register for circle invitations?")
            .setView(name)
            .setMessage(
                "The artist's host will keep your display name and app signing identity so it can invite " +
                "you. Your follower entry is not public. Local-only following remains available.")
            .setPositiveButton("Register",
                               new DialogInterface.OnClickListener() {
                                   public void onClick(DialogInterface d, int w) {
                                       String who = name.getText().toString();
                                       task("Registering with artist", new Task() {
                                           public void run() throws Exception {
                                               HammerCommunity.follow(peer, request("follow", who), job);
                                               store.follow(new HammerStore.Peer(peer, "Artist", false));
                                               HammerFollowService.prefs(BackstageActivity.this)
                                                   .edit()
                                                   .putString("community." + peer.pin, peer.encode())
                                                   .putString("artist." + target, "Artist")
                                                   .apply();
                                               runOnUiThread(new Runnable() {
                                                   public void run() { HammerFollowService.changed(BackstageActivity.this); }
                                               });
                                           }
                                       });
                                   }
                               })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void unfollow() {
        task("Removing invitation registration", new Task() {
            public void run() throws Exception {
                HammerCommunity.follow(peer, request("unfollow", ""), job);
                HammerFollowService.prefs(BackstageActivity.this).edit().remove("community." + peer.pin).apply();
            }
        });
    }
    private void inbox() {
        task("Checking private invitation", new Task() {
            public void run() throws Exception {
                String invitation = HammerCommunity.follow(peer, request("inbox", ""), job);
                Hammer.require(!invitation.isEmpty(), "No current circle invitation from this artist");
                Hammer.Card circle = new Hammer.Card(peer.host, peer.port, peer.pin, invitation);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!closed)
                            new Lux.Builder(BackstageActivity.this)
                                .setTitle("Join the artist's listening circle?")
                                .setMessage(
                                    "This invitation opens the artist's existing private group. Members " +
                                    "share access; the artist can rotate the invitation to revoke it.")
                                .setPositiveButton(
                                    "Accept",
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            task("Saving invitation", new Task() {
                                                public void run() throws Exception {
                                                    store.follow(new HammerStore.Peer(
                                                        circle, "Artist listening circle", false));
                                                    runOnUiThread(new Runnable() {
                                                        public void run() {
                                                            HammerFollowService.changed(BackstageActivity.this);
                                                        }
                                                    });
                                                }
                                            });
                                        }
                                    })
                                .setNegativeButton("Later", null)
                                .show();
                    }
                });
            }
        });
    }
    private void followers() {
        task("Reading registered followers", new Task() {
            public void run() throws Exception {
                List<HammerCommunity.Follower> fs = store.community.followers();
                String[] names = new String[fs.size()];
                for (int k = 0; k < fs.size(); k++)
                    names[k] = fs.get(k).name + " · " +
                               Hammer.hash(Base64.getDecoder().decode(fs.get(k).key)).substring(0, 10);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!closed)
                            new Lux.Builder(BackstageActivity.this)
                                .setTitle("Invite a registered follower")
                                .setItems(
                                    names,
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int n) {
                                            new Lux.Builder(BackstageActivity.this)
                                                .setTitle("Invite " + fs.get(n).name + "?")
                                                .setMessage("They will receive access to ALL songs in your " +
                                                            "current private circle when they next " +
                                                            "connect. This is not a separate group.")
                                                .setPositiveButton(
                                                    "Invite",
                                                    new DialogInterface.OnClickListener() {
                                                        public void onClick(DialogInterface q, int w) {
                                                            task(
                                                                "Saving invitation for follower", new Task() {
                                                                    public void run() throws Exception {
                                                                        store.community.invite(fs.get(n).key);
                                                                    }
                                                                });
                                                        }
                                                    })
                                                .setNegativeButton("Cancel", null)
                                                .show();
                                        }
                                    })
                                .setNegativeButton("Close", null)
                                .show();
                    }
                });
            }
        });
    }
    protected void onDestroy() {
        closed = true;
        job.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
}
