package ai.jackknife.killayue.studio.ui;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.jackknife.killayue.Skin;
import ai.jackknife.killayue.Ui;
import ai.jackknife.killayue.studio.AiJob;
import ai.jackknife.killayue.studio.Edit;
import ai.jackknife.killayue.studio.NativeEngine;
import ai.jackknife.killayue.studio.Project;
import ai.jackknife.killayue.studio.StudioModels;
import ai.jackknife.killayue.studio.Track;
import ai.jackknife.killayue.studio.WavIn;

/**
 * THE SPLITTER: a track into drums, bass, other and vocals with Demucs, on the phone. The model downloads once
 * (pinned, from JackKnifeAI). The job runs in its own process at low priority, can be cancelled and carried
 * on, and when it is done the four stems join the project as one step (one Undo takes them away), with the
 * track they came from muted.
 */
final class SplitterScreen implements Screen, StudioSession.Listener {
    private final StudioActivity a;
    private final ScrollView view;
    private final LinearLayout col, tracks;
    private final TextView model, job;
    private final Button download, cancel, resume;
    private final AtomicBoolean downloadCancel = new AtomicBoolean();
    private volatile String downloadState;
    private boolean downloading;
    private long jobStarted;
    private double jobStartProgress;
    private long lastPoll;

    SplitterScreen(StudioActivity a) {
        this.a = a;
        view = new ScrollView(a);
        col = Ui.column(a);
        col.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 16));
        col.addView(Ui.label(a, "The Splitter"));
        col.addView(Ui.text(a, "Split a track into drums, bass, other and vocals, each its own track. It runs on the phone, "
                + "in the background, while you work.", Skin.TEXT, 14));
        model = Ui.text(a, "", Skin.DIM, 14);
        col.addView(model, Ui.gap(a, 12));
        download = Ui.button(a, "Download the stems model", false);
        col.addView(download, Ui.gap(a, 6));
        col.addView(Ui.label(a, "Tracks"));
        tracks = Ui.column(a);
        col.addView(tracks);
        col.addView(Ui.label(a, "Job"));
        job = Ui.text(a, "Nothing running.", Skin.TEXT, 14);
        col.addView(job);
        LinearLayout row = Ui.row(a);
        cancel = Ui.chip(a, "Cancel");
        resume = Ui.chip(a, "Carry on");
        row.addView(cancel);
        row.addView(resume);
        col.addView(row, Ui.gap(a, 6));
        view.addView(col);

        download.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (downloading) {
                    downloadCancel.set(true);
                    return;
                }
                startDownload();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AiJob j = latest();
                if (j != null) {
                    try {
                        j.cancel();
                    } catch (IOException e) {
                        Ui.toast(SplitterScreen.this.a, String.valueOf(e.getMessage()));
                    }
                }
            }
        });
        resume.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AiJob j = latest();
                if (j == null) return;
                String why = AiJobs.start(SplitterScreen.this.a, j);
                if (why != null) Ui.toast(SplitterScreen.this.a, why);
                else {
                    jobStarted = System.currentTimeMillis();
                    jobStartProgress = j.progress();
                }
            }
        });
        a.session.listen(this);
    }

    public String name() {
        return "Stems";
    }

    public View view() {
        return view;
    }

    public void shown() {
        projectChanged();
        poll();
    }

    public void hidden() {}

    public void projectChanged() {
        boolean have = AiJobs.installed(a, "stems");
        long mb = StudioModels.bytes("stems") >> 20;
        model.setText(have ? "The stems model is ready." : downloadState != null ? downloadState
                : "Stems need a one-time download of " + mb + " MB (the Demucs model and the engine it runs on).");
        download.setVisibility(have ? View.GONE : View.VISIBLE);
        download.setText(downloading ? "Stop the download" : "Download the stems model (" + mb + " MB)");
        tracks.removeAllViews();
        Project p = a.session.project();
        if (p == null) return;
        for (final Track t : p.tracks) {
            if (t.role != null && t.role.startsWith("stem:")) continue;
            if (t.clips.isEmpty()) continue;
            Button b = Ui.button(a, "Split “" + t.name + "”", false);
            b.setEnabled(have);
            tracks.addView(b, Ui.gap(a, 6));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    split(t);
                }
            });
        }
    }

    private void startDownload() {
        downloading = true;
        downloadCancel.set(false);
        projectChanged();
        new Thread(new Runnable() {
            public void run() {
                String done;
                try {
                    ImportJob.checkSpace(AiJobs.dir(a), 0, 48000);
                    AiJobs.download(a, "stems", new AiJobs.Progress() {
                        public void at(long d, long total) {
                            downloadState = "Downloading: " + (d >> 20) + " of " + (total >> 20) + " MB";
                            a.runOnUiThread(new Runnable() {
                                public void run() {
                                    model.setText(downloadState);
                                }
                            });
                        }
                    }, downloadCancel);
                    done = null;
                } catch (IOException e) {
                    done = e.getMessage();
                }
                final String why = done;
                a.runOnUiThread(new Runnable() {
                    public void run() {
                        downloading = false;
                        downloadState = why == null ? null : "The download stopped: " + why;
                        projectChanged();
                    }
                });
            }
        }, "stems-download").start();
    }

    private void split(Track t) {
        File dir = a.session.dir();
        Project p = a.session.project();
        if (dir == null || p == null || t.clips.isEmpty()) return;
        try {
            File song = new File(dir, t.clips.get(0).file);
            AiJob j = AiJobs.stems(a, dir, t.id, song, p.rate);
            j.put("trackName", t.name);
            j.save();
            String why = AiJobs.start(a, j);
            if (why != null) Ui.toast(a, why);
            jobStarted = System.currentTimeMillis();
            jobStartProgress = 0;
            poll();
        } catch (IOException e) {
            Ui.toast(a, String.valueOf(e.getMessage()));
        }
    }

    private AiJob latest() {
        File dir = a.session.dir();
        if (dir == null) return null;
        for (AiJob j : AiJobs.jobs(dir)) if ("stems".equals(j.kind())) return j;
        return null;
    }

    public void frame(NativeEngine.Status s) {
        long now = System.currentTimeMillis();
        if (now - lastPoll < 500) return;
        lastPoll = now;
        poll();
    }

    private void poll() {
        AiJob j = latest();
        if (j == null) {
            job.setText("Nothing running.");
            cancel.setVisibility(View.GONE);
            resume.setVisibility(View.GONE);
            return;
        }
        String state = j.state(), name = String.valueOf(j.get("trackName"));
        double p = j.progress();
        boolean live = AiJobs.busy() && AiJobs.current() != null && AiJobs.current().dir().equals(j.dir());
        if ("running".equals(state) && live) {
            String left = "";
            double done = p - jobStartProgress;
            if (done > 0.02 && jobStarted > 0) {
                long secs = (long) ((System.currentTimeMillis() - jobStarted) / 1000.0 * (1 - p) / done);
                left = secs < 90 ? " · under 2 minutes left" : " · about " + Math.round(secs / 60.0) + " minutes left";
            }
            job.setText("Splitting “" + name + "”: " + Math.round(p * 100) + "%" + left);
        } else if ("done".equals(state)) {
            if (!Boolean.TRUE.equals(j.get("applied"))) apply(j);
            job.setText("“" + name + "” is split: its stems are in the project.");
            if (!live && AiJobs.busy()) AiJobs.finished(a);
        } else if ("failed".equals(state)) {
            job.setText("The split stopped: " + j.error() + ". Carry on to try again from where it got to.");
            if (live) AiJobs.finished(a);
        } else if ("cancelled".equals(state)) {
            job.setText("The split of “" + name + "” was stopped at " + Math.round(p * 100) + "%. Carry on to finish it.");
            if (live) AiJobs.finished(a);
        } else {
            job.setText(live ? "Starting..." : "The split of “" + name + "” is waiting. Carry on to run it.");
        }
        cancel.setVisibility(live ? View.VISIBLE : View.GONE);
        resume.setVisibility(!live && !"done".equals(state) ? View.VISIBLE : View.GONE);
    }

    /** The finished stems into the project: one step, the original muted. */
    private void apply(AiJob j) {
        Project p = a.session.project();
        File dir = a.session.dir();
        if (p == null || dir == null) return;
        try {
            String rel = (String) j.get("outRel"), track = j.subject();
            String[] names = {"Drums", "Bass", "Other", "Vocals"}, files = {"drums", "bass", "other", "vocals"};
            Edit[] edits = new Edit[5];
            edits[0] = Edit.mute(track, true);
            for (int i = 0; i < 4; i++) {
                String f = rel + "/" + files[i] + ".wav";
                WavIn w = WavIn.open(new File(dir, f));
                long frames = w.frames;
                w.close();
                edits[i + 1] = Edit.addTrack(names[i], f, frames, "stem:" + files[i]);
            }
            if (p.track(track) == null) edits[0] = Edit.compound("Stems", edits[1], edits[2], edits[3], edits[4]);
            a.session.run(p.track(track) == null ? edits[0] : Edit.compound("Split into stems", edits));
            j.put("applied", Boolean.TRUE);
            j.save();
            Ui.toast(a, "The stems are in: Drums, Bass, Other, Vocals.");
        } catch (IOException e) {
            job.setText("The stems are made but could not be added: " + e.getMessage());
        }
    }
}
