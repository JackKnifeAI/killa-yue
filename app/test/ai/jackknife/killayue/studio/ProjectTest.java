package ai.jackknife.killayue.studio;

public class ProjectTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static boolean rejects(String text) {
        try {
            Project.fromText(text);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public static void main(String[] a) {
        Project p = new Project("Gold in the Wire", 48000);
        Track v = p.addTrack("Vocals");
        Clip c = p.addClip(v, "audio/vocals.wav", 0, 0, 48000 * 180);
        same("IDs are short, typed and stable", "t1 c2", v.id + " " + c.id);
        same("a new track is at unity gain, centred, not muted or soloed", "0.0 0.0 false false", v.gainDb + " " + v.pan + " " + v.mute + " " + v.solo);
        String text = p.toText();
        same("JSON round trip is exact", text, Project.fromText(text).toText());
        Track d = p.addTrack("Drums");
        d.solo = true;
        same("solo: only soloed tracks play", "false true", p.audible(v) + " " + p.audible(d));
        d.mute = true;
        same("mute beats solo", false, p.audible(d));
        same("a copy is equal and independent", text.length() < p.copy().toText().length(), true);
        same("an absolute file path is refused", true, rejects(text.replace("audio/vocals.wav", "/sdcard/x.wav")));
        same("a path climbing out of the project is refused", true, rejects(text.replace("audio/vocals.wav", "../x.wav")));
        same("a pan beyond hard left is refused", true, rejects(text.replace("\"pan\":0", "\"pan\":-1.5")));
        same("a duplicate ID is refused", true, rejects(text.replace("\"c2\"", "\"t1\"")));
        same("a negative clip start is refused", true, rejects(text.replace("\"start\":0", "\"start\":-1")));
        same("an impossible rate is refused", true, rejects(text.replace("48000", "7")));
        same("three dots inside a file name are fine", false, rejects(text.replace("audio/vocals.wav", "audio/Wait... what.wav")));
        same("a path segment of .. is refused", true, rejects(text.replace("audio/vocals.wav", "audio/../../x.wav")));
        Project empty = new Project("Empty", 48000);
        same("an ID counter of 0 is refused (IDs start at 1)", true, rejects(empty.toText().replace("\"nextId\":1", "\"nextId\":0")));
        same("an ID counter past nine digits is refused (IDs have at most nine)", true,
                rejects(empty.toText().replace("\"nextId\":1", "\"nextId\":1000000000")));

        System.out.println("ProjectTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
