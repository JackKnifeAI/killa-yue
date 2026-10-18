package ai.jackknife.killayue.compat;

/** The entire request language: fixed suites, fixed drivers, and correlation metadata. */
public final class CompatibilityPlan {
    public static final int SUITE_VERSION = 1;
    public static final int MAX_REQUEST_BYTES = 4096;
    public static final int PROCESS_TIMEOUT_MS = 90000;
    public final String suite, driver, requestId, candidate, deviceKey;
    public final int issue;

    public CompatibilityPlan(String suite, String driver, String requestId, int issue,
                             String candidate, String deviceKey) {
        if (!"capabilities".equals(suite) && !"math".equals(suite)) throw new IllegalArgumentException("Unknown test suite");
        if (!"cpu".equals(driver) && !"system".equals(driver) && !"turnip".equals(driver))
            throw new IllegalArgumentException("Unknown driver");
        if ("capabilities".equals(suite) && "cpu".equals(driver)) throw new IllegalArgumentException("Choose a GPU driver for capabilities");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{0,64}")) throw new IllegalArgumentException("Invalid request ID");
        if (issue < 0) throw new IllegalArgumentException("Invalid issue number");
        if (!hash(candidate) || !hash(deviceKey)) throw new IllegalArgumentException("Invalid build or device digest");
        if (requestId.length() > 0 && (issue == 0 || candidate.length() != 64 || deviceKey.length() != 64))
            throw new IllegalArgumentException("Retests must specify their issue, candidate APK and device configuration");
        if (requestId.length() == 0 && (issue != 0 || candidate.length() > 0 || deviceKey.length() > 0))
            throw new IllegalArgumentException("Retest metadata requires a request ID");
        this.suite = suite; this.driver = driver; this.requestId = requestId;
        this.issue = issue; this.candidate = candidate; this.deviceKey = deviceKey;
    }
    private static boolean hash(String value) { return value != null && (value.isEmpty() || value.matches("[0-9a-f]{64}")); }
    public boolean retest() { return !requestId.isEmpty(); }
}
