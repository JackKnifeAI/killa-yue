package ai.jackknife.killayue.studio;
public class PlayAlongTest {
    static void check(boolean b, String m) {
        if (!b)
            throw new AssertionError(m);
    }
    public static void main(String[] a) {
        Project p = new Project("Session", 48000);
        Track t = p.addTrack("Vocal");
        Clip c = p.addClip(t, "audio/take.wav", 24000, 100, 96000);
        History h = new History(p);
        h.run(ClipEdits.split(p, c.id, 48000));
        check(p.tracks.get(0).clips.size() == 2, "split");
        check(p.tracks.get(0).clips.get(1).offset == 24100, "split source");
        h.undo();
        check(p.clip(c.id).length == 96000, "undo split");
        h.redo();
        check(p.tracks.get(0).clips.size() == 2, "redo split");
        Project q = Project.fromText(p.toText());
        check(q.performance.tempo == 120, "tempo round trip");
        check(BeatGrid.snap(13000, 48000, 120, 4) == 12000, "sixteenth snap");
        check(BeatGrid.stepFrame(1, 48000, 120, 0.5) == 9000, "swing");
        check(TakePlacement.start(1000, 2000, 2500, 240) == 1260, "clock placement");
        check(TakePlacement.passes(0, 96000, 24000, 48000).size() == 3, "loop passes incl lead");
        try {
            ClipEdits.trim(p, c.id, 0, 999999);
            throw new AssertionError("trim range");
        } catch (IllegalArgumentException ok) {
        }
        p.performance.pads[3].file = "audio/take.wav";
        p.performance.pads[3].length = 100;
        p.performance.steps[3][2] = 0.7f;
        q = Project.fromText(p.toText());
        check(q.performance.steps[3][2] == 0.7f, "pattern saved");
        check(q.performance.pads[3].length == 100, "pad saved");
        // Durable history preserves performance edits across reopening, not just an in-memory undo.
        History restored = History.fromJson(Json.parse(Json.write(h.toJson("test"))), p.copy());
        check(restored.undo(), "saved snapshot undo");
        check(restored.redo(), "saved snapshot redo");
        Project takeProject = new Project("Comp", 48000);
        Track aTrack = takeProject.addTrack("Take");
        aTrack.role = "take";
        Clip aClip = takeProject.addClip(aTrack, "takes/a.wav", 0, 0, 96000);
        History compHistory = new History(takeProject);
        compHistory.run(ClipEdits.comp(takeProject, aClip.id, 12000, 36000));
        check(takeProject.tracks.get(1).clips.get(0).offset == 12000, "comp source offset");
        check(takeProject.track(aTrack.id).mute, "comp mutes original");
        compHistory.undo();
        check(takeProject.tracks.size() == 1 && !takeProject.track(aTrack.id).mute, "undo comp");
        TakeJournal journal = new TakeJournal();
        journal.file = "takes/b.wav";
        journal.track = aTrack.id;
        journal.start = 0;
        journal.first = 1000;
        journal.anchor = 2000;
        journal.latency = 200;
        Edit takeEdit = journal.add(takeProject, 4800);
        compHistory.run(takeEdit);
        Clip placed = takeProject.track(aTrack.id).clips.get(1);
        check(placed.start == 0 && placed.offset == 1200 && placed.length == 3600, "negative take start trimmed");
        check(journal.add(takeProject, 4800) == null, "receipt replay does not duplicate recording");
        journal.file = "takes/count-in.wav";
        journal.start = 96000;
        journal.first = 0;
        journal.anchor = 96000;
        journal.latency = 0;
        compHistory.run(journal.add(takeProject, 192000));
        Clip counted = takeProject.track(aTrack.id).clips.get(2);
        check(counted.start == 96000 && counted.offset == 96000 && counted.length == 96000, "count-in excluded when starting mid-song");
        Project overlaps = new Project("Overlap", 48000);
        Track lane = overlaps.addTrack("Audio");
        Clip left = overlaps.addClip(lane, "audio/a.wav", 0, 0, 48000);
        Clip right = overlaps.addClip(lane, "audio/b.wav", 48000, 0, 48000);
        History cross = new History(overlaps);
        cross.run(ClipEdits.move(overlaps, right.id, 36000));
        check(overlaps.clip(left.id).fadeOut == 12000 && overlaps.clip(right.id).fadeIn == 12000, "automatic overlap crossfade");
        cross.undo();
        check(overlaps.clip(left.id).fadeOut == 0, "undo automatic crossfade");
        Project many = new Project("Long loop take", 48000);
        for (int i = 0; i < 63; i++)
            many.addTrack("Track " + i);
        TakeJournal longTake = new TakeJournal();
        longTake.track = many.tracks.get(0).id;
        longTake.file = "takes/long.wav";
        longTake.loop = true;
        longTake.loopEnd = 48000;
        new History(many).run(longTake.add(many, 48000L * 300));
        check(many.tracks.size() == 64 && many.tracks.get(63).clips.size() == 299, "long takes stay inside engine track limit");
        check(many.tracks.get(63).mute && many.tracks.get(63).clips.get(298).offset == 48000L * 299, "overflow audio preserved");
        Performance tempo = new Performance();
        tempo.tempos.put(96000L, 60.0);
        TempoMap map = new TempoMap(48000, tempo);
        check(map.frameAt(4.25) == 108000, "tempo segment frame");
        check(Math.abs(map.beatAt(108000) - 4.25) < 1e-12, "tempo segment beat");
        check(map.snap(109000, 4) == 108000, "tempo-aware snap");
        System.out.println("PlayAlongTest passed");
    }
}
