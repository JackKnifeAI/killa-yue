package ai.jackknife.killayue;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tells the person when a newer Killa YuE is out, and installs it when they say so. On by default
 * (Engine tab: "Check for updates"): at most once a day, on launch, the app asks GitHub for the
 * project's latest release. A newer version shows its notes, with Update, Later and Skip this version.
 *
 * Update downloads the release's APK into the app's cache (it resumes after a dropped connection),
 * checks it against the SHA-256 GitHub publishes for it, and hands it to Android's own installer,
 * which asks the person to confirm and only accepts an APK signed with the same key as this app.
 * The first time, Android asks to allow Killa YuE to install apps. Nothing is installed while a song
 * is being made: installing restarts the app. Nothing happens without the person tapping Update.
 */
public class Updates {
    static final String LATEST = "https://api.github.com/repos/JackKnifeAI/killa-yue/releases/latest";
    static final String PAGE = "https://github.com/JackKnifeAI/killa-yue/releases/latest";
    static final long DAY = 24L * 3600 * 1000;

    static final String INSTALLED = "ai.jackknife.killayue.UPDATE_INSTALL";

    /** A verified APK waiting for the person to allow installing apps, then Install. */
    static volatile File ready;
    static volatile String readyTag = "";

    static boolean enabled(Context c) {
        return c.getSharedPreferences("cfg", 0).getBoolean("update_check", true);
    }

    static void enabled(Context c, boolean on) {
        c.getSharedPreferences("cfg", 0).edit().putBoolean("update_check", on).apply();
    }

    /** On launch: checks when it is on and a day has passed since the last check. */
    static void onLaunch(Activity a) {
        clean(a);
        if (!enabled(a)) return;
        SharedPreferences cfg = a.getSharedPreferences("cfg", 0);
        if (System.currentTimeMillis() - cfg.getLong("update_checked", 0) < DAY) return;
        check(a, false);
    }

