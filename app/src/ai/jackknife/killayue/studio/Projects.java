package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Studio's projects, one folder each under the studio folder: project.json and history.json, view.json
 * (where the playhead and the zoom were: not undoable), and audio/ (imports, stems, bounces, with their .kpk
 * peak files), takes/ (recordings), jobs/ (AI work in progress) and export/. Plain Java, no Android.
 */
public final class Projects {
    public static final String[] FOLDERS = {"audio", "takes", "jobs", "export"};

    public static final class Entry {
        public File dir;
        public String name;
        public String problem;  // why it opened from a backup, or null
        public long modified;   // when project.json was last written
        public long bytes;      // everything in the folder
    }

    private Projects() {}

    /** A new project folder named after the project and the moment (a number is added if that folder exists). */
    public static File create(File root, String name, int rate, String song, String title, long now) throws IOException {
        String base = slug(name) + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(now));
        File dir = new File(root, base);
        for (int n = 2; dir.exists(); n++) dir = new File(root, base + "-" + n);
        for (String f : FOLDERS)
            if (!new File(dir, f).mkdirs()) throw new IOException("Could not make the project folder " + new File(dir, f) + ".");
        Project p = new Project(name, rate);
        p.sourceSong = song;
        p.sourceTitle = title;
        ProjectStore.save(p, dir);
        new File(dir, ProjectStore.MAIN).setLastModified(now);
        return dir;
    }

    /** Every project, newest change first; a damaged one is listed by its backup, with what is wrong. */
    public static List<Entry> list(File root) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            boolean any = false;
            for (String n : new String[] {ProjectStore.MAIN, ProjectStore.TMP, ProjectStore.BAK}) any |= new File(d, n).isFile();
            if (!any) continue;
            Entry e = new Entry();
            e.dir = d;
            try {
                ProjectStore.Loaded l = ProjectStore.load(d);
                e.name = l.project.name;
                e.problem = l.problem;
            } catch (IOException x) {
                e.name = d.getName();
                e.problem = x.getMessage();
            }
            File main = new File(d, ProjectStore.MAIN);
            e.modified = main.isFile() ? main.lastModified() : d.lastModified();
            e.bytes = size(d);
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                if (a.modified != b.modified) return a.modified > b.modified ? -1 : 1;
                return b.dir.getName().compareTo(a.dir.getName());
            }
        });
        return out;
    }

    public static void rename(File dir, String name) throws IOException {
        if (name == null || name.trim().length() == 0) throw new IOException("A project needs a name.");
        Project p = ProjectStore.load(dir).project;
        p.name = name.trim();
        ProjectStore.save(p, dir);
    }

    public static void delete(File dir) {
        File[] in = dir.listFiles();
        if (in != null)
            for (File f : in) {
                if (f.isDirectory()) delete(f);
                else f.delete();
            }
        dir.delete();
    }

    public static long size(File dir) {
        long n = 0;
        File[] in = dir.listFiles();
        if (in != null)
            for (File f : in) n += f.isDirectory() ? size(f) : f.length();
        return n;
    }

    /** Files in audio/ that neither the project nor its undo history refers to (with their peak files). */
    public static List<String> unused(File dir, Project p, History h) {
        HashSet<String> used = new HashSet<String>();
        files(p.toJson(), used);
        files(h.toJson("x"), used);
        ArrayList<String> out = new ArrayList<String>();
        File[] in = new File(dir, "audio").listFiles();
        if (in == null) return out;
        for (File f : in) {
            String rel = "audio/" + f.getName();
            String owner = rel.endsWith(".kpk") ? rel.substring(0, rel.length() - 4) : rel;
            if (f.isFile() && !used.contains(owner)) out.add(f.getName());
        }
        Collections.sort(out);
        return out;
    }

    /** Removes what unused() finds; the bytes freed. */
    public static long compact(File dir, Project p, History h) {
        long freed = 0;
        for (String name : unused(dir, p, h)) {
            File f = new File(new File(dir, "audio"), name);
            long n = f.length();
            if (f.delete()) freed += n;
        }
        return freed;
    }

    private static void files(Object j, HashSet<String> out) {
        if (j instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) j).entrySet()) {
                if ("file".equals(e.getKey()) && e.getValue() instanceof String) out.add((String) e.getValue());
                else files(e.getValue(), out);
            }
        } else if (j instanceof List) {
            for (Object o : (List<?>) j) files(o, out);
        }
    }

    /** A folder name from a project name: letters and digits, dashes between words, "project" if nothing is left. */
    static String slug(String name) {
        StringBuilder b = new StringBuilder();
        boolean dash = false;
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length() && b.length() < 40; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (dash && b.length() > 0) b.append('-');
                b.append(c);
                dash = false;
            } else {
                dash = true;
            }
        }
        return b.length() == 0 ? "project" : b.toString();
    }
}
