package ai.jackknife.killayue.studio;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A portable project: one .killa file (a zip) with the project, its undo history and every recording it uses,
 * to move to another phone or keep as a backup. Instrument samples are left out (they are made again on the
 * other side), and acoustic packs are named rather than copied, so a bundle stays the size of its own audio.
 * Each file is listed with its SHA-256 in killa-bundle.json and checked on the way in; nothing outside the new
 * project folder can be written, and a bundle that does not open as a project is removed again.
 */
public final class Bundle {
    private Bundle() {}
    public static final String MANIFEST = "killa-bundle.json", EXTENSION = ".killa";
    public static final int FORMAT = 1;

    public interface Progress {
        void at(long done, long total);
    }

    /** What goes in: everything but samples that are rebuilt, jobs in progress, exports and temporary files. */
    static boolean wanted(String rel) {
        if (rel.startsWith("jobs/") || rel.startsWith("export/") || rel.startsWith("audio/instruments-"))
            return false;
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return !name.endsWith(".part") && !name.endsWith(".tmp") && !name.startsWith(".");
    }
    static void walk(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            String rel = prefix + f.getName();
            if (f.isDirectory())
                walk(f, rel + "/", out);
            else if (f.isFile() && wanted(rel))
                out.add(rel);
        }
    }

    /** Writes the project in dir as a bundle. Off the UI thread; save the project first. */
    public static void export(File dir, OutputStream target, Progress progress, AtomicBoolean cancel) throws IOException {
        ProjectStore.Loaded loaded = ProjectStore.load(dir);
        ArrayList<String> files = new ArrayList<String>();
        walk(dir, "", files);
        long total = 0;
        for (String f : files) total += new File(dir, f).length();
        LinkedHashSet<String> packs = new LinkedHashSet<String>();
        for (Track t : loaded.project.tracks) {
            Sounds.Sound s = Sounds.get(t.notes.instrument);
            if (s != null && s.acoustic())
                packs.add(s.pack);
        }
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<String, Object>();
        manifest.put("format", FORMAT);
        manifest.put("name", loaded.project.name);
        manifest.put("rate", loaded.project.rate);
        manifest.put("made", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date()));
        manifest.put("packs", new ArrayList<Object>(packs));
        ArrayList<Object> list = new ArrayList<Object>();
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(target, 1 << 16));
        try {
            long done = 0;
            byte[] buf = new byte[1 << 16];
            for (String rel : files) {
                if (cancel.get())
                    throw new IOException("Cancelled.");
                File f = new File(dir, rel);
                ZipEntry e = new ZipEntry("project/" + rel);
                // recordings barely compress; storing them is as small and far faster
                boolean audio = rel.endsWith(".wav") || rel.endsWith(".flac") || rel.endsWith(".kpk");
                e.setMethod(audio ? ZipEntry.STORED : ZipEntry.DEFLATED);
                MessageDigest sha = sha();
                if (audio) {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    try (InputStream in = new FileInputStream(f)) {
                        for (int n; (n = in.read(buf)) > 0;) {
                            crc.update(buf, 0, n);
                            sha.update(buf, 0, n);
                        }
                    }
                    e.setSize(f.length());
                    e.setCompressedSize(f.length());
                    e.setCrc(crc.getValue());
                    sha = sha();
                }
                zip.putNextEntry(e);
                try (InputStream in = new FileInputStream(f)) {
                    for (int n; (n = in.read(buf)) > 0;) {
                        if (cancel.get())
                            throw new IOException("Cancelled.");
                        zip.write(buf, 0, n);
                        sha.update(buf, 0, n);
                        done += n;
                        progress.at(done, total);
                    }
                }
                zip.closeEntry();
                LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("path", rel);
                m.put("bytes", f.length());
                m.put("sha256", SoundPacks.hex(sha.digest()));
                list.add(m);
            }
            manifest.put("files", list);
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(Json.write(manifest).getBytes("UTF-8"));
            zip.closeEntry();
        } finally {
            zip.close();
        }
    }

    public static final class Opened {
        public File dir;
        public String name;
        /** Acoustic packs the project uses that are not on this phone yet. */
        public final ArrayList<String> missingPacks = new ArrayList<String>();
    }

    /** Unpacks a bundle into a new project folder under root and checks it opens. */
    public static Opened open(File root, InputStream source, long size, Progress progress, AtomicBoolean cancel)
        throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File dir = new File(root, "imported-" + stamp);
        for (int n = 2; dir.exists(); n++) dir = new File(root, "imported-" + stamp + "-" + n);
        if (!dir.mkdirs())
            throw new IOException("Could not make the project folder.");
        boolean ok = false;
        try {
            LinkedHashMap<String, String> hashes = new LinkedHashMap<String, String>();
            Object manifest = null;
            long done = 0;
            byte[] buf = new byte[1 << 16];
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source, 1 << 16))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                    if (cancel.get())
                        throw new IOException("Cancelled.");
                    String name = e.getName();
                    if (e.isDirectory())
                        continue;
                    if (MANIFEST.equals(name)) {
                        java.io.ByteArrayOutputStream m = new java.io.ByteArrayOutputStream();
                        for (int n; (n = zip.read(buf)) > 0;) {
                            m.write(buf, 0, n);
                            if (m.size() > 8 << 20)
                                throw new IOException("The bundle is damaged.");
                        }
                        manifest = Json.parse(new String(m.toByteArray(), "UTF-8"));
                        continue;
                    }
                    if (!name.startsWith("project/"))
                        continue;
                    String rel = safe(name.substring(8));
                    File out = new File(dir, rel);
                    out.getParentFile().mkdirs();
                    MessageDigest sha = sha();
                    try (OutputStream o = new FileOutputStream(out)) {
                        for (int n; (n = zip.read(buf)) > 0;) {
                            o.write(buf, 0, n);
                            sha.update(buf, 0, n);
                            done += n;
                            progress.at(done, Math.max(done, size));
                        }
                    }
                    hashes.put(rel, SoundPacks.hex(sha.digest()));
                }
            }
            if (!(manifest instanceof Map))
                throw new IOException("This is not a Killa YuE project bundle.");
            Map<?, ?> m = (Map<?, ?>) manifest;
            if (!(m.get("format") instanceof Number) || ((Number) m.get("format")).intValue() > FORMAT)
                throw new IOException("This bundle was made by a newer Killa YuE; update the app to open it.");
            if (!(m.get("files") instanceof List))
                throw new IOException("The bundle is damaged.");
            for (Object o : (List<?>) m.get("files")) {
                Map<?, ?> f = (Map<?, ?>) o;
                String rel = safe((String) f.get("path"));
                if (!String.valueOf(f.get("sha256")).equals(hashes.get(rel)))
                    throw new IOException("The bundle is damaged: " + rel + " does not match.");
            }
            for (String f : Projects.FOLDERS) new File(dir, f).mkdirs();
            ProjectStore.Loaded loaded = ProjectStore.load(dir);
            Opened opened = new Opened();
            opened.dir = dir;
            opened.name = loaded.project.name;
            if (m.get("packs") instanceof List)
                for (Object pack : (List<?>) m.get("packs"))
                    if (pack instanceof String && SoundPacks.info((String) pack) != null
                        && !SoundPacks.installedIn(root, (String) pack))
                        opened.missingPacks.add((String) pack);
            ok = true;
            return opened;
        } finally {
            if (!ok)
                Projects.delete(dir);
        }
    }
    /** A relative path that stays inside the project folder. */
    static String safe(String rel) throws IOException {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains("\0"))
            throw new IOException("The bundle is damaged.");
        for (String part : rel.split("/"))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("The bundle is damaged.");
        return rel;
    }
    static MessageDigest sha() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
