package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Projects: the Studio's folder of projects, as THE VAULT lists them. */
public class ProjectsTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static void bytes(File f, int n) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(new byte[n]);
        o.close();
    }

    public static void main(String[] a) throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "projects-test-" + System.nanoTime());
        long t0 = 1760500000000L;  // 15 Oct 2025, a fixed clock

        File one = Projects.create(root, "Yeah. (remix)", 48000, "20261013-222501-1.json", "Yeah.", t0);
        same("a project folder is named after it, with its time", true, one.getName().startsWith("yeah-remix-"));
        for (String sub : new String[] {"audio", "takes", "jobs", "export"}) same("it has " + sub + "/", true, new File(one, sub).isDirectory());
        Project p = ProjectStore.load(one).project;
        same("and a project of that name, rate and song", "Yeah. (remix) 48000 Yeah.", p.name + " " + p.rate + " " + p.sourceTitle);

        File two = Projects.create(root, "Yeah. (remix)", 48000, null, null, t0);
        same("the same name at the same moment gets its own folder", false, one.equals(two));
        File emoji = Projects.create(root, "🔥🔥", 48000, null, null, t0 + 1000);
        same("a name of only emoji still makes a folder", true, emoji.isDirectory() && emoji.getName().startsWith("project-"));
        same("and keeps its name inside", "🔥🔥", ProjectStore.load(emoji).project.name);

        bytes(new File(one, "audio/song.wav"), 5000);
        List<Projects.Entry> list = Projects.list(root);
        same("the vault lists every project", 3, list.size());
        same("newest first", emoji.getName(), list.get(0).dir.getName());
        Projects.Entry e1 = null;
        for (Projects.Entry e : list) if (e.dir.equals(one)) e1 = e;
        same("with its size on disk", true, e1 != null && e1.bytes >= 5000);

        // a damaged project is still listed, with what is wrong, and opens from its backup when there is one
        File bad = Projects.create(root, "Broken", 48000, null, null, t0 + 2000);
        ProjectStore.save(ProjectStore.load(bad).project, bad);  // a second save leaves a .bak
        FileOutputStream o = new FileOutputStream(new File(bad, ProjectStore.MAIN));
        o.write("{garbage".getBytes("UTF-8"));
        o.close();
        Projects.Entry be = null;
        for (Projects.Entry e : Projects.list(root)) if (e.dir.equals(bad)) be = e;
        same("a damaged project is listed by its backup's name", "Broken", be == null ? null : be.name);
        File nothing = new File(root, "not-a-project");
        nothing.mkdirs();
        same("a folder with no project in it is not listed", 4, Projects.list(root).size());

        Projects.rename(one, "Yeah. (night mix)");
        same("rename changes the name, not the folder", "Yeah. (night mix)", ProjectStore.load(one).project.name);

        // compact: audio nothing refers to (not the project, not its undo history) is removed
        Project q = ProjectStore.load(one).project;
        History h = new History(q);
        h.run(Edit.addTrack("Song", "audio/song.wav", 1000));
        h.run(Edit.addTrack("Old take", "audio/take1.wav", 1000));
        h.run(Edit.removeTrack(q.tracks.get(1).id));  // take1 is only in the history now
        bytes(new File(one, "audio/take1.wav"), 100);
        bytes(new File(one, "audio/take1.wav.kpk"), 10);
        bytes(new File(one, "audio/song.wav.kpk"), 10);
        bytes(new File(one, "audio/stray.wav"), 300);
        bytes(new File(one, "audio/stray.wav.kpk"), 10);
        ProjectStore.save(q, one);
        ProjectStore.saveHistory(h, one);
        List<String> unused = Projects.unused(one, q, h);
        same("compact finds what nothing uses", "[stray.wav, stray.wav.kpk]", unused);
        same("compacting frees its bytes", 310L, Projects.compact(one, q, h));
        same("and keeps what undo could bring back", true, new File(one, "audio/take1.wav").isFile());

        // a snapshot (made on the UI thread) saved by the autosave thread loads back with its history
        String[] snap = ProjectStore.snapshot(q, h);
        File three = Projects.create(root, "Snap", 48000, null, null, t0 + 3000);
        ProjectStore.saveSnapshot(three, snap);
        ProjectStore.Loaded sl = ProjectStore.load(three);
        same("a saved snapshot is the project", q.toText(), sl.project.toText());
        ProjectStore.LoadedHistory slh = ProjectStore.loadHistory(three, sl.project, sl.originalText);
        same("and its history belongs to it", null, slh.problem);
        same("all of it", h.steps().size(), slh.history.steps().size());

        Projects.delete(two);
        same("delete removes the whole project", false, two.exists());
        Projects.delete(root);
        System.out.println("ProjectsTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
