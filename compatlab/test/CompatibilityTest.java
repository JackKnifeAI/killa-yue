package ai.jackknife.killayue.compat;
public final class CompatibilityTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static ProcessBuilder fixture(String mode) {
        return new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
            "ai.jackknife.killayue.compat.ProcessFixture", mode);
    }
    public static void main(String[] args) throws Exception {
        DiagnosticProcess.Stop no = new DiagnosticProcess.Stop() { public boolean requested() { return false; } };
        long start = System.nanoTime();
        check("timeout".equals(DiagnosticProcess.run(fixture("hang"), 150, no).status));
        check(System.nanoTime() - start < 5000000000L);
        check("output_limit".equals(DiagnosticProcess.run(fixture("flood"), 5000, no).status));
        check(DiagnosticProcess.run(fixture("crash"), 5000, no).exitCode == 7);
        DiagnosticProcess.Result stderr = DiagnosticProcess.run(fixture("stderr"), 5000, no);
        check("result".equals(stderr.output));
        check(stderr.errors.size() == 1 && "VK_ERROR_DEVICE_LOST".equals(stderr.errors.get(0)));
        check("cancelled".equals(DiagnosticProcess.run(fixture("hang"), 5000,
            new DiagnosticProcess.Stop() { public boolean requested() { return true; } }).status));
        String hash = new String(new char[64]).replace('\0', 'a');
        check(new CompatibilityPlan("math", "system", "request-1", 1, hash, hash).retest());
        for (String[] invalid : new String[][] {
            {"shell", "system", "", "", ""}, {"math", "../../driver", "", "", ""},
            {"math", "system", "$(id)", hash, hash}, {"math", "system", "retest", "", hash}
        }) {
            boolean rejected = false;
            try { new CompatibilityPlan(invalid[0], invalid[1], invalid[2], 1, invalid[3], invalid[4]); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected);
        }
        System.out.println("PASS process timeout, pipe bounds, crash, cancellation, stderr privacy and request restrictions");
    }
}
