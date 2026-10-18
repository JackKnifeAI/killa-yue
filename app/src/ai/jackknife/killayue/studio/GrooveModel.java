package ai.jackknife.killayue.studio;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Killa Groove: a drum-groove model trained by tools/groove/train.py on the Groove MIDI Dataset (Magenta, CC BY 4.0),
 * 13.6 hours of real drummers in 18 styles. A two-layer GRU plays one 16th-note step at a time: for nine drum voices,
 * whether each is hit, how hard and how far off the grid, so a groove has a drummer's feel. Runs on the phone in a
 * fraction of a second; the result is ordinary notes for Killa Drums, editable and open to the MIDI effects.
 */
public final class GrooveModel {
    public static final String[] STYLES = {"rock", "funk", "jazz", "latin", "hiphop", "soul", "pop", "afrobeat",
                                           "reggae", "punk", "gospel", "blues", "country", "neworleans", "dance",
                                           "highlife", "middleeastern", "afrocuban"};
    public static final String[] STYLE_NAMES = {"Rock", "Funk", "Jazz", "Latin", "Hip-hop", "Soul", "Pop", "Afrobeat",
                                                "Reggae", "Punk", "Gospel", "Blues", "Country", "New Orleans", "Dance",
                                                "Highlife", "Middle Eastern", "Afro-Cuban"};
    /** Killa Drums keys for the nine voices: kick, snare, closed hat, open hat, low, mid and high tom, crash, ride. */
    public static final int[] KEYS = {36, 38, 42, 46, 43, 47, 50, 49, 51};
    static final int V = 9, F = 27;
    final int in, hidden, layers;
    final float[][] wih, whh, bih, bhh;
    final float[] wout, bout;

    private GrooveModel(int in, int hidden, int layers) {
        this.in = in;
        this.hidden = hidden;
        this.layers = layers;
        wih = new float[layers][];
        whh = new float[layers][];
        bih = new float[layers][];
        bhh = new float[layers][];
        wout = new float[F * hidden];
        bout = new float[F];
    }

    public static GrooveModel read(InputStream stream) throws IOException {
        DataInputStream d = new DataInputStream(stream);
        byte[] head = new byte[24];
        d.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (h.get() != 'K' || h.get() != 'G' || h.get() != 'R' || h.get() != 'V' || h.getInt() != 1)
            throw new IOException("Not a Killa Groove model.");
        int in = h.getInt(), hidden = h.getInt(), layers = h.getInt(), out = h.getInt();
        if (out != F || in != F + STYLES.length + 16 || hidden < 8 || hidden > 2048 || layers < 1 || layers > 4)
            throw new IOException("The groove model does not match this app.");
        GrooveModel m = new GrooveModel(in, hidden, layers);
        for (int l = 0; l < layers; l++) {
            int inputs = l == 0 ? in : hidden;
            m.wih[l] = floats(d, 3 * hidden * inputs);
            m.whh[l] = floats(d, 3 * hidden * hidden);
            m.bih[l] = floats(d, 3 * hidden);
            m.bhh[l] = floats(d, 3 * hidden);
        }
        System.arraycopy(floats(d, F * hidden), 0, m.wout, 0, F * hidden);
        System.arraycopy(floats(d, F), 0, m.bout, 0, F);
        return m;
    }
    static float[] floats(DataInputStream d, int n) throws IOException {
        byte[] b = new byte[n * 4];
        d.readFully(b);
        float[] f = new float[n];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f);
        return f;
    }

    static float sigmoid(float x) { return (float) (1 / (1 + Math.exp(-x))); }

    /** One GRU layer step (PyTorch's gate order: reset, update, new). */
    void cell(int l, float[] x, int xn, float[] h, float[] gi, float[] gh) {
        int H = hidden;
        for (int r = 0; r < 3 * H; r++) {
            float a = bih[l][r], b = bhh[l][r];
            int wi = r * xn, wh = r * H;
            for (int k = 0; k < xn; k++) a += wih[l][wi + k] * x[k];
            for (int k = 0; k < H; k++) b += whh[l][wh + k] * h[k];
            gi[r] = a;
            gh[r] = b;
        }
        for (int j = 0; j < H; j++) {
            float reset = sigmoid(gi[j] + gh[j]), update = sigmoid(gi[H + j] + gh[H + j]);
            float n = (float) Math.tanh(gi[2 * H + j] + reset * gh[2 * H + j]);
            h[j] = (1 - update) * n + update * h[j];
        }
    }

    /**
     * Plays bars of 4/4 in a style. busy (0.5 to 1.5) makes the model less or more ready to hit (1 as it learned);
     * feel (0 to 1.5) scales its timing away from the grid, 1 being as the drummers played. Same seed, same groove.
     */
    public List<MidiNotes.Note> play(int style, int bars, float busy, float feel, long seed) {
        if (style < 0 || style >= STYLES.length || bars < 1 || bars > 16)
            throw new IllegalArgumentException("Invalid groove request.");
        Random random = new Random(seed);
        float[][] h = new float[layers][hidden];
        float[] gi = new float[3 * hidden], gh = new float[3 * hidden];
        float[] step = new float[F], x = new float[in], out = new float[F];
        ArrayList<MidiNotes.Note> notes = new ArrayList<MidiNotes.Note>();
        int steps = bars * 16, id = 1;
        for (int t = 0; t < steps; t++) {
            java.util.Arrays.fill(x, 0);
            System.arraycopy(step, 0, x, 0, F);
            x[F + style] = 1;
            x[F + STYLES.length + t % 16] = 1;
            float[] layerIn = x;
            int n = in;
            for (int l = 0; l < layers; l++) {
                cell(l, layerIn, n, h[l], gi, gh);
                layerIn = h[l];
                n = hidden;
            }
            for (int o = 0; o < F; o++) {
                float a = bout[o];
                for (int k = 0; k < hidden; k++) a += wout[o * hidden + k] * h[layers - 1][k];
                out[o] = a;
            }
            java.util.Arrays.fill(step, 0);
            for (int v = 0; v < V; v++) {
                float p = sigmoid(out[v] + (busy - 1) * 2.5f);  // busier: more ready to hit
                if (random.nextFloat() >= p)
                    continue;
                float vel = sigmoid(out[V + v]), off = (float) Math.tanh(out[2 * V + v]) * .5f;
                step[v] = 1;
                step[V + v] = vel;
                step[2 * V + v] = off;
                MidiNotes.Note note = new MidiNotes.Note();
                note.id = id++;
                note.key = KEYS[v];
                note.beat = Math.max(0, (t + off * feel) / 4.0);
                note.length = .25;
                note.velocity = Math.max(.12f, Math.min(1, vel));
                notes.add(note);
            }
        }
        return notes;
    }
}
