package ai.jackknife.killayue.studio;
import java.util.Map;
public final class ChamberTest {
    static int passed;
    static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
        passed++;
    }
    public static void main(String[] args) {
        Project p = new Project("Chamber", 48000);
        Performance f = p.performance;
        f.pattern = true;
        f.steps[0][15] = 1;
        f.chamber.patterns[1][1][15] = .5f;
        f.chamber.chain.clear();
        f.chamber.chain.add(0);
        f.chamber.chain.add(1);
        f.chamber.song = true;
        p.validate();
        check(p.toText().equals(Project.fromText(p.toText()).toText()), "four patterns and song order round trip");
        check(f.chamber.patterns[0] == f.steps, "pattern A is the existing step bank");
        check(f.chamber.bankAt(0, 16) == 0 && f.chamber.bankAt(16, 16) == 1 && f.chamber.bankAt(32, 16) == -1,
            "song advances and ends");
        check(p.renderFrames() == 210000, "song length includes last one-shot tail");
        f.chamber.mute[1] = true;
        check(p.renderFrames() == 192000, "muted final pad adds no tail");
        f.chamber.solo[1] = true;
        check(!f.chamber.audible(0) && !f.chamber.audible(1), "mute wins over solo");
        f.chamber.mute[1] = false;
        check(f.chamber.audible(1) && !f.chamber.audible(0), "pad solo filters other pads");
        f.chamber.song = false;
        f.chamber.active = 1;
        check(f.chamber.bankAt(512, 16) == 1, "single pattern loops independently of song order");
        f.pads[1].mode = 2;
        f.pads[1].release = .01f;
        check(p.renderFrames() >= 96480 && p.renderFrames() <= 96481,
            "gated pattern loop ends at next step plus release");
        float[] packed = f.chamber.nativeData();
        check(packed.length == Chamber.DATA && packed[0] == 1 && packed[37] == 2, "bounded native configuration");
        History h = new History(p);
        Project changed = p.copy();
        changed.performance.chamber.levels = true;
        changed.performance.chamber.repeat = 3;
        h.run(ClipEdits.change(p, changed, "Playing controls"));
        h.undo();
        check(!p.performance.chamber.levels && p.performance.chamber.repeat == 0, "Chamber controls undo together");
        h.redo();
        check(p.performance.chamber.levels && p.performance.chamber.repeat == 3, "Chamber controls redo");
        Map<String, Object> old = p.toJson();
        old.put("version", 8);((Map<?,?>)old.get("performance")).remove("chamber");
        Project migrated = Project.fromText(Json.write(old));
        check(migrated.performance.steps[0][15] == 1 && migrated.performance.chamber.active == 0
                && !migrated.performance.chamber.song,
            "migration retains old pattern A without enabling new controls");
        Chamber bad = new Chamber(new float[16][32]);
        bad.chain.clear();
        try {
            bad.validate();
            throw new AssertionError("empty chain");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        bad.chain.add(0);
        bad.repeat = 4;
        try {
            bad.validate();
            throw new AssertionError("unsupported repeat");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
        System.out.println("ChamberTest: " + passed + " passed");
    }
}
