package ai.jackknife.killayue.studio;

import java.util.ArrayList;

/**
 * Every model and runtime the Studio downloads, pinned by size and SHA-256, from JackKnifeAI's Hugging Face
 * first (docs/models.md). Plain Java; the downloads themselves use Store.fetch.
 */
public final class StudioModels {
    public static final class File {
        public final String feature, name, sha256;
        public final long size;
        public final String[] urls;

        File(String feature, String name, long size, String sha256, String... urls) {
            this.feature = feature;
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
            this.urls = urls;
        }
    }

    static final String HTDEMUCS = "https://huggingface.co/JackKnifeAI/htdemucs-executorch/resolve/main/";
    static final String VOICE = "https://huggingface.co/JackKnifeAI/killa-studio-voice/resolve/main/";
    static final String STUDIO_AI = "https://github.com/JackKnifeAI/killa-yue/releases/download/studio-ai-v1/";

    public static final File[] ALL = {
        // Demucs v4 (Meta, MIT) exported for ExecuTorch with Vulkan, and the ExecuTorch 1.3.1 runtime it runs on
        new File("stems", "htdemucs_vulkan.pte", 302594560L, "7c8db21cb65c11b2befeb8e2e6538b56c6ad9d275e82d0151e1d3d08cc22f296",
                HTDEMUCS + "htdemucs_vulkan.pte"),
        new File("stems", "libexecutorch_ffi.so", 111533272L, "cb8391689f77d86b9f6d7866985695485d5bc7651243e6ca699764e51c175d71",
                HTDEMUCS + "libexecutorch_ffi.so"),
        // Speak: Kokoro-82M v1.0 (Apache-2.0) on sherpa-onnx (Apache-2.0) and ONNX Runtime (MIT), unpacked into voice/
        new File("voice", "killa-voice-v1.zip", 355131969L, "7b3128b89ba5688ce12e41202df396ea8e18513da56d33c0d426502bd81dac98",
                VOICE + "killa-voice-v1.zip", STUDIO_AI + "killa-voice-v1.zip"),
        // Beats: Stable Audio Open Small (Stability AI Community License) on LiteRT, unpacked into beats/, joins here
        // once the converted model is published; until then the Beat Lab says it is coming.
    };

    /** A feature whose files are published (beats waits for its model's licence). */
    public static boolean available(String feature) {
        return forFeature(feature).length > 0;
    }

    private StudioModels() {}

    public static File[] forFeature(String feature) {
        ArrayList<File> out = new ArrayList<File>();
        for (File f : ALL) if (f.feature.equals(feature)) out.add(f);
        return out.toArray(new File[0]);
    }

    public static long bytes(String feature) {
        long n = 0;
        for (File f : forFeature(feature)) n += f.size;
        return n;
    }
}
