package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.*;
import ai.jackknife.killayue.studio.*;
import android.app.ProgressDialog;
import java.io.*;
final class StretchJob {
    static void ask(final StudioActivity a, final String id) {
        final Project p = a.session.project();
        final Clip clip = p.clip(id);
        if (clip == null)
            return;
        StudioDialogs.number(a, "New clip length (% of original)", 100, new StudioDialogs.Number() {
            public void value(final double percent) {
                if (percent < 25 || percent > 400)
                    throw new IllegalArgumentException("Choose 25–400%.");
                StudioDialogs.number(a, "Pitch shift (semitones)", 0, new StudioDialogs.Number() {
                    public void value(final double pitch) {
                        if (pitch < -24 || pitch > 24)
                            throw new IllegalArgumentException("Choose −24 to +24 semitones.");
                        if (a.session.recording.busy() || AiJobs.busy() || MainActivity.jobBusy)
                            throw new IllegalStateException("Finish recording or generation first.");
                        final File dir = a.session.dir();
                        final String source = p.toText();
                        final String name = "audio/stretch-" + System.currentTimeMillis() + ".wav";
                        final ProgressDialog progress = new ProgressDialog(a);
                        progress.setMessage("Stretching audio…");
                        progress.setCancelable(false);
                        progress.show();
                        new Thread(new Runnable() {
                            public void run() {
                                String why = null;
                                try {
                                    NativeStretch.run(new File(dir, clip.file), new File(dir, name), clip.offset, clip.length,
                                                      percent / 100, pitch);
                                    NativeStretch.peaks(new File(dir, name));
                                } catch (Exception e) {
                                    why = e.getMessage();
                                }
                                final String error = why;
                                a.runOnUiThread(new Runnable() {
                                    public void run() {
                                        progress.dismiss();
                                        if (error != null) {
                                            Ui.toast(a, error);
                                            return;
                                        }
                                        if (a.session.dir() == null || !dir.equals(a.session.dir()) ||
                                            !source.equals(a.session.project().toText())) {
                                            Ui.toast(a, "Audio rendered; the project changed, so the clip was left as it was.");
                                            return;
                                        }
                                        Project q = p.copy();
                                        Clip c = q.clip(id);
                                        c.file = name;
                                        c.offset = 0;
                                        c.length = Math.round(clip.length * percent / 100);
                                        c.fadeIn = Math.min(c.fadeIn, c.length);
                                        c.fadeOut = Math.min(c.fadeOut, c.length);
                                        a.session.run(ClipEdits.change(p, q, "Stretch / pitch clip"));
                                    }
                                });
                            }
                        }, "studio-stretch").start();
                    }
                });
            }
        });
    }
}
