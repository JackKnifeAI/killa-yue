// stem_bench: splits a song into stems with htdemucs on the phone and reports how long it took and how
// much memory it needed. It follows demucs's own apply_model (split, overlap 0.25, triangle weights, the
// last window padded with the audio before it) and separate.py's normalisation, so its stems can be held
// against the PyTorch reference on ops.
//   stem_bench MODEL.pte SONG_44100.wav OUTDIR [max_windows]
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <vector>
#include "executorch_ffi.h"
#include "stem_istft.hpp"
#include "studio/wav.h"

static const char* kNames[4] = {"drums", "bass", "other", "vocals"};

static void check(ETStatus* s, const char* what) {
    if (!s) {
        std::fprintf(stderr, "%s: no status\n", what);
        std::exit(2);
    }
    if (s->code != ET_OK) {
        std::fprintf(stderr, "%s: %s\n", what, s->message ? s->message : "failed");
        std::exit(2);
    }
    et_status_free(s);
}

static long peakKb() {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line))
        if (line.rfind("VmHWM:", 0) == 0) return std::atol(line.c_str() + 6);
    return -1;
}

static double seconds(std::chrono::steady_clock::time_point a) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - a).count();
}

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "stem_bench MODEL.pte SONG_44100.wav OUTDIR [max_windows]\n");
        return 1;
    }
    const long maxWindows = argc > 4 ? std::atol(argv[4]) : 1L << 30;
    std::string err;
    std::unique_ptr<studio::WavReader> in(studio::WavReader::open(argv[2], err));
    if (!in || in->rate() != 44100 || in->channels() != 2) {
        std::fprintf(stderr, "need a 44.1 kHz stereo WAV: %s\n", err.c_str());
        return 1;
    }
    const long n = in->frames();
    std::vector<float> mix(2 * (size_t)n);
    in->read(0, mix.data(), (int)n);

    // separate.py: ref = wav.mean(0); wav = (wav - ref.mean()) / ref.std()  (unbiased std)
    double sum = 0, sq = 0;
    for (long i = 0; i < n; i++) sum += 0.5 * (mix[2 * i] + mix[2 * i + 1]);
    const double mean = sum / n;
    for (long i = 0; i < n; i++) {
        const double d = 0.5 * (mix[2 * i] + mix[2 * i + 1]) - mean;
        sq += d * d;
    }
    const double sd = std::sqrt(sq / (n - 1));
    std::vector<float> chans(2 * (size_t)n);  // planar, normalised
    for (long i = 0; i < n; i++)
        for (int c = 0; c < 2; c++) chans[c * (size_t)n + i] = (float)((mix[2 * i + c] - mean) / sd);

    const bool vulkan = et_backend_available(ET_BACKEND_VULKAN);
    auto t0 = std::chrono::steady_clock::now();
    ETModule* module = nullptr;
    check(et_module_load_file(argv[1], &module), "load");
    const double loadS = seconds(t0);
    std::printf("vulkan backend %d, model loaded in %.2f s, peak memory %ld MB\n", vulkan ? 1 : 0, loadS, peakKb() / 1024);

    const int S = killa_stems::frames;            // 7.8 s at 44.1 kHz
    const long stride = (long)((1.0 - 0.25) * S);  // apply_model: int((1 - overlap) * segment)
    std::vector<float> weight(S);
    for (int i = 0; i < S / 2; i++) weight[i] = (float)(i + 1);
    for (int i = S / 2; i < S; i++) weight[i] = (float)(S - i);
    const float wmax = *std::max_element(weight.begin(), weight.end());
    for (auto& w : weight) w /= wmax;
    std::vector<float> out(8 * (size_t)n, 0.f), wsum((size_t)n, 0.f), window(2 * (size_t)S);

    long windows = 0;
    double modelS = 0, istftS = 0, worst = 0;
    const int T = (S + killa_stems::hop - 1) / killa_stems::hop;
    auto all = std::chrono::steady_clock::now();
    for (long offset = 0; offset < n && windows < maxWindows; offset += stride, windows++) {
        const long len = std::min<long>(S, n - offset);
        const long start = offset - (S - len) / 2;  // TensorChunk.padded: context from around the chunk
        for (int c = 0; c < 2; c++)
            for (int i = 0; i < S; i++) {
                const long at = start + i;
                window[c * (size_t)S + i] = at >= 0 && at < n ? chans[c * (size_t)n + at] : 0.f;
            }
        auto tw = std::chrono::steady_clock::now();
        int64_t shape[] = {1, 2, S};
        ETTensor* tensor = nullptr;
        check(et_tensor_create(window.data(), window.size() * sizeof(float), shape, 3, ET_DTYPE_FLOAT32, &tensor), "tensor");
        ETTensor** outs = nullptr;
        int32_t count = 0;
        check(et_module_forward(module, &tensor, 1, &outs, &count), "forward");
        const double m = seconds(tw);
        auto ti = std::chrono::steady_clock::now();
        std::vector<float> sep = killa_stems::reconstruct((const float*)et_tensor_data(outs[0]), (const float*)et_tensor_data(outs[1]), T);
        istftS += seconds(ti);
        modelS += m;
        worst = std::max(worst, m);
        et_tensor_array_free(outs, count);
        et_tensor_free(tensor);
        const long trim = (S - len) / 2;  // center_trim
        for (int ch = 0; ch < 8; ch++)
            for (long i = 0; i < len; i++) out[ch * (size_t)n + offset + i] += weight[i] * sep[ch * (size_t)S + trim + i];
        for (long i = 0; i < len; i++) wsum[offset + i] += weight[i];
        std::printf("window %ld: model %.2f s, peak memory %ld MB\n", windows + 1, m, peakKb() / 1024);
        std::fflush(stdout);
    }
    const double total = seconds(all);
    const double covered = std::min<double>(n, (double)(windows - 1) * stride + S) / 44100.0;

    for (int s = 0; s < 4; s++) {
        std::string path = std::string(argv[3]) + "/" + kNames[s] + ".wav";
        std::unique_ptr<studio::WavWriter> w(studio::WavWriter::create(path.c_str(), 44100, 2, err));
        std::vector<float> inter(2 * (size_t)n);
        for (long i = 0; i < n; i++)
            for (int c = 0; c < 2; c++) {
                const float ws = wsum[i] > 0 ? wsum[i] : 1.f;
                inter[2 * i + c] = (float)(out[(s * 2 + c) * (size_t)n + i] / ws * sd + mean);
            }
        w->write(inter.data(), (int)n);
        w->finish();
    }
    std::printf("RESULT windows %ld, song %.1f s, covered %.1f s, model %.1f s (worst window %.2f s), istft %.1f s, total %.1f s = %.2fx real time, peak memory %ld MB\n",
                windows, n / 44100.0, covered, modelS, worst, istftS, total, total / covered, peakKb() / 1024);
    et_module_free(module);
    return 0;
}
