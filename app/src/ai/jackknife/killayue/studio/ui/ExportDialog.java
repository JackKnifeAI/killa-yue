package ai.jackknife.killayue.studio.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.jackknife.killayue.Lux;
import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.Project;

/** Export: which kind, then progress with Cancel, then where it went, with Share. */
final class ExportDialog {
    private ExportDialog() {}

    static void show(final StudioActivity a) {
        if (a.session.project() == null) return;
        new Lux.Builder(a).setTitle("Export").setItems(ExportJob.NAMES, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                run(a, which);
            }
        }).show();
    }

    private static void run(final StudioActivity a, final int kind) {
        final Project p = a.session.project().copy();
        final File dir = a.session.dir();
        final AtomicBoolean cancel = new AtomicBoolean();
        final TextView msg = Ui.text(a, "Starting...", Skin.TEXT, 15);
        msg.setPadding(Ui.dp(a, 24), Ui.dp(a, 16), Ui.dp(a, 24), Ui.dp(a, 8));
        final AlertDialog d = new Lux.Builder(a).setTitle(ExportJob.NAMES[kind]).setView(msg).setCancelable(false)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface di, int w) {
                        cancel.set(true);
                    }
                }).show();
        new Thread(new Runnable() {
            public void run() {
                ArrayList<File> made = null;
                String err = null;
                try {
                    made = ExportJob.run(a, p, dir, kind, new ExportJob.Progress() {
                        public void at(final String what, final float f) {
                            a.runOnUiThread(new Runnable() {
                                public void run() {
                                    msg.setText(what + ": " + Math.round(f * 100) + "%");
                                }
                            });
                        }
                    }, cancel);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final ArrayList<File> files = made;
                final String why = err;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        d.dismiss();
                        if (files == null) {
                            if (!cancel.get()) new Lux.Builder(a).setTitle("Export failed").setMessage(why).setPositiveButton("OK", null).show();
                            return;
                        }
                        done(a, kind, files);
                    }
                });
            }
        }, "studio-export").start();
    }

    private static void done(final StudioActivity a, int kind, final ArrayList<File> files) {
        String[] paths = new String[files.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = files.get(i).getPath();
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        MediaScannerConnection.scanFile(a, paths, null, new MediaScannerConnection.OnScanCompletedListener() {
            public void onScanCompleted(String path, Uri uri) {
                if (uri != null)
                    synchronized (uris) {
                        uris.add(uri);
                    }
            }
        });
        String where = kind == ExportJob.LIBRARY ? "It is in your library now, as “" + a.session.project().name + "”."
                : "Saved to " + files.get(0).getParentFile().getPath().replaceFirst("^/storage/emulated/0/", "") + ":\n" + files.get(0).getName()
                        + (files.size() > 1 ? "\nand " + (files.size() - 1) + " more" : "");
        Lux.Builder b = new Lux.Builder(a);
        b.setTitle("Exported").setMessage(where).setPositiveButton("OK", null);
        if (kind != ExportJob.LIBRARY)
            b.setNeutralButton("Share", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    ArrayList<Uri> got;
                    synchronized (uris) {
                        got = new ArrayList<Uri>(uris);
                    }
                    if (got.isEmpty()) {
                        Ui.toast(a, "Still being added to your music; try Share again in a moment.");
                        return;
                    }
                    Intent i = new Intent(got.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
                    i.setType("audio/*");
                    if (got.size() == 1) i.putExtra(Intent.EXTRA_STREAM, got.get(0));
                    else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, got);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(i, "Share"));
                }
            });
        b.show();
    }
}
