package ai.jackknife.killayue.studio;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A project goes out as one .killa file and comes back whole; damaged or hostile bundles are refused. */
public final class BundleTest {
    static int passed;
    static void check(boolean b, String s) {
        if (!b)
            throw new AssertionError(s);
        passed++;
    }
    static final Bundle.Progress QUIET = new Bundle.Progress() {
        public void at(long done, long total) {}
    };
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("killa-bundle").toFile();
        File dir = Projects.create(root, "Night drive", 48000, null, null, System.currentTimeMillis());
        Project p = ProjectStore.load(dir).project;
        WavOut w = WavOut.create(new File(dir, "audio/vocal.wav"), 48000, 1);
        short[] s = new short[4800];
        for (int i = 0; i < s.length; i++) s[i] = (short) (i * 7);
        w.write(s, s.length);
        w.finish();
        p.addClip(p.addTrack("Vocal"), "audio/vocal.wav", 0, 0, 4800);
        Track piano = p.addTrack("Piano");
        piano.notes.instrument = 16;
        piano.notes.add(0, 60);
        ProjectStore.save(p, dir);
        Sounds.ensure(dir, 48000, 2, 60, 0);  // a rebuilt sample: left out of the bundle
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bundle.export(dir, out, QUIET, new AtomicBoolean());
        byte[] bundle = out.toByteArray();
        check(bundle.length > 9600 && bundle.length < 40000, "the bundle holds the recording, not the samples (" + bundle.length + ")");
        File other = Files.createTempDirectory("killa-bundle-other").toFile();
        Bundle.Opened o = Bundle.open(other, new ByteArrayInputStream(bundle), bundle.length, QUIET, new AtomicBoolean());
        Project back = ProjectStore.load(o.dir).project;
        check(o.name.equals("Night drive") && back.tracks.size() == 2, "the project opens on the other side");
        check(java.util.Arrays.equals(Files.readAllBytes(new File(o.dir, "audio/vocal.wav").toPath()),
                                      Files.readAllBytes(new File(dir, "audio/vocal.wav").toPath())), "the recording is identical");
        check(o.missingPacks.size() == 1 && o.missingPacks.get(0).equals("grand"), "the grand piano pack is named as missing");
        check(Projects.list(other).size() == 1, "it is listed as a project");
        // a bundle that tries to write outside its folder
        ByteArrayOutputStream evil = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(evil)) {
            z.putNextEntry(new ZipEntry("project/../../escape.txt"));
            z.write("x".getBytes("UTF-8"));
            z.closeEntry();
        }
        boolean refused = false;
        try {
            Bundle.open(other, new ByteArrayInputStream(evil.toByteArray()), 100, QUIET, new AtomicBoolean());
        } catch (java.io.IOException e) {
            refused = true;
        }
        check(refused && !new File(other.getParentFile(), "escape.txt").exists(), "path escape refused");
        check(Projects.list(other).size() == 1, "and nothing is left behind");
        // a damaged recording
        byte[] damaged = bundle.clone();
        int at = indexOf(damaged, "project/audio/vocal.wav".getBytes("UTF-8")) + 23 + 300;
        damaged[at] ^= 0x55;
        refused = false;
        try {
            Bundle.open(other, new ByteArrayInputStream(damaged), damaged.length, QUIET, new AtomicBoolean());
        } catch (Exception e) {
            refused = true;
        }
        check(refused && Projects.list(other).size() == 1, "a damaged recording is refused and cleaned up");
        Projects.delete(root);
        Projects.delete(other);
        System.out.println("BundleTest: " + passed + " checks passed");
    }
    static int indexOf(byte[] b, byte[] k) {
        for (int i = 0; i + k.length <= b.length; i++) {
            boolean m = true;
            for (int j = 0; j < k.length && m; j++) m = b[i + j] == k[j];
            if (m)
                return i;
        }
        return -1;
    }
}
