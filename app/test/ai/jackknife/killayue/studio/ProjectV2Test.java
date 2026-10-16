package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.FileOutputStream;

/** Project format 2: the song it came from, a loop region, track colours and roles; older projects open. */
public class ProjectV2Test {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got)))
            passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static String refused(String text) {
        try {
            Project.fromText(text);
            return "accepted";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    static final String V1 = "{\"version\":1,\"name\":\"Old\",\"rate\":48000,\"nextId\":3,\"masterDb\":0,\"tracks\":[{\"id\":\"t1\","
        + "\"name\":\"Song\",\"gainDb\":-6,\"pan\":0,\"mute\":false,\"solo\":false,\"clips\":[{\"id\":\"c2\",\"file\":\"audio/song.wav\","
        + "\"start\":0,\"offset\":0,\"length\":1000,\"gainDb\":0}]}]}";

    static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }

    static String sha(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x & 0xff));
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        // an old project opens as the new format, with defaults
        Project old = Project.fromText(V1);
        same("a format 1 project opens as format 2", Project.VERSION + " " + Project.VERSION, old.version + " " + Project.VERSION);
        same("with no loop yet", "0 0 false", old.loopStart + " " + old.loopEnd + " " + old.loopOn);
        same("its tracks get a colour", true, old.track("t1").color != 0);
        same("and no role", null, old.track("t1").role);
        same("and no source song", null, old.sourceSong);

        // the new fields go round
        Project p = new Project("Remix of Yeah.", 48000);
        p.sourceSong = "20261013-222501-1.json";
        p.sourceTitle = "Yeah.";
        Track t = p.addTrack("Vocals");
        t.role = "stem:vocals";
        t.color = 0xffcc8800;
        p.addClip(t, "audio/vocals.wav", 0, 0, 1000);
        p.loopStart = 48000;
        p.loopEnd = 96000;
        p.loopOn = true;
        same("format 2 round-trips", p.toText(), Project.fromText(p.toText()).toText());
        same("negative colours (ARGB) round-trip", 0xffcc8800, Project.fromText(p.toText()).track(t.id).color);

        // what a project must never hold
        same("a newer format is refused, saying so", true,
            refused(p.toText().replace("\"version\":" + Project.VERSION, "\"version\":" + (Project.VERSION + 1))).contains("newer"));
        p.loopEnd = 1000;
        same("a loop that ends before it starts is refused", true, refused(p.toText()).contains("loop"));
        p.loopEnd = 96000;
        p.loopStart = -5;
        same("a loop before the start is refused", true, refused(p.toText()).contains("loop"));
        p.loopStart = 48000;

        // the new edits
        History h = new History(p);
        h.run(Edit.setLoop(1000, 5000, false));
        same("set the loop", "1000 5000 false", p.loopStart + " " + p.loopEnd + " " + p.loopOn);
        h.run(Edit.setColor(t.id, 0xff3366ff));
        same("colour a track", 0xff3366ff, p.track(t.id).color);
        History back = History.fromJson(Json.parse(Json.write(h.toJson("x"))), p.copy());
        back.undo();
        same("undo a colour after saving", 0xffcc8800, back.project().track(t.id).color);
        back.undo();
        same("undo the loop after saving", "48000 96000 true",
            back.project().loopStart + " " + back.project().loopEnd + " " + back.project().loopOn);
        same("labels", "Colour Loop", h.undoLabel() + " " + (h.undo() ? h.undoLabel() : ""));

        // a track added with a role keeps it through undo, redo and saving
        History hr = new History(new Project("Roles", 48000));
        hr.run(Edit.addTrack("Vocals", "audio/stems-1/vocals.wav", 1000, "stem:vocals"));
        String rid = hr.project().tracks.get(0).id;
        same("an added track has its role", "stem:vocals", hr.project().track(rid).role);
        History hr2 = History.fromJson(Json.parse(Json.write(hr.toJson("x"))), hr.project().copy());
        hr2.undo();
        hr2.redo();
        same("and keeps it after saving, undo and redo", "stem:vocals", hr2.project().track(rid).role);

        // the undo history of an old project survives the upgrade
        File dir = new File(System.getProperty("java.io.tmpdir"), "v2test-" + System.nanoTime());
        dir.mkdirs();
        write(new File(dir, ProjectStore.MAIN), V1);
        write(new File(dir, ProjectStore.HISTORY),
            "{\"version\":1,\"fingerprint\":\"" + sha(V1) + "\",\"current\":1,\"steps\":["
                + "{\"id\":0,\"parent\":-1,\"when\":1,\"redo\":1},"
                + ("{\"id\":1,\"parent\":0,\"when\":2,\"redo\":null,\"edit\":{\"kind\":\"gain\",\"track\":\"t1\",\"value\":-6,\"old\":0}}]"
                   + "}"));
        ProjectStore.Loaded l = ProjectStore.load(dir);
        ProjectStore.LoadedHistory lh = ProjectStore.loadHistory(dir, l.project, l.originalText);
        same("an old history belongs to the upgraded project", null, lh.problem);
        same("its steps are all there", 2, lh.history.steps().size());
        lh.history.undo();
        same("and it undoes", 0f, l.project.track("t1").gainDb);
        for (File f : dir.listFiles()) f.delete();
        dir.delete();

        System.out.println("ProjectV2Test: " + passed + " passed, " + failed + " failed");
        if (failed > 0)
            System.exit(1);
    }
}
