package ai.jackknife.killayue.studio;

/** StudioModels: every download the Studio offers is pinned by size and SHA-256, from JackKnifeAI first. */
public class StudioModelsTest {
    private static int failed, passed;

    static void same(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        }
    }

    public static void main(String[] a) {
        same("there are downloads", true, StudioModels.ALL.length > 0);
        boolean pinned = true, ours = true;
        for (StudioModels.File f : StudioModels.ALL) {
            pinned &= f.size > 0 && f.sha256.matches("[0-9a-f]{64}") && f.name.matches("[A-Za-z0-9._-]+");
            ours &= f.urls.length >= 1 && f.urls[0].startsWith("https://huggingface.co/JackKnifeAI/");
            for (String u : f.urls) pinned &= u.startsWith("https://");
        }
        same("every file has a size, a SHA-256 and a safe name", true, pinned);
        same("every file comes from JackKnifeAI first", true, ours);
        same("stems need the model and its runtime", 2, StudioModels.forFeature("stems").length);
        same("the stems download is about 400 MB", true, StudioModels.bytes("stems") > 350_000_000L && StudioModels.bytes("stems") < 450_000_000L);
        System.out.println("StudioModelsTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
