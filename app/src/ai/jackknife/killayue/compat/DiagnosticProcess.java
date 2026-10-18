package ai.jackknife.killayue.compat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Drains both pipes with bounded storage; the UI can cancel even if a native driver hangs. */
public final class DiagnosticProcess {
    public interface Stop { boolean requested(); }
    public static final class Result {
        public final String output, status;
        public final java.util.List<String> errors;
        public final int exitCode;
        Result(String output, String status, int exitCode, String stderr) {
            this.output = output; this.status = status; this.exitCode = exitCode;
            errors = new java.util.ArrayList<String>();
            for (String code : new String[] {"VK_ERROR_DEVICE_LOST", "VK_ERROR_OUT_OF_HOST_MEMORY",
                    "VK_ERROR_OUT_OF_DEVICE_MEMORY", "VK_ERROR_INITIALIZATION_FAILED", "VK_ERROR_FEATURE_NOT_PRESENT",
                    "VK_ERROR_EXTENSION_NOT_PRESENT", "VK_ERROR_INCOMPATIBLE_DRIVER", "VK_ERROR_UNKNOWN"})
                if (stderr.contains(code)) errors.add(code);
        }
    }
    private static final class Drain extends Thread {
        final InputStream input;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int limit;
        volatile boolean overflow;
        Drain(InputStream input, int limit) { this.input = input; this.limit = limit; setDaemon(true); }
        public void run() {
            byte[] chunk = new byte[4096];
            try {
                int n;
                while ((n = input.read(chunk)) != -1) {
                    synchronized (bytes) {
                        int keep = Math.min(n, Math.max(0, limit - bytes.size()));
                        bytes.write(chunk, 0, keep);
                        if (keep < n && limit != 0) overflow = true;
                    }
                }
            } catch (Exception ignored) { }
        }
        String text() throws Exception { synchronized (bytes) { return bytes.toString("UTF-8"); } }
    }
    public static Result run(ProcessBuilder builder, long timeoutMs, Stop stop) throws Exception {
        if (stop.requested()) return new Result("", "cancelled", -1, "");
        Process process = builder.start();
        Drain out = new Drain(process.getInputStream(), 65536);
        // Keep bounded diagnostic stderr only in RAM to extract known error codes; never export raw logs.
        Drain err = new Drain(process.getErrorStream(), 65536);
        out.start(); err.start();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String status = "finished";
        int exit = -1;
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (stop.requested()) { status = "cancelled"; break; }
                if (out.overflow) { status = "output_limit"; break; }
                if (System.nanoTime() >= deadline) { status = "timeout"; break; }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            if (!process.isAlive()) exit = process.exitValue();
            out.join(1000); err.join(1000);
            if (out.overflow) status = "output_limit";
            return new Result(out.text(), status, exit, err.text());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
        }
    }
}
