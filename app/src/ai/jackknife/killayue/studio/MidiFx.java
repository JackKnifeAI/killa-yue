package ai.jackknife.killayue.studio;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * The Studio's MIDI effects: arpeggiator, chords, scale lock, harmonizer, echo, strum, humanize, velocity,
 * transpose, key range, note length, chance, note repeat, random pitch, swing and mono/legato, chained in any
 * order. One engine serves both the live keyboard and playback: notes enter as starts and ends in beat time, each
 * effect may answer at once or later (never earlier), and the chain is stepped from one moment to the next. Every
 * effect's randomness is seeded, so a track sounds the same on every play. Pure Java, so it is tested on a computer.
 */
public final class MidiFx {
    private MidiFx() {}

    /** Note values in beats (a beat is a quarter note), for rates and times. */
    public static final double[] RATES = {4, 2, 1, 2 / 3.0, .75, .5, 1 / 3.0, .375, .25, 1 / 6.0, .125, 1 / 12.0};
    public static final String[] RATE_NAMES = {"1 bar", "1/2", "1/4", "1/4 triplet", "1/8 dotted", "1/8",
                                               "1/8 triplet", "1/16 dotted", "1/16", "1/16 triplet", "1/32",
                                               "1/32 triplet"};
    public static final String[] KEYS = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};
    public static final String[] SCALE_NAMES = {"Major", "Natural minor", "Harmonic minor", "Melodic minor",
                                                "Dorian", "Phrygian", "Lydian", "Mixolydian", "Locrian",
                                                "Major pentatonic", "Minor pentatonic", "Blues", "Whole tone",
                                                "Hijaz", "Hungarian minor", "Japanese (In)", "Bhairav", "Chromatic"};
    public static final int[][] SCALES = {
        {0, 2, 4, 5, 7, 9, 11}, {0, 2, 3, 5, 7, 8, 10}, {0, 2, 3, 5, 7, 8, 11}, {0, 2, 3, 5, 7, 9, 11},
        {0, 2, 3, 5, 7, 9, 10}, {0, 1, 3, 5, 7, 8, 10}, {0, 2, 4, 6, 7, 9, 11}, {0, 2, 4, 5, 7, 9, 10},
        {0, 1, 3, 5, 6, 8, 10}, {0, 2, 4, 7, 9}, {0, 3, 5, 7, 10}, {0, 3, 5, 6, 7, 10}, {0, 2, 4, 6, 8, 10},
        {0, 1, 4, 5, 7, 8, 10}, {0, 2, 3, 6, 7, 8, 11}, {0, 1, 5, 7, 8}, {0, 1, 4, 5, 7, 8, 11},
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}};

    /** One adjustable setting of an effect. labels != null: a choice; otherwise a number from min to max. */
    public static final class Param {
        public final String name, unit;
        public final double min, max, initial, step;
        public final String[] labels;
        Param(String name, double min, double max, double initial, double step, String unit) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.initial = initial;
            this.step = step;
            this.unit = unit;
            labels = null;
        }
        Param(String name, String[] labels, int initial) {
            this.name = name;
            this.labels = labels;
            min = 0;
            max = labels.length - 1;
            this.initial = initial;
            step = 1;
            unit = "";
        }
        public String show(double v) {
            if (labels != null)
                return labels[(int) Math.max(0, Math.min(labels.length - 1, Math.round(v)))];
            String s = step >= 1 ? "" + Math.round(v) : String.format(java.util.Locale.US, "%.2f", v);
            return s + unit;
        }
    }

    /** A starting or ending note in beat time; id ties an end to its start. */
    static final class Ev {
        final double beat;
        final int id, key;
        final float vel;
        final boolean on;
        final long order;
        Ev(double beat, int id, int key, float vel, boolean on, long order) {
            this.beat = beat;
            this.id = id;
            this.key = key;
            this.vel = vel;
            this.on = on;
            this.order = order;
        }
    }
    private static final Comparator<Ev> BY_TIME = new Comparator<Ev>() {
        public int compare(Ev a, Ev b) {
            if (a.beat != b.beat)
                return a.beat < b.beat ? -1 : 1;
            if (a.on != b.on)
                return a.on ? 1 : -1;  // an end before a start at the same moment: retriggers stay clean
            return a.order < b.order ? -1 : a.order > b.order ? 1 : 0;
        }
    };

    /** Where an effect sends its notes: into the next effect's queue, never before now. */
    static final class Ctx {
        final Chain chain;
        final PriorityQueue<Ev> out;
        double now, bpm;
        /** Playback: a latched arpeggio stops at the end of the bar after the last written note. */
        double until = Double.POSITIVE_INFINITY;
        boolean live;
        Ctx(Chain chain, PriorityQueue<Ev> out) {
            this.chain = chain;
            this.out = out;
        }
        int start(double beat, int key, float vel) {
            int id = ++chain.ids;
            out.add(new Ev(Math.max(now, beat), id, key, Math.max(.01f, Math.min(1, vel)), true, chain.order++));
            return id;
        }
        void end(double beat, int id) { out.add(new Ev(Math.max(now, beat), id, 0, 0, false, chain.order++)); }
        /** Milliseconds as beats at the current tempo. */
        double ms(double ms) { return ms / 60000.0 * bpm; }
    }

    public abstract static class Effect {
        public final String kind;
        public final double[] p;
        public boolean on = true;
        public long seed = 1;
        Random random = new Random(1);
        Effect(String kind) {
            this.kind = kind;
            Param[] ps = params();
            p = new double[ps.length];
            for (int i = 0; i < ps.length; i++) p[i] = ps[i].initial;
        }
        public abstract String title();
        public abstract Param[] params();
        int choice(int i) { return (int) Math.round(p[i]); }
        void reset() { random = new Random(seed); }
        abstract void start(Ev e, Ctx c);
        abstract void end(Ev e, Ctx c);
        /** Time-driven effects act here; called after each moment's notes. */
        void advance(Ctx c) {}
        /** The next moment this effect wants to act by itself, or infinity. */
        double wake() { return Double.POSITIVE_INFINITY; }
        public Map<String, Object> json() {
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("kind", kind);
            m.put("on", on);
            m.put("seed", seed);
            ArrayList<Object> v = new ArrayList<Object>();
            for (double d : p) v.add(d);
            m.put("p", v);
            return m;
        }
    }

    /** An effect that maps each incoming note to some outgoing notes, and ends them together. */
    abstract static class Mapper extends Effect {
        final HashMap<Integer, int[]> live = new HashMap<Integer, int[]>();
        Mapper(String kind) { super(kind); }
        void reset() {
            super.reset();
            live.clear();
        }
        void end(Ev e, Ctx c) {
            int[] ids = live.remove(e.id);
            if (ids != null)
                for (int id : ids) c.end(c.now + endDelay(e.id), id);
        }
        double endDelay(int id) { return 0; }
    }

    // ---------------------------------------------------------------- the effects

    public static final class Arpeggiator extends Effect {
        static final String[] MODES = {"Up", "Down", "Up and down", "Down and up", "Up and down, ends twice",
                                       "Converge", "Diverge", "Pinky up", "Thumb up", "As played", "Random",
                                       "Chord"};
        final ArrayList<int[]> held = new ArrayList<int[]>();  // {inputId, key, velocity*1000}, in press order
        final ArrayList<int[]> latched = new ArrayList<int[]>();
        double next = Double.POSITIVE_INFINITY, origin;
        long step;
        boolean fresh;
        int[] sounding = new int[0];
        Arpeggiator() { super("arp"); }
        public String title() { return "Arpeggiator"; }
        public Param[] params() {
            return new Param[] {new Param("Pattern", MODES, 0), new Param("Rate", RATE_NAMES, 8),
                                new Param("Octaves", 1, 4, 1, 1, ""), new Param("Gate", 5, 200, 80, 5, "%"),
                                new Param("Swing", 0, 75, 0, 1, "%"),
                                new Param("Latch", new String[] {"Off", "On"}, 0),
                                new Param("Velocity", new String[] {"As played", "Fixed", "Accent every 4"}, 0),
                                new Param("Repeat each", 1, 4, 1, 1, "×"),
                                new Param("Start", new String[] {"At once", "On the beat grid"}, 0)};
        }
        void reset() {
            super.reset();
            held.clear();
            latched.clear();
            next = Double.POSITIVE_INFINITY;
            step = 0;
            sounding = new int[0];
        }
        double rate() { return RATES[choice(1)]; }
        List<int[]> source() { return held.isEmpty() && choice(5) == 1 ? latched : held; }
        boolean over(Ctx c) { return held.isEmpty() && next >= c.until; }
        void start(Ev e, Ctx c) {
            if (held.isEmpty() && choice(5) == 1)
                latched.clear();
            held.add(new int[] {e.id, e.key, Math.round(e.vel * 1000)});
            if (choice(5) == 1)
                latched.add(held.get(held.size() - 1));
            if (next == Double.POSITIVE_INFINITY) {
                step = 0;
                origin = choice(8) == 1 ? Math.ceil(c.now / rate() - 1e-9) * rate() : c.now;
                next = origin;
            }
        }
        void end(Ev e, Ctx c) {
            for (int i = 0; i < held.size(); i++)
                if (held.get(i)[0] == e.id) {
                    held.remove(i);
                    break;
                }
            if (source().isEmpty())
                stop(c);
        }
        void stop(Ctx c) {
            for (int id : sounding) c.end(c.now, id);
            sounding = new int[0];
            next = Double.POSITIVE_INFINITY;
        }
        double wake() { return next; }
        void advance(Ctx c) {
            while (next <= c.now + 1e-9) {
                List<int[]> src = source();
                if (src.isEmpty() || over(c)) {
                    stop(c);
                    return;
                }
                double at = next, r = rate(), len = Math.max(1 / 64.0, r * p[3] / 100);
                for (int id : sounding) c.end(at, id);
                ArrayList<int[]> notes = new ArrayList<int[]>();
                ArrayList<int[]> sorted = new ArrayList<int[]>();
                int octaves = choice(2);
                for (int o = 0; o < octaves; o++)
                    for (int[] h : src) sorted.add(new int[] {h[0], h[1] + 12 * o, h[2]});
                ArrayList<int[]> played = new ArrayList<int[]>(sorted);
                Collections.sort(sorted, new Comparator<int[]>() {
                    public int compare(int[] a, int[] b) { return a[1] - b[1]; }
                });
                int repeat = Math.max(1, choice(7));
                long s = step / repeat;
                int n = sorted.size(), mode = choice(0);
                if (mode == 11)
                    notes.addAll(sorted);
                else if (mode == 10)
                    notes.add(sorted.get(random.nextInt(n)));
                else
                    notes.add(pick(mode, sorted, played, s));
                int[] ids = new int[notes.size()];
                float accent = choice(6) == 2 ? (step % 4 == 0 ? 1f : .68f) : 1f;
                for (int i = 0; i < ids.length; i++) {
                    float v = choice(6) == 1 ? .8f : notes.get(i)[2] / 1000f;
                    ids[i] = c.start(at, notes.get(i)[1], v * accent);
                    c.end(at + len, ids[i]);
                }
                sounding = len < r ? new int[0] : ids;
                step++;
                // swing delays every second step
                double base = origin + step * r;
                next = base + (step % 2 == 1 ? r * p[4] / 100 : 0);
            }
        }
        static int[] pick(int mode, List<int[]> up, List<int[]> played, long s) {
            int n = up.size();
            switch (mode) {
                case 1: return up.get(n - 1 - (int) (s % n));
                case 2:
                case 3: {
                    if (n == 1)
                        return up.get(0);
                    int cycle = 2 * n - 2, i = (int) (s % cycle), k = i < n ? i : cycle - i;
                    return up.get(mode == 2 ? k : n - 1 - k);
                }
                case 4: {
                    int i = (int) (s % (2 * n));
                    return up.get(i < n ? i : 2 * n - 1 - i);
                }
                case 5:
                case 6: {
                    int i = (int) (s % n), k = i % 2 == 0 ? i / 2 : n - 1 - i / 2;
                    if (mode == 6)
                        k = (n - 1) / 2 + (i % 2 == 0 ? -(i / 2) : (i + 1) / 2);
                    return up.get(Math.max(0, Math.min(n - 1, k)));
                }
                case 7: {
                    if (n == 1)
                        return up.get(0);
                    int i = (int) (s % (2 * (n - 1)));
                    return i % 2 == 1 ? up.get(n - 1) : up.get(i / 2);
                }
                case 8: {
                    if (n == 1)
                        return up.get(0);
                    int i = (int) (s % (2 * (n - 1)));
                    return i % 2 == 0 ? up.get(0) : up.get(1 + i / 2);
                }
                case 9: return played.get((int) (s % played.size()));
                default: return up.get((int) (s % n));
            }
        }
    }

    public static final class Chord extends Mapper {
        static final String[] TYPES = {"Major", "Minor", "7th", "Major 7th", "Minor 7th", "Sus2", "Sus4",
                                       "Add 9", "9th", "Minor 9th", "Major 9th", "6th", "Minor 6th",
                                       "Diminished", "Diminished 7th", "Half-diminished", "Augmented",
                                       "Power (5th)", "Octaves", "In key: triad", "In key: 7th", "In key: 9th"};
        static final int[][] SHAPES = {{0, 4, 7}, {0, 3, 7}, {0, 4, 7, 10}, {0, 4, 7, 11}, {0, 3, 7, 10},
                                       {0, 2, 7}, {0, 5, 7}, {0, 4, 7, 14}, {0, 4, 7, 10, 14},
                                       {0, 3, 7, 10, 14}, {0, 4, 7, 11, 14}, {0, 4, 7, 9}, {0, 3, 7, 9},
                                       {0, 3, 6}, {0, 3, 6, 9}, {0, 3, 6, 10}, {0, 4, 8}, {0, 7, 12}, {0, 12}};
        Chord() { super("chord"); }
        public String title() { return "Chord"; }
        public Param[] params() {
            return new Param[] {new Param("Chord", TYPES, 0), new Param("Inversion", 0, 3, 0, 1, ""),
                                new Param("Voicing", new String[] {"Close", "Open (spread)", "Drop 2"}, 0),
                                new Param("Key", KEYS, 0), new Param("Scale", SCALE_NAMES, 0),
                                new Param("Added notes", 10, 100, 85, 5, "%")};
        }
        int[] notes(int key) {
            int type = choice(0);
            int[] k;
            if (type >= 19) {
                int[] scale = SCALES[choice(4)];
                int count = type == 19 ? 3 : type == 20 ? 4 : 5, root = Scale.snap(key, choice(3), scale, 0);
                k = new int[count];
                for (int i = 0; i < count; i++) k[i] = Scale.degree(root, 2 * i, choice(3), scale);
            } else {
                k = new int[SHAPES[type].length];
                for (int i = 0; i < k.length; i++) k[i] = key + SHAPES[type][i];
            }
            Arrays.sort(k);
            for (int i = 0; i < choice(1) && i < k.length - 1; i++) {
                k[0] += 12;
                Arrays.sort(k);
            }
            if (choice(2) == 1 && k.length >= 3)
                k[1] += 12;
            else if (choice(2) == 2 && k.length >= 4)
                k[k.length - 2] -= 12;
            Arrays.sort(k);
            return k;
        }
        void start(Ev e, Ctx c) {
            int[] keys = notes(e.key);
            int[] ids = new int[keys.length];
            for (int i = 0; i < keys.length; i++)
                ids[i] = c.start(c.now, keys[i], keys[i] == e.key ? e.vel : (float) (e.vel * p[5] / 100));
            live.put(e.id, ids);
        }
    }

    public static final class Scale extends Mapper {
        Scale() { super("scale"); }
        public String title() { return "Scale lock"; }
        public Param[] params() {
            return new Param[] {new Param("Key", KEYS, 0), new Param("Scale", SCALE_NAMES, 0),
                                new Param("Out-of-key notes",
                                          new String[] {"Move to nearest", "Move up", "Move down", "Drop them"}, 0),
                                new Param("White keys", new String[] {"Play as they are", "Play the scale"}, 0)};
        }
        /** The nearest scale note to key (direction 0 nearest, 1 up, 2 down); ties go down. */
        static int snap(int key, int root, int[] scale, int direction) {
            for (int d = 0; d < 12; d++) {
                if (direction != 1 && in(key - d, root, scale))
                    return key - d;
                if (direction != 2 && in(key + d, root, scale))
                    return key + d;
            }
            return key;
        }
        static boolean in(int key, int root, int[] scale) {
            int pc = ((key - root) % 12 + 12) % 12;
            for (int s : scale)
                if (s == pc)
                    return true;
            return false;
        }
        /** The scale note `steps` scale degrees above key (key must be in the scale). */
        static int degree(int key, int steps, int root, int[] scale) {
            int pc = ((key - root) % 12 + 12) % 12, idx = 0;
            for (int i = 0; i < scale.length; i++)
                if (scale[i] == pc)
                    idx = i;
            int total = idx + steps, octave = Math.floorDiv(total, scale.length), at = Math.floorMod(total, scale.length);
            return key - scale[idx] + scale[at] + 12 * octave;
        }
        static final int[] WHITE = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
        void start(Ev e, Ctx c) {
            int[] scale = SCALES[choice(1)];
            int root = choice(0), key = e.key;
            if (choice(3) == 1) {
                int pc = ((key % 12) + 12) % 12;
                if (pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10) {
                    live.put(e.id, new int[0]);
                    return;  // black keys are quiet when the white keys play the scale
                }
                int white = Math.floorDiv(key, 12) * 7 + WHITE[pc] - 35;  // C4 (60) is the root's first degree
                int octave = Math.floorDiv(white, scale.length), at = Math.floorMod(white, scale.length);
                key = 60 + root + 12 * octave + scale[at];
            } else if (!in(key, root, scale)) {
                if (choice(2) == 3) {
                    live.put(e.id, new int[0]);
                    return;
                }
                key = snap(key, root, scale, choice(2));
            }
            live.put(e.id, new int[] {c.start(c.now, key, e.vel)});
        }
    }

    public static final class Harmonizer extends Mapper {
        static final String[] VOICES = {"Off", "3rd up", "4th up", "5th up", "6th up", "Octave up", "3rd down",
                                        "4th down", "5th down", "6th down", "Octave down"};
        static final int[] STEPS = {0, 2, 3, 4, 5, 7, -2, -3, -4, -5, -7};
        Harmonizer() { super("harmony"); }
        public String title() { return "Harmonizer"; }
        public Param[] params() {
            return new Param[] {new Param("Key", KEYS, 0), new Param("Scale", SCALE_NAMES, 0),
                                new Param("Voice 1", VOICES, 1), new Param("Voice 2", VOICES, 0),
                                new Param("Voice level", 10, 100, 80, 5, "%")};
        }
        void start(Ev e, Ctx c) {
            int[] scale = SCALES[choice(1)];
            int root = choice(0), base = Scale.snap(e.key, root, scale, 0);
            ArrayList<Integer> ids = new ArrayList<Integer>();
            ids.add(c.start(c.now, e.key, e.vel));
            for (int v = 2; v <= 3; v++)
                if (choice(v) != 0) {
                    int k = choice(v) == 5 ? e.key + 12 : choice(v) == 10 ? e.key - 12
                                                       : Scale.degree(base, STEPS[choice(v)], root, scale);
                    ids.add(c.start(c.now, k, (float) (e.vel * p[4] / 100)));
                }
            int[] out = new int[ids.size()];
            for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
            live.put(e.id, out);
        }
    }

    public static final class Echo extends Effect {
        final HashMap<Integer, int[]> repeats = new HashMap<Integer, int[]>();
        Echo() { super("echo"); }
        public String title() { return "Note echo"; }
        public Param[] params() {
            return new Param[] {new Param("Time", RATE_NAMES, 4), new Param("Repeats", 1, 16, 3, 1, ""),
                                new Param("Feedback", 10, 100, 60, 5, "%"),
                                new Param("Pitch per repeat", -12, 12, 0, 1, " st")};
        }
        void reset() {
            super.reset();
            repeats.clear();
        }
        void start(Ev e, Ctx c) {
            int n = choice(1);
            double t = RATES[choice(0)];
            float v = e.vel;
            // the first is the note itself and ends with it; the repeats are each nine tenths of the echo time
            int[] ids = {c.start(c.now, e.key, v)};
            for (int r = 1; r <= n; r++) {
                v *= p[2] / 100;
                int id = c.start(c.now + r * t, e.key + r * choice(3), v);
                c.end(c.now + r * t + Math.max(1 / 64.0, t * .9), id);
            }
            repeats.put(e.id, ids);
        }
        void end(Ev e, Ctx c) {
            int[] ids = repeats.remove(e.id);
            if (ids != null)
                c.end(c.now, ids[0]);
        }
    }

    public static final class Strum extends Effect {
        final ArrayList<Ev> pending = new ArrayList<Ev>();
        final HashMap<Integer, Integer> map = new HashMap<Integer, Integer>();
        final HashMap<Integer, Double> began = new HashMap<Integer, Double>();
        final HashMap<Integer, Boolean> endedEarly = new HashMap<Integer, Boolean>();
        double deadline = Double.POSITIVE_INFINITY;
        int strums;
        Strum() { super("strum"); }
        public String title() { return "Strum"; }
        public Param[] params() {
            return new Param[] {new Param("Speed", 5, 200, 35, 5, " ms per string"),
                                new Param("Direction", new String[] {"Down (low first)", "Up (high first)",
                                                                     "Alternate", "Random"}, 0),
                                new Param("Velocity ramp", -50, 50, -10, 5, "%")};
        }
        void reset() {
            super.reset();
            pending.clear();
            map.clear();
            began.clear();
            endedEarly.clear();
            deadline = Double.POSITIVE_INFINITY;
            strums = 0;
        }
        void start(Ev e, Ctx c) {
            if (pending.isEmpty())
                deadline = c.now + (c.live ? c.ms(14) : 0);
            pending.add(e);
        }
        void end(Ev e, Ctx c) {
            Integer id = map.remove(e.id);
            if (id != null) {
                c.end(Math.max(c.now, began.remove(id) + 1 / 64.0), id);
                return;
            }
            for (Ev p : pending)
                if (p.id == e.id)
                    endedEarly.put(e.id, true);
        }
        double wake() { return deadline; }
        void advance(Ctx c) {
            if (pending.isEmpty() || c.now < deadline - 1e-9)
                return;
            ArrayList<Ev> group = new ArrayList<Ev>(pending);
            pending.clear();
            deadline = Double.POSITIVE_INFINITY;
            Collections.sort(group, new Comparator<Ev>() {
                public int compare(Ev a, Ev b) { return a.key - b.key; }
            });
            int dir = choice(1);
            if (dir == 1 || (dir == 2 && strums % 2 == 1))
                Collections.reverse(group);
            else if (dir == 3)
                Collections.shuffle(group, random);
            strums++;
            double gap = c.ms(p[0]);
            for (int i = 0; i < group.size(); i++) {
                Ev e = group.get(i);
                double at = c.now + i * gap;
                float v = (float) (e.vel * (1 + p[2] / 100 * i / Math.max(1, group.size() - 1)));
                int id = c.start(at, e.key, v);
                began.put(id, at);
                if (endedEarly.remove(e.id) != null)
                    c.end(at + 1 / 16.0, id);
                else
                    map.put(e.id, id);
            }
        }
    }

    public static final class Humanize extends Mapper {
        final HashMap<Integer, Double> delays = new HashMap<Integer, Double>();
        Humanize() { super("humanize"); }
        public String title() { return "Humanize"; }
        public Param[] params() {
            return new Param[] {new Param("Timing", 0, 60, 12, 1, " ms"), new Param("Velocity", 0, 50, 12, 1, "%"),
                                new Param("Length", 0, 50, 8, 1, "%")};
        }
        void reset() {
            super.reset();
            delays.clear();
        }
        void start(Ev e, Ctx c) {
            double d = c.ms(random.nextDouble() * p[0]);
            float v = (float) (e.vel * (1 + (random.nextDouble() * 2 - 1) * p[1] / 100));
            int id = c.start(c.now + d, e.key, v);
            delays.put(id, d + c.ms(random.nextDouble() * p[2] * 4));
            live.put(e.id, new int[] {id});
        }
        void end(Ev e, Ctx c) {
            int[] ids = live.remove(e.id);
            if (ids != null) {
                Double d = delays.remove(ids[0]);
                c.end(c.now + (d == null ? 0 : d), ids[0]);
            }
        }
    }

    public static final class Velocity extends Mapper {
        Velocity() { super("velocity"); }
        public String title() { return "Velocity"; }
        public Param[] params() {
            return new Param[] {new Param("Mode", new String[] {"Range", "Fixed", "Random"}, 0),
                                new Param("Lowest", 1, 100, 1, 1, "%"), new Param("Highest", 1, 100, 100, 1, "%"),
                                new Param("Curve", -100, 100, 0, 5, ""), new Param("Fixed level", 1, 100, 90, 1, "%")};
        }
        void start(Ev e, Ctx c) {
            double v = e.vel;
            if (choice(0) == 1)
                v = p[4] / 100;
            else {
                if (choice(0) == 2)
                    v = random.nextDouble();
                v = Math.pow(v, Math.pow(2, -p[3] / 66.0));
                v = p[1] / 100 + v * (p[2] - p[1]) / 100;
            }
            live.put(e.id, new int[] {c.start(c.now, e.key, (float) v)});
        }
    }

    public static final class Transpose extends Mapper {
        Transpose() { super("transpose"); }
        public String title() { return "Transpose"; }
        public Param[] params() {
            return new Param[] {new Param("Semitones", -12, 12, 0, 1, " st"), new Param("Octaves", -3, 3, 0, 1, "")};
        }
        void start(Ev e, Ctx c) {
            live.put(e.id, new int[] {c.start(c.now, e.key + choice(0) + 12 * choice(1), e.vel)});
        }
    }

    public static final class Range extends Mapper {
        Range() { super("range"); }
        public String title() { return "Key range"; }
        public Param[] params() {
            return new Param[] {new Param("Lowest key", 21, 108, 21, 1, ""), new Param("Highest key", 21, 108, 108, 1, ""),
                                new Param("Outside", new String[] {"Drop", "Fold into range"}, 1)};
        }
        void start(Ev e, Ctx c) {
            int lo = Math.min(choice(0), choice(1)), hi = Math.max(choice(0), choice(1)), k = e.key;
            if (k < lo || k > hi) {
                if (choice(2) == 0 || hi - lo < 11) {
                    live.put(e.id, new int[0]);
                    return;
                }
                while (k < lo) k += 12;
                while (k > hi) k -= 12;
            }
            live.put(e.id, new int[] {c.start(c.now, k, e.vel)});
        }
    }

    public static final class Length extends Effect {
        final HashMap<Integer, int[]> map = new HashMap<Integer, int[]>();
        final HashMap<Integer, Double> began = new HashMap<Integer, Double>();
        int last = -1;
        Length() { super("length"); }
        public String title() { return "Note length"; }
        public Param[] params() {
            return new Param[] {new Param("Mode", new String[] {"Fixed length", "Longer by", "Legato"}, 0),
                                new Param("Length", RATE_NAMES, 8), new Param("Longer by", 0, 300, 50, 10, "%")};
        }
        void reset() {
            super.reset();
            map.clear();
            began.clear();
            last = -1;
        }
        void start(Ev e, Ctx c) {
            int mode = choice(0);
            if (mode == 2 && last >= 0) {
                c.end(c.now, last);
                last = -1;
            }
            int id = c.start(c.now, e.key, e.vel);
            if (mode == 0)
                c.end(c.now + RATES[choice(1)], id);
            else
                map.put(e.id, new int[] {id});
            began.put(id, c.now);
            if (mode == 2)
                last = id;
        }
        void end(Ev e, Ctx c) {
            int[] ids = map.remove(e.id);
            if (ids == null)
                return;
            int mode = choice(0);
            Double b = began.remove(ids[0]);
            if (mode == 1)
                c.end(c.now + (c.now - (b == null ? c.now : b)) * p[2] / 100, ids[0]);
            // legato notes end when the next one starts
        }
        void finish(Ctx c) {
            if (last >= 0)
                c.end(c.now + .25, last);
            last = -1;
        }
    }

    public static final class Chance extends Mapper {
        long count;
        Chance() { super("chance"); }
        public String title() { return "Chance"; }
        public Param[] params() {
            return new Param[] {new Param("Chance to play", 0, 100, 75, 5, "%"),
                                new Param("Also play only every", 1, 8, 1, 1, " notes"),
                                new Param("Starting at", 1, 8, 1, 1, "")};
        }
        void reset() {
            super.reset();
            count = 0;
        }
        void start(Ev e, Ctx c) {
            long n = count++;
            boolean play = random.nextDouble() * 100 < p[0] && (n - (choice(2) - 1)) % Math.max(1, choice(1)) == 0
                           && n >= choice(2) - 1;
            live.put(e.id, play ? new int[] {c.start(c.now, e.key, e.vel)} : new int[0]);
        }
    }

    public static final class Repeat extends Effect {
        final HashMap<Integer, double[]> held = new HashMap<Integer, double[]>();  // id → {key, vel, next, step}
        final HashMap<Integer, Integer> sounding = new HashMap<Integer, Integer>();
        Repeat() { super("repeat"); }
        public String title() { return "Note repeat"; }
        public Param[] params() {
            return new Param[] {new Param("Rate", RATE_NAMES, 8), new Param("Gate", 10, 100, 50, 5, "%"),
                                new Param("Count", 0, 16, 0, 1, ""),
                                new Param("Velocity ramp", -80, 80, 0, 5, "%")};
        }
        void reset() {
            super.reset();
            held.clear();
            sounding.clear();
        }
        void start(Ev e, Ctx c) {
            int count = choice(2);
            double r = RATES[choice(0)], len = Math.max(1 / 64.0, r * p[1] / 100);
            if (count > 0) {  // a fixed roll: all of it now, held or not
                for (int i = 0; i < count; i++) {
                    float v = (float) (e.vel * (1 + p[3] / 100 * i / Math.max(1, count - 1)));
                    int id = c.start(c.now + i * r, e.key, v);
                    c.end(c.now + i * r + len, id);
                }
                return;
            }
            held.put(e.id, new double[] {e.key, e.vel, c.now, 0});
        }
        void end(Ev e, Ctx c) {
            held.remove(e.id);
            Integer id = sounding.remove(e.id);
            if (id != null)
                c.end(c.now, id);
        }
        double wake() {
            double w = Double.POSITIVE_INFINITY;
            for (double[] h : held.values()) w = Math.min(w, h[2]);
            return w;
        }
        void advance(Ctx c) {
            double r = RATES[choice(0)], len = Math.max(1 / 64.0, r * p[1] / 100);
            ArrayList<Integer> keys = new ArrayList<Integer>(held.keySet());
            Collections.sort(keys);
            for (int in : keys) {
                double[] h = held.get(in);
                while (h[2] <= c.now + 1e-9) {
                    Integer old = sounding.remove(in);
                    if (old != null)
                        c.end(h[2], old);
                    float v = (float) (h[1] * Math.max(.05, 1 + p[3] / 100 * Math.min(1, h[3] / 8)));
                    int id = c.start(h[2], (int) h[0], v);
                    c.end(h[2] + len, id);
                    if (len >= r)
                        sounding.put(in, id);
                    h[3]++;
                    h[2] += r;
                }
            }
        }
    }

    public static final class RandomPitch extends Mapper {
        RandomPitch() { super("random"); }
        public String title() { return "Random pitch"; }
        public Param[] params() {
            return new Param[] {new Param("Chance", 0, 100, 30, 5, "%"), new Param("Range", 1, 24, 7, 1, " st"),
                                new Param("Direction", new String[] {"Up or down", "Up", "Down"}, 0)};
        }
        void start(Ev e, Ctx c) {
            int k = e.key;
            if (random.nextDouble() * 100 < p[0]) {
                int range = choice(1), d = 1 + random.nextInt(range);
                int dir = choice(2) == 1 ? 1 : choice(2) == 2 ? -1 : random.nextBoolean() ? 1 : -1;
                k += dir * d;
            }
            live.put(e.id, new int[] {c.start(c.now, k, e.vel)});
        }
    }

    public static final class Swing extends Mapper {
        final HashMap<Integer, Double> delays = new HashMap<Integer, Double>();
        Swing() { super("swing"); }
        public String title() { return "Swing"; }
        public Param[] params() {
            return new Param[] {new Param("Grid", new String[] {"1/8", "1/16"}, 1), new Param("Swing", 0, 75, 30, 1, "%")};
        }
        void reset() {
            super.reset();
            delays.clear();
        }
        void start(Ev e, Ctx c) {
            double g = choice(0) == 0 ? .5 : .25, pos = c.now / g, nearest = Math.rint(pos);
            boolean offbeat = Math.abs(pos - nearest) < .25 && ((long) nearest) % 2 == 1;
            double d = offbeat ? g * p[1] / 100 : 0;
            int id = c.start(c.now + d, e.key, e.vel);
            delays.put(e.id, d);
            live.put(e.id, new int[] {id});
        }
        double endDelay(int id) {
            Double d = delays.remove(id);
            return d == null ? 0 : d;
        }
    }

    public static final class Mono extends Effect {
        final ArrayList<int[]> held = new ArrayList<int[]>();  // {inputId, key, vel*1000}
        static final int NONE = Integer.MIN_VALUE;  // input ids are negative, so none is not -1
        int sounding = -1, soundingInput = NONE;
        Mono() { super("mono"); }
        public String title() { return "Mono / legato"; }
        public Param[] params() {
            return new Param[] {new Param("Priority", new String[] {"Last played", "Lowest", "Highest"}, 0),
                                new Param("When let go", new String[] {"Return to held note", "Stop"}, 0)};
        }
        void reset() {
            super.reset();
            held.clear();
            sounding = -1;
            soundingInput = NONE;
        }
        int[] chosen() {
            if (held.isEmpty())
                return null;
            int[] best = held.get(held.size() - 1);
            if (choice(0) != 0)
                for (int[] h : held)
                    if (choice(0) == 1 ? h[1] < best[1] : h[1] > best[1])
                        best = h;
            return best;
        }
        void play(Ctx c) {
            int[] n = chosen();
            if (n != null && n[0] == soundingInput)
                return;
            if (sounding >= 0)
                c.end(c.now, sounding);
            sounding = -1;
            soundingInput = NONE;
            if (n != null) {
                sounding = c.start(c.now, n[1], n[2] / 1000f);
                soundingInput = n[0];
            }
        }
        void start(Ev e, Ctx c) {
            held.add(new int[] {e.id, e.key, Math.round(e.vel * 1000)});
            play(c);
        }
        void end(Ev e, Ctx c) {
            for (int i = 0; i < held.size(); i++)
                if (held.get(i)[0] == e.id) {
                    held.remove(i);
                    break;
                }
            if (e.id == soundingInput && choice(1) == 1) {
                c.end(c.now, sounding);
                sounding = -1;
                soundingInput = NONE;
                held.clear();
                return;
            }
            if (e.id == soundingInput || held.isEmpty())
                play(c);
        }
    }

    // ---------------------------------------------------------------- registry, presets and the chain

    public static final String[] KINDS = {"arp", "chord", "scale", "harmony", "echo", "strum", "humanize",
                                          "velocity", "transpose", "range", "length", "chance", "repeat",
                                          "random", "swing", "mono"};
    public static Effect make(String kind) {
        if ("arp".equals(kind)) return new Arpeggiator();
        if ("chord".equals(kind)) return new Chord();
        if ("scale".equals(kind)) return new Scale();
        if ("harmony".equals(kind)) return new Harmonizer();
        if ("echo".equals(kind)) return new Echo();
        if ("strum".equals(kind)) return new Strum();
        if ("humanize".equals(kind)) return new Humanize();
        if ("velocity".equals(kind)) return new Velocity();
        if ("transpose".equals(kind)) return new Transpose();
        if ("range".equals(kind)) return new Range();
        if ("length".equals(kind)) return new Length();
        if ("chance".equals(kind)) return new Chance();
        if ("repeat".equals(kind)) return new Repeat();
        if ("random".equals(kind)) return new RandomPitch();
        if ("swing".equals(kind)) return new Swing();
        if ("mono".equals(kind)) return new Mono();
        throw new IllegalArgumentException("Unknown MIDI effect.");
    }
    /** One line about each effect, for the rack's menu. */
    public static String about(String kind) {
        if ("arp".equals(kind)) return "Plays held notes one after another in a pattern.";
        if ("chord".equals(kind)) return "One finger plays a whole chord.";
        if ("scale".equals(kind)) return "Every note lands in key; or the white keys play any scale.";
        if ("harmony".equals(kind)) return "Adds harmony voices in key, a third or a fifth away.";
        if ("echo".equals(kind)) return "Repeats each note in time, fading, optionally climbing.";
        if ("strum".equals(kind)) return "Rolls chords like a guitar strum.";
        if ("humanize".equals(kind)) return "Loosens timing, velocity and length like a real player.";
        if ("velocity".equals(kind)) return "Shapes how hard notes are: range, curve, fixed or random.";
        if ("transpose".equals(kind)) return "Moves everything by semitones and octaves.";
        if ("range".equals(kind)) return "Keeps notes inside a range: drop or fold the rest.";
        if ("length".equals(kind)) return "Fixed, longer or legato notes.";
        if ("chance".equals(kind)) return "Each note plays only sometimes, or every Nth note.";
        if ("repeat".equals(kind)) return "Machine-gun note repeat and fixed rolls (trap hats).";
        if ("random".equals(kind)) return "Sometimes moves a note up or down; follow with Scale lock.";
        if ("swing".equals(kind)) return "Pushes the off-beats late for a groove.";
        return "One note at a time, for basses and leads.";
    }

    public static final String[] PRESETS = {"Trance arp", "Lo-fi chords", "Trap hi-hat rolls", "Dub echo",
                                            "Guitar strum", "Never a wrong note", "Pop harmony",
                                            "Played by a human", "Rolling bass", "Generative sparkle"};
    public static Chain preset(int which) {
        Chain c = new Chain();
        switch (which) {
            case 0: {
                Effect a = c.add("arp");
                a.p[0] = 2;
                a.p[1] = 8;
                a.p[2] = 2;
                a.p[3] = 60;
                break;
            }
            case 1: {
                c.add("chord").p[0] = 20;
                c.add("strum").p[0] = 18;
                Effect h = c.add("humanize");
                h.p[0] = 16;
                c.add("swing").p[1] = 22;
                break;
            }
            case 2: {
                Effect r = c.add("repeat");
                r.p[0] = 9;
                r.p[1] = 40;
                r.p[3] = -30;
                c.add("velocity").p[3] = 20;
                break;
            }
            case 3: {
                Effect e = c.add("echo");
                e.p[0] = 4;
                e.p[1] = 6;
                e.p[2] = 65;
                break;
            }
            case 4: {
                c.add("chord").p[0] = 0;
                c.add("strum").p[0] = 30;
                break;
            }
            case 5: {
                Effect s = c.add("scale");
                s.p[1] = 10;
                break;
            }
            case 6: {
                c.add("scale");
                c.add("harmony").p[2] = 1;
                break;
            }
            case 7: c.add("humanize"); break;
            case 8: {
                c.add("mono");
                Effect a = c.add("arp");
                a.p[0] = 9;
                a.p[1] = 8;
                a.p[3] = 55;
                a.p[8] = 1;
                break;
            }
            default: {
                c.add("chance").p[0] = 70;
                Effect r = c.add("random");
                r.p[1] = 12;
                c.add("scale").p[1] = 9;
                Effect e = c.add("echo");
                e.p[0] = 6;
                e.p[2] = 55;
                break;
            }
        }
        return c;
    }

    public static final class Chain {
        public static final int MAX = 8, MAX_OUT = 16384;
        public final ArrayList<Effect> effects = new ArrayList<Effect>();
        int ids;
        long order;
        PriorityQueue<Ev>[] queues;
        Ctx[] ctx;
        public boolean empty() {
            for (Effect e : effects)
                if (e.on)
                    return false;
            return true;
        }
        public Effect add(String kind) {
            if (effects.size() >= MAX)
                throw new IllegalArgumentException("A track holds up to 8 MIDI effects.");
            Effect e = make(kind);
            e.seed = 1 + effects.size() * 7919L + kind.hashCode();
            effects.add(e);
            return e;
        }
        public Chain copy() { return read(json()); }
        public List<Object> json() {
            ArrayList<Object> v = new ArrayList<Object>();
            for (Effect e : effects) v.add(e.json());
            return v;
        }
        public static Chain read(Object value) {
            if (!(value instanceof List) || ((List<?>) value).size() > MAX)
                throw new IllegalArgumentException("Damaged MIDI effects.");
            Chain c = new Chain();
            for (Object o : (List<?>) value) {
                if (!(o instanceof Map))
                    throw new IllegalArgumentException("Damaged MIDI effect.");
                Map<?, ?> m = (Map<?, ?>) o;
                if (!(m.get("kind") instanceof String) || !(m.get("p") instanceof List))
                    throw new IllegalArgumentException("Damaged MIDI effect.");
                Effect e = make((String) m.get("kind"));
                List<?> ps = (List<?>) m.get("p");
                Param[] defs = e.params();
                for (int i = 0; i < Math.min(ps.size(), defs.length); i++) {
                    if (!(ps.get(i) instanceof Number))
                        throw new IllegalArgumentException("Damaged MIDI effect setting.");
                    double d = ((Number) ps.get(i)).doubleValue();
                    if (!(d >= defs[i].min && d <= defs[i].max))
                        throw new IllegalArgumentException("A MIDI effect setting is out of range.");
                    e.p[i] = d;
                }
                e.on = !Boolean.FALSE.equals(m.get("on"));
                if (m.get("seed") instanceof Number)
                    e.seed = ((Number) m.get("seed")).longValue();
                c.effects.add(e);
            }
            return c;
        }

        // ---------------------------------------------------------- stepping

        @SuppressWarnings("unchecked")
        void begin(double bpm, boolean live) {
            ArrayList<Effect> active = active();
            queues = new PriorityQueue[active.size() + 1];
            ctx = new Ctx[active.size()];
            for (int i = 0; i < queues.length; i++) queues[i] = new PriorityQueue<Ev>(64, BY_TIME);
            for (int i = 0; i < ctx.length; i++) {
                ctx[i] = new Ctx(this, queues[i + 1]);
                ctx[i].bpm = bpm;
                ctx[i].live = live;
                active.get(i).reset();
            }
            ids = 0;
            order = 0;
        }
        ArrayList<Effect> active() {
            ArrayList<Effect> a = new ArrayList<Effect>();
            for (Effect e : effects)
                if (e.on)
                    a.add(e);
            return a;
        }
        /** The next moment anything is due: a queued note or an effect waking up. */
        double due() {
            double t = Double.POSITIVE_INFINITY;
            for (PriorityQueue<Ev> q : queues)
                if (!q.isEmpty())
                    t = Math.min(t, q.peek().beat);
            for (Effect e : active()) t = Math.min(t, e.wake());
            return t;
        }
        /** Runs every effect up to now, in chain order; finished notes come out of the last queue. */
        void step(double now, List<Ev> out) {
            ArrayList<Effect> a = active();
            for (int i = 0; i < a.size(); i++) {
                Ctx c = ctx[i];
                c.now = now;
                PriorityQueue<Ev> in = queues[i];
                while (!in.isEmpty() && in.peek().beat <= now + 1e-9) {
                    Ev e = in.poll();
                    if (e.on)
                        a.get(i).start(e, c);
                    else
                        a.get(i).end(e, c);
                }
                a.get(i).advance(c);
            }
            PriorityQueue<Ev> last = queues[a.size()];
            while (!last.isEmpty() && last.peek().beat <= now + 1e-9) out.add(last.poll());
        }
        void finish(double now, List<Ev> out) {
            ArrayList<Effect> a = active();
            for (int i = 0; i < a.size(); i++)
                if (a.get(i) instanceof Length) {
                    ctx[i].now = now;
                    ((Length) a.get(i)).finish(ctx[i]);
                }
        }

        /** The notes as heard, with this chain applied. The input is not changed. */
        public List<MidiNotes.Note> process(List<MidiNotes.Note> notes, double bpm) {
            begin(bpm, false);
            ArrayList<MidiNotes.Note> sorted = new ArrayList<MidiNotes.Note>(notes);
            Collections.sort(sorted, new Comparator<MidiNotes.Note>() {
                public int compare(MidiNotes.Note a, MidiNotes.Note b) {
                    return a.beat != b.beat ? Double.compare(a.beat, b.beat) : a.key - b.key;
                }
            });
            int inputIds = 0;
            double last = 0;
            for (MidiNotes.Note n : sorted) last = Math.max(last, n.beat + n.length);
            for (Ctx x : ctx) x.until = Math.ceil(last / 4 + 1e-9) * 4;
            for (MidiNotes.Note n : sorted) {
                int id = -(++inputIds);
                queues[0].add(new Ev(n.beat, id, n.key, n.velocity, true, order++));
                queues[0].add(new Ev(n.beat + n.length, id, n.key, 0, false, order++));
            }
            ArrayList<Ev> out = new ArrayList<Ev>();
            HashMap<Integer, MidiNotes.Note> open = new HashMap<Integer, MidiNotes.Note>();
            ArrayList<MidiNotes.Note> result = new ArrayList<MidiNotes.Note>();
            int guard = 0, nextId = 1;
            double now = 0;
            boolean finished = false;
            while (guard++ < 400000) {
                double t = due();
                if (t == Double.POSITIVE_INFINITY) {
                    if (finished)
                        break;
                    finish(now, out);
                    finished = true;
                    continue;
                }
                now = Math.max(now, t);
                if (now > 100250)
                    break;
                out.clear();
                step(now, out);
                for (Ev e : out) {
                    if (e.on) {
                        if (e.key < MidiNotes.LOW || e.key > MidiNotes.HIGH || result.size() >= MAX_OUT)
                            continue;
                        MidiNotes.Note n = new MidiNotes.Note();
                        n.id = nextId++;
                        n.key = e.key;
                        n.beat = Math.min(100000, e.beat);
                        n.velocity = Math.max(.01f, Math.min(1, e.vel));
                        n.length = 1 / 64.0;
                        open.put(e.id, n);
                        result.add(n);
                    } else {
                        MidiNotes.Note n = open.remove(e.id);
                        if (n != null)
                            n.length = Math.max(1 / 64.0, Math.min(256, e.beat - n.beat));
                    }
                }
            }
            for (MidiNotes.Note n : open.values()) n.length = Math.max(1 / 64.0, Math.min(256, now - n.beat + .25));
            return result;
        }

        /** The live side: notes in as they are played, notes out when they are due. Not thread-safe; one owner. */
        public final class Live {
            final ArrayList<Ev> out = new ArrayList<Ev>();
            int inputs;
            final HashMap<Integer, Integer> inputIds = new HashMap<Integer, Integer>();
            public Live(double bpm) { begin(bpm, true); }
            public void tempo(double bpm) {
                for (Ctx c : ctx) c.bpm = bpm;
            }
            /** A key pressed (or released) at beat now; key identifies the press for its release. */
            public void note(double now, int key, float velocity, boolean on) {
                if (on) {
                    int id = -(++inputs);
                    Integer old = inputIds.put(key, id);
                    if (old != null)
                        queues[0].add(new Ev(now, old, key, 0, false, order++));
                    queues[0].add(new Ev(now, id, key, velocity, true, order++));
                } else {
                    Integer id = inputIds.remove(key);
                    if (id != null)
                        queues[0].add(new Ev(now, id, key, 0, false, order++));
                }
            }
            final HashMap<Integer, Integer> keyOf = new HashMap<Integer, Integer>();
            final int[] current = new int[128];
            /**
             * Runs up to now; returns what to sound: {key, velocity*1000, on?1:0} each. The keyboard has one voice per
             * key, so an end only counts for the note that is still sounding on its key.
             */
            public List<int[]> poll(double now) {
                ArrayList<int[]> sound = new ArrayList<int[]>();
                for (int guard = 0; guard < 4096; guard++) {
                    double t = due();
                    if (t > now)
                        break;
                    out.clear();
                    step(Math.max(t, 0), out);
                    for (Ev e : out)
                        if (e.on) {
                            if (e.key < 0 || e.key > 127)
                                continue;
                            keyOf.put(e.id, e.key);
                            current[e.key] = e.id;
                            sound.add(new int[] {e.key, Math.round(e.vel * 1000), 1});
                        } else {
                            Integer k = keyOf.remove(e.id);
                            if (k != null && current[k] == e.id) {
                                current[k] = 0;
                                sound.add(new int[] {k, 0, 0});
                            }
                        }
                }
                return sound;
            }
            /** When the next note is due, so a player thread can sleep until then. */
            public double due() { return Chain.this.due(); }
        }
    }
}