    /** Asks GitHub for the latest release. byHand: "Check now", which also says when this is the newest. */
    static void check(final Activity a, final boolean byHand) {
        final Context app = a.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                JSONObject rel = null;
                try {
                    rel = latest();
                    app.getSharedPreferences("cfg", 0).edit().putLong("update_checked", System.currentTimeMillis()).apply();
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final JSONObject r = rel;
                final String err = error;
                ui.post(new Runnable() {
                    public void run() {
                        if (a.isFinishing()) return;
                        if (r == null) {
                            if (byHand) Ui.toast(a, "Could not check for updates: " + err);
                            return;
                        }
                        offer(a, r, byHand);
                    }
                });
            }
        }, "update-check").start();
    }

    static JSONObject latest() throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(LATEST).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(15000);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("User-Agent", "KillaYuE");
            if (con.getResponseCode() != 200) throw new Exception("GitHub answered " + con.getResponseCode());
            InputStream in = con.getInputStream();
            try {
                return new JSONObject(new String(Api.readAll(in), "UTF-8"));
            } finally {
                in.close();
            }
        } finally {
            con.disconnect();
        }
    }

    static void offer(final Activity a, JSONObject rel, boolean byHand) {
        final String tag = rel.optString("tag_name").replaceFirst("^v", "");
        String mine = version(a);
        final SharedPreferences cfg = a.getSharedPreferences("cfg", 0);
        if (tag.length() == 0 || compare(tag, mine) <= 0) {
            if (byHand) Ui.toast(a, "Killa YuE " + mine + " is the newest version");
            return;
        }
        if (!byHand && tag.equals(cfg.getString("update_skip", ""))) return;
        // the release's APK, its size, and the SHA-256 GitHub keeps for it (or the one in the notes)
        JSONObject apk = null;
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject x = assets.optJSONObject(i);
            if (x != null && x.optString("name").endsWith(".apk") && x.optLong("size") > 0) apk = x;
        }
        final String notesRaw = rel.optString("body");
        final JSONObject asset = apk;
        String notes = notesRaw.replaceAll("(?m)^#+\\s*", "").replace("**", "").trim();
        if (notes.length() > 1200) notes = notes.substring(0, 1200).trim() + "…";
        new Lux.Builder(a).setTitle("Killa YuE " + tag + " is out")
                .setMessage("You have " + mine + ". Update installs it over this one and keeps your songs, projects and models.\n\n" + notes)
                .setPositiveButton(asset != null ? "Update" : "Open the release", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (asset == null) {
                            open(a, PAGE);
                            return;
                        }
                        download(a, tag, asset, sha256Of(asset, notesRaw));
                    }
                })
                .setNeutralButton("Skip this version", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cfg.edit().putString("update_skip", tag).apply();
                    }
                })
                .setNegativeButton("Later", null).show();
    }

    static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Ui.toast(a, "No browser to open " + url);
        }
    }

    /** GitHub's own digest of the asset ("sha256:..."), or the "SHA-256: `...`" line of our release notes; "" if neither. */
    static String sha256Of(JSONObject asset, String notes) {
        String d = asset.optString("digest");
        if (d.startsWith("sha256:")) return d.substring(7).toLowerCase(java.util.Locale.US);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("SHA-256:\\s*`?([0-9a-fA-F]{64})").matcher(notes);
        return m.find() ? m.group(1).toLowerCase(java.util.Locale.US) : "";
    }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "updates");
        d.mkdirs();
        return d;
    }

    /** Removes downloads of versions this app already is (or is newer than). */
    static void clean(Context c) {
        File[] fs = dir(c).listFiles();
        String mine = version(c);
        if (fs == null) return;
        for (File f : fs) {
            String v = f.getName().replaceFirst("^killa-yue-", "").replaceFirst("\\.apk(\\.part)?$", "");
            if (compare(v, mine) <= 0) f.delete();
        }
    }

    /** Downloads the APK with a progress dialog, checks it, then offers to install it. */
    static void download(final Activity a, final String tag, JSONObject asset, final String sha) {
        if (MainActivity.jobBusy) {
            Ui.toast(a, "Finish the song first: installing an update restarts the app.");
            return;
        }
        final String url = asset.optString("browser_download_url");
        final long size = asset.optLong("size");
        final File apk = new File(dir(a), "killa-yue-" + tag + ".apk");
        final File part = new File(dir(a), "killa-yue-" + tag + ".apk.part");
        final Store.Dl dl = new Store.Dl();
        dl.total = size;
        final TextView status = Ui.text(a, "Starting the download", Skin.TEXT, 15);
        status.setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), Ui.dp(a, 4));
        final android.app.AlertDialog box = new Lux.Builder(a).setTitle("Downloading Killa YuE " + tag).setView(status)
                .setCancelable(false)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        dl.cancel = true;
                    }
                }).show();
        final Handler ui = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            public void run() {
                if (!box.isShowing()) return;
                if (size > 0) status.setText((dl.done >> 20) + " of " + (size >> 20) + " MB");
                ui.postDelayed(this, 500);
            }
        };
        ui.post(tick);
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    if (!apk.isFile()) {
                        Store.fetch(url, part, dl);
                        if (dl.cancel) return;
                        if (size > 0 && part.length() != size) throw new Exception("the download is the wrong size");
                        if (!part.renameTo(apk)) throw new Exception("could not keep the download");
                    }
                    if (sha.length() > 0) {
                        ui.post(new Runnable() {
                            public void run() {
                                status.setText("Checking the download");
                            }
                        });
                        if (!sha.equals(Store.sha256(apk))) {
                            apk.delete();
                            throw new Exception("the download does not match the SHA-256 published with the release, so it was deleted");
                        }
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        try {
                            box.dismiss();
                        } catch (Exception e) {
                            // the screen went away
                        }
                        if (dl.cancel) {
                            Ui.toast(a, "Update cancelled. It carries on from where it stopped next time.");
                        } else if (fe != null) {
                            new Lux.Builder(a).setTitle("The update did not download").setMessage(fe)
                                    .setPositiveButton("OK", null).show();
                        } else {
                            ready = apk;
                            readyTag = tag;
                            askInstall(a);
                        }
                    }
                });
            }
        }, "update-download").start();
    }

    /** The downloaded, checked APK: install it now, or when the person has allowed Killa YuE to install apps. */
    static void askInstall(final Activity a) {
        final File apk = ready;
        if (apk == null || !apk.isFile()) return;
        new Lux.Builder(a).setTitle("Install Killa YuE " + readyTag + "?")
                .setMessage("Downloaded and checked. Installing restarts the app; your songs, projects and models stay.")
                .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        install(a, apk);
                    }
                })
                .setNegativeButton("Later", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ready = null; // the file stays, so "Check for updates now" installs without downloading again
                    }
                }).show();
    }

    /** Back on screen: the person may have just allowed installing apps. */
    static void resume(Activity a) {
        if (ready != null && canInstall(a)) askInstall(a);
    }

    static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static void install(Activity a, File apk) {
        if (MainActivity.jobBusy) {
            Ui.toast(a, "Finish the song first: installing an update restarts the app.");
            return;
        }
        if (!canInstall(a)) {
            Ui.toast(a, "Allow Killa YuE to install apps, then come back");
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                Ui.toast(a, "Open Settings > Apps > Killa YuE > Install unknown apps");
            }
            return;
        }
        try {
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(a.getPackageName());
            sp.setSize(apk.length());
            int id = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(id);
            try {
                OutputStream out = s.openWrite("killa-yue.apk", 0, apk.length());
                InputStream in = new FileInputStream(apk);
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                } finally {
                    in.close();
                    out.close();
                }
                // Android answers on MainActivity (onNewIntent -> result): first "confirm", then success or why not
                Intent back = new Intent(a, MainActivity.class).setAction(INSTALLED);
                // mutable from Android 12: the installer writes its answer into the intent
                int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0);
                s.commit(android.app.PendingIntent.getActivity(a, 7, back, flags).getIntentSender());
            } finally {
                s.close();
            }
            ready = null;
        } catch (Exception e) {
            new Lux.Builder(a).setTitle("Could not install the update").setMessage(String.valueOf(e.getMessage()))
                    .setPositiveButton("OK", null).show();
        }
    }

    /** Android's answer to an install, delivered to MainActivity. True when the intent was one. */
    static boolean result(Activity a, Intent in) {
        if (in == null || !INSTALLED.equals(in.getAction())) return false;
        int st = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) in.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) a.startActivity(confirm);
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            String why = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                why = "This copy of Killa YuE was signed differently from the release (a test build?), so Android will not "
                        + "update it in place. Uninstall it and install the release from GitHub. (" + why + ")";
            if (st != PackageInstaller.STATUS_FAILURE_ABORTED)
                new Lux.Builder(a).setTitle("The update was not installed").setMessage(String.valueOf(why))
                        .setPositiveButton("OK", null).show();
        }
        return true;
    }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Compares versions such as 1.4.2 and 1.10.0 number by number; anything after a "-" is ignored. */
    static int compare(String x, String y) {
        String[] a = x.split("-")[0].split("\\."), b = y.split("-")[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int p = i < a.length ? num(a[i]) : 0, q = i < b.length ? num(b[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
