package ai.jackknife.killayue.studio;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Designed sounds render cleanly, packs pitch roots to keys, and MIDI files go in and out. */
public final class SoundsTest {
    static int passed;
    static void check(boolean b, String s) {
        if (!b)
            throw new AssertionError(s);
        passed++;
    }
    static float peak(File f) throws Exception {
        try (WavIn in = WavIn.open(f)) {
            float[] v = new float[(int) in.frames * in.channels];
            in.read(0, v, (int) in.frames);
            float p = 0;
            for (float x : v) {
                if (Float.isNaN(x) || Float.isInfinite(x))
                    return -1;
                p = Math.max(p, Math.abs(x));
            }
            return p;
        }
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("killa-sounds").toFile();
        File project = new File(dir, "song");
        project.mkdirs();
        // every designed sound, low, middle and high, every layer
        for (Sounds.Sound s : Sounds.ALL) {
            if (s.acoustic())
                continue;
            for (int key : new int[] {21, 36, 60, 96, 108})
                for (int layer = 0; layer < s.layers; layer++) {
                    float p = peak(Sounds.ensure(project, 48000, s.id, key, layer));
                    check(p >= 0 && p <= 1, s.name + " " + key + " stays finite and unclipped (" + p + ")");
                    if (key >= 36 && key <= 96)
                        check(p > .02, s.name + " " + key + " layer " + layer + " is audible (" + p + ")");
                }
        }
        check(Sounds.path(0, 48000, 60, 0).equals(InstrumentSamples.path(0, 48000, 60)), "old projects keep their Pluck files");
        for (int k = 35; k <= 59; k++) check(peak(Sounds.ensure(project, 44100, 7, k, 1)) > .01, "drum key " + k + " sounds");

        // an acoustic pack, with a fake decoder that only copies the root WAVs
        File root = new File(new File(dir, ".killa-sounds"), "grand/source");
        root.mkdirs();
        StringBuilder roots = new StringBuilder("{\"roots\":[");
        int[] keys = {48, 60, 72};
        for (int i = 0; i < keys.length; i++)
            for (int layer = 0; layer < 3; layer++) {
                WavOut w = WavOut.create(new File(root, keys[i] + "-" + layer + ".wav"), 48000, 2);
                short[] tone = new short[48000 * 2];
                double hz = 440 * Math.pow(2, (keys[i] - 69) / 12.0);
                for (int f = 0; f < 48000; f++)
                    tone[2 * f] = tone[2 * f + 1] = (short) (8000 * (layer + 1) * Math.sin(2 * Math.PI * hz * f / 48000));
                w.write(tone, 48000);
                w.finish();
                roots.append(i + layer == 0 ? "" : ",").append("{\"key\":").append(keys[i]).append(",\"layer\":")
                    .append(layer).append(",\"file\":\"").append(keys[i]).append("-").append(layer).append(".flac\"}");
            }
        Files.write(new File(root, "pack.json").toPath(), (roots + "]}").getBytes("UTF-8"));
        Sounds.Sound grand = Sounds.get(16);
        check(SoundPacks.installed(project, grand), "pack installed beside the projects");
        File a4 = Sounds.ensure(project, 48000, 16, 69, 2);
        try (WavIn in = WavIn.open(a4)) {
            float[] v = new float[(int) in.frames * 2];
            in.read(0, v, (int) in.frames);
            int crossings = 0;
            for (int f = 1000; f < 25000; f++)
                if (v[2 * (f - 1)] < 0 && v[2 * f] >= 0)
                    crossings++;
            // A4 pitched down from C5: 440 Hz over 24,000 frames at 48 kHz is 220 cycles
            check(Math.abs(crossings - 220) <= 2, "A4 pitched from the C5 root sounds at 440 Hz (" + crossings + ")");
            check(in.frames > 56000 && in.frames < 58000, "pitching down lengthens the recording");
        }
        check(SoundPacks.nearest(SoundPacks.roots(root), 66, 0).key == 72, "a tie goes to the root above");
        final int[] seen = {0};
        Sounds.bank(project, 48000, 16, new Sounds.Progress() {
            public void at(int done, int total) { seen[0] = total; }
        }, new AtomicBoolean());
        check(seen[0] == 88 * 3, "the whole bank renders, four at a time");
        check(Sounds.files(project, 48000, 16).length == 264 && Sounds.files(project, 48000, 16)[263].isFile(), "every key and layer");
        Project p = new Project("Packs", 48000);
        Track t = p.addTrack("Piano");
        t.notes.instrument = 16;
        t.notes.add(0, 60).velocity = .2f;
        t.notes.add(1, 60).velocity = 1f;
        ArrayList<Clip> clips = t.notes.clips(p);
        check(clips.get(0).file.endsWith("60-0.wav") && clips.get(1).file.endsWith("60-2.wav"), "velocity picks the layer");
        check(new File(project, clips.get(1).file).isFile(), "clips name the shared pack from inside a project");

        // the beat model's restyle input: 44.1 kHz stereo float, at most the length asked for
        File beatIn = new File(dir, "beat-in.wav");
        SoundPacks.toBeatInput(a4, beatIn, 1);
        try (WavIn in = WavIn.open(beatIn)) {
            check(in.rate == 44100 && in.channels == 2 && in.floats && in.frames == 44100, "restyle input converted");
        }

        // MIDI files: write a project, read it back
        Project song = new Project("MIDI out", 48000);
        song.performance.tempo = 96;
        Track keysTrack = song.addTrack("Keys");
        keysTrack.notes.instrument = 2;
        for (int i = 0; i < 8; i++) {
            MidiNotes.Note n = keysTrack.notes.add(i * .5, 60 + i);
            n.length = .5;
            n.velocity = (i + 1) / 8f;
        }
        Track drums = song.addTrack("Beat");
        drums.notes.instrument = 7;
        drums.notes.add(0, 36);
        drums.notes.add(1, 38);
        byte[] file = Smf.write(song, false);
        Smf.Song back = Smf.read(file);
        check(Math.abs(back.tempo - 96) < .01, "tempo round trip");
        check(back.parts.size() == 2 && back.parts.get(0).name.equals("Keys") && back.parts.get(0).notes.size() == 8, "parts and names");
        check(Math.abs(back.parts.get(0).notes.get(3).beat - 1.5) < 1e-9 && Math.abs(back.parts.get(0).notes.get(3).length - .5) < 1e-9, "beats and lengths");
        check(Math.abs(back.parts.get(0).notes.get(7).velocity - 1) < .01, "velocity");
        check(back.parts.get(1).drums() && Smf.sound(back.parts.get(1)) == 7, "drums on channel 10 get the kit");
        check(Smf.sound(back.parts.get(0)) == 2, "an electric piano program gets Killa Keys");
        MidiNotes imported = Smf.notes(back.parts.get(0), 2);
        check(imported.notes.size() == 8, "imported notes are valid track notes");
        keysTrack.notes.fx.add("chord");
        check(Smf.read(Smf.write(song, true)).parts.get(0).notes.size() == 24, "export as heard includes the effects");
        // format 0 with running status and note-on velocity 0
        byte[] f0 = {'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0, 96, 'M', 'T', 'r', 'k', 0, 0, 0, 20,
                     0, (byte) 0x90, 60, 100, 0, 64, 90, 96, 60, 0, 0, 64, 0, 0, (byte) 0xC1, 5, 0, (byte) 0xFF, 0x2F, 0};
        Smf.Song zero = Smf.read(f0);
        check(zero.parts.size() == 1 && zero.parts.get(0).notes.size() == 2 && zero.parts.get(0).notes.get(0).length == 1,
              "format 0, running status, zero-velocity offs");
        boolean refused = false;
        try {
            Smf.read("not midi at all".getBytes("UTF-8"));
        } catch (java.io.IOException e) {
            refused = true;
        }
        check(refused, "a non-MIDI file is refused");
        Projects.delete(dir);
        System.out.println("SoundsTest: " + passed + " checks passed");
    }
}
