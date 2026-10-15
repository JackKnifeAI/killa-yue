package ai.jackknife.killayue;

import java.io.File;
import java.nio.file.Files;

/**
 * Tests for About: what the app says about itself and the licence texts it carries. The APK must carry the same
 * licence files as the repository, and the About text must name everything the app bundles and every way it
 * uses the network. Run from app/ (run.sh does).
 */
public class AboutTest {
    private static int failed, passed;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + (detail.isEmpty() ? "" : "\n  " + detail));
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), "UTF-8");
    }

    private static void sameFile(String name, String repo, String asset) throws Exception {
        check(name, new File(asset).isFile() && read(repo).equals(read(asset)), asset + " differs from " + repo + " (copy it over)");
    }

    public static void main(String[] a) throws Exception {
        sameFile("the APK carries the repository's licence file", "../THIRD_PARTY_LICENSES.md", "assets/licenses/THIRD_PARTY_LICENSES.txt");
        sameFile("the APK carries the Rust crates' licences", "../engine/scout/THIRD_PARTY_LICENSES.md", "assets/licenses/RUST_CRATES.txt");

        for (String part : new String[] {"yue2.cpp", "GGML", "Mesa Turnip", "Vulkan loader", "zlib", "Zstandard", "Expat", "libc++",
                "Killa typefaces", "QNN", "CLIP", "Project Nayuki", "stable-diffusion.cpp", "whisper.cpp", "miniaudio", "llama.cpp",
                "cpp-httplib", "nlohmann/json", "xxHash", "Rust", "abcjs", "Gemma 4 E4B"})
            check("About names " + part, About.TEXT.contains(part), "");
        check("About tells the truth about the web: research searches it", About.TEXT.contains("search")
                && !About.TEXT.contains("the only network use is downloading"), "");
        check("About's title is the installed version", About.title("1.3.0").equals("Killa YuE 1.3.0"), About.title("1.3.0"));
        check("About's title without a version", About.title(null).equals("Killa YuE"), About.title(null));

        System.out.println("AboutTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
