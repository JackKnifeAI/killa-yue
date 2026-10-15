package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

public class ProjectStoreTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("studio").toFile();
        Project p = new Project("Remix", 48000);
        p.addTrack("Vocals");
        ProjectStore.save(p, dir);
        String first = p.toText();
        p.addTrack("Drums");
        ProjectStore.save(p, dir);
        ProjectStore.Loaded ok = ProjectStore.load(dir);
        same("the latest save loads", p.toText(), ok.project.toText());
        same("not from the backup", false, ok.fromBackup);
        same("the previous save is kept as the backup", first, new String(Files.readAllBytes(new File(dir, "project.json.bak").toPath()), "UTF-8"));
        same("no temporary file is left behind", false, new File(dir, "project.json.tmp").exists());

        write(new File(dir, "project.json"), p.toText().substring(0, 40)); // power cut mid-write
        ProjectStore.Loaded rescued = ProjectStore.load(dir);
        same("a cut-off project loads from the backup", first, rescued.project.toText());
        same("and says so", true, rescued.fromBackup && rescued.problem != null && rescued.problem.length() > 0);

        write(new File(dir, "project.json"), "");
        write(new File(dir, "project.json.bak"), "{}");
        boolean threw = false;
        try {
            ProjectStore.load(dir);
        } catch (java.io.IOException e) {
            threw = true;
        }
        same("nothing usable: a clear error, not a crash", true, threw);
        File dir2 = Files.createTempDirectory("studio").toFile();
        Project good = new Project("Good", 48000);
        good.addTrack("Vocals");
        ProjectStore.save(good, dir2);
        ProjectStore.save(good, dir2);
        File main = new File(dir2, "project.json");
        main.setReadable(false);
        ProjectStore.Loaded unreadable = ProjectStore.load(dir2);
        same("a project.json that cannot be read: the backup loads", "Good", unreadable.project.name);
        main.setReadable(true);
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100000; i++) deep.append('[');
        write(main, deep.toString());
        ProjectStore.Loaded nested = ProjectStore.load(dir2);
        same("absurdly nested JSON: the backup loads, no crash", true, nested.fromBackup);
        Project broken = new Project("Bad", 48000);
        broken.addTrack("X").gainDb = Float.NaN;
        threw = false;
        try {
            ProjectStore.save(broken, dir2);
        } catch (java.io.IOException e) {
            threw = true;
        }
        same("saving a project that cannot be valid is an IOException, as save promises", true, threw);

        System.out.println("ProjectStoreTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
