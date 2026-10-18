package ai.jackknife.killayue.studio.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Store;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.History;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.ProjectStore;
import ai.jackknife.killayue.studio.Projects;
import ai.jackknife.killayue.studio.Track;

/** THE VAULT: the projects, newest first; a new one from a song or a file; open, rename, compact, delete. */
final class VaultScreen implements Screen {
    static final int PICK_FILE = 41, PICK_BUNDLE = 42, SAVE_BUNDLE = 43;
    private File exporting;

    private final StudioActivity a;
    private final ScrollView view;
    private final LinearLayout list;

    VaultScreen(StudioActivity a) {
        this.a = a;
        view = new ScrollView(a);
        LinearLayout col = Ui.column(a);
        col.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 16));
        col.addView(Ui.label(a, "The Vault"));
        Button fromSong = Ui.button(a, "New from a song", true);
        col.addView(fromSong, Ui.gap(a, 4));
        fromSong.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickSong();
            }
        });
        Button fromFile = Ui.button(a, "Bring in a file", false);
        col.addView(fromFile, Ui.gap(a, 10));
        fromFile.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                a.startActivityForResult(i, PICK_FILE);
            }
        });
        Button bundle = Ui.button(a, "Open a portable project (.killa)", false);
        col.addView(bundle, Ui.gap(a, 2));
        bundle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                a.startActivityForResult(i, PICK_BUNDLE);
            }
        });
        col.addView(Ui.label(a, "Projects"));
        list = Ui.column(a);
        col.addView(list);
        view.addView(col);
    }

    public String name() {
        return "Vault";
    }

    public View view() {
        return view;
    }

    public void shown() {
        refresh();
    }

    public void hidden() {}

    public void frame(NativeEngine.Status s) {}

    void refresh() {
        list.removeAllViews();
        List<Projects.Entry> ps = Projects.list(StudioSession.root(a));
        if (ps.isEmpty()) {
            list.addView(Ui.text(a, "No projects yet. Make one from a song you made, or bring in a file.", Skin.DIM, 14));
            return;
        }
        DateFormat when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        long all = 0;
        for (Projects.Entry e : ps) all += e.bytes;
        list.addView(Ui.text(a, ps.size() + (ps.size() == 1 ? " project uses " : " projects use ") + size(all) + " · the phone has "
                + size(StudioSession.root(a).getUsableSpace()) + " free. Tap ⋯ to wrap up, compact or delete one.", Skin.DIM, 13), Ui.gap(a, 6));
        for (final Projects.Entry e : ps) {
            LinearLayout line = Ui.row(a);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            Button b = Ui.button(a, e.name, false);
            b.setText(e.name + "\n" + when.format(new Date(e.modified)) + " · " + size(e.bytes) + (e.problem != null ? " · opened from a backup" : ""));
            b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            b.setTextSize(14);
            if (a.session.dir() != null && a.session.dir().equals(e.dir)) b.setTextColor(Skin.ACCENT);
            line.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
            Button more = RoundFace.button(a, "⋯");
            more.setContentDescription("What to do with " + e.name);
            ((LinearLayout.LayoutParams) more.getLayoutParams()).leftMargin = Ui.dp(a, 6);
            more.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    menu(e);
                }
            });
            line.addView(more);
            list.addView(line, Ui.gap(a, 8));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openProject(e.dir);
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    menu(e);
                    return true;
                }
            });
        }
    }

    static String size(long bytes) {
        if (bytes < 1 << 20) return (bytes >> 10) + " KB";
        if (bytes < 1L << 30) return (bytes >> 20) + " MB";
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
    }

    void openProject(File dir) {
        try {
            a.session.open(dir);
            a.opened();
        } catch (Exception x) {
            new Lux.Builder(a).setTitle("Could not open it").setMessage(String.valueOf(x.getMessage())).setPositiveButton("OK", null).show();
        }
    }

    private void menu(final Projects.Entry e) {
        final String[] items = {"Open", "Rename", "Save a portable copy (.killa), to move or back up", "Wrap up (the mix into my library, then free the space)",
            "Compact (free unused audio)", "Delete"};
        new Lux.Builder(a).setTitle(e.name + " · " + size(e.bytes)).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) openProject(e.dir);
                else if (w == 1) rename(e);
                else if (w == 2) saveBundle(e);
                else if (w == 3) wrapUp(e);
                else if (w == 4) compact(e);
                else delete(e);
            }
        }).show();
    }

    private void rename(final Projects.Entry e) {
        final EditText name = Ui.edit(a, "Name", 1);
        name.setText(e.name);
        new Lux.Builder(a).setTitle("Rename").setView(name).setPositiveButton("Rename", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                try {
                    if (a.session.dir() != null && a.session.dir().equals(e.dir)) {
                        Ui.toast(a, "Close it first (open another project), then rename it.");
                        return;
                    }
                    Projects.rename(e.dir, name.getText().toString());
                } catch (Exception x) {
                    Ui.toast(a, String.valueOf(x.getMessage()));
                }
                refresh();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void compact(final Projects.Entry e) {
        try {
            ProjectStore.Loaded l = ProjectStore.load(e.dir);
            History h = ProjectStore.loadHistory(e.dir, l.project, l.originalText).history;
            long freed = Projects.compact(e.dir, l.project, h);
            Ui.toast(a, freed == 0 ? "Nothing to free: every file is used." : "Freed " + size(freed) + ".");
        } catch (Exception x) {
            Ui.toast(a, String.valueOf(x.getMessage()));
        }
        refresh();
    }

    /** Finishing a project: its mix becomes a song in the library, then it can be deleted to give the space back. */
    private void wrapUp(final Projects.Entry e) {
        try {
            a.session.open(e.dir);
            a.projectChanged();
        } catch (Exception x) {
            new Lux.Builder(a).setTitle("Could not open it").setMessage(String.valueOf(x.getMessage())).setPositiveButton("OK", null).show();
            return;
        }
        ExportDialog.run(a, ExportJob.LIBRARY, new Runnable() {
            public void run() {
                new Lux.Builder(a).setTitle("Wrapped up")
                        .setMessage("The mix of " + e.name + " is in your library now. Delete the project to free " + size(e.bytes)
                                + "? Its tracks, stems and recordings go; the song in your library stays.")
                        .setPositiveButton("Delete the project", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (a.session.dir() != null && a.session.dir().equals(e.dir)) a.session.close();
                                Projects.delete(e.dir);
                                refresh();
                            }
                        }).setNegativeButton("Keep it", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                refresh();
                            }
                        }).show();
            }
        });
    }

    private void delete(final Projects.Entry e) {
        new Lux.Builder(a).setTitle("Delete " + e.name + "?")
                .setMessage("Its recordings, stems and everything in it go too (" + size(e.bytes) + "). Songs in your library are not touched.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (a.session.dir() != null && a.session.dir().equals(e.dir)) a.session.close();
                        Projects.delete(e.dir);
                        refresh();
                    }
                }).setNegativeButton("Keep it", null).show();
    }

    // ---------- new projects ----------

    private void pickSong() {
        File[] metas = Store.library(a).listFiles();
        final ArrayList<File> songs = new ArrayList<File>();
        final ArrayList<String> names = new ArrayList<String>();
        if (metas != null) {
            Arrays.sort(metas, new Comparator<File>() {
                public int compare(File x, File y) {
                    return Long.compare(y.lastModified(), x.lastModified());
                }
            });
            for (File m : metas) {
                if (!m.getName().endsWith(".json")) continue;
                try {
                    JSONObject j = new JSONObject(new String(Store.read(m), "UTF-8"));
                    if (j.optString("_audio").length() == 0) continue;
                    songs.add(m);
                    names.add(j.optString("_title", m.getName()));
                } catch (Exception x) {
                    // not a song
                }
            }
        }
        if (songs.isEmpty()) {
            Ui.toast(a, "There are no songs in your library yet.");
            return;
        }
        new Lux.Builder(a).setTitle("Which song?").setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                newFromSong(songs.get(w));
            }
        }).show();
    }

    /** A new project from a library song, with a progress dialog; opens it when done. */
    void newFromSong(File meta) {
        final AtomicBoolean cancel = new AtomicBoolean();
        final TextView msg = Ui.text(a, "Bringing in the song...", Skin.TEXT, 15);
        msg.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8));
        final AlertDialog d = new Lux.Builder(a).setTitle("New project").setView(msg).setCancelable(false)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        cancel.set(true);
                    }
                }).show();
        a.session.createFromSong(meta, new StudioSession.Progress() {
            public void at(String what, float f) {
                msg.setText(what + ": " + Math.round(f * 100) + "%");
            }
        }, cancel, new StudioSession.Done() {
            public void done(File project, String error) {
                d.dismiss();
                if (project == null) {
                    if (!cancel.get()) new Lux.Builder(a).setTitle("Could not make it").setMessage(error).setPositiveButton("OK", null).show();
                    refresh();
                    return;
                }
                openProject(project);
            }
        });
    }

    void result(int request, int result, Intent data) {
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_FILE) {
            String name = displayName(data.getData()), type = a.getContentResolver().getType(data.getData());
            if ((type != null && type.contains("midi")) || name.endsWith(".mid") || name.endsWith(".midi")) Ui.toast(a, "To bring in a MIDI file, open a project and use Keys → ⋯.");
            else newFromFile(data.getData());
        } else if (request == PICK_BUNDLE) openBundle(data.getData());
        else if (request == SAVE_BUNDLE && exporting != null) writeBundle(exporting, data.getData());
    }

    private void saveBundle(Projects.Entry e) {
        if (a.session.dir() != null && a.session.dir().equals(e.dir)) a.session.flush();
        exporting = e.dir;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, e.name + ai.jackknife.killayue.studio.Bundle.EXTENSION);
        a.startActivityForResult(i, SAVE_BUNDLE);
    }

    /** Runs a bundle job with a progress dialog; work returns an error or null. */
    private interface BundleWork {
        String run(TextView msg, AtomicBoolean cancel) throws Exception;
    }
    private void bundleJob(final String title, final BundleWork work) {
        final AtomicBoolean cancel = new AtomicBoolean();
        final TextView msg = Ui.text(a, title + "…", Skin.TEXT, 15);
        msg.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8));
        final AlertDialog d = new Lux.Builder(a).setTitle(title).setView(msg).setCancelable(false)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        cancel.set(true);
                    }
                }).show();
        new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                    out = work.run(msg, cancel);
                } catch (Exception x) {
                    out = cancel.get() ? null : "!" + x.getMessage();
                }
                final String said = out;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        d.dismiss();
                        refresh();
                        if (said == null) return;
                        if (said.startsWith("!")) new Lux.Builder(a).setTitle(title).setMessage(said.substring(1)).setPositiveButton("OK", null).show();
                        else Ui.toast(a, said);
                    }
                });
            }
        }, "studio-bundle").start();
    }
    private ai.jackknife.killayue.studio.Bundle.Progress progress(final TextView msg) {
        return new ai.jackknife.killayue.studio.Bundle.Progress() {
            long last;
            public void at(final long done, final long total) {
                long now = System.currentTimeMillis();
                if (now - last < 150) return;
                last = now;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        msg.setText(size(done) + " of " + size(Math.max(done, total)));
                    }
                });
            }
        };
    }
    private void writeBundle(final File dir, final Uri uri) {
        bundleJob("Saving a portable copy", new BundleWork() {
            public String run(TextView msg, AtomicBoolean cancel) throws Exception {
                java.io.OutputStream out = a.getContentResolver().openOutputStream(uri, "w");
                if (out == null) throw new java.io.IOException("Could not write there.");
                try {
                    ai.jackknife.killayue.studio.Bundle.export(dir, out, progress(msg), cancel);
                } finally {
                    out.close();
                }
                return "Saved. Open it on any phone with Killa YuE: Studio → Vault → Open a portable project.";
            }
        });
    }
    private void openBundle(final Uri uri) {
        bundleJob("Opening the portable project", new BundleWork() {
            public String run(TextView msg, AtomicBoolean cancel) throws Exception {
                long size = -1;
                try {
                    android.content.res.AssetFileDescriptor fd = a.getContentResolver().openAssetFileDescriptor(uri, "r");
                    if (fd != null) {
                        size = fd.getLength();
                        fd.close();
                    }
                } catch (Exception ignored) {
                    // size unknown
                }
                java.io.InputStream in = a.getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("Could not read it.");
                final ai.jackknife.killayue.studio.Bundle.Opened o;
                try {
                    o = ai.jackknife.killayue.studio.Bundle.open(StudioSession.root(a), in, size, progress(msg), cancel);
                } finally {
                    in.close();
                }
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        openProject(o.dir);
                        if (!o.missingPacks.isEmpty())
                            new Lux.Builder(a).setTitle("Recorded instruments to download")
                                .setMessage("This project plays " + o.missingPacks.size() + (o.missingPacks.size() == 1 ? " recorded instrument" : " recorded instruments")
                                    + " not on this phone yet. Open Keys, choose the track, and tap Download when asked.")
                                .setPositiveButton("OK", null).show();
                    }
                });
                return "Opened " + o.name + ".";
            }
        });
    }

    private void newFromFile(final Uri uri) {
        final AtomicBoolean cancel = new AtomicBoolean();
        final TextView msg = Ui.text(a, "Bringing in the file...", Skin.TEXT, 15);
        msg.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8));
        final AlertDialog d = new Lux.Builder(a).setTitle("New project").setView(msg).setCancelable(false)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        cancel.set(true);
                    }
                }).show();
        final String name = displayName(uri);
        final int rate = a.session.rate();
        new Thread(new Runnable() {
            public void run() {
                File dir = null;
                String err = null;
                try {
                    dir = Projects.create(StudioSession.root(a), name, rate, null, name, System.currentTimeMillis());
                    long frames = ImportJob.run(a, uri, new File(dir, "audio/import.wav"), rate, new ImportJob.Progress() {
                        public void at(final float f) {
                            a.runOnUiThread(new Runnable() {
                                public void run() {
                                    msg.setText("Bringing in the file: " + Math.round(f * 100) + "%");
                                }
                            });
                        }
                    }, cancel);
                    Project p = ProjectStore.load(dir).project;
                    Track t = p.addTrack(name);
                    t.role = "import";
                    p.addClip(t, "audio/import.wav", 0, 0, frames);
                    ProjectStore.save(p, dir);
                } catch (Exception x) {
                    err = cancel.get() ? null : String.valueOf(x.getMessage());
                    if (dir != null) Projects.delete(dir);
                    dir = null;
                }
                final File made = dir;
                final String why = err;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        d.dismiss();
                        if (made != null) openProject(made);
                        else if (why != null) new Lux.Builder(a).setTitle("Could not bring it in").setMessage(why).setPositiveButton("OK", null).show();
                    }
                });
            }
        }, "studio-import").start();
    }

    private String displayName(Uri uri) {
        String n = null;
        try {
            android.database.Cursor c = a.getContentResolver().query(uri, new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) n = c.getString(0);
                c.close();
            }
        } catch (RuntimeException e) {
            // no name
        }
        if (n == null) n = "Imported";
        return n.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
    }
}
