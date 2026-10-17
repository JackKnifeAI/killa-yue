package ai.jackknife.killayue.studio;
import java.io.File;
import java.util.ArrayList;
public final class MidiNotesTest {
    static int passed;
    static void check(boolean b, String s) {
        if (!b)
            throw new AssertionError(s);
        passed++;
    }
    public static void main(String[] args) throws Exception {
        Project p = new Project("Notes", 48000);
        Track t = p.addTrack("Pluck");
        t.notes.instrument = 0;
        MidiNotes.Note n = t.notes.add(.12, 60);
        n.length = 1.13;
        p.validate();
        check(p.toText().equals(Project.fromText(p.toText()).toText()), "note round trip");
        MidiNotes next = t.notes.copy();
        next.quantise(.25);
        check(next.notes.get(0).beat == 0 && next.notes.get(0).length == 1.25, "quantise start and end");
        History h = new History(p);
        h.run(new NotesEdit(p, t.id, next, "Quantise"));
        h.undo();
        check(p.track(t.id).notes.notes.get(0).beat == .12, "undo note transaction");
        h.redo();
        check(p.track(t.id).notes.notes.get(0).beat == 0, "redo note transaction");
        Project reopened = Project.fromText(p.toText());
        History saved = History.fromJson(Json.parse(Json.write(h.toJson("test"))), reopened);
        saved.undo();
        check(
            reopened.track(t.id).notes.notes.get(0).beat == .12, "saved undo restores editable notes after reopening");
        java.util.Map<String, Object> old = p.toJson();
        old.put("version", 7);
        for (Object track : (java.util.List<?>) old.get("tracks"))((java.util.Map<?,?>)track).remove("notes");
        check(Project.fromText(Json.write(old)).tracks.get(0).notes.instrument == -1,
            "format 7 migrates with no instrument processing");
        p.performance.tempos.put(24000L, 60.0);
        Clip c = p.track(t.id).notes.clips(p).get(0);
        check(c.start == 0 && c.length == 39840, "tempo-aware note end plus release");
        check(p.renderFrames() == 39840, "notes contribute to full render length");
        h.run(Edit.removeTrack(t.id));
        check(p.tracks.isEmpty(), "remove note track");
        h.undo();
        check(p.track(t.id).notes.notes.size() == 1, "restore notes with track");
        MidiNotes broken = next.copy();
        broken.notes.get(0).velocity = Float.NaN;
        try {
            broken.validate();
            throw new AssertionError("NaN");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        final ArrayList<String> events = new ArrayList<String>();
        MidiInput input = new MidiInput(new MidiInput.Target() {
            public void note(int ch, int key, int v, boolean on) {
                events.add(ch + ":" + key + ":" + v + ":" + on);
            }
            public void control(int ch, int c, int v) {
                events.add("cc:" + ch + ":" + c + ":" + v);
            }
        });
        input.receive(new byte[] {(byte) 0x92, 60}, 0, 2);
        input.receive(new byte[] {100, 61, (byte) 0xf8, 0, 62, 90}, 0, 6);
        check(events.size() == 3 && events.get(0).equals("2:60:100:true") && events.get(1).equals("2:61:0:false")
                && events.get(2).equals("2:62:90:true"),
            "split packets, running status, realtime, note-on zero");
        input.receive(
            new byte[] {(byte) 0xf0, 1, 2, (byte) 0xf8, 3, (byte) 0xf7, 63, 100, (byte) 0xb2, 64, 127}, 0, 11);
        check(
            events.size() == 4 && events.get(3).equals("cc:2:64:127"), "sysex clears running status, control decoding");
        input.reset();
        input.receive(new byte[] {64, 100}, 0, 2);
        check(events.size() == 4, "reset drops incomplete status");
        File root = new File(System.getProperty("java.io.tmpdir"), "samples-test-" + System.nanoTime());
        root.mkdirs();
        try {
            File file = InstrumentSamples.ensure(root, 48000, 0, 60);
            WavIn wav = WavIn.open(file);
            try {
                check(wav.frames == 192000 && wav.channels == 1 && wav.rate == 48000,
                    "original sample format and duration");
                float[] b = new float[4096];
                wav.read(0, b, 4096);
                double e = 0;
                for (float v : b) e += v * v;
                check(e > 1 && e < 4096, "sample has finite audible energy");
            } finally {
                wav.close();
            }
            check(file.equals(InstrumentSamples.ensure(root, 48000, 0, 60)), "existing sample reused");
        } finally {
            Projects.delete(root);
        }
        System.out.println("MidiNotesTest: " + passed + " passed");
    }
}
