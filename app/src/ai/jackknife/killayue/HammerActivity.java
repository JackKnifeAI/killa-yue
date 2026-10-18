package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit sharing, on-device catalog search and peer playback. No account or bootstrap service. */
public final class HammerActivity extends Activity {
    private LinearLayout body, results, playerPanel;
    private TextView status, playerStatus;
    private EditText query;
    private SeekBar seek;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Hammer.Job job;
    private boolean busy, closed;
    private boolean settingsMode, discoveryPending;
    private String artistFilter;
    private final Map<String, ProfileHit> profiles = new LinkedHashMap<String, ProfileHit>();
    private final Map<String, HammerStore.Peer> discovered =
        new java.util.concurrent.ConcurrentHashMap<String, HammerStore.Peer>();
    private HammerNearby nearby;
    private static final class ProfileHit {
        final HammerStore.Peer peer;
        final HammerProfile profile;
        ProfileHit(HammerStore.Peer p, HammerProfile a) {
            peer = p;
            profile = a;
        }
    }
    private int streamMode = HammerBuffer.AUTO;
    private HammerStore store;
    private final List<Hit> hits = new ArrayList<Hit>();
    private static final class Hit {
        final HammerStore.Peer peer;
        final Hammer.Song song;
        Hit(HammerStore.Peer p, Hammer.Song s) {
            peer = p;
            song = s;
        }
    }
    private interface Work {
        void run() throws Exception;
    }
    static void open(Context c) { c.startActivity(new Intent(c, HammerActivity.class)); }
    static void settings(Context c) {
        c.startActivity(new Intent(c, HammerActivity.class).putExtra("settings", true));
    }
    static void publish(Context c, org.json.JSONObject song) {
        c.startActivity(new Intent(c, HammerActivity.class).putExtra("publish", song.toString()));
    }
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Skin.load(this);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, pad, pad, pad);
        body.setBackgroundColor(Skin.DECK);
        scroll.addView(body);
        setContentView(scroll);
        settingsMode = getIntent().getBooleanExtra("settings", false);
        artistFilter = getIntent().getStringExtra("artist");
        body.addView(Ui.title(this, settingsMode ? "Sharing settings" : "Discover"));
        label(settingsMode ? "Private circles, computer hosts and connection tools. Your Library controls " +
                             "which songs are shared."
                           : "Music and artists hosted by people. Publish your songs from Library.");
        status = label("Opening local storage…");
        if (settingsMode) {
            button(body, "Start hosting", new Runnable() {
                public void run() { HammerService.startHost(HammerActivity.this); }
            });
            button(body, "Stop hosting", new Runnable() {
                public void run() { HammerService.stopHost(); }
            });
            button(body, "My hosted songs", new Runnable() {
                public void run() { hosted(); }
            });
            button(body, "Public peer card", new Runnable() {
                public void run() { invite(false); }
            });
            button(body, "Private circle invitation", new Runnable() {
                public void run() { invite(true); }
            });
            button(body, "Revoke private invitations", new Runnable() {
                public void run() {
                    new Lux.Builder(HammerActivity.this)
                        .setTitle("Replace the circle invitation?")
                        .setMessage("All current private invitations stop working. Copies people already "
                                    + "downloaded remain theirs.")
                        .setPositiveButton("Revoke",
                                           new DialogInterface.OnClickListener() {
                                               public void onClick(DialogInterface d, int w) {
                                                   work("Revoking invitations", new Work() {
                                                       public void run() throws Exception {
                                                           store.revoke();
                                                           HammerService.disconnect();
                                                       }
                                                   });
                                               }
                                           })
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            });
            button(body, "Add peer or invitation", new Runnable() {
                public void run() { addPeer(""); }
            });
            button(body, "Followed peers", new Runnable() {
                public void run() { peers(); }
            });
        } else {
            button(body, "My artist profile", new Runnable() {
                public void run() {
                    startActivity(new Intent(HammerActivity.this, HammerArtistActivity.class));
                }
            });
        }
        if (settingsMode) {
            button(body, "Live Follow on / off", new Runnable() {
                public void run() { liveFollow(); }
            });
            button(body, "Followed artists", new Runnable() {
                public void run() { artists(); }
            });
        }
        button(body, "Refresh discovery", new Runnable() {
            public void run() { refresh(); }
        });
        query = Ui.edit(this, "Search artists, songs and styles", 1);
        body.addView(query);
        button(body, "Search", new Runnable() {
            public void run() { render(); }
        });
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);
        playerPanel = new LinearLayout(this);
        playerPanel.setOrientation(LinearLayout.VERTICAL);
        body.addView(playerPanel);
        playerStatus = Ui.text(this, "Stopped", Skin.DIM, 14);
        playerPanel.addView(playerStatus);
        seek = new SeekBar(this);
        playerPanel.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (user)
                    HammerService.seek(p);
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
        button(playerPanel, "Play / pause", new Runnable() {
            public void run() { HammerService.pause(); }
        });
        button(playerPanel, "Stop playback", new Runnable() {
            public void run() { HammerService.stopPlayback(); }
        });
        if (settingsMode) {
            button(body, "Downloaded songs", new Runnable() {
                public void run() { downloads(); }
            });
            button(body, "Save desktop companion", new Runnable() {
                public void run() {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                   .setType("application/zip")
                                   .addCategory(Intent.CATEGORY_OPENABLE)
                                   .putExtra(Intent.EXTRA_TITLE, "hammer-server.zip");
                    startActivityForResult(i, 840);
                }
            });
            button(body, "Cancel current transfer", new Runnable() {
                public void run() {
                    if (job != null)
                        job.cancel();
                }
            });
            label(
                "Connect using a trusted peer card. Direct connections need reachable addresses: Wi-Fi, a "
                + "hotspot, or a reachable internet/VPN endpoint. No global tracker or NAT relay is bundled. "
                + "Lossless/original mode transfers the existing file unchanged; an MP3 master cannot regain "
                +
                "lost detail. Public metadata is public; encryption does not hide peer IP addresses. Search "
                + "words stay here.");
        }
        work("Opening HAMMER", new Work() {
            public void run() throws Exception {
                store = HammerAndroid.store(HammerActivity.this);
                HammerAndroid.identity();
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        String browse = getIntent().getStringExtra("browse_peer");
                        if (browse != null)
                            try {
                                Hammer.Card card = Hammer.Card.parse(browse);
                                discovered.put(HammerStore.peerId(card),
                                               new HammerStore.Peer(card, "Artist", false));
                            } catch (Exception ignored) {
                            }
                        String incoming = getIntent().getStringExtra("invite");
                        if (incoming != null)
                            addPeer(incoming);
                        String publish = getIntent().getStringExtra("publish");
                        if (publish != null)
                            publishDialog(publish);
                        if (!settingsMode) {
                            nearby = new HammerNearby(HammerActivity.this, new HammerNearby.Found() {
                                public void peer(final Hammer.Card card) {
                                    ui.post(new Runnable() {
                                        public void run() {
                                            if (closed || discovered.size() >= 64)
                                                return;
                                            try {
                                                discovered.put(
                                                    HammerStore.peerId(card),
                                                    new HammerStore.Peer(card, "Nearby artist", false));
                                                discoveryPending = true;
                                                if (!busy)
                                                    refresh();
                                            } catch (Exception ignored) {
                                            }
                                        }
                                    });
                                }
                            });
                            nearby.start();
                            getIntent().putExtra("refresh", true);
                        }
                    }
                });
            }
        });
        ui.post(tick);
    }
    private TextView label(String text) {
        TextView v = Ui.text(this, text, Skin.TEXT, 14);
        v.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        body.addView(v);
        return v;
    }
    private void button(LinearLayout parent, String title, final Runnable action) {
        Button b = Ui.button(this, title, false);
        parent.addView(b);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (store == null) {
                    Ui.toast(HammerActivity.this, "HAMMER is still opening");
                    return;
                }
                action.run();
            }
        });
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (closed)
                return;
            playerPanel.setVisibility("Stopped".equals(HammerService.playback) ? android.view.View.GONE
                                                                                 : android.view.View.VISIBLE);
            playerStatus.setText(HammerService.status + "\n" + HammerService.playback + "\n" +
                                 HammerService.bufferStatus() + "\n" + HammerFollowService.status);
            seek.setMax(HammerService.duration());
            if (!seek.isPressed())
                seek.setProgress(HammerService.position());
            if (busy && job != null && job.total > 0)
                status.setText("Downloading " + (job.done * 100 / job.total) +
                               "% — Cancel keeps the partial copy");
            ui.postDelayed(this, 500);
        }
    };
    private void work(final String title, final Work action) {
        if (busy) {
            Ui.toast(this, "Finish or cancel the current operation first");
            return;
        }
        busy = true;
        job = new Hammer.Job();
        status.setText(title);
        worker.execute(new Runnable() {
            public void run() {
                String error = null;
                try {
                    action.run();
                } catch (Exception e) {
                    error = job.cancelled ? "Cancelled" : e.getMessage();
                    if (error == null || error.isEmpty())
                        error = "Peer unavailable or access denied";
                }
                final String message = error;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (closed)
                            return;
                        status.setText(message == null ? title + " — done" : title + ": " + message);
                        if (message == null && title.equals("Opening HAMMER") &&
                            getIntent().getBooleanExtra("refresh", false)) {
                            getIntent().removeExtra("refresh");
                            refresh();
                        } else if (discoveryPending && !settingsMode) {
                            refresh();
                        }
                    }
                });
            }
        });
    }
    private void publishDialog(String raw) {
        try {
            final org.json.JSONObject meta = new org.json.JSONObject(raw);
            final String name = meta.optString("_audio");
            if (name.contains("/") || name.contains("\\") || name.contains(".."))
                throw new IOException("Invalid library audio");
            final File file = new File(Store.library(this), name);
            final String title = meta.optString("_title", "Untitled");
            LinearLayout v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            final EditText tags = Ui.edit(this, "Public tags / style (optional)", 2);
            v.addView(tags);
            final CheckBox pub = new CheckBox(this);
            pub.setText("Publish publicly (off = private circle)");
            pub.setTextColor(Skin.TEXT);
            v.addView(pub);
            final CheckBox reshare = new CheckBox(this);
            reshare.setText("Allow downloads and hosting by other people");
            reshare.setTextColor(Skin.TEXT);
            reshare.setChecked(true);
            v.addView(reshare);
            new Lux.Builder(this)
                .setTitle("Host “" + title + "”")
                .setMessage(
                    "Only this audio, title and the tags you enter are shared. The existing audio file is "
                    + "served directly; no second master is kept. Downloaded copies can outlive this host.")
                .setView(v)
                .setPositiveButton("Publish",
                                   new DialogInterface.OnClickListener() {
                                       public void onClick(DialogInterface d, int w) {
                                           final String text = tags.getText().toString();
                                           final boolean publicSong = pub.isChecked(),
                                                         allow = reshare.isChecked();
                                           work("Indexing original audio", new Work() {
                                               public void run() throws Exception {
                                                   store.publish(file, title, text, allow, publicSong,
                                                                 HammerAndroid.identity(), job);
                                               }
                                           });
                                       }
                                   })
                .setNegativeButton("Cancel", null)
                .show();
        } catch (Exception e) {
            Ui.toast(this, "Could not open that library song");
        }
    }
    private void invite(final boolean privateCircle) {
        final EditText address = Ui.edit(this, "Address other peers can reach", 1);
        String local = ShareServer.localAddress();
        address.setText(local == null ? "" : local);
        new Lux.Builder(this)
            .setTitle(privateCircle ? "Private circle invitation" : "Public peer card")
            .setMessage("Choose this phone's reachable address. Port 8784 must reach this phone. Start "
                        + "hosting before someone connects.")
            .setView(address)
            .setPositiveButton("Create card",
                               new DialogInterface.OnClickListener() {
                                   public void onClick(DialogInterface d, int w) {
                                       final String host = address.getText().toString().trim();
                                       work("Creating peer card", new Work() {
                                           public void run() throws Exception {
                                               final Hammer.Card card =
                                                   new Hammer.Card(host, Hammer.PORT, HammerAndroid.pin(),
                                                                   privateCircle ? store.token() : "");
                                               ui.post(new Runnable() {
                                                   public void run() {
                                                       if (!closed)
                                                           showCard(card);
                                                   }
                                               });
                                           }
                                       });
                                   }
                               })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void showCard(final Hammer.Card card) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        ImageView image = new ImageView(this);
        io.nayuki.qrcodegen.QrCode qr =
            io.nayuki.qrcodegen.QrCode.encodeText(card.encode(), io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int scale = 4, side = (qr.size + 8) * scale;
        int[] pixels = new int[side * side];
        for (int y = 0; y < side; y++)
            for (int x = 0; x < side; x++)
                pixels[y * side + x] = qr.getModule(x / scale - 4, y / scale - 4) ? 0xff000000 : 0xffffffff;
        image.setImageBitmap(android.graphics.Bitmap.createBitmap(pixels, side, side,
                                                                  android.graphics.Bitmap.Config.ARGB_8888));
        v.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(this, 260)));
        TextView text = Ui.text(this,
                                (card.isPrivate()
                                     ? "Anyone holding this invitation can read your private circle. Send it "
                                           + "through a trusted channel.\n\n"
                                     : "Anyone with this public card can read your public catalog.\n\n") +
                                    card.encode(),
                                Skin.TEXT, 12);
        text.setTextIsSelectable(true);
        v.addView(text);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(v);
        AlertDialog d =
            new Lux.Builder(this)
                .setTitle(card.isPrivate() ? "Private invitation" : "Public card")
                .setView(scroll)
                .setPositiveButton(
                    "Copy card",
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("HAMMER peer", card.encode()));
                        }
                    })
                .setNegativeButton("Done", null)
                .create();
        if (card.isPrivate())
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        d.show();
    }
    private void addPeer(String initial) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final EditText card = Ui.edit(this, "hammer:// peer card", 3);
        card.setText(initial);
        v.addView(card);
        final EditText name = Ui.edit(this, "Name for this peer", 1);
        v.addView(name);
        final CheckBox introduce = new CheckBox(this);
        introduce.setText("Introduce this PUBLIC peer to visitors (off by default)");
        introduce.setTextColor(Skin.TEXT);
        v.addView(introduce);
        new Lux.Builder(this)
            .setTitle("Follow a peer")
            .setMessage(
                "Accept cards from people you trust. The card pins their identity. An invitation also grants "
                + "access to their private circle. Connections start when you refresh, play, or enable Live "
                + "Follow. If Live Follow is already on, this peer connects immediately.")
            .setView(v)
            .setPositiveButton(
                "Follow",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String raw = card.getText().toString(), label = name.getText().toString();
                        final boolean share = introduce.isChecked();
                        work("Saving peer", new Work() {
                            public void run() throws Exception {
                                store.follow(new HammerStore.Peer(Hammer.Card.parse(raw),
                                                                  label.isEmpty() ? "Peer" : label, share));
                                HammerFollowService.changed(HammerActivity.this);
                            }
                        });
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void peers() {
        final List<HammerStore.Peer> list = store.followed();
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = list.get(i).label + (list.get(i).card.isPrivate() ? " · private" : " · public");
        new Lux.Builder(this)
            .setTitle("Followed peers")
            .setItems(
                labels,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final HammerStore.Peer p = list.get(which);
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(p.label)
                            .setMessage(
                                "Identity: " + p.card.pin +
                                ("\nIntroductions are public cards only; adding one still requires your "
                                 + "choice."))
                            .setPositiveButton(
                                "Find introductions",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        work("Fetching introductions", new Work() {
                                            public void run() throws Exception {
                                                final List<Hammer.Card> ps = Hammer.peers(p.card, job);
                                                ui.post(new Runnable() {
                                                    public void run() {
                                                        if (closed)
                                                            return;
                                                        String[] names = new String[ps.size()];
                                                        for (int i = 0; i < names.length; i++)
                                                            names[i] = ps.get(i).pin.substring(0, 16) +
                                                                       " · " + ps.get(i).host;
                                                        new Lux.Builder(HammerActivity.this)
                                                            .setTitle("Public peers")
                                                            .setItems(names,
                                                                      new DialogInterface.OnClickListener() {
                                                                          public void onClick(
                                                                              DialogInterface d, int n) {
                                                                              addPeer(ps.get(n).encode());
                                                                          }
                                                                      })
                                                            .show();
                                                    }
                                                });
                                            }
                                        });
                                    }
                                })
                            .setNeutralButton(
                                "Forget peer",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        work("Removing peer", new Work() {
                                            public void run() throws Exception {
                                                store.forget(p.card);
                                                HammerFollowService.changed(HammerActivity.this);
                                                ui.post(new Runnable() {
                                                    public void run() {
                                                        for (Iterator<Hit> it = hits.iterator();
                                                             it.hasNext();)
                                                            if (HammerStore.peerId(it.next().peer.card)
                                                                    .equals(HammerStore.peerId(p.card)))
                                                                it.remove();
                                                        render();
                                                    }
                                                });
                                            }
                                        });
                                    }
                                })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                })
            .setNegativeButton("Close", null)
            .show();
    }
    private void refresh() {
        if (busy) {
            discoveryPending = true;
            return;
        }
        discoveryPending = false;
        work("Refreshing catalogs", new Work() {
            public void run() throws Exception {
                final List<Hit> fresh = new ArrayList<Hit>();
                int failures = 0;
                final Map<String, ProfileHit> artists = new LinkedHashMap<String, ProfileHit>();
                List<HammerStore.Peer> peers = new ArrayList<HammerStore.Peer>(store.followed());
                peers.addAll(new ArrayList<HammerStore.Peer>(discovered.values()));
                Set<String> visited = new HashSet<String>();
                final java.util.concurrent.ScheduledExecutorService timer =
                    Executors.newSingleThreadScheduledExecutor();
                final Hammer.Job discoveryJob = job;
                timer.schedule(new Runnable() {
                    public void run() { discoveryJob.cancel(); }
                }, 30, TimeUnit.SECONDS);
                try {
                    for (int peerIndex = 0; peerIndex < peers.size() && visited.size() < 32 && !job.cancelled;
                         peerIndex++) {
                        HammerStore.Peer p = peers.get(peerIndex);
                        if (!visited.add(HammerStore.peerId(p.card)))
                            continue;
                        job.check();
                        try {
                            for (Hammer.Song s : Hammer.catalog(p.card, job))
                                fresh.add(new Hit(p, s));
                            if (!p.card.isPrivate())
                                try {
                                    List<HammerPresence> hints = Hammer.directory(p.card, job);
                                    Collections.shuffle(hints);
                                    int accepted = 0;
                                    for (HammerPresence hint : hints) {
                                        if (++accepted > 8 || peers.size() >= 128)
                                            break;
                                        if (!visited.contains(HammerStore.peerId(hint.card)))
                                            peers.add(
                                                new HammerStore.Peer(hint.card, "Network artist", false));
                                    }
                                } catch (Exception unsupported) {
                                }
                            try {
                                for (HammerProfile profile : Hammer.profiles(p.card, job)) {
                                    ProfileHit old = artists.get(profile.id());
                                    if (old == null || profile.revision > old.profile.revision)
                                        artists.put(profile.id(), new ProfileHit(p, profile));
                                }
                            } catch (Exception unsupported) { /* Older peers still serve music. */
                            }
                        } catch (Exception e) {
                            failures++;
                        }
                    }
                } finally {
                    timer.shutdownNow();
                }
                final int failed = failures;
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        hits.clear();
                        hits.addAll(fresh);
                        profiles.clear();
                        profiles.putAll(artists);
                        render();
                        String wanted = getIntent().getStringExtra("song");
                        if (wanted != null) {
                            getIntent().removeExtra("song");
                            for (Hit h : fresh)
                                if (h.song.id.equals(wanted)) {
                                    song(h);
                                    break;
                                }
                        }
                        Ui.toast(HammerActivity.this,
                                 fresh.size() + " entries; " + failed + " peers unavailable");
                    }
                });
            }
        });
    }
    private void render() {
        if (closed)
            return;
        results.removeAllViews();
        String q = query.getText().toString();
        int count = 0;
        if (artistFilter == null)
            for (final ProfileHit profile : profiles.values())
                if (profile.profile.matches(q)) {
                    button(results, "Artist · " + profile.profile.name, new Runnable() {
                        public void run() {
                            try {
                                startActivity(new Intent(HammerActivity.this, HammerArtistActivity.class)
                                                  .putExtra("peer", profile.peer.card.encode())
                                                  .putExtra("artist", profile.profile.id()));
                            } catch (Exception ignored) {
                            }
                        }
                    });
                }
        Set<String> displayed = new HashSet<String>();
        for (final Hit hit : hits)
            if ((artistFilter == null || artistFilter.equals(shortAuthorFull(hit.song.key))) &&
                (hit.song.matches(q) || artistMatches(hit.song.key, q)) &&
                displayed.add(hit.song.id + hit.song.sig)) {
                if (++count > 100)
                    break;
                button(results, hit.song.title + " · " + artistName(hit.song.key), new Runnable() {
                    public void run() { song(hit); }
                });
            }
        TextView n = Ui.text(this,
                             count > 100 ? "Showing the first 100 matches. Narrow your search."
                                         : count + " songs in reachable catalogs",
                             Skin.DIM, 13);
        results.addView(n);
        if (hits.isEmpty() && profiles.isEmpty())
            results.addView(Ui.text(
                this,
                "Nearby hosts appear automatically. Internet discovery needs a reachable community entry " +
                "peer; "
                    + "it cannot cross every router yet. Add an entry peer in Library → Sharing settings.",
                Skin.DIM, 14));
    }
    private String shortAuthorFull(String key) {
        try {
            return Hammer.hash(java.util.Base64.getDecoder().decode(key));
        } catch (Exception e) {
            return "";
        }
    }
    private String artistName(String key) {
        ProfileHit p = profiles.get(shortAuthorFull(key));
        return p == null ? "Artist " + shortAuthor(key) : p.profile.name;
    }
    private boolean artistMatches(String key, String query) {
        ProfileHit p = profiles.get(shortAuthorFull(key));
        return p != null && p.profile.matches(query);
    }
    private List<Hammer.Card> hosts(Hit hit) {
        List<Hammer.Card> ps = new ArrayList<Hammer.Card>();
        ps.add(hit.peer.card);
        Set<String> known = new HashSet<String>();
        known.add(HammerStore.peerId(hit.peer.card));
        for (Hit h : hits)
            if (h.song.id.equals(hit.song.id) && h.song.sig.equals(hit.song.sig) &&
                known.add(HammerStore.peerId(h.peer.card)))
                ps.add(h.peer.card);
        return ps;
    }
    private void song(final Hit hit) {
        new Lux.Builder(this)
            .setTitle(hit.song.title)
            .setNeutralButton(
                "Track details",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(hit.song.title)
                            .setMessage(hit.song.tags + "\n" + (hit.song.size / 1048576) + " MiB · " +
                                        (hit.peer.card.isPrivate() ? "Private circle" : "Public catalog") +
                                        "\nPublisher: " + shortAuthor(hit.song.key))
                            .setPositiveButton("Done", null)
                            .show();
                    }
                })
            .setItems(new String[] {"Stream / retry stream",
                                    hit.song.reshare ? "Download / resume" : "Streaming only", "Comments",
                                    "Up / down votes", "Follow this artist", "Artist profile"},
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, int which) {
                              if (which == 0) {
                                  new Lux.Builder(HammerActivity.this)
                                      .setTitle("Stream quality")
                                      .setItems(
                                          new String[] {"Auto · adaptive Opus",
                                                        "Original · lossless when source is lossless",
                                                        "Data saver · 48 kbps Opus"},
                                          new DialogInterface.OnClickListener() {
                                              public void onClick(DialogInterface d, int mode) {
                                                  streamMode = mode;
                                                  try {
                                                      HammerService.play(HammerActivity.this, hosts(hit),
                                                                         hit.song, streamMode);
                                                  } catch (Exception e) {
                                                      Ui.toast(HammerActivity.this, "Could not open stream");
                                                  }
                                              }
                                          })
                                      .setNegativeButton("Cancel", null)
                                      .show();
                              } else if (which == 1 && hit.song.reshare)
                                  download(hit);
                              else if (which == 2)
                                  comments(hit);
                              else if (which == 3)
                                  votes(hit);
                              else if (which == 4)
                                  followArtist(hit);
                              else if (which == 5)
                                  startActivity(new Intent(HammerActivity.this, HammerArtistActivity.class)
                                                    .putExtra("peer", hit.peer.card.encode())
                                                    .putExtra("artist", shortAuthorFull(hit.song.key)));
                          }
                      })
            .setNegativeButton("Close", null)
            .show();
    }
    private String shortAuthor(String key) {
        try {
            return Hammer.hash(java.util.Base64.getDecoder().decode(key)).substring(0, 16);
        } catch (Exception e) {
            return "Unknown";
        }
    }
    private void download(final Hit hit) {
        work("Downloading verified audio", new Work() {
            public void run() throws Exception {
                File target = store.downloadFile(hit.song), part =
                                                                new File(store.root, hit.song.id + ".part");
                if (!target.isFile()) {
                    Exception failure = null;
                    for (Hammer.Card p : hosts(hit)) {
                        job.check();
                        try {
                            Hammer.download(p, hit.song, part, target, job);
                            failure = null;
                            break;
                        } catch (Exception e) {
                            failure = e;
                        }
                    }
                    if (failure != null)
                        throw failure;
                }
                Hammer.require(target.length() == hit.song.size && Hammer.hash(target).equals(hit.song.id),
                               "Local copy does not match");
                store.rememberDownload(hit.song);
                try {
                    ProfileHit artist = profiles.get(shortAuthorFull(hit.song.key));
                    if (artist != null) {
                        store.publicObjects.rememberProfile(artist.profile);
                        if (!artist.profile.image.isEmpty())
                            store.publicObjects.putImage(Hammer.image(artist.peer.card, artist.profile.id(),
                                                                      artist.profile.image, true, job));
                    }
                    HammerArt art = Hammer.cover(hit.peer.card, hit.song, job);
                    if (art != null) {
                        store.publicObjects.putImage(
                            Hammer.image(hit.peer.card, art.song, art.image, false, job));
                        store.publicObjects.cover(hit.song, art);
                    }
                } catch (Exception optionalArtwork) { /* The verified recording remains usable. */
                }
                saveLibrary(hit.song);
                if (!hit.peer.card.isPrivate())
                    try {
                        store.importPublicMetadata(hit.song, Hammer.comments(hit.peer.card, hit.song.id, job),
                                                   Hammer.votes(hit.peer.card, hit.song.id, job));
                    } catch (Exception e) {
                        ui.post(new Runnable() {
                            public void run() {
                                if (!closed)
                                    Ui.toast(HammerActivity.this,
                                             "Audio verified; public annotations can be synced later");
                            }
                        });
                    }
            }
        });
    }
    private void downloads() {
        work("Reading downloads", new Work() {
            public void run() throws Exception {
                final List<Hammer.Song> songs = store.downloads();
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        String[] names = new String[songs.size()];
                        for (int i = 0; i < names.length; i++)
                            names[i] = songs.get(i).title;
                        new Lux.Builder(HammerActivity.this)
                            .setTitle("Verified downloads")
                            .setItems(
                                names,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int n) {
                                        final Hammer.Song s = songs.get(n);
                                        new Lux.Builder(HammerActivity.this)
                                            .setTitle(s.title)
                                            .setItems(
                                                new String[] {"Save to Library", "Host in private circle",
                                                              "Publish publicly"},
                                                new DialogInterface.OnClickListener() {
                                                    public void onClick(DialogInterface d, final int action) {
                                                        if (action == 0)
                                                            work("Saving to Library", new Work() {
                                                                public void run() throws Exception {
                                                                    saveLibrary(s);
                                                                }
                                                            });
                                                        else
                                                            new Lux.Builder(HammerActivity.this)
                                                                .setTitle(
                                                                    action == 2
                                                                        ? "Publish this download publicly?"
                                                                        : "Host this download privately?")
                                                                .setMessage(
                                                                    "The original signed title and publisher "
                                                                    + "stay attached. You are offering your "
                                                                    + "verified copy from this device.")
                                                                .setPositiveButton(
                                                                    "Host copy",
                                                                    new DialogInterface.OnClickListener() {
                                                                        public void onClick(DialogInterface d,
                                                                                            int w) {
                                                                            work("Hosting downloaded copy",
                                                                                 new Work() {
                                                                                     public void run()
                                                                                         throws Exception {
                                                                                         store.rehost(
                                                                                             s, action == 2);
                                                                                     }
                                                                                 });
                                                                        }
                                                                    })
                                                                .setNegativeButton("Cancel", null)
                                                                .show();
                                                    }
                                                })
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
    private void saveLibrary(Hammer.Song song) throws Exception {
        String base = "hammer-" + song.id;
        File out = new File(Store.library(this), base + song.extension()), source = store.downloadFile(song);
        if (!out.exists()) {
            File tmp = File.createTempFile("hammer-", ".part", Store.library(this));
            try {
                try (InputStream in = new FileInputStream(source);
                     FileOutputStream file = new FileOutputStream(tmp)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) != -1) {
                        job.check();
                        file.write(b, 0, n);
                    }
                    file.getFD().sync();
                }
                Hammer.require(Hammer.hash(tmp).equals(song.id), "Copy verification failed");
                Hammer.require(tmp.renameTo(out), "Could not save song");
            } finally {
                tmp.delete();
            }
        }
        Hammer.require(Hammer.hash(out).equals(song.id), "Existing Library file differs");
        store.relocateDownload(song, out);
        if (!source.getCanonicalFile().equals(out.getCanonicalFile()))
            Hammer.require(source.delete(), "Library copy saved; old download could not be removed");
        Store.write(new File(Store.library(this), base + ".hammer"), store.publicMetadata(song));
        try {
            HammerArt cover = store.publicObjects.localCover(song.id);
            if (cover != null && cover.key.equals(song.key)) {
                File raw = File.createTempFile("download-cover-", ".image", getCacheDir());
                try {
                    HammerStore.atomic(raw, store.publicObjects.localImage(cover.image));
                    Store.write(new File(Store.library(this), base + ".cover.jpg"),
                                ((HammerCodec)HammerMedia.codec).image(raw));
                } finally {
                    raw.delete();
                }
            }
        } catch (Exception optionalArtwork) { /* A bad cover must not hide verified audio from Library. */
        }
        File meta = new File(Store.library(this), base + ".json");
        if (!meta.exists()) {
            org.json.JSONObject m = new org.json.JSONObject();
            m.put("_title", song.title);
            m.put("_audio", out.getName());
            m.put("style", song.tags);
            m.put("_created", System.currentTimeMillis());
            m.put("_hammer_id", song.id);
            m.put("_hammer_publisher", song.key);
            Store.write(meta, m.toString().getBytes("UTF-8"));
        }
    }
    private void hosted() {
        final List<HammerStore.Hosted> list = store.hosted();
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = list.get(i).song.title + (list.get(i).publicSong ? " · public" : " · private");
        new Lux.Builder(this)
            .setTitle("Hosted songs")
            .setItems(
                names,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int n) {
                        final HammerStore.Hosted h = list.get(n);
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(h.song.title)
                            .setItems(
                                new String[] {h.publicSong ? "Move to private circle" : "Publish publicly",
                                              "Unpublish", "Read / moderate comments"},
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, final int action) {
                                        if (action == 2) {
                                            localComments(h.song);
                                            return;
                                        }
                                        new Lux.Builder(HammerActivity.this)
                                            .setTitle(action == 1 ? "Stop offering this song?"
                                                                  : "Change who can discover this song?")
                                            .setMessage(
                                                "Existing downloads cannot be recalled. Public publishing "
                                                + "makes the title and tags available to any visitor.")
                                            .setPositiveButton(
                                                "Apply",
                                                new DialogInterface.OnClickListener() {
                                                    public void onClick(DialogInterface d, int w) {
                                                        work("Updating publication", new Work() {
                                                            public void run() throws Exception {
                                                                if (action == 1)
                                                                    store.unpublish(h.song.id);
                                                                else
                                                                    store.visibility(h.song.id,
                                                                                     !h.publicSong);
                                                                if (action == 1 || h.publicSong)
                                                                    HammerService.disconnect();
                                                            }
                                                        });
                                                    }
                                                })
                                            .setNegativeButton("Cancel", null)
                                            .show();
                                    }
                                })
                            .show();
                    }
                })
            .setNegativeButton("Close", null)
            .show();
    }
    private void comments(final Hit hit) {
        work("Reading signed comments", new Work() {
            public void run() throws Exception {
                final List<Hammer.Comment> cs = Hammer.comments(hit.peer.card, hit.song.id, job);
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        StringBuilder b = new StringBuilder();
                        for (Hammer.Comment c : cs)
                            b.append(c.name)
                                .append(" [")
                                .append(shortAuthor(c.key))
                                .append("]\n")
                                .append(c.body)
                                .append("\n\n");
                        new Lux.Builder(HammerActivity.this)
                            .setTitle("Comments on this peer")
                            .setMessage(b.length() == 0 ? "No comments on this host yet." : b.toString())
                            .setPositiveButton("Write comment",
                                               new DialogInterface.OnClickListener() {
                                                   public void onClick(DialogInterface d, int w) {
                                                       writeComment(hit);
                                                   }
                                               })
                            .setNeutralButton(
                                "Sync public track metadata",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        work("Syncing signed comments", new Work() {
                                            public void run() throws Exception {
                                                Hammer.require(
                                                    !hit.peer.card.isPrivate(),
                                                    "Private comments stay in their original circle");
                                                store.importPublicMetadata(
                                                    hit.song, cs,
                                                    Hammer.votes(hit.peer.card, hit.song.id, job));
                                            }
                                        });
                                    }
                                })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                });
            }
        });
    }
    private void writeComment(final Hit hit) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final EditText name = Ui.edit(this, "Display name (identity shown beside it)", 1);
        final EditText text = Ui.edit(this, "Your comment", 4);
        v.addView(name);
        v.addView(text);
        new Lux.Builder(this)
            .setTitle("Post signed comment")
            .setMessage("This peer stores your comment. Public song comments can be copied to other hosts; "
                        + "private song comments are visible to the host's invited circle.")
            .setView(v)
            .setPositiveButton(
                "Post",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String who = name.getText().toString(), body = text.getText().toString();
                        work("Posting comment", new Work() {
                            public void run() throws Exception {
                                Hammer.Comment c = new Hammer.Comment();
                                c.song = hit.song.id;
                                c.audience = hit.peer.card.isPrivate()
                                                 ? Hammer.hash(hit.peer.card.token.getBytes("US-ASCII"))
                                                 : "public";
                                c.id = Hammer.random();
                                c.time = System.currentTimeMillis();
                                c.name = who;
                                c.body = body;
                                c.sign(HammerAndroid.identity());
                                Hammer.comment(hit.peer.card, c, job);
                            }
                        });
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void localComments(final Hammer.Song song) {
        work("Reading hosted comments", new Work() {
            public void run() throws Exception {
                final List<Hammer.Comment> cs = store.comments(song.id, store.token());
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        String[] names = new String[cs.size()];
                        for (int i = 0; i < names.length; i++)
                            names[i] =
                                cs.get(i).name + " [" + shortAuthor(cs.get(i).key) + "]: " + cs.get(i).body;
                        new Lux.Builder(HammerActivity.this)
                            .setTitle("Tap an author to moderate")
                            .setItems(names,
                                      new DialogInterface.OnClickListener() {
                                          public void onClick(DialogInterface d, final int n) {
                                              new Lux.Builder(HammerActivity.this)
                                                  .setTitle("Block this author on your host?")
                                                  .setMessage(
                                                      "Their comments disappear from your host. Other "
                                                      + "peers control their own copies.")
                                                  .setPositiveButton(
                                                      "Block author",
                                                      new DialogInterface.OnClickListener() {
                                                          public void onClick(DialogInterface d, int w) {
                                                              work("Blocking comment author", new Work() {
                                                                  public void run() throws Exception {
                                                                      store.block(cs.get(n).key);
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
    private void liveFollow() {
        if (HammerFollowService.prefs(this).getBoolean("live", false)) {
            HammerFollowService.stop(this);
            return;
        }
        if (store.followed().isEmpty()) {
            Ui.toast(this, "Follow a peer first");
            return;
        }
        if (!HammerFollowService.hasArtists(this)) {
            Ui.toast(this, "Open a song and follow its artist first");
            return;
        }
        new Lux.Builder(this)
            .setTitle("Enable Live Follow?")
            .setMessage("Keep encrypted connections to up to sixteen followed peers for immediate release "
                        + "events while connected. This uses extra battery and a persistent notification. "
                        + "Android or network restrictions can still interrupt delivery; reconnect catches "
                        + "up. Hosting does not need to be on.")
            .setPositiveButton(
                "Enable",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        android.app.NotificationManager manager =
                            (android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                        android.app.NotificationChannel releases =
                            manager.getNotificationChannel("hammer-releases");
                        if (!manager.areNotificationsEnabled() ||
                            (releases != null &&
                             releases.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE)) {
                            Ui.toast(HammerActivity.this,
                                     "Allow release notifications, then enable Live Follow");
                            Intent settings =
                                new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                            startActivity(settings);
                            return;
                        }
                        HammerFollowService.start(HammerActivity.this);
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void followArtist(final Hit hit) {
        final EditText name = Ui.edit(this, "Name for this artist", 1);
        name.setText(artistName(hit.song.key));
        new Lux.Builder(this)
            .setTitle("Follow the signed artist identity")
            .setMessage("Followed hosts can introduce copies by this artist. Their signing key, not a "
                        + "profile name, identifies a new release. Enable Live Follow for notifications.")
            .setView(name)
            .setPositiveButton(
                "Follow artist",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String artistLabel = name.getText().toString();
                        work("Following artist", new Work() {
                            public void run() throws Exception {
                                store.follow(new HammerStore.Peer(
                                    hit.peer.card, artistLabel.isEmpty() ? "Artist" : artistLabel, false));
                                String id = Hammer.hash(java.util.Base64.getDecoder().decode(hit.song.key));
                                HammerFollowService.prefs(HammerActivity.this)
                                    .edit()
                                    .putString("artist." + id, artistLabel)
                                    .apply();
                                HammerFollowService.changed(HammerActivity.this);
                            }
                        });
                    }
                })
            .setNegativeButton("Cancel", null)
            .show();
    }
    private void artists() {
        final List<String> ids = new ArrayList<String>(), names = new ArrayList<String>();
        for (java.util.Map.Entry<String, ?> e : HammerFollowService.prefs(this).getAll().entrySet())
            if (e.getKey().startsWith("artist.")) {
                ids.add(e.getKey());
                names.add(String.valueOf(e.getValue()));
            }
        new Lux.Builder(this)
            .setTitle("Tap an artist to unfollow")
            .setItems(
                names.toArray(new String[0]),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        HammerFollowService.prefs(HammerActivity.this).edit().remove(ids.get(w)).apply();
                        HammerFollowService.changed(HammerActivity.this);
                    }
                })
            .setNegativeButton("Close", null)
            .show();
    }
    private void votes(final Hit hit) {
        work("Reading signed vote metadata", new Work() {
            public void run() throws Exception {
                final List<Hammer.Vote> list = Hammer.votes(hit.peer.card, hit.song.id, job);
                int up = 0, down = 0;
                for (Hammer.Vote v : list) {
                    if (v.value == 1)
                        up++;
                    if (v.value == -1)
                        down++;
                }
                final String count = up + " up · " + down + " down";
                ui.post(new Runnable() {
                    public void run() {
                        if (closed)
                            return;
                        new Lux.Builder(HammerActivity.this)
                            .setTitle(count)
                            .setNeutralButton(
                                "About votes",
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int which) {
                                        new Lux.Builder(HammerActivity.this)
                                            .setTitle("About votes")
                                            .setMessage(
                                                "Votes show no profile name. A separate signing key "
                                                + "per track and "
                                                + "circle lets hosts merge votes without double-counting "
                                                + "replicas. "
                                                + "Keys are not proof of unique people.")
                                            .setPositiveButton("Done", null)
                                            .show();
                                    }
                                })
                            .setItems(
                                new String[] {"Upvote", "Downvote", "Clear my vote"},
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int n) {
                                        final int value = n == 0 ? 1 : n == 1 ? -1 : 0;
                                        work("Saving anonymous-display vote", new Work() {
                                            public void run() throws Exception {
                                                Hammer.Vote v = new Hammer.Vote();
                                                v.song = hit.song.id;
                                                v.audience = hit.peer.card.isPrivate()
                                                                 ? Hammer.hash(hit.peer.card.token.getBytes(
                                                                       "US-ASCII"))
                                                                 : "public";
                                                v.value = value;
                                                java.security.KeyPair key =
                                                    HammerAndroid.voteIdentity(v.song, v.audience);
                                                String pub = Hammer.publicKey(key.getPublic());
                                                String seq = "vote." + v.song + "." + v.audience;
                                                long last =
                                                    getSharedPreferences("hammer-votes", 0).getLong(seq, 0);
                                                for (Hammer.Vote old : list)
                                                    if (old.key.equals(pub) &&
                                                        old.audience.equals(v.audience))
                                                        last = Math.max(last, old.sequence);
                                                v.sequence = last + 1;
                                                v.sign(key);
                                                Hammer.vote(hit.peer.card, v, job);
                                                getSharedPreferences("hammer-votes", 0)
                                                    .edit()
                                                    .putLong(seq, v.sequence)
                                                    .apply();
                                                if (!hit.peer.card.isPrivate())
                                                    for (Hammer.Card p : hosts(hit)) {
                                                        job.check();
                                                        if (p == hit.peer.card)
                                                            continue;
                                                        try {
                                                            Hammer.vote(p, v, job);
                                                        } catch (Exception ignored) {
                                                        }
                                                    }
                                            }
                                        });
                                    }
                                })
                            .setNegativeButton("Close", null)
                            .show();
                    }
                });
            }
        });
    }
    @Override
    protected void onActivityResult(int request, int result, final Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 840 && result == RESULT_OK && data != null && data.getData() != null) {
            work("Saving desktop server", new Work() {
                public void run() throws Exception {
                    try (InputStream in = getAssets().open("hammer-server.zip");
                         OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                        Hammer.require(out != null, "Cannot write selected file");
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) != -1) {
                            job.check();
                            out.write(b, 0, n);
                        }
                    }
                }
            });
        }
    }
    @Override
    protected void onDestroy() {
        closed = true;
        if (nearby != null)
            nearby.close();
        ui.removeCallbacksAndMessages(null);
        if (job != null)
            job.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
}
