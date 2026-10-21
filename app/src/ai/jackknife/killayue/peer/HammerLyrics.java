package ai.jackknife.killayue.peer;

import java.io.*;
import java.security.KeyPair;
import java.util.Base64;

/**
 * A song's words, signed by its publisher like a cover: plain lines, or timed LRC lines ("[01:23.45] words") that
 * light up as they are sung. Adding lyrics never changes the audio hash.
 */
public final class HammerLyrics {
    public static final int LIMIT = 32768;
    public String song = "", text = "", key = "", sig = "";
    private byte[] payload() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        Hammer.text(out, "HAMMER lyrics 1", 32);
        Hammer.text(out, song, 64);
        Hammer.text(out, text, LIMIT);
        return b.toByteArray();
    }
    public void sign(KeyPair pair) throws Exception {
        key = Hammer.publicKey(pair.getPublic());
        sig = Base64.getEncoder().encodeToString(Hammer.signature(pair.getPrivate(), payload()));
        validate();
    }
    public void validate() throws Exception {
        Hammer.require(Hammer.digest(song) && !text.trim().isEmpty(), "Invalid lyrics");
        Hammer.require(Hammer.verify(key, sig, payload()), "Lyrics signature does not match");
    }
    public void write(DataOutputStream out) throws IOException {
        Hammer.text(out, song, 64);
        Hammer.text(out, text, LIMIT);
        Hammer.text(out, key, 256);
        Hammer.text(out, sig, 256);
    }
    public static HammerLyrics read(DataInputStream in) throws Exception {
        HammerLyrics l = new HammerLyrics();
        l.song = Hammer.text(in, 64);
        l.text = Hammer.text(in, LIMIT);
        l.key = Hammer.text(in, 256);
        l.sig = Hammer.text(in, 256);
        l.validate();
        return l;
    }

    /** One timed line; {@code ms} is -1 for untimed lyrics. */
    public static final class Line {
        public final long ms;
        public final String words;
        Line(long ms, String words) {
            this.ms = ms;
            this.words = words;
        }
    }
    /** The lines, timed when the text is LRC; section tags such as "[Chorus]" are dropped. */
    public java.util.List<Line> lines() {
        java.util.List<Line> out = new java.util.ArrayList<Line>();
        java.util.regex.Pattern stamp = java.util.regex.Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        for (String raw : text.split("\r?\n")) {
            java.util.regex.Matcher m = stamp.matcher(raw);
            java.util.List<Long> times = new java.util.ArrayList<Long>();
            int end = 0;
            while (m.find() && m.start() == end) {
                String frac = m.group(3) == null ? "0" : m.group(3);
                long f = Long.parseLong(frac) * (frac.length() == 1 ? 100 : frac.length() == 2 ? 10 : 1);
                times.add(Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000 + f);
                end = m.end();
            }
            String words = raw.substring(end).trim();
            if (words.isEmpty() || words.matches("\\[[^\\]]*\\]") || raw.matches("\\[[a-z]+:.*\\]"))
                continue;  // blank, a section tag, or LRC metadata such as [ar:...]
            if (times.isEmpty())
                out.add(new Line(-1, words));
            for (long t : times)
                out.add(new Line(t, words));
        }
        java.util.Collections.sort(out, new java.util.Comparator<Line>() {
            public int compare(Line a, Line b) { return Long.compare(a.ms, b.ms); }
        });
        return wordTimed(out) ? phrases(out) : out;
    }
    /** Timed word by word (as Suno writes them): most "lines" are a single word. */
    private static boolean wordTimed(java.util.List<Line> lines) {
        if (lines.size() < 12 || lines.get(0).ms < 0)
            return false;
        int single = 0;
        for (Line l : lines)
            if (!l.words.trim().contains(" "))
                single++;
        return single * 10 >= lines.size() * 7;
    }
    /**
     * Rebuilds sung lines from timed words: a line ends at a pause, at the end of a sentence or clause, or after
     * ten words; bracketed tags such as "[Verse 1]" or "[Old Master Voice Sample]" are dropped even when split
     * across words.
     */
    private static java.util.List<Line> phrases(java.util.List<Line> words) {
        java.util.List<Line> out = new java.util.ArrayList<Line>();
        StringBuilder line = new StringBuilder();
        long start = -1, last = -1;
        int count = 0;
        boolean tag = false;
        for (Line w : words) {
            StringBuilder kept = new StringBuilder();
            for (char ch : w.words.toCharArray()) {
                if (ch == '[')
                    tag = true;
                else if (ch == ']')
                    tag = false;
                else if (!tag)
                    kept.append(ch);
            }
            String word = kept.toString().trim();
            if (line.length() > 0 && (w.ms - last > 900 || count >= 10)) {
                out.add(new Line(start, line.toString()));
                line.setLength(0);
                count = 0;
            }
            last = w.ms;
            if (word.isEmpty())
                continue;
            if (line.length() == 0)
                start = w.ms;
            else
                line.append(' ');
            line.append(word);
            count++;
            if (word.matches(".*[.!?;:,\"”]$") && count >= 3) {
                out.add(new Line(start, line.toString()));
                line.setLength(0);
                count = 0;
            }
        }
        if (line.length() > 0)
            out.add(new Line(start, line.toString()));
        return out;
    }
}
