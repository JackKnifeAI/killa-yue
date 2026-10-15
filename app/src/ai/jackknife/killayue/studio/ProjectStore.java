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
    public static final String HISTORY = "history.json";

    public static final class LoadedHistory {
        public History history;
        public String problem;  // why the saved history was not used (a fresh one is given instead), or null
    }

    public static final class Loaded {
        public Project project;
        public boolean fromBackup;   // not from project.json
        public String problem;       // why project.json was not used, or null
        public String originalText;  // the file's text as saved, before any upgrade: what its history was fingerprinted on
    }

    private ProjectStore() {
    }

    public static void save(Project p, File dir) throws IOException {
        try {
            p.validate();
        } catch (IllegalArgumentException e) {
            throw new IOException("Not saved: " + e.getMessage(), e);
        }
        write(dir, MAIN, p.toText());
    }

    /** The undo tree beside the project, marked with the project state it belongs to. Save the project first. */
    public static void saveHistory(History h, File dir) throws IOException {
        write(dir, HISTORY, Json.write(h.toJson(fingerprint(h.project()))));
    }

    /**
     * The saved undo tree for p as loaded. If it is missing, damaged, or belongs to another state of the project
     * (a save the history did not see), a fresh history is given and problem says why: undoing into the wrong
     * values would be worse than starting over.
     */
    public static LoadedHistory loadHistory(File dir, Project p) {
        return loadHistory(dir, p, null);
    }

    /**
     * The same for a project that was upgraded as it loaded: asSaved (Loaded.originalText) is the text its history
     * was fingerprinted on, before the upgrade.
     */
    public static LoadedHistory loadHistory(File dir, Project p, String asSaved) {
        LoadedHistory l = new LoadedHistory();
        String want = asSaved != null ? fingerprintOf(asSaved) : fingerprint(p), why = "there is no saved history";
        for (String name : new String[] {HISTORY, HISTORY + ".tmp", HISTORY + ".bak"}) {
            File f = new File(dir, name);
            if (!f.isFile()) continue;
            try {
                Object j = Json.parse(read(f));
                Object print = j instanceof java.util.Map ? ((java.util.Map<?, ?>) j).get("fingerprint") : null;
                if (!want.equals(print)) {
                    why = "the saved history belongs to a different version of the project";
                    continue;
                }
                l.history = History.fromJson(j, p);
                return l;
            } catch (IOException e) {
                why = e.getMessage();
            } catch (RuntimeException e) {
                why = "the saved history is damaged (" + e.getMessage() + ")";
            } catch (StackOverflowError e) {
                why = "the saved history is damaged";
            }
        }
        l.history = new History(p);
        l.problem = why;
        return l;
    }

    static String fingerprint(Project p) {
        return fingerprintOf(p.toText());
    }

    static String fingerprintOf(String projectText) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(projectText.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** name.tmp written and forced to disk, the current name kept as name.bak, then the new one renamed in. */
    private static void write(File dir, String name, String text) throws IOException {
        File tmp = new File(dir, name + ".tmp"), main = new File(dir, name), bak = new File(dir, name + ".bak");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (main.exists() && !main.renameTo(bak)) throw new IOException("Could not keep the previous save as " + bak);
        if (!tmp.renameTo(main)) throw new IOException("Could not put the new save in place as " + main);
        syncFolder(dir);
    }

    /** Makes the renames themselves durable, where the system allows it (a best effort: the backup covers the rest). */
    private static void syncFolder(File dir) {
        try {
            java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ);
            try {
                ch.force(true);
            } finally {
                ch.close();
            }
        } catch (IOException e) {
            // some systems cannot open a folder this way; the saved files and their backup are still whole
        } catch (RuntimeException e) {
            // likewise
        }
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
                    String text = read(f);
                    l.project = Project.fromText(text);
                    l.originalText = text;
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
