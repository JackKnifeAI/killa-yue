package ai.jackknife.killayue.studio;
import java.util.*;
/** Maps input stream frame numbers to the output timeline, subtracting measured round-trip latency. */
public final class TakePlacement {
    public static long start(long projectAnchor, long outputAnchor, long inputFirst, long latency) {
        return Math.addExact(projectAnchor, Math.subtractExact(Math.subtractExact(inputFirst, outputAnchor), latency));
    }
    /** [timeline start, source offset, length] for successive loop passes, including any lead-in. */
    public static List<long[]> passes(long start, long length, long loopStart, long loopEnd) {
        if (length < 0 || loopStart < 0 || loopEnd <= loopStart)
            throw new IllegalArgumentException("Invalid loop take.");
        ArrayList<long[]> r = new ArrayList<long[]>();
        long off = 0;
        if (start < 0) {
            off = Math.min(length, -start);
            start = 0;
        }
        while (off < length) {
            if (start >= loopEnd)
                start = loopStart + (start - loopStart) % (loopEnd - loopStart);
            long n = Math.min(length - off, loopEnd - start);
            r.add(new long[] {start, off, n});
            off += n;
            start = loopStart;
        }
        return r;
    }
}
