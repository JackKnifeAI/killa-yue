package ai.jackknife.killayue.studio;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
public final class NativeInstrumentsTest {
    public static int passed;
    static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
        passed++;
    }
    static float[] read(File file) throws Exception {
        WavIn w = WavIn.open(file);
        try {
            float[] samples = new float[(int) w.frames * w.channels];
            check(w.read(0, samples, (int) w.frames) == w.frames, "read complete instrument render");
            return samples;
        } finally {
            w.close();
        }
    }
    static double energy(float[] samples, int first, int end) {
        double e = 0;
        for (int i = first; i < Math.min(end, samples.length); i++) e += samples[i] * samples[i];
        return e;
    }
    public static void main(String[] args) throws Exception {
        NativeEngine.load();
        File dir = new File(System.getProperty("java.io.tmpdir"), "instruments-jni-" + System.nanoTime());
        dir.mkdirs();
        try {
            Project p = new Project("Piano", 48000);
            Track t = p.addTrack("Pluck");
            t.notes.instrument = 0;
            MidiNotes.Note note = t.notes.add(1, 60);
            note.length = 1;
            note.velocity = .7f;
            p.performance.tempos.put(24000L, 60.0);
            p.validate();
            String before = p.toText();
            File out = new File(dir, "notes.wav");
            NativeEngine e = NativeEngine.offline(p.rate);
            try {
                e.load(p, dir);
                e.export(out, p.renderFrames(), true);
                float[] first = read(out);
                check(energy(first, 0, 47000) < 1e-12, "note onset follows beats with silent lead-in");
                check(energy(first, 50000, 80000) > 1, "scheduled instrument is audible");
                check(first.length == 151680, "tempo-map note end and release included in export");
                e.export(out, p.renderFrames(), true);
                check(java.util.Arrays.equals(first, read(out)), "repeated note exports are deterministic");
                check(before.equals(p.toText()), "rendering preserves the editable project");
                t.mute = true;
                e.load(p, dir);
                e.export(out, 80000, true);
                check(energy(read(out), 0, 160000) == 0, "note tracks obey mixer mute");
                t.mute = false;
                t.notes.instrument = 1;
                t.pan = -1;
                e.load(p, dir);
                e.export(out, p.renderFrames(), true);
                float[] pan = read(out);
                double left = 0, right = 0;
                for (int i = 0; i < pan.length; i += 2) {
                    left += pan[i] * pan[i];
                    right += pan[i + 1] * pan[i + 1];
                }
                check(left > 1 && right < 1e-10, "second sampled instrument obeys track pan");
            } finally {
                e.close();
            }
            InstrumentSamples.bank(dir, 48000, 0, new InstrumentSamples.Progress() {
                public void at(int done, int total) {}
            }, new AtomicBoolean());
            e = NativeEngine.offline(48000);
            try {
                e.keyboard(dir, 0);
                check(e.note(60, .8f, true), "live key accepted while stopped");
                float[] block = new float[1024];
                e.render(block, 512);
                check(energy(block, 0, 1024) > 1e-5, "live streamed keyboard reaches output while stopped");
                check(!e.note(12, 1, true), "unsupported keyboard pitch rejected");
                check(e.note(60, 0, false), "live note release accepted");
                for (int i = 0; i < 16; i++) {
                    e.render(block, 512);
                    Thread.sleep(1);
                }
                check(energy(block, 0, 1024) < 1e-10, "live release drains to silence");
                e.note(64, .7f, true);
                e.note(67, .7f, true);
                e.render(block, 512);
                e.notesOff();
                for (int i = 0; i < 4; i++) e.render(block, 512);
                check(energy(block, 0, 1024) < 1e-10, "all notes off clears a chord");
            } finally {
                e.close();
            }
            Project p44 = new Project("44k", 44100);
            Track t44 = p44.addTrack("Tines");
            t44.notes.instrument = 1;
            t44.notes.add(0, 69);
            e = NativeEngine.offline(44100);
            try {
                e.load(p44, dir);
                e.export(out, p44.renderFrames(), true);
                check(energy(read(out), 0, 40000) > 1, "samples generated and streamed at project rate");
            } finally {
                e.close();
            }
        } finally {
            Projects.delete(dir);
        }
        System.out.println("NativeInstrumentsTest: " + passed + " passed");
    }
}
