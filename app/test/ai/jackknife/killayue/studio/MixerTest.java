package ai.jackknife.killayue.studio;

public final class MixerTest {
    static int passed;
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); passed++; }
    static void invalid(Project p, String why) {
        try { p.validate(); throw new AssertionError(why); } catch (IllegalArgumentException expected) { passed++; }
    }
    public static void main(String[] args) {
        Project p = new Project("Mixer", 48000);
        Track a = p.addTrack("Drums"), b = p.addTrack("Vocal");
        check(p.mixer.get(a.id).reverb == 0 && p.mixer.strips.size() == 3, "reading a dry track does not mutate it");
        History history = new History(p);
        Mixer m = p.mixer.copy();
        String group = m.addGroup("Band");
        m.edit(a.id).route = group;
        m.edit(a.id).reverb = .25f;
        m.edit(group).delay = .5f;
        m.addSlot(a.id, 1).value = -6;
        m.addSlot(Mixer.MASTER, 2).value = .5f;
        history.run(new MixerEdit(p, m, "Build mixer", null));
        check(p.toText().equals(Project.fromText(p.toText()).toText()), "routing and slot IDs round trip");
        float[] packed = p.mixer.nativeData(p);
        check(packed[1] == 1 && packed[2+6] == 0 && packed[2+Mixer.STRIDE+6] == -1, "track indices route to groups or master");
        check(packed[2+64*Mixer.STRIDE+5] == .5f && packed[2+74*Mixer.STRIDE+8] == 2, "group sends and master chain pack");
        p.mixer.edit(group).solo = true;
        check(p.audible(a) && !p.audible(b), "group solo includes members only");
        p.mixer.edit(group).mute = true;
        check(!p.audible(a), "group mute wins over solo");
        p.mixer.edit(group).mute = p.mixer.edit(group).solo = false;
        b.solo = true;
        check(!p.audible(a) && p.audible(b), "track solo remains isolated");
        b.solo = false;
        Object gesture = new Object();
        for (float value : new float[] {.3f, .6f, .8f}) {
            m = p.mixer.copy(); m.edit(a.id).reverb = value;
            history.drag(new MixerEdit(p, m, "Send", a.id + ":reverb"), gesture);
        }
        check(history.steps().size() == 3, "one send gesture is one undo step");
        History loaded = History.fromJson(Json.parse(Json.write(history.toJson("x"))), p.copy());
        loaded.undo();
        check(loaded.project().mixer.get(a.id).reverb == .25f, "saved gesture undoes to its starting value");
        loaded.redo();
        check(loaded.project().mixer.get(a.id).reverb == .8f, "saved gesture redoes its final value");
        m = p.mixer.copy(); m.removeGroup(group);
        history.run(new MixerEdit(p, m, "Remove group", null));
        check(p.mixer.get(a.id).route.isEmpty(), "removing group routes members to master");
        history.undo();
        check(p.mixer.get(a.id).route.equals(group), "undo restores group membership");
        history.run(Edit.removeTrack(a.id));
        check(!p.mixer.strips.containsKey(a.id), "deleted track leaves no dangling mixer strip");
        p.validate();
        history.undo();
        check(p.mixer.get(a.id).chain.size() == 1 && p.mixer.get(a.id).route.equals(group), "undo restores deleted track rack and route");
        Project one = p.copy(); one.tracks.remove(1); one.mixer = one.mixer.stem(a.id); one.validate();
        check(one.mixer.groups.isEmpty() && one.mixer.get(a.id).reverb == 0 && one.mixer.get(a.id).chain.size() == 1, "stems retain track inserts only");
        check(one.mixer.get(Mixer.MASTER).chain.isEmpty(), "stems bypass master inserts");
        Project bad = p.copy(); bad.mixer.edit(group).route = group; invalid(bad, "group feedback rejected");
        bad = p.copy(); bad.mixer.edit(Mixer.REVERB).reverb = .5f; invalid(bad, "return feedback rejected");
        bad = p.copy(); bad.mixer.edit(a.id).reverb = Float.NaN; invalid(bad, "NaN send rejected");
        bad = p.copy(); bad.mixer.edit(a.id).chain.get(0).id = bad.mixer.get(Mixer.MASTER).chain.get(0).id; invalid(bad, "duplicate slots rejected");
        check(p.mixer.tailFrames(48000) == 192000 && new Mixer().tailFrames(48000) == 0, "wet mixes have four-second tail, dry mixes unchanged");
        java.util.Map<String, Object> old = p.toJson();
        old.put("version", 3); old.remove("mixer");
        Project migrated = Project.fromText(Json.write(old));
        check(migrated.version == Project.VERSION && migrated.mixer.groups.isEmpty() && migrated.mixer.get(a.id).chain.isEmpty(), "v3 projects migrate dry");
        java.util.Map<String, Object> missing = p.toJson();
        missing.remove("mixer");
        try { Project.fromText(Json.write(missing)); throw new AssertionError("v4 must not silently discard missing mixer state"); }
        catch (IllegalArgumentException expected) { passed++; }
        m = p.mixer.copy();
        String first = m.get(Mixer.MASTER).chain.get(0).id;
        Mixer.Slot last = m.addSlot(Mixer.MASTER, 3);
        last.bypass = true;
        java.util.Collections.swap(m.edit(Mixer.MASTER).chain, 0, 1);
        history.run(new MixerEdit(p, m, "Reorder rack", null));
        Project ordered = Project.fromText(p.toText());
        check(ordered.mixer.get(Mixer.MASTER).chain.get(0).id.equals(last.id)
            && ordered.mixer.get(Mixer.MASTER).chain.get(0).bypass
            && ordered.mixer.get(Mixer.MASTER).chain.get(1).id.equals(first), "rack order, identity and bypass survive saving");
        check(ordered.mixer.nativeData(ordered)[2+74*Mixer.STRIDE+10] == 1, "bypass reaches native config");
        history.undo();
        check(p.mixer.get(Mixer.MASTER).chain.size() == 1 && p.mixer.get(Mixer.MASTER).chain.get(0).id.equals(first), "rack order undo");
        int next = p.mixer.nextId;
        history.jumpTo(0);
        check(p.mixer.nextId >= next, "undo never reuses group or slot IDs");
        System.out.println("MixerTest: " + passed + " passed");
    }
}
