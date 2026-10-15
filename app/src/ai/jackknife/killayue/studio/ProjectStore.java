package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;

/**
 * Saves and loads project.json so that a crash or a power cut never loses a project. Saving writes
 * project.json.tmp and forces it to disk, keeps the current project.json as project.json.bak, then renames
 * the new file into place. Loading takes project.json, or, if that is damaged, the newer of the files a cut
 * save can leave behind: project.json.tmp when it is whole, then project.json.bak.
 */
public final class ProjectStore {
    public static final String MAIN = "project.json", TMP = "project.json.tmp", BAK = "project.json.bak";

    public static final class Loaded {
        public Project project;
        public boolean fromBackup;   // not from project.json
        public String problem;       // why project.json was not used, or null
    }

    private ProjectStore() {
    }

    public static void save(Project p, File dir) throws IOException {
        try {
            p.validate();
        } catch (IllegalArgumentException e) {
            throw new IOException("Not saved: " + e.getMessage(), e);
        }
        File tmp = new File(dir, TMP), main = new File(dir, MAIN), bak = new File(dir, BAK);
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(p.toText().getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (main.exists() && !main.renameTo(bak)) throw new IOException("Could not keep the previous save as " + bak);
        if (!tmp.renameTo(main)) throw new IOException("Could not put the new save in place as " + main);
    }

    public static Loaded load(File dir) throws IOException {
        Loaded l = new Loaded();
        String mainProblem = null;
        for (String name : new String[] {MAIN, TMP, BAK}) {
            File f = new File(dir, name);
            String why;
            if (!f.isFile()) {
                why = "it is missing";
            } else {
                try {
                    l.project = Project.fromText(read(f));
                    l.fromBackup = !name.equals(MAIN);
                    l.problem = mainProblem;
                    return l;
                } catch (IOException e) {        // unreadable: permissions, a failing card
                    why = e.getMessage();
                } catch (RuntimeException e) {   // damaged or not a project
                    why = e.getMessage();
                } catch (StackOverflowError e) { // never expected (JSON nesting is capped), but never a crash
                    why = "it is nested too deeply";
                }
            }
            if (mainProblem == null) mainProblem = "project.json could not be used: " + why;
        }
        throw new IOException(mainProblem + ", and no earlier save could be read either.");
    }

    private static String read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
