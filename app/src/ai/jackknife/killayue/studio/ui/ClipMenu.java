package ai.jackknife.killayue.studio.ui;
import ai.jackknife.killayue.*;
import ai.jackknife.killayue.studio.*;
import android.content.*;
final class ClipMenu {
    static void show(final Context a, final StudioSession s, final String id, final long at) {
        final Project p = s.project();
        final Clip c = p.clip(id);
        if (c == null)
            return;
        new Lux.Builder(a)
            .setTitle("Clip")
            .setItems(new String[] {"Move to playhead (snap)", "Nudge (milliseconds)", "Trim start to here", "Trim end to here",
                                    "Split here", "Duplicate", "Fade in / out (ms)", "Stretch / pitch", "Remove"},
                      new DialogInterface.OnClickListener() {
                          public void onClick(DialogInterface d, int w) {
                              try {
                                  if (w == 0)
                                      s.run(ClipEdits.move(p, id, new TempoMap(p.rate, p.performance).snap(s.status().position, 4)));
                                  else if (w == 1)
                                      StudioDialogs.number(a, "Nudge (+ later, − earlier)", 0, new StudioDialogs.Number() {
                                          public void value(double v) {
                                              s.run(ClipEdits.move(s.project(), id, c.start + Math.round(v * p.rate / 1000)));
                                          }
                                      });
                                  else if (w == 2)
                                      s.run(ClipEdits.trim(p, id, at - c.start, c.start + c.length - at));
                                  else if (w == 3)
                                      s.run(ClipEdits.trim(p, id, 0, at - c.start));
                                  else if (w == 4)
                                      s.run(ClipEdits.split(p, id, new TempoMap(p.rate, p.performance).snap(at, 4)));
                                  else if (w == 5)
                                      s.run(ClipEdits.duplicate(p, id));
                                  else if (w == 6)
                                      StudioDialogs.number(a, "Fade each end (ms)", 10, new StudioDialogs.Number() {
                                          public void value(double v) {
                                              long f = Math.round(v * p.rate / 1000);
                                              s.run(ClipEdits.fades(s.project(), id, f, f));
                                          }
                                      });
                                  else if (w == 7)
                                      StretchJob.ask((StudioActivity)a, id);
                                  else
                                      s.run(ClipEdits.remove(p, id));
                              } catch (Exception e) {
                                  Ui.toast(a, e.getMessage());
                              }
                          }
                      })
            .show();
    }
}
