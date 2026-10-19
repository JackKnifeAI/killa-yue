package ai.jackknife.killayue;

import ai.jackknife.killayue.peer.*;
import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Separate explicit consent for a minimal monthly installation count. No profile or usage events are sent.
 */
public final class CommunityCountActivity extends Activity {
    // Filled with the first ops node's public pinned card after deployment verification.
    static final String DEFAULT_NODE =
        "hammer://12D3KooWLRPuZ5ce9YGFvuJLcNdUmVH1hwM8CJ7b9favwqxTepSJ.p2p:4001/" +
        "#ab07ac21b20aa2afb4f13af9f4c3d575e1e1d9c5795b74a05af3cf6beb809da3";
    private static final AtomicBoolean running = new AtomicBoolean();
    private TextView status;
    private EditText address;
    private CheckBox enabled;
    private boolean closed;
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("community-count", 0); }
    static synchronized String secret(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("secret", "");
        if (s.isEmpty()) {
            s = Hammer.random();
            p.edit().putString("secret", s).commit();
        }
        return s;
    }
    static String node(Context c) { return prefs(c).getString("node", DEFAULT_NODE); }
    static void onLaunch(Context c) {
        Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        if (!p.getBoolean("enabled", false) || node(app).isEmpty() ||
            HammerCensus.month().equals(p.getString("sentMonth", "")) ||
            System.currentTimeMillis() - p.getLong("lastAttempt", 0) < 6 * 3600000L ||
            !running.compareAndSet(false, true))
            return;
        p.edit().putLong("lastAttempt", System.currentTimeMillis()).apply();
        new Thread(new Runnable() {
            public void run() {
                try {
                    send(app, false, true);
                } catch (Exception ignored) { /* Never log tokens or identities. */
                } finally {
                    running.set(false);
                }
            }
        }, "community-monthly-count").start();
    }
    private static int send(Context c, boolean remove, boolean participate) throws Exception {
        Hammer.Card node = Hammer.Card.parse(node(c));
        Hammer.require(!node.isPrivate(), "Use a public node card");
        if (node.isP2p())
            HammerAndroid.network(c);
        // Recheck consent after starting transport; disabling prevents a queued new registration.
        Hammer.require(remove || !participate || prefs(c).getBoolean("enabled", false), "Counting is off");
        String token = participate ? HammerCensus.token(secret(c), node.pin, HammerCensus.month()) : "";
        int count = HammerCensus.count(node, token, remove, new Hammer.Job());
        if (participate && !remove)
            prefs(c).edit().putString("sentMonth", HammerCensus.month()).apply();
        return count;
    }
    public void onCreate(Bundle saved) {
        setTheme(Skin.themeFor(CommunityCountActivity.this, Skin.id(CommunityCountActivity.this)));
        super.onCreate(saved);
        Skin.load(CommunityCountActivity.this);
        ScrollView scroll = new ScrollView(CommunityCountActivity.this);
        LinearLayout body = Ui.column(CommunityCountActivity.this);
        int pad = Ui.dp(CommunityCountActivity.this, 16);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
        scroll.setBackground(Skin.background(CommunityCountActivity.this));
        scroll.setFitsSystemWindows(true);
        setContentView(scroll);
        body.addView(Ui.title(CommunityCountActivity.this, "Community headcount"));
        body.addView(
            Ui.text(CommunityCountActivity.this,
                    "Help measure participating installations active this UTC calendar month. "
                        + "The selected node receives only the month and a random-looking monthly token, " +
                          "separate from your profile. "
                        + "It keeps tokens until the next month to avoid counting repeat launches. No " +
                          "songs, listening history or hardware identifiers are included. "
                        + "This joins the network when the node uses a network card. Connected peers can " +
                          "see network addresses; our counting service does not retain IP logs. "
                        + "Reinstalling or using multiple devices can count again. This is not a verified " +
                          "count of people.",
                    Skin.DIM, 14));
        address = Ui.edit(CommunityCountActivity.this, "Public counting node card", 3);
        address.setText(node(CommunityCountActivity.this));
        body.addView(address);
        Button save = Ui.button(CommunityCountActivity.this, "Save counting node", false);
        body.addView(save);
        enabled = new CheckBox(CommunityCountActivity.this);
        enabled.setText("Include this installation in the monthly count");
        enabled.setTextColor(Skin.TEXT);
        enabled.setChecked(prefs(CommunityCountActivity.this).getBoolean("enabled", false));
        body.addView(enabled);
        status = Ui.text(CommunityCountActivity.this, "Counting is optional. The app works with it off.", Skin.DIM, 14);
        body.addView(status);
        save.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                try {
                    Hammer.Card card = Hammer.Card.parse(address.getText().toString().trim());
                    Hammer.require(!card.isPrivate(), "Use a public node card");
                    Hammer.require(!prefs(CommunityCountActivity.this).getBoolean("enabled", false),
                                   "Turn counting off before changing nodes");
                    prefs(CommunityCountActivity.this)
                        .edit()
                        .putString("node", card.encode())
                        .remove("sentMonth")
                        .remove("lastAttempt")
                        .apply();
                    status.setText("Node saved");
                } catch (Exception e) {
                    status.setText(e.getMessage());
                }
            }
        });
        enabled.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton button, boolean on) {
                if (node(CommunityCountActivity.this).isEmpty()) {
                    enabled.setChecked(false);
                    status.setText("Save a counting node first");
                    return;
                }
                prefs(CommunityCountActivity.this).edit().putBoolean("enabled", on).remove("sentMonth").apply();
                update(!on, true);
            }
        });
        Button count = Ui.button(CommunityCountActivity.this, "Refresh this node's monthly count", false);
        body.addView(count);
        count.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                update(false, prefs(CommunityCountActivity.this).getBoolean("enabled", false));
            }
        });
    }
    private void update(boolean remove, boolean participate) {
        if (!running.compareAndSet(false, true)) {
            status.setText(remove ? "Counting is off. A previous request is finishing; its token may " +
                                    "remain until next month."
                                  : "A count request is in progress; try again shortly");
            return;
        }
        status.setText(remove ? "Counting off. Removing this month's token…" : "Contacting selected node…");
        new Thread(new Runnable() {
            public void run() {
                String result;
                try {
                    int count = send(getApplicationContext(), remove, participate);
                    result = count + " participating installations on this node · " + HammerCensus.month() +
                             (remove ? "\nYour current token was removed." : "");
                } catch (Exception e) {
                    result = remove ? "Counting is off. Node unavailable: this month's previous token " +
                                      "expires next month."
                                    : "Node unavailable or this host does not provide counting. Your " +
                                      "choice is saved; no count was confirmed.";
                } finally {
                    running.set(false);
                }
                final String text = result;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!closed)
                            status.setText(text);
                    }
                });
            }
        }, "community-count-request").start();
    }
    protected void onDestroy() {
        closed = true;
        super.onDestroy();
    }
}
