package ai.jackknife.killayue.studio;
import java.util.ArrayList;
import java.util.List;

/** Every MIDI effect, offline and live, plus the chain's save format. */
public final class MidiFxTest {
    static int passed;
    static void check(boolean b, String s) {
        if (!b)
            throw new AssertionError(s);
        passed++;
    }
    static MidiNotes.Note note(double beat, int key, double length, float vel) {
        MidiNotes.Note n = new MidiNotes.Note();
        n.beat = beat;
        n.key = key;
        n.length = length;
        n.velocity = vel;
        return n;
    }
    static List<MidiNotes.Note> chord(double beat, double length, int... keys) {
        ArrayList<MidiNotes.Note> out = new ArrayList<MidiNotes.Note>();
        for (int k : keys) out.add(note(beat, k, length, .8f));
        return out;
    }
    static String keys(List<MidiNotes.Note> ns) {
        StringBuilder b = new StringBuilder();
        for (MidiNotes.Note n : ns) b.append(b.length() == 0 ? "" : " ").append(n.key);
        return b.toString();
    }
    public static void main(String[] args) {
        MidiFx.Chain c = new MidiFx.Chain();
        List<MidiNotes.Note> in = chord(0, 1, 60, 64, 67);
        check(c.process(in, 120).size() == 3, "an empty chain passes notes through");

        // arpeggiator: up, sixteenths, one beat held → four steps over C E G
        MidiFx.Effect arp = c.add("arp");
        List<MidiNotes.Note> out = c.process(in, 120);
        check(keys(out).equals("60 64 67 60"), "arp up plays the held chord in sixteenths: " + keys(out));
        check(Math.abs(out.get(1).beat - .25) < 1e-9 && Math.abs(out.get(0).length - .2) < 1e-9, "arp step and gate");
        arp.p[0] = 1;
        check(keys(c.process(in, 120)).equals("67 64 60 67"), "arp down");
        arp.p[0] = 2;
        check(keys(c.process(chord(0, 1.5, 60, 64, 67), 120)).equals("60 64 67 64 60 64"), "arp up and down");
        arp.p[0] = 8;
        check(keys(c.process(chord(0, 1, 60, 64, 67), 120)).equals("60 64 60 67"), "arp thumb up");
        arp.p[0] = 0;
        arp.p[2] = 2;
        check(keys(c.process(chord(0, 1.5, 60, 64), 120)).equals("60 64 72 76 60 64"), "arp across two octaves");
        arp.p[2] = 1;
        arp.p[4] = 50;
        out = c.process(chord(0, 1, 60, 64), 120);
        check(Math.abs(out.get(1).beat - .375) < 1e-9 && Math.abs(out.get(2).beat - .5) < 1e-9, "arp swing delays every second step");
        arp.p[4] = 0;
        arp.p[5] = 1;
        out = c.process(chord(0, .5, 60, 64), 120);
        check(out.size() > 2 && out.size() < 16384, "latched arp keeps going after the keys are let go");
        arp.p[5] = 0;

        // chords
        c = new MidiFx.Chain();
        MidiFx.Effect chord = c.add("chord");
        check(keys(c.process(chord(0, 1, 60), 120)).equals("60 64 67"), "one finger plays a major triad");
        chord.p[0] = 4;
        check(keys(c.process(chord(0, 1, 62), 120)).equals("62 65 69 72"), "minor seventh");
        chord.p[0] = 19;
        check(keys(c.process(chord(0, 1, 62), 120)).equals("62 65 69"), "in key: D in C major is D minor");
        chord.p[0] = 0;
        chord.p[1] = 1;
        check(keys(c.process(chord(0, 1, 60), 120)).equals("64 67 72"), "first inversion");

        // scale lock
        c = new MidiFx.Chain();
        MidiFx.Effect scale = c.add("scale");
        check(keys(c.process(chord(0, 1, 61), 120)).equals("60"), "C♯ moves to C in C major (ties go down)");
        scale.p[2] = 1;
        check(keys(c.process(chord(0, 1, 61), 120)).equals("62"), "or up");
        scale.p[2] = 3;
        check(c.process(chord(0, 1, 61), 120).isEmpty(), "or is dropped");
        scale.p[2] = 0;
        scale.p[0] = 9;
        scale.p[1] = 10;  // A minor pentatonic
        scale.p[3] = 1;
        check(keys(c.process(chord(0, 1, 60, 62, 64, 65, 67, 69), 120)).equals("69 72 74 76 79 81"),
              "white keys from C4 play A minor pentatonic from A4");
        check(c.process(chord(0, 1, 61), 120).isEmpty(), "and black keys are quiet");

        // harmonizer: a third above in key
        c = new MidiFx.Chain();
        c.add("harmony");
        check(keys(c.process(chord(0, 1, 62), 120)).equals("62 65"), "a third above D in C major is F");

        // echo: three repeats at a dotted eighth, fading
        c = new MidiFx.Chain();
        c.add("echo");
        out = c.process(chord(0, .25, 60), 120);
        check(out.size() == 4 && Math.abs(out.get(3).beat - 2.25) < 1e-9 && out.get(3).velocity < out.get(1).velocity,
              "echo repeats in time and fades");

        // strum: down, 35 ms per string at 120 bpm = 0.07 beat
        c = new MidiFx.Chain();
        c.add("strum").p[2] = 0;
        out = c.process(chord(0, 1, 67, 60, 64), 120);
        check(keys(out).equals("60 64 67") && Math.abs(out.get(2).beat - .14) < 1e-9, "strum rolls low to high");

        // humanize stays repeatable, and only ever later
        c = new MidiFx.Chain();
        c.add("humanize");
        List<MidiNotes.Note> a = c.process(chord(0, 1, 60, 64), 120), b = c.process(chord(0, 1, 60, 64), 120);
        check(a.get(0).beat == b.get(0).beat && a.get(0).beat >= 0 && a.get(0).beat <= .025, "humanize is seeded");

        // velocity, transpose, range, length, chance, repeat, random, swing, mono
        c = new MidiFx.Chain();
        MidiFx.Effect v = c.add("velocity");
        v.p[0] = 1;
        check(Math.abs(c.process(chord(0, 1, 60), 120).get(0).velocity - .9f) < 1e-6, "fixed velocity");
        c = new MidiFx.Chain();
        c.add("transpose").p[1] = -1;
        check(keys(c.process(chord(0, 1, 60), 120)).equals("48"), "octave down");
        c = new MidiFx.Chain();
        MidiFx.Effect range = c.add("range");
        range.p[0] = 48;
        range.p[1] = 72;
        check(keys(c.process(chord(0, 1, 84, 36), 120)).equals("48 72"), "fold into range");
        c = new MidiFx.Chain();
        c.add("length");
        check(Math.abs(c.process(chord(0, 3, 60), 120).get(0).length - .25) < 1e-9, "fixed sixteenth length");
        c = new MidiFx.Chain();
        MidiFx.Effect legato = c.add("length");
        legato.p[0] = 2;
        ArrayList<MidiNotes.Note> line = new ArrayList<MidiNotes.Note>();
        line.add(note(0, 60, .1, .8f));
        line.add(note(1, 62, .1, .8f));
        check(Math.abs(c.process(line, 120).get(0).length - 1) < 1e-9, "legato holds to the next note");
        c = new MidiFx.Chain();
        MidiFx.Effect chance = c.add("chance");
        chance.p[0] = 100;
        chance.p[1] = 2;
        ArrayList<MidiNotes.Note> eight = new ArrayList<MidiNotes.Note>();
        for (int i = 0; i < 8; i++) eight.add(note(i, 60 + i, .5, .8f));
        check(c.process(eight, 120).size() == 4, "every second note");
        c = new MidiFx.Chain();
        c.add("repeat");
        check(c.process(chord(0, 1, 42), 120).size() == 4, "note repeat while held, sixteenths");
        c = new MidiFx.Chain();
        MidiFx.Effect roll = c.add("repeat");
        roll.p[0] = 11;
        roll.p[2] = 3;
        out = c.process(chord(0, .01, 42), 120);
        check(out.size() == 3 && Math.abs(out.get(2).beat - 1 / 6.0) < 1e-9, "a fixed triplet roll, even for a tap");
        c = new MidiFx.Chain();
        c.add("random").p[0] = 100;
        for (MidiNotes.Note n : c.process(eight, 120)) check(n.key != 60 || n.beat != 0, "random moves every note");
        c = new MidiFx.Chain();
        c.add("swing").p[1] = 50;
        ArrayList<MidiNotes.Note> straight = new ArrayList<MidiNotes.Note>();
        for (int i = 0; i < 4; i++) straight.add(note(i * .25, 60, .2, .8f));
        out = c.process(straight, 120);
        check(out.get(0).beat == 0 && Math.abs(out.get(1).beat - .375) < 1e-9, "swing pushes the off-beat sixteenth");
        c = new MidiFx.Chain();
        c.add("mono");
        ArrayList<MidiNotes.Note> overlap = new ArrayList<MidiNotes.Note>();
        overlap.add(note(0, 48, 2, .8f));
        overlap.add(note(1, 55, .5, .8f));
        out = c.process(overlap, 120);
        check(keys(out).equals("48 55 48") && Math.abs(out.get(0).length - 1) < 1e-9, "mono: last note wins, returns to the held one");

        // chains in order: chord then arp
        c = new MidiFx.Chain();
        c.add("chord");
        c.add("arp");
        check(keys(c.process(chord(0, 1, 60), 120)).equals("60 64 67 60"), "chord into arpeggiator");

        // save format
        MidiFx.Chain saved = MidiFx.Chain.read(Json.parse(Json.write(c.json())));
        check(saved.effects.size() == 2 && saved.effects.get(1).kind.equals("arp"), "chain round trip");
        boolean refused = false;
        try {
            java.util.List<Object> bad = c.json();
            ((java.util.List<Object>) ((java.util.Map<String, Object>) bad.get(0)).get("p")).set(0, 999.0);
            MidiFx.Chain.read(bad);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "an out-of-range setting is refused");
        for (int i = 0; i < MidiFx.PRESETS.length; i++) {
            List<MidiNotes.Note> p = MidiFx.preset(i).process(chord(0, 2, 60, 64, 67), 110);
            check(!p.isEmpty() || i == 9, "preset " + MidiFx.PRESETS[i] + " plays");
        }
        for (String k : MidiFx.KINDS) check(MidiFx.make(k).params().length > 0 && !MidiFx.about(k).isEmpty(), k);

        // a track with effects: playback, the project file, and an unchanged score
        Project proj = new Project("Fx", 48000);
        Track t = proj.addTrack("Keys");
        t.notes.instrument = 16;
        t.notes.add(0, 60).length = 1;
        t.notes.fx.add("chord");
        proj.validate();
        Project again = Project.fromText(proj.toText());
        check(again.tracks.get(0).notes.fx.effects.size() == 1, "effects saved with the track");
        check(again.tracks.get(0).notes.notes.size() == 1 && again.tracks.get(0).notes.clips(again).size() == 3,
              "notes stay as written; playback hears the chord");
        check(again.tracks.get(0).notes.clips(again).get(0).file.startsWith(SoundPacks.FOLDER), "acoustic notes come from the shared packs");

        // live: the same arp, stepped in real time
        c = new MidiFx.Chain();
        c.add("arp");
        MidiFx.Chain.Live live = c.new Live(120);
        live.note(0, 60, .8f, true);
        live.note(0, 64, .8f, true);
        int ons = 0;
        for (double now = 0; now <= 1.001; now += .01)
            for (int[] s : live.poll(now)) ons += s[2];
        live.note(1.01, 60, 0, false);
        live.note(1.01, 64, 0, false);
        int offs = 0;
        for (int[] s : live.poll(1.5)) offs += s[2] == 0 ? 1 : 0;
        check(ons == 5, "live arp steps on time: " + ons);
        check(live.due() == Double.POSITIVE_INFINITY && offs <= 1, "live arp stops when the keys are let go");
        System.out.println("MidiFxTest: " + passed + " checks passed");
    }
}
