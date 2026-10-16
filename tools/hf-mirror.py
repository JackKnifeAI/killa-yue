"""Copies the models Killa YuE uses to JackKnifeAI's Hugging Face account (see docs/models.md).

For each file: download from its source, check the app's SHA-256 pin, upload it with a README that credits the
authors and carries the licence, then check the copy's hash on Hugging Face. One file at a time, deleted once it
is up, so ops never holds more than one model. Files already up with the right hash are skipped, so it can be
run again after a cut. Run large copies at night (they saturate ops by day).

    python -I tools/hf-mirror.py [--dry-run] [repo ...]
"""
import hashlib
import os
import sys
import tempfile

from huggingface_hub import HfApi, hf_hub_download

OWNER = "JackKnifeAI"

# repo -> (licence id, README, [(source repo, source file, file name here, size, sha256)])
PLAN = {
    "whisper-small-ggml": ("mit", """Whisper small (OpenAI), quantised to q5_1 in whisper.cpp's ggml format, as used by Killa YuE's lyric sync.
Copied unchanged from [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp). Whisper is by OpenAI (MIT); the ggml
conversion is by the whisper.cpp authors (MIT).""", [
        ("ggerganov/whisper.cpp", "ggml-small-q5_1.bin", "ggml-small-q5_1.bin", 190085487,
         "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
    ]),
    "sd15-cover-gpu": ("creativeml-openrail-m", """The three files Killa YuE paints album covers with on phones without the Snapdragon 8 Elite NPU,
copied unchanged from their sources:

- `stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf`: Stable Diffusion 1.5 (Runway, CompVis, Stability AI) as GGUF, from
  [second-state/stable-diffusion-v1-5-GGUF](https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF).
  Licence: CreativeML OpenRAIL-M. Its use restrictions apply to everyone who uses it:
  https://huggingface.co/spaces/CompVis/stable-diffusion-license
- `lcm-lora-sdv1-5.safetensors`: the LCM LoRA for SD 1.5, from
  [latent-consistency/lcm-lora-sdv1-5](https://huggingface.co/latent-consistency/lcm-lora-sdv1-5). Licence: OpenRAIL++.
- `taesd.safetensors`: TAESD by Ollin Boer Bohan, from [madebyollin/taesd](https://huggingface.co/madebyollin/taesd).
  Licence: MIT.""", [
        ("second-state/stable-diffusion-v1-5-GGUF", "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
         "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf", 1763578176,
         "d0555243938c62faeefb4ac93f6c7a053ad373a4290c5256bce229aeb193bf94"),
        ("latent-consistency/lcm-lora-sdv1-5", "pytorch_lora_weights.safetensors", "lcm-lora-sdv1-5.safetensors", 134621556,
         "8f90d840e075ff588a58e22c6586e2ae9a6f7922996ee6649a7f01072333afe4"),
        ("madebyollin/taesd", "diffusion_pytorch_model.safetensors", "taesd.safetensors", 9793292,
         "db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549"),
    ]),
    "Gemma-4-E4B-abliterated-GGUF": ("apache-2.0", """The songwriter model Killa YuE's Write with AI suggests: huihui-ai's abliteration of Google's Gemma 4 E4B-it
([huihui-ai/Huihui-gemma-4-E4B-it-abliterated](https://huggingface.co/huihui-ai/Huihui-gemma-4-E4B-it-abliterated)),
quantised to Q4_K_M by mradermacher, copied unchanged from
[mradermacher/Huihui-gemma-4-E4B-it-abliterated-GGUF](https://huggingface.co/mradermacher/Huihui-gemma-4-E4B-it-abliterated-GGUF).
Licence: Apache-2.0, as both model cards state (https://ai.google.dev/gemma/docs/gemma_4_license).""", [
        ("mradermacher/Huihui-gemma-4-E4B-it-abliterated-GGUF", "Huihui-gemma-4-E4B-it-abliterated.Q4_K_M.gguf",
         "Huihui-gemma-4-E4B-it-abliterated.Q4_K_M.gguf", 5335286272,
         "d793116ec11cacac0228e7c907a40e9765969d76dd45708f0f638df008718f87"),
    ]),
    "YuE2-GGUF": ("cc-by-nc-4.0", """The YuE2 song model files Killa YuE makes songs with, copied unchanged from
[Serveurperso/YuE2-GGUF](https://huggingface.co/Serveurperso/YuE2-GGUF) (GGUF conversions by Serveurperso for yue2.cpp).
YuE is by M-A-P (Multimodal Art Projection): https://github.com/multimodal-art-projection/YuE

Licence: CC BY-NC 4.0, with the authors' additional permission for creators: individuals may use and monetise
what they make; companies need a licence from the authors for commercial use. Copied here, free to download,
for the free Killa YuE app.""", [
        ("Serveurperso/YuE2-GGUF", f, f, size, sha) for f, size, sha in [
            ("YuE2-3B-Q5_K_M.gguf", 2622936832, "cd3efd250b734a229172800f08f33a893ad1f66b521666e176cdae4a0729b281"),
            ("YuE2-3B-Q6_K.gguf", 2943331072, "42068d5e57713df11b9f6a5a3751072d9bbefe231d1d088f9c100e026ffa4ef9"),
            ("YuE2-3B-Q8_0.gguf", 3810232064, "41121ce97786d7795a325bcf123ca196956c03bb252c9e75384cfc1f2fc19e6b"),
            ("YuE2-Vae-F32.gguf", 530497344, "93e49dfb1970e89ad64cacb17cf13b5d05f6bb30ef7ed3adae3050bcb728638a"),
            ("MERT-v2-FullSong-Q8_0.gguf", 902283712, "35a6032ec7e8123c02becdefc7bfe10a3adb33faa8a1951dc0af864b4494b8ef"),
            ("SheetSage2-Q8_0.gguf", 105629856, "95d8fa8761753b4833782a52b68d5c0afa3bd3cfbc03d3dcbbf8ded0c13f611e"),
            ("yue2-mothersuperior-realaudio-tokenizer-v4-Q8_0.gguf", 46527328,
             "ab860529c61bcef0350d5e43d4291835b380e76246c48be4f26fa8839fe31ce5"),
        ]
    ]),
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def remote_sha(api, repo, name):
    """The LFS SHA-256 of a file already in the repo, or None."""
    try:
        for f in api.get_paths_info(repo, [name], expand=True):
            if getattr(f, "lfs", None):
                return f.lfs.sha256 if hasattr(f.lfs, "sha256") else f.lfs.get("sha256")
    except Exception:
        return None
    return None


def readme(repo, licence, body):
    return f"---\nlicense: {licence}\ntags:\n- killa-yue\n---\n\n# {repo}\n\n{body}\n\nCopied for [Killa YuE](https://github.com/JackKnifeAI/killa-yue): every file is pinned there by size and SHA-256.\n"


def main():
    dry = "--dry-run" in sys.argv
    only = [a for a in sys.argv[1:] if not a.startswith("--")]
    api = HfApi()
    for name, (licence, body, files) in PLAN.items():
        if only and name not in only:
            continue
        repo = f"{OWNER}/{name}"
        print(f"== {repo} ({licence})", flush=True)
        if not dry:
            api.create_repo(repo, repo_type="model", exist_ok=True)
            api.upload_file(path_or_fileobj=readme(repo, licence, body).encode(), path_in_repo="README.md", repo_id=repo,
                            commit_message="README: source, authors, licence")
        for src, src_name, dst_name, size, pin in files:
            have = None if dry else remote_sha(api, repo, dst_name)
            if have == pin:
                print(f"   {dst_name}: already there", flush=True)
                continue
            print(f"   {dst_name}: {size / 1e9:.2f} GB from {src}", flush=True)
            if dry:
                continue
            with tempfile.TemporaryDirectory(dir=os.path.expanduser("~/.cache")) as tmp:
                path = hf_hub_download(src, src_name, local_dir=tmp)
                if os.path.getsize(path) != size or sha256(path) != pin:
                    raise SystemExit(f"{src}/{src_name} does not match the app's pin: not copied")
                api.upload_file(path_or_fileobj=path, path_in_repo=dst_name, repo_id=repo,
                                commit_message=f"{dst_name} from {src} (SHA-256 {pin[:12]})")
            got = remote_sha(api, repo, dst_name)
            if got != pin:
                raise SystemExit(f"{repo}/{dst_name}: the copy's hash is {got}, not {pin}")
            print(f"   {dst_name}: up, hash checked", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
