# Models the app uses, and where we host them

Every model is downloaded on request, never shipped in the APK. Alexander decided on 15 October 2026 to host every model the app uses on JackKnifeAI's Hugging Face account ("yes and add all the other models the entire app uses as well!"). Each model is pinned by size and SHA-256, so a model can neither change underneath the app nor disappear. Before we host a copy, its licence must allow redistribution. Each repository's README credits the authors and carries the licence and any use restrictions. The app downloads from JackKnifeAI first and falls back to the original source.

Licences are as each model's Hugging Face card declared them on 15 October 2026.

| Model (app feature) | Files | Source | Licence | Redistributable | JackKnifeAI repository |
|---|---|---|---|---|---|
| YuE2 song model (making songs) | `YuE2-3B-{Q5_K_M,Q6_K,Q8_0}.gguf`, `YuE2-Vae-F32.gguf`, `MERT-v2-FullSong-Q8_0.gguf`, `SheetSage2-Q8_0.gguf`, `yue2-mothersuperior-realaudio-tokenizer-v4-Q8_0.gguf` | `Serveurperso/YuE2-GGUF` (from M-A-P's YuE) | CC BY-NC 4.0 (the authors also give creators permission to use and monetise what they make) | Yes, non-commercially and with attribution: it is free to download for a free app | `JackKnifeAI/YuE2-GGUF` |
| Whisper small (lyric sync) | `ggml-small-q5_1.bin` | `ggerganov/whisper.cpp` (OpenAI Whisper) | MIT | Yes | `JackKnifeAI/whisper-small-ggml` |
| Songwriter (Write with AI) | `Huihui-gemma-4-E4B-it-abliterated.Q4_K_M.gguf` | `mradermacher/Huihui-gemma-4-E4B-it-abliterated-GGUF` (huihui-ai's abliteration of Google's Gemma 4 E4B-it) | Apache-2.0 | Yes | `JackKnifeAI/Gemma-4-E4B-abliterated-GGUF` |
| GPU covers | `stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf` | `second-state/stable-diffusion-v1-5-GGUF` | CreativeML OpenRAIL-M (use restrictions travel with it) | Yes, with the licence and its restrictions | `JackKnifeAI/sd15-cover-gpu` |
| GPU covers | `pytorch_lora_weights.safetensors` (LCM LoRA) | `latent-consistency/lcm-lora-sdv1-5` | OpenRAIL++ | Yes, with the licence | `JackKnifeAI/sd15-cover-gpu` |
| GPU covers | `diffusion_pytorch_model.safetensors` (TAESD) | `madebyollin/taesd` | MIT | Yes | `JackKnifeAI/sd15-cover-gpu` |
| NPU covers (Snapdragon 8 Elite) | Qualcomm's compiled Stable Diffusion 1.5 bundle | Qualcomm AI Hub (public assets bucket; HF card `qualcomm/Stable-Diffusion-v1.5`) | CreativeML OpenRAIL-M on the card; the compiled bundle comes from Qualcomm's own servers | Not until Qualcomm's terms for the compiled bundle are confirmed | stays at its source for now |
| Stems (Studio, 1.4.0) | `htdemucs` exported for ExecuTorch (Vulkan) | Meta's Demucs v4 (`htdemucs`) | MIT | Yes, as our own export from Meta's weights (the demixr export's licence is not stated) | `JackKnifeAI/htdemucs-executorch` |
| Stems runtime | `libexecutorch_ffi.so` (ExecuTorch 1.3.1 with Vulkan) | PyTorch ExecuTorch (BSD-3) through executorch_native (MIT) | BSD-3 and MIT | Yes | `JackKnifeAI/htdemucs-executorch` |
| Instruments (Studio, phase 3) | SFZ packs | Salamander Grand (CC-BY-3.0), VSCO-2 CE (CC0) | CC-BY-3.0, CC0 | Yes, with attribution | phase 3 |
| AI beats and voices (Studio, phase 6) | ACE-Step 1.5 GGUF, VoxCPM2 GGUF | `Serveurperso/ACE-Step-1.5-GGUF`, `DennisHuang648/VoxCPM2-GGUF` | to check in phase 6 (ACE-Step, Qwen and VoxCPM2 weights) | after the check | phase 6, only if they pass the speed gate |

## How the copies are made

`tools/hf-mirror.py` copies a source repository's pinned files to a JackKnifeAI repository. For each file it:

1. downloads the file;
2. checks its SHA-256 against the app's pin;
3. uploads it with a README that names the source, its authors, the licence and the use restrictions;
4. downloads the copy again and checks its hash a second time.

Large copies run at night (1 to 7 AM), because a big transfer during the day saturates ops.
