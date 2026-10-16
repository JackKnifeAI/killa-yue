package ai.jackknife.killayue.studio;
import java.util.*;
/** Piecewise-constant tempo in quarter-note beats; boundaries integrate exactly instead of rounding each beat. */
public final class TempoMap {
    private final int rate;
    private final double initial;
    private final TreeMap<Long, Double> changes;
    public TempoMap(int rate, Performance p) {
        this.rate = rate;
        initial = p.tempo;
        changes = p.tempos;
    }
    public double bpmAt(long frame) {
        Map.Entry<Long, Double> e = changes.floorEntry(frame);
        return e == null ? initial : e.getValue();
    }
    public double beatAt(long frame) {
        long from = 0;
        double beat = 0, bpm = initial;
        for (Map.Entry<Long, Double> e : changes.entrySet()) {
            if (e.getKey() > frame)
                break;
            beat += (e.getKey() - from) * bpm / (rate * 60.0);
            from = e.getKey();
            bpm = e.getValue();
        }
        return beat + (frame - from) * bpm / (rate * 60.0);
    }
    public long frameAt(double beat) {
        long from = 0;
        double sofar = 0, bpm = initial;
        for (Map.Entry<Long, Double> e : changes.entrySet()) {
            double next = sofar + (e.getKey() - from) * bpm / (rate * 60.0);
            if (next > beat)
                break;
            sofar = next;
            from = e.getKey();
            bpm = e.getValue();
        }
        return Math.round(from + (beat - sofar) * rate * 60 / bpm);
    }
    public long snap(long frame, int subdivision) { return Math.max(0, frameAt(Math.rint(beatAt(frame) * subdivision) / subdivision)); }
}
