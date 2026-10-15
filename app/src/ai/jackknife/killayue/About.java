package ai.jackknife.killayue;

/** What the app says about itself in "About and licences". Plain Java, so the tests can read it. */
public final class About {
    private About() {}

    public static final String TEXT = "Songs made entirely on your phone. Your songs and lyrics stay on it unless you share them. "
            + "The app uses the network only to download models and adapters you ask for, to share with a phone you choose, "
            + "and, when Write with AI is set to look things up first, to send the search queries it writes from your request to "
            + "web search sites (DuckDuckGo, Mojeek, Marginalia, Wikipedia, Bing, GDELT, the Internet Archive, and Brave if you "
            + "give it a key) and read the pages they find.\n\n"
            + "Needs a phone with a Qualcomm Adreno GPU, Android 9 or later, about 6 GB of free memory for "
            + "full-length songs and 4 GB of storage for the model.\n\n"
            + "App: (c) 2026 JackKnife AI, Apache License 2.0.\n"
            + "Song model: YuE2 by M-A-P, weights under CC BY-NC 4.0 with the authors' creator permission; "
            + "downloaded separately, not part of this app. Companies need a licence from the authors for commercial use.\n"
            + "Engine: yue2.cpp by Serveurperso, MIT. GGML, MIT.\n"
            + "GPU driver: Mesa Turnip, MIT. Vulkan loader, Apache License 2.0.\n"
            + "zlib (zlib licence), Zstandard (BSD), Expat (MIT), libc++ (Apache 2.0 with LLVM exception).\n"
            + "Killa typefaces: SIL Open Font License 1.1.\n"
            + "Album covers: Qualcomm AI Engine Direct (QNN) runtime, under the Qualcomm AI Hub Model License, included in this app only; "
            + "QNN API headers (c) 2026 Qualcomm Technologies, Inc., BSD 3-Clause; CLIP tokenizer vocabulary (c) 2021 OpenAI, MIT. "
            + "QR codes: QR Code generator by Project Nayuki, MIT. "
            + "GPU covers: stable-diffusion.cpp by leejet, MIT; the GPU cover models (Stable Diffusion 1.5, OpenRAIL-M; LCM LoRA, OpenRAIL++; "
            + "TAESD, MIT) are downloaded separately. "
            + "Lyric sync: whisper.cpp by ggml-org, MIT; the Whisper small model (OpenAI, MIT) is downloaded separately. "
            + "The cover model (Stable Diffusion 1.5 as compiled by Qualcomm) is downloaded separately and is licensed CreativeML OpenRAIL-M.\n"
            + "Lyric sync includes miniaudio by David Reid, public domain or MIT No Attribution.\n"
            + "Sheet music: abcjs by Paul Rosen and Gregory Dyke, MIT.\n"
            + "Write with AI: llama.cpp by ggml-org, MIT, with cpp-httplib (MIT), nlohmann/json (MIT), xxHash (BSD 2-Clause) and "
            + "public-domain parts; its web research is built with Rust and 157 open-source crates (MIT, Apache 2.0, MPL 2.0 and others, "
            + "each listed with its licence text). The songwriter model, Gemma 4 E4B abliterated by huihui-ai "
            + "(Apache License 2.0), is downloaded separately.\n"
            + "Adapters you install belong to their makers and carry their own terms.\n\n"
            + "Killa YuE is an independent project and is not affiliated with or endorsed by the YuE2 authors.\n"
            + "Full licence texts are inside the app package (assets/licenses) and in the source repository: "
            + "github.com/JackKnifeAI/killa-yue";

    public static String title(String version) {
        return version == null || version.isEmpty() ? "Killa YuE" : "Killa YuE " + version;
    }
}
