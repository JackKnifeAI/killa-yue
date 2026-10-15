package ai.jackknife.killayue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The recogniser's progress, read from what whisper prints while it listens, and an honest time left. */
final class SyncProgress {
    private static final Pattern LINE = Pattern.compile("^whisper_print_progress_callback: progress =\\s*(\\d+)%");

    private SyncProgress() {
    }

    /** 0 to 100 for a whisper progress line, -1 for anything else (a recognised word, a log line). */
    static int percent(String line) {
        Matcher m = LINE.matcher(line.trim());
        return m.find() ? Math.min(100, Integer.parseInt(m.group(1))) : -1;
    }

    /** "about 6 minutes left" from how far it has got in the time so far; "" when it is too early to tell or done. */
    static String timeLeft(int percent, long elapsedMs) {
        if (percent < 5 || percent >= 100) return "";
        long leftMs = elapsedMs * (100 - percent) / percent;
        if (leftMs < 60000) return "under a minute left";
        long minutes = Math.round(leftMs / 60000.0);
        if (minutes == 1) return "about a minute left";
        return "about " + minutes + " minutes left";
    }
}
