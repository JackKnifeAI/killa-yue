package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.LuxFrame;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.SoundPacks;
import ai.jackknife.killayue.studio.Sounds;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sounds: designed sounds made on the phone, and recorded instruments downloaded once as packs and shared by
 * every project. Tap a sound to play it on the track; a recorded one is downloaded first. Hold one to remove its
 * download.
 */
final class SoundsDialog {
    private SoundsDialog() {}
    static void install(final Context c) {
        if (SoundPacks.decoder == null)
            SoundPacks.decoder = new SoundPacks.Decoder() {
                public void decode(File in, File out, int rate) throws IOException {
                    ImportJob.run(c.getApplicationContext(), in, out, rate, new ImportJob.Progress() {
                        public void at(float f) {}
                    }, new AtomicBoolean());
                }
            };
    }
    static void show(StudioActivity a, KeysScreen keys) { show(a, keys, false); }
    static void show(final StudioActivity a, final KeysScreen keys, final boolean newTrack) {
        install(a);
        final File root = StudioSession.root(a);
        ScrollView scroll = new ScrollView(a);
        LinearLayout list = Ui.column(a);
        int pad = Ui.dp(a, 12);
        list.setPadding(pad, 0, pad, pad);
        scroll.addView(list);
        final AlertDialog[] dialog = new AlertDialog[1];
        String group = "";
        int current = keys.current() == null ? -1 : keys.current().notes.instrument;
        for (final Sounds.Sound s : Sounds.ALL) {
            if (!s.group.equals(group)) {
                group = s.group;
                TextView g = Ui.label(a, s.acoustic() ? group + " · recorded" : "Designed on the phone");
                list.addView(g, Ui.gap(a, 10));
            }
            boolean here = !s.acoustic() || SoundPacks.installedIn(root, s.pack);
            SoundPacks.Info info = s.acoustic() ? SoundPacks.info(s.pack) : null;
            LinearLayout card = Ui.row(a);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(new LuxFrame(12, 6));
            card.setPadding(Ui.dp(a, 18), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
            LinearLayout words = Ui.column(a);
            TextView name = Ui.text(a, s.name, s.id == current && !newTrack ? Skin.HI : Skin.TEXT, 20);
            name.setTypeface(Skin.script());
            words.addView(name);
            words.addView(Ui.text(a, s.blurb, Skin.DIM, 12));
            if (info != null)
                words.addView(Ui.text(a, info.credit, Skin.FAINT, 10));
            card.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
            String state = !s.acoustic() ? "Ready"
                         : here ? VaultScreen.size(SoundPacks.size(root, s.pack))
                         : info == null || info.sha256.isEmpty() ? "Coming soon"
                         : "↓ " + VaultScreen.size(info.bytes);
            TextView tag = Ui.text(a, state, here ? Skin.ACCENT : Skin.LO, 12);
            tag.setTypeface(Skin.text(), Typeface.BOLD);
            tag.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 4), 0);
            card.addView(tag);
            card.setContentDescription(s.name + ", " + s.blurb + ", " + state);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (s.acoustic() && !SoundPacks.installedIn(root, s.pack)) {
                        download(a, s, new Runnable() {
                            public void run() {
                                if (dialog[0] != null)
                                    dialog[0].dismiss();
                                keys.soundChosen(s.id, newTrack);
                            }
                        });
                        return;
                    }
                    if (dialog[0] != null)
                        dialog[0].dismiss();
                    keys.soundChosen(s.id, newTrack);
                }
            });
            if (s.acoustic())
                card.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        if (!SoundPacks.installedIn(root, s.pack))
                            return false;
                        new Lux.Builder(a)
                            .setTitle("Remove " + s.name + "?")
                            .setMessage("Frees " + VaultScreen.size(SoundPacks.size(root, s.pack)) + ". Projects that use it ask to download it again.")
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    SoundPacks.remove(root, s.pack);
                                    if (dialog[0] != null)
                                        dialog[0].dismiss();
                                    keys.loadedSound = -1;
                                }
                            })
                            .setNegativeButton("Keep", null)
                            .show();
                        return true;
                    }
                });
            list.addView(card, Ui.gap(a, 6));
        }
        dialog[0] = new Lux.Builder(a)
                        .setTitle(newTrack ? "A sound for the new track" : "Sounds")
                        .setView(scroll)
                        .setNegativeButton("Close", null)
                        .create();
        dialog[0].show();
        dialog[0].getWindow().setLayout(-1, -2);
    }

    /** Downloads, verifies and installs a pack, then runs after on the UI thread. */
    static void download(final StudioActivity a, final Sounds.Sound s, final Runnable after) {
        install(a);
        final SoundPacks.Info info = SoundPacks.info(s.pack);
        if (info == null || info.sha256.isEmpty()) {
            Ui.toast(a, s.name + " is coming in an update.");
            return;
        }
        final AtomicBoolean cancel = new AtomicBoolean();
        final TextView status = Ui.text(a, "Downloading " + s.name + "…", Skin.TEXT, 15);
        int pad = Ui.dp(a, 18);
        status.setPadding(pad, pad, pad, pad);
        final AlertDialog progress = new Lux.Builder(a)
                                         .setTitle(s.name)
                                         .setView(status)
                                         .setCancelable(false)
                                         .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                                             public void onClick(DialogInterface d, int w) { cancel.set(true); }
                                         })
                                         .create();
        progress.show();
        final File root = StudioSession.root(a);
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                try {
                    final boolean[] decoding = {false};
                    SoundPacks.install(root, info, new Sounds.Progress() {
                        long last;
                        public void at(final int done, final int total) {
                            long now = System.currentTimeMillis();
                            if (now - last < 120 && done < total)
                                return;
                            last = now;
                            final int pct = (int) (done * 100L / Math.max(1, total));
                            if (done == total && !decoding[0])
                                decoding[0] = true;
                            a.runOnUiThread(new Runnable() {
                                public void run() {
                                    status.setText((decoding[0] ? "Unpacking the recordings: " : "Downloading " + s.name + ": ") + pct + "%");
                                }
                            });
                        }
                    }, cancel);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String why = error;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        if (a.isFinishing() || a.isDestroyed())
                            return;
                        progress.dismiss();
                        if (cancel.get())
                            return;
                        if (why != null)
                            new Lux.Builder(a).setTitle("Could not download " + s.name).setMessage(why).setPositiveButton("OK", null).show();
                        else
                            after.run();
                    }
                });
            }
        }, "studio-sound-pack").start();
    }
}
