package ai.jackknife.killayue;

/** Tests for the songwriter's title: read from the <title> block or a "Title:" heading, cleaned to one short line. */
public class SongTextTitleTest {
    private static int failed, passed;

    private static void same(String name, String want, String got) {
        if (want.equals(got)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        String body = "<style>\ndark synthwave, 120 bpm\n</style>\n<lyrics>\n[Verse]\nNeon rain\n</lyrics>";
        SongText.Song s = SongText.parse("<title>\nNeon Rain\n</title>\n" + body);
        same("the title block", "Neon Rain", s.title);
        same("the style still reads", "dark synthwave, 120 bpm", s.style);
        same("quotes and a full stop come off", "Straight Off The Phone",
                SongText.parse("<title>\"Straight Off The Phone.\"</title>\n" + body).title);
        same("a Title: heading when the tag is missing", "Cut the Line",
                SongText.parse("**Title:** Cut the Line\n" + body).title);
        same("no title leaves it empty", "", SongText.parse(body).title);
        same("inside the thinking does not count", "", SongText.parse("<think><title>Draft</title></think>\n" + body).title);
        same("one line only", "Gold in the Wire", SongText.cleanTitle("Gold in the Wire\nA second line"));
        StringBuilder longT = new StringBuilder();
        for (int i = 0; i < 30; i++) longT.append("word ");
        same("at most 80 characters", true + "", (SongText.cleanTitle(longT.toString()).length() <= 80) + "");
        System.out.println("SongTextTitleTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
