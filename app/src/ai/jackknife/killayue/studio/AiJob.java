package ai.jackknife.killayue.studio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A job for the app's ":ai" process (stems now; beats and voices later), as files in its own folder that
 * both processes read: job.json says what to do, status.json how it is going (rewritten whole and renamed
 * into place, so a reader never sees half of it), and a file named cancel asks it to stop. No IPC to
 * break: the job survives the app being closed, and a killed job's folder still says where it got to.
 */
public final class AiJob {
    private final File dir;
    private final LinkedHashMap<String, Object> job;

    private AiJob(File dir, LinkedHashMap<String, Object> job) {
        this.dir = dir;
        this.job = job;
    }

    public static AiJob create(File dir, String kind, String subject) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not make " + dir + ".");
        LinkedHashMap<String, Object> j = new LinkedHashMap<String, Object>();
        j.put("kind", kind);
        j.put("subject", subject);
        j.put("created", Long.valueOf(System.currentTimeMillis()));
        AiJob a = new AiJob(dir, j);
        a.save();
        a.status("waiting", 0, null);
        return a;
    }

    /** The job in dir, or null if there is none (or it is damaged). */
    public static AiJob open(File dir) {
        try {
            Object j = Json.parse(read(new File(dir, "job.json")));
            if (!(j instanceof Map)) return null;
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) j).entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
            return new AiJob(dir, m);
        } catch (Exception e) {
            return null;
        }
    }

    public File dir() {
        return dir;
    }

    public void put(String key, Object value) {
        job.put(key, value);
    }

    public void save() throws IOException {
        write(new File(dir, "job.json"), Json.write(job));
    }

    public String kind() {
        return (String) job.get("kind");
    }

    public String subject() {
        return (String) job.get("subject");
    }

    public Object get(String key) {
        return job.get(key);
    }

    public int getInt(String key) {
        Object o = job.get(key);
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private Map<?, ?> status() {
        try {
            Object s = Json.parse(read(new File(dir, "status.json")));
            return s instanceof Map ? (Map<?, ?>) s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** waiting, running, done, failed or cancelled (null if unknown). */
    public String state() {
        Map<?, ?> s = status();
        return s == null ? null : (String) s.get("state");
    }

    public double progress() {
        Map<?, ?> s = status();
        Object p = s == null ? null : s.get("progress");
        return p instanceof Number ? ((Number) p).doubleValue() : 0;
    }

    public String error() {
        Map<?, ?> s = status();
        return s == null ? null : (String) s.get("error");
    }

    /** The job's side: how it is going (written whole, then renamed into place). */
    public void status(String state, double progress, String error) throws IOException {
        LinkedHashMap<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("state", state);
        s.put("progress", Double.valueOf(progress));
        if (error != null) s.put("error", error);
        s.put("when", Long.valueOf(System.currentTimeMillis()));
        write(new File(dir, "status.json"), Json.write(s));
    }

    /** The app's side: asks the job to stop (it saves where it got to). */
    public void cancel() throws IOException {
        new FileOutputStream(new File(dir, "cancel")).close();
    }

    public boolean cancelled() {
        return new File(dir, "cancel").exists();
    }

    /** Clears a cancel, before the job is run again (it carries on from its checkpoint). */
    public void uncancel() {
        new File(dir, "cancel").delete();
    }

    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            o.write(text.getBytes("UTF-8"));
            o.getFD().sync();
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("Could not write " + f + ".");
    }

    private static String read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
