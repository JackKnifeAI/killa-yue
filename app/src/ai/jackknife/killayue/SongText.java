package ai.jackknife.killayue;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The songwriter's text work, without Android: the messages sent to the model, reading its answer
 * into a style and lyrics, and cleaning the lyrics into the sections the song engine sings.
 */
public class SongText {
    static final int STYLE_CHARS = 1000, LYRICS_CHARS = 5000;

    private static final Pattern STYLE = Pattern.compile("<style>\\s*(.*?)\\s*(?:</style>|<lyrics>|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    // to the LAST closing tag (greedy): some models close </lyrics> after every section; with no
    // closing tag at all, to the end of the answer
    private static final Pattern LYRICS = Pattern.compile("<lyrics>\\s*(.*)</lyrics>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern LYRICS_OPEN = Pattern.compile("<lyrics>\\s*(.*)$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    // models that ignore the tags tend to write headings instead: "STYLE:", "**Sound description:**", "LYRICS:"
    private static final Pattern STYLE_HEAD = Pattern.compile("^[#*\\s]*(?:style(?: prompt)?|sound(?: description)?)\\s*[:*]+\\**\\s*(.*?)\\s*^[#*\\s]*lyrics\\s*[:*]+\\**\\s*(.*)$",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern LABEL = Pattern.compile("^\\s*[\\[(]?\\s*([A-Za-z][A-Za-z -]*?)\\s*\\d*\\s*[\\])]?\\s*:?\\s*$");
    // who sings a line, written in front of it ("Female: ...", "Choir 2: ..."); sung words like "Mama said:" stay
    private static final String SINGER = "(?i)^(?:male|female|man|woman|both|all|lead|choir|chorus|crowd|backing(?: vocals?)?|vocals?|singer|rapper|duet|him|her)\\s*\\d*\\s*:\\s+";
    // a direction in brackets inside a line: "(echo)", "(x2)", "[guitar riff]"; sung ad-libs like "(yeah)" stay
    private static final String DIRECTION = "(?i)\\s*[(\\[][^)\\]]*\\b(?:echo|repeat|x\\d|\\d+x|spoken|whisper\\w*|sound|sfx|instrumental|guitar|drums?|synths?|bass|piano|beat|fade\\w*|pause|silence|laugh\\w*|applause|crowd noise|riff|solo|tempo|key change|ad-?lib)\\b[^)\\]]*[)\\]]";
    private static final Pattern THINK = Pattern.compile("<think>.*?(</think>|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** The parts of a song as the model wrote them, after cleaning. */
    static class Song {
        String style = "", lyrics = "";
        boolean complete;    // both parts were found
    }

    static String system(String template, int styleChars, int lyricsChars) {
        return template.replace("{STYLE_CHARS}", String.valueOf(styleChars)).replace("{LYRICS_CHARS}", String.valueOf(lyricsChars));
    }

    static String newSong(String idea, String feel, String details) {
        StringBuilder b = new StringBuilder("Write a song about: ").append(idea.trim());
        if (feel != null && feel.trim().length() > 0) b.append("\nGenre and feel: ").append(feel.trim());
        if (details != null && details.trim().length() > 0) b.append("\nAlso: ").append(details.trim());
        return b.toString();
    }

    /** Asks for one part again ("style" or "lyrics"), showing the model the whole song so the parts stay matched. */
    static String rewrite(String part, String style, String lyrics, String note, int target) {
        StringBuilder b = new StringBuilder("Here is the song you wrote:\n<style>\n").append(style.trim())
                .append("\n</style>\n<lyrics>\n").append(lyrics.trim()).append("\n</lyrics>\n");
        if ("style".equals(part)) {
            b.append("Rewrite only the style prompt, true to these lyrics, about ").append(target).append(" characters long.");
        } else {
            b.append("Rewrite only the lyrics, in the same style, about ").append(target)
                    .append(" characters long. Keep the chorus hook unless asked otherwise.");
        }
        if (note != null && note.trim().length() > 0) b.append(' ').append(note.trim());
        b.append(" Answer with the <").append(part).append("> block only.");
        return b.toString();
    }

    /** A note that asks for the right length, or "" when the part is close enough (within 15%). */
    static String lengthNote(String part, int have, int target) {
        if (target <= 0 || Math.abs(have - target) <= target * 0.15) return "";
        String name = "style".equals(part) ? "The style prompt is" : "The lyrics are";
        if (have < target) {
            return name + " " + have + " characters; make " + ("style".equals(part) ? "it" : "them") + " about " + target
                    + ("lyrics".equals(part) ? " by adding verses and repeating the chorus." : " with more detail on the sound.");
        }
        return name + " " + have + " characters; tighten " + ("style".equals(part) ? "it" : "them") + " to about " + target + ".";
    }

    /** Reads a whole answer into style and lyrics. */
    static Song parse(String answer) {
        String a = THINK.matcher(answer).replaceAll("");
        Song s = new Song();
        Matcher ms = STYLE.matcher(a), ml = LYRICS.matcher(a);
        boolean hs = ms.find(), hl = ml.find();
        if (!hl) {
            ml = LYRICS_OPEN.matcher(a);
            hl = ml.find();
        }
        if (hs) s.style = ms.group(1);
        if (hl) s.lyrics = ml.group(1);
        if (!hs || !hl) {
            Matcher h = STYLE_HEAD.matcher(a);
            if (h.find()) {
                if (!hs) s.style = h.group(1);
                if (!hl) s.lyrics = h.group(2);
                hs = hl = true;
            }
        }
        s.style = cleanStyle(s.style);
        s.lyrics = cleanLyrics(s.lyrics);
        s.complete = hs && hl && s.style.length() > 0 && s.lyrics.length() > 0;
        return s;
    }

    /** Reads an answer that should hold one part: the block if it is there, else the whole answer. */
    static String parsePart(String part, String answer) {
        String a = THINK.matcher(answer).replaceAll("");
        Matcher m = ("style".equals(part) ? STYLE : LYRICS).matcher(a);
        boolean found = m.find();
        if (!found && !"style".equals(part)) {
            m = LYRICS_OPEN.matcher(a);
            found = m.find();
        }
        String body = found ? m.group(1) : a;
        return "style".equals(part) ? cleanStyle(body) : cleanLyrics(body);
    }

    static String cleanStyle(String s) {
        s = s.replaceAll("</?(style|lyrics)>", "").replace("**", "").replaceAll("(?m)^\\s*#+\\s*", "");
        return s.replaceAll("\\s*\\n\\s*", " ").replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * Lyrics as the engine sings them: sections under Killa's labels, one blank line between them,
     * and only words to be sung (no stage directions, markdown or speaker names).
     */
    static String cleanLyrics(String s) {
        s = s.replaceAll("</?(style|lyrics)>", "").replace("**", "").replace("__", "");
        String[] rows = s.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i].trim().replaceAll("^#+\\s*", "");
            if (r.length() == 0) continue;   // only section labels split the song
            String label = label(r);
            if (label != null) {
                if (out.length() > 0) out.append("\n\n");
                out.append(label);
                continue;
            }
            // a line that is only a direction: "(guitar solo)", "[Sound of rain]", "*whispered*"
            if (r.matches("^[(\\[*].*[)\\]*]$")) continue;
            r = r.replaceAll("\\s*\\*[^*]*\\*\\s*", " ").replaceAll(SINGER, "").replaceAll(DIRECTION, " ").replaceAll("\\s{2,}", " ").trim();
            if (r.length() == 0) continue;
            if (out.length() > 0) out.append("\n");
            out.append(r);
        }
        return out.toString().trim();
    }

    /** Killa's label for a section heading, or null when the line is not one. */
    static String label(String line) {
        boolean bracketed = line.startsWith("[") || line.startsWith("(");
        Matcher m = LABEL.matcher(line);
        if (!m.matches()) return null;
        String w = m.group(1).toLowerCase(Locale.US).replaceAll("[\\s-]+", " ").trim();
        if (!bracketed && !line.trim().endsWith(":") && w.split(" ").length > 2) return null;
        if (w.startsWith("verse")) return "[Verse]";
        if (w.startsWith("pre chorus") || w.startsWith("prechorus") || w.equals("build") || w.equals("lift")) return "[Pre-Chorus]";
        if (w.startsWith("post chorus") || w.startsWith("chorus") || w.equals("hook") || w.equals("refrain")) return "[Chorus]";
        if (w.startsWith("bridge") || w.equals("breakdown") || w.equals("middle eight")) return "[Bridge]";
        if (w.startsWith("intro")) return "[Intro]";
        if (w.startsWith("outro") || w.equals("coda") || w.equals("end") || w.equals("ending")) return "[Outro]";
        // instrumental parts have nothing to sing: they become no section of their own
        if (bracketed && (w.contains("instrumental") || w.contains("solo") || w.contains("interlude") || w.equals("break"))) return null;
        return null;
    }

    /** Section labels in order, for a quick check that the song has a shape. */
    static ArrayList<String> sections(String lyrics) {
        ArrayList<String> out = new ArrayList<String>();
        for (String r : lyrics.split("\n")) if (r.matches("^\\[[A-Za-z-]+\\]$")) out.add(r);
        return out;
    }
}
