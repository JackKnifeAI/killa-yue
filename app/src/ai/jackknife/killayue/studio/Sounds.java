package ai.jackknife.killayue.studio;
import java.io.File;
import java.io.IOException;

/**
 * Every sound an instrument track can play. Designed sounds are synthesised on the phone (InstrumentSamples);
 * acoustic sounds come from a downloaded pack of real recordings (SoundPacks), pitched to all 88 keys once and
 * then streamed from storage like every other sample. A track keeps the sound's number, so numbers never change.
 */
public final class Sounds {
    private Sounds() {}
    public static final class Sound {
        public final int id, layers, releaseMs;
        /** Longest a note may ring, in seconds: the length of each key's sample. */
        public final float seconds;
        public final String name, group, blurb;
        /** The pack's id for an acoustic sound; null for a designed one. */
        public final String pack;
        Sound(int id, String group, String name, String pack, int layers, int releaseMs, float seconds, String blurb) {
            this.id = id;
            this.group = group;
            this.name = name;
            this.pack = pack;
            this.layers = layers;
            this.releaseMs = releaseMs;
            this.seconds = seconds;
            this.blurb = blurb;
        }
        public boolean acoustic() { return pack != null; }
    }
    public static final Sound[] ALL = {
        new Sound(0, "Designed", "Killa Pluck", null, 1, 80, 4, "A bright harmonic pluck."),
        new Sound(1, "Designed", "Killa Tines", null, 1, 80, 4, "Bell-like tines, like a music box."),
        new Sound(2, "Designed", "Killa Keys", null, 2, 220, 6, "A warm electric piano; harder playing barks."),
        new Sound(3, "Designed", "Killa 808", null, 1, 120, 4, "A deep 808 bass with a pitch drop and grit."),
        new Sound(4, "Designed", "Killa Pad", null, 1, 900, 8, "A wide, slow analog pad."),
        new Sound(5, "Designed", "Killa Bass", null, 2, 90, 3, "A round synth bass; harder playing opens the filter."),
        new Sound(6, "Designed", "Killa Lead", null, 1, 160, 5, "A singing saw lead with vibrato."),
        new Sound(7, "Designed", "Killa Drums", null, 2, 60, 3,
                  "A drum kit on the General MIDI keys: kick C2, snare D2, clap D♯2, hats F♯2 and A♯2, crash C♯3."),
        new Sound(16, "Piano", "Grand Piano", "grand", 3, 260, 7, "A Yamaha C5 grand, three velocity layers."),
        new Sound(17, "Piano", "Upright Piano", "upright", 2, 220, 6, "A warm, close upright piano."),
        new Sound(18, "Piano", "Bright Upright", "upright-bright", 2, 220, 6, "A brighter, punchier upright."),
        new Sound(19, "Keys", "Organ", "organ", 1, 140, 6, "A pipe organ."),
        new Sound(20, "Strings", "String Ensemble", "strings", 1, 600, 6, "Violins, violas and cellos, bowed with vibrato."),
        new Sound(21, "Strings", "Pizzicato Strings", "pizzicato", 1, 200, 3, "Plucked string section."),
        new Sound(22, "Strings", "Harp", "harp", 1, 500, 6, "A concert harp."),
        new Sound(23, "Woodwinds", "Flute", "flute", 1, 260, 5, "A flute with gentle vibrato."),
        new Sound(24, "Woodwinds", "Clarinet", "clarinet", 1, 220, 5, "A woody clarinet."),
        new Sound(25, "Brass", "Trumpet", "trumpet", 1, 200, 5, "A bright trumpet."),
        new Sound(26, "Brass", "French Horn", "horn", 1, 300, 5, "A round French horn."),
        new Sound(27, "Brass", "Trombone", "trombone", 1, 240, 5, "A tenor trombone."),
        new Sound(28, "Mallets", "Marimba", "marimba", 1, 300, 4, "A rosewood marimba."),
        new Sound(29, "Mallets", "Glockenspiel", "glockenspiel", 1, 600, 4, "Bright steel bars."),
        new Sound(30, "Mallets", "Xylophone", "xylophone", 1, 200, 3, "Hard, short wooden bars."),
    };
    public static Sound get(int id) {
        for (Sound s : ALL)
            if (s.id == id)
                return s;
        return null;
    }
    public static boolean valid(int id) { return get(id) != null; }
    /** A designed sound standing in for an acoustic one whose pack is not downloaded. */
    public static int fallback(int id) {
        Sound s = get(id);
        if (s == null || !s.acoustic())
            return id;
        if (s.group.equals("Piano") || s.group.equals("Keys"))
            return 2;
        if (s.group.equals("Mallets"))
            return 1;
        if (id == 21 || id == 22)
            return 0;
        if (s.group.equals("Brass") || s.group.equals("Woodwinds"))
            return 6;
        return 4;
    }
    /** The velocity layer a note plays: each layer covers an equal share of the velocity range, softest first. */
    public static int layer(Sound s, float velocity) {
        return Math.max(0, Math.min(s.layers - 1, (int) (velocity * s.layers)));
    }
    /** The level inside the chosen layer, as the native keyboard plays it (keyboard.cpp layerGain). */
    public static float gainDb(Sound s, float velocity) {
        if (s.layers <= 1)
            return (float) (20 * Math.log10(Math.max(1e-4, velocity)));
        double span = 1.0 / s.layers, within = velocity >= 1 ? 1 : (velocity % span) / span;
        return (float) ((within - 1) * 6);
    }
    /** Project-relative file for one key and layer. Designed sounds keep their original v1 paths. */
    public static String path(int id, int rate, int key, int layer) {
        Sound s = get(id);
        if (s == null)
            throw new IllegalArgumentException("Unknown sound.");
        if (!s.acoustic())
            return id <= 1 ? InstrumentSamples.path(id, rate, key)
                           : "audio/instruments-v2/" + rate + "/" + id + "-" + key + "-" + layer + ".wav";
        return SoundPacks.path(s, rate, key, layer);
    }
    /** Makes sure one key's sample exists (renders it if needed). Acoustic sounds need their pack installed. */
    public static File ensure(File dir, int rate, int id, int key, int layer) throws IOException {
        Sound s = get(id);
        if (s == null)
            throw new IOException("Unknown sound.");
        if (!s.acoustic())
            return InstrumentSamples.ensure(dir, rate, id, key, layer);
        return SoundPacks.ensure(dir, rate, s, key, layer);
    }
    public interface Progress {
        void at(int done, int total);
    }
    /** Every key and layer, for the live keyboard. Off the UI thread. */
    public static void bank(File dir, int rate, int id, Progress progress, java.util.concurrent.atomic.AtomicBoolean cancel)
        throws IOException {
        final Sound s = get(id);
        if (s == null)
            throw new IOException("Unknown sound.");
        if (s.acoustic()) {
            SoundPacks.bank(dir, rate, s, progress, cancel);
            return;
        }
        int total = MidiNotes.KEYS * s.layers, done = 0;
        for (int key = MidiNotes.LOW; key <= MidiNotes.HIGH; key++)
            for (int layer = 0; layer < s.layers; layer++) {
                if (cancel.get())
                    throw new IOException("Cancelled.");
                InstrumentSamples.ensure(dir, rate, id, key, layer);
                progress.at(++done, total);
            }
    }
    /** Every key and layer's file, key-major, as NativeEngine.keyboard loads them. */
    public static File[] files(File dir, int rate, int id) {
        Sound s = get(id);
        File[] out = new File[MidiNotes.KEYS * s.layers];
        for (int key = MidiNotes.LOW; key <= MidiNotes.HIGH; key++)
            for (int layer = 0; layer < s.layers; layer++)
                out[(key - MidiNotes.LOW) * s.layers + layer] = new File(dir, path(id, rate, key, layer));
        return out;
    }
}
