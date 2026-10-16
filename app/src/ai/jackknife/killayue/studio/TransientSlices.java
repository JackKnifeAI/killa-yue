package ai.jackknife.killayue.studio;
import java.io.*;
import java.util.*;
/** Streaming 10 ms onset detector. Loudness rises choose slice boundaries, with a 60 ms refractory interval. */
public final class TransientSlices {
    public static long[] find(File file, long offset, long length, int count) throws IOException {
        WavIn in = WavIn.open(file);
        try {
            if (offset < 0 || length <= 0 || offset > in.frames - length || count < 1 || count > 16)
                throw new IOException("Invalid slice range.");
            int block = Math.max(1, in.rate / 100);
            float[] b = new float[block * in.channels];
            ArrayList<long[]> candidates = new ArrayList<long[]>();
            double smooth = 0;
            long last = -in.rate;
            for (long at = 0; at < length; at += block) {
                int n = (int)Math.min(block, length - at);
                in.read(offset + at, b, n);
                double sum = 0;
                for (int i = 0; i < n * in.channels; i++)
                    sum += b[i] * b[i];
                double rms = Math.sqrt(sum / Math.max(1, n * in.channels));
                double rise = rms - smooth;
                if (at > 0 && rise > Math.max(.015, smooth * .5) && at - last > in.rate * .06) {
                    candidates.add(new long[] {at, Math.round(rise * 1000000)});
                    last = at;
                }
                smooth = .8 * smooth + .2 * rms;
            }
            Collections.sort(candidates, new Comparator<long[]>() {
                public int compare(long[] a, long[] b) { return Long.compare(b[1], a[1]); }
            });
            TreeSet<Long> points = new TreeSet<Long>();
            points.add(0L);
            points.add(length);
            for (long[] c : candidates) {
                if (points.size() >= count + 1)
                    break;
                points.add(c[0]);
            }
            // For sustained material, fill the largest remaining gap instead of inventing transients.
            while (points.size() < count + 1) {
                long from = 0, best = 0, bestSize = 0;
                for (long to : points) {
                    if (to - from > bestSize) {
                        best = from;
                        bestSize = to - from;
                    }
                    from = to;
                }
                if (bestSize < 2)
                    break;
                points.add(best + bestSize / 2);
            }
            long[] out = new long[points.size()];
            int i = 0;
            for (long n : points)
                out[i++] = n;
            return out;
        } finally {
            in.close();
        }
    }
}
