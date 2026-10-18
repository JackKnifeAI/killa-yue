package ai.jackknife.killayue.studio;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Editable beat-based notes; audio is streamed through the same tracks and effects as clips. */
public final class MidiNotes {
    /** The 88 keys of a piano. Projects made with the old 49-key range (36 to 84) still read unchanged. */
    public static final int LOW = 21, HIGH = 108, KEYS = HIGH - LOW + 1, MAX = 4096;
    public int instrument = -1, nextId = 1;
    public final ArrayList<Note> notes = new ArrayList<Note>();
    /** The track's MIDI effects, applied as the notes play; the notes themselves stay as written. */
    public MidiFx.Chain fx = new MidiFx.Chain();
    public static final class Note {
        public int id, key = 60;
        public double beat, length = 1;
        public float velocity = .8f;
        public List<Object> json() {
            ArrayList<Object> v = new ArrayList<Object>();
            v.add(id);
            v.add(key);
            v.add(beat);
            v.add(length);
            v.add(velocity);
            return v;
        }
    }
    public Note find(int id) {
        for (Note n : notes)
            if (n.id == id)
                return n;
        return null;
    }
    public Note add(double beat, int key) {
        Note n = new Note();
        n.id = nextId++;
        n.beat = beat;
        n.key = key;
        notes.add(n);
        return n;
    }
    public MidiNotes copy() {
        return read(json());
    }
    public void validate() {
        if ((instrument != -1 && !Sounds.valid(instrument)) || nextId < 1 || nextId > 999999999 || notes.size() > MAX
            || (instrument < 0 && !notes.isEmpty()))
            throw new IllegalArgumentException("Invalid instrument or too many notes (maximum 4096 per track).");
        HashSet<Integer> ids = new HashSet<Integer>();
        for (Note n : notes)
            if (n == null || n.id < 1 || n.id >= nextId || !ids.add(n.id) || n.key < LOW || n.key > HIGH
                || !(n.beat >= 0 && n.beat <= 100000) || !(n.length >= 1.0 / 64 && n.length <= 256)
                || n.beat + n.length > 100256 || !(n.velocity > 0 && n.velocity <= 1))
                throw new IllegalArgumentException("Notes need a valid pitch, beat, length and velocity.");
    }
    public void quantise(double grid) {
        if (!(grid >= 1.0 / 64 && grid <= 4))
            throw new IllegalArgumentException("Invalid note grid.");
        for (Note n : notes) {
            double end = Math.round((n.beat + n.length) / grid) * grid;
            n.beat = Math.round(n.beat / grid) * grid;
            n.length = Math.max(grid, end - n.beat);
        }
        validate();
    }
    public Map<String, Object> json() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("instrument", instrument);
        m.put("nextId", nextId);
        ArrayList<Object> ns = new ArrayList<Object>();
        for (Note n : notes) ns.add(n.json());
        m.put("notes", ns);
        if (!fx.empty())
            m.put("fx", fx.json());
        return m;
    }
    public static MidiNotes read(Object value) {
        if (!(value instanceof Map))
            throw new IllegalArgumentException("Damaged instrument notes.");Map<?,?> m=(Map<?,?>)value;
        MidiNotes out = new MidiNotes();
        out.instrument = integer(m.get("instrument"));
        out.nextId = integer(m.get("nextId"));
        if (!(m.get("notes") instanceof List) || ((List<?>) m.get("notes")).size() > MAX)
            throw new IllegalArgumentException("Damaged note list.");
        for (Object item : (List<?>) m.get("notes")) {
            if (!(item instanceof List) || ((List<?>) item).size() != 5)
                throw new IllegalArgumentException("Damaged note.");
            List<?> n = (List<?>) item;
            Note note = new Note();
            note.id = integer(n.get(0));
            note.key = integer(n.get(1));
            note.beat = number(n.get(2));
            note.length = number(n.get(3));
            note.velocity = (float) number(n.get(4));
            out.notes.add(note);
        }
        if (m.get("fx") != null)
            out.fx = MidiFx.Chain.read(m.get("fx"));
        out.validate();
        return out;
    }
    private static double number(Object v) {
        if (!(v instanceof Number))
            throw new IllegalArgumentException("Damaged note value.");
        return ((Number) v).doubleValue();
    }
    private static int integer(Object v) {
        double d = number(v);
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Damaged note number.");
        return (int) d;
    }
    /**
     * The notes as clips of their keys' samples, after the track's MIDI effects. A note rings for as long as it is
     * held plus the sound's release, up to the length of its sample. Notes stay editable, never baked in.
     */
    public ArrayList<Clip> clips(Project p) {
        ArrayList<Clip> clips = new ArrayList<Clip>();
        Sounds.Sound sound = Sounds.get(instrument);
        if (sound == null)
            return clips;
        TempoMap tempo = new TempoMap(p.rate, p.performance);
        long release = (long) p.rate * sound.releaseMs / 1000, longest = (long) (sound.seconds * p.rate);
        for (Note n : played(p)) {
            Clip c = new Clip();
            int layer = Sounds.layer(sound, n.velocity);
            c.id = "note" + n.id;
            c.file = Sounds.path(instrument, p.rate, n.key, layer);
            c.start = tempo.frameAt(n.beat);
            long held = tempo.frameAt(n.beat + n.length) - c.start;
            c.length = Math.max(1, Math.min(longest, held + release));
            c.fadeIn = Math.min(c.length, p.rate / 500);
            c.fadeOut = Math.min(c.length, Math.max(p.rate / 500, release));
            c.gainDb = Sounds.gainDb(sound, n.velocity);
            clips.add(c);
        }
        return clips;
    }
    /** The notes as heard: after the MIDI effects (each effect's randomness is seeded, so playback repeats). */
    public List<Note> played(Project p) {
        return fx.empty() ? notes : fx.process(notes, p == null ? 120 : p.performance.tempo);
    }
    public long end(Project p) {
        long end = 0;
        for (Clip c : clips(p)) end = Math.max(end, c.start + c.length);
        return end;
    }
}
