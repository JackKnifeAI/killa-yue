// stems_test.cpp
// Splitting a song into stems, with a stand-in model: demucs's windows (7.8 s, a quarter overlapping, triangle
// weights, the song's own normalisation) put back together exactly, written as they finish, and a job that is
// stopped part way carries on to the very same result.
#include "studio/stems.h"
#include "studio/wav.h"
#include "test.h"
#include <atomic>
#include <cmath>
#include <cstdio>
#include <memory>
#include <string>
#include <sys/stat.h>
#include <vector>
using namespace studio;

// A stand-in for Demucs: each stem is a share of the window (0.1, 0.2, 0.3, 0.4) plus an offset taken from the
// window's own content, so overlapping windows disagree and only the right blend gives the right answer.
static void stand(const float* in, float* out) {
    const int S = StemJob::kWindow;
    const float share[4] = {0.1f, 0.2f, 0.3f, 0.4f};
    for (int s = 0; s < 4; s++)
        for (int c = 0; c < 2; c++) {
            const float offset = 0.2f * in[(size_t)c * S + S / 3] * (s + 1);
            for (int i = 0; i < S; i++) out[((size_t)s * 2 + c) * S + i] = share[s] * in[(size_t)c * S + i] + offset;
        }
}

struct Shares : StemModel {
    int calls = 0, stopAfter = -1;
    bool plain = false;  // only the shares (no offsets): a stem is then exactly its share of the window
    std::atomic<bool>* cancel = nullptr;
    bool run(const float* in, float* out, std::string&) override {
        stand(in, out);
        if (plain) {
            const int S = StemJob::kWindow;
            const float share[4] = {0.1f, 0.2f, 0.3f, 0.4f};
            for (int s = 0; s < 4; s++)
                for (int c = 0; c < 2; c++)
                    for (int i = 0; i < S; i++) out[((size_t)s * 2 + c) * S + i] = share[s] * in[(size_t)c * S + i];
        }
        if (++calls == stopAfter && cancel) cancel->store(true);
        return true;
    }
};

// demucs's apply_model the plain way, the whole song in memory: the answer the streaming job must give
static std::vector<std::vector<float>> reference(const std::vector<float>& x, long n) {
    const int S = StemJob::kWindow, stride = StemJob::kStride;
    double mean = 0, sq = 0;
    for (long i = 0; i < n; i++) mean += 0.5 * ((double)x[2 * i] + x[2 * i + 1]);
    mean /= n;
    for (long i = 0; i < n; i++) {
        double d = 0.5 * ((double)x[2 * i] + x[2 * i + 1]) - mean;
        sq += d * d;
    }
    const double sd = std::sqrt(sq / (n - 1));
    std::vector<double> acc(8 * (size_t)n, 0.0), ws((size_t)n, 0.0);
    std::vector<float> win(2 * (size_t)S), out(8 * (size_t)S);
    for (long o = 0; o < n; o += stride) {
        const long len = std::min<long>(S, n - o), start = o - (S - len) / 2;
        for (int c = 0; c < 2; c++)
            for (int i = 0; i < S; i++) {
                const long at = start + i;
                win[(size_t)c * S + i] = at >= 0 && at < n ? (float)((x[2 * at + c] - mean) / sd) : 0.f;
            }
        stand(win.data(), out.data());
        for (long i = 0; i < len; i++) {
            const double w = (i < S / 2 ? i + 1 : S - i) / (double)(S / 2);
            ws[o + i] += w;
            for (int c = 0; c < 8; c++) acc[(size_t)c * n + o + i] += w * out[(size_t)c * S + (S - len) / 2 + i];
        }
    }
    std::vector<std::vector<float>> stems(4, std::vector<float>(2 * (size_t)n));
    for (int s = 0; s < 4; s++)
        for (long i = 0; i < n; i++)
            for (int c = 0; c < 2; c++) stems[s][2 * i + c] = (float)(acc[(size_t)(s * 2 + c) * n + i] / ws[i] * sd + mean);
    return stems;
}

static std::vector<float> readAll(const std::string& p, int* rate = nullptr) {
    std::string err;
    std::unique_ptr<WavReader> r(WavReader::open(p.c_str(), err));
    if (!r) return {};
    if (rate) *rate = r->rate();
    std::vector<float> s(2 * (size_t)r->frames());
    r->read(0, s.data(), (int)r->frames());
    return s;
}

int main() {
    const std::string dir = tmpPath("stems-test");
    mkdir(dir.c_str(), 0755);
    const std::string song = dir + "/song.wav";
    const long n = 44100 * 20 + 1234;  // 20 s: three full windows and a short last one
    {
        std::string err;
        std::unique_ptr<WavWriter> w(WavWriter::create(song.c_str(), 44100, 2, err));
        std::vector<float> s(2 * (size_t)n);
        for (long i = 0; i < n; i++) {
            s[2 * i] = (float)(0.3 * std::sin(2 * M_PI * 110 * i / 44100.0) + 0.05);
            s[2 * i + 1] = (float)(0.2 * std::sin(2 * M_PI * 165 * i / 44100.0) - 0.02);
        }
        w->write(s.data(), (int)n);
        w->finish();
    }
    const std::vector<float> src = readAll(song);
    const std::vector<std::vector<float>> want = reference(src, n);
    std::string err;
    {   // the whole job: four stems, each its share of the song, exactly, the song's length
        Shares m;
        StemJob job(song, dir + "/out", dir + "/work", 44100);
        mkdir((dir + "/out").c_str(), 0755);
        mkdir((dir + "/work").c_str(), 0755);
        CHECK(job.run(m, nullptr, err), "a song splits");
        CHECK(m.calls == StemJob::windows(n), "one model run per window");
        const char* names[4] = {"drums", "bass", "other", "vocals"};
        const float share[4] = {0.1f, 0.2f, 0.3f, 0.4f};
        double worst = 0;
        bool lengths = true;
        for (int s = 0; s < 4; s++) {
            auto st = readAll(dir + "/out/" + names[s] + ".wav");
            if (st.size() != src.size()) lengths = false;
            for (size_t i = 0; i < st.size() && i < src.size(); i++) worst = std::max(worst, (double)std::fabs(st[i] - want[s][i]));
        }
        (void)share;
        CHECK(lengths, "every stem is as long as the song");
        std::printf("  worst difference from demucs's own blend: %.2g\n", worst);
        CHECK(worst < 2e-6, "the windows blend exactly as demucs blends them (streamed, against the whole song in memory)");
        CHECK(job.progress() == 1.0, "progress reached the end");
    }
    {   // stopped after two windows, then run again: the same stems, bit for bit
        std::atomic<bool> cancel{false};
        Shares m;
        m.cancel = &cancel;
        m.stopAfter = 2;
        mkdir((dir + "/out2").c_str(), 0755);
        mkdir((dir + "/work2").c_str(), 0755);
        StemJob job(song, dir + "/out2", dir + "/work2", 44100);
        CHECK(!job.run(m, &cancel, err) && err.find("cancel") != std::string::npos, "a stopped job says so");
        Shares again;
        StemJob job2(song, dir + "/out2", dir + "/work2", 44100);
        CHECK(job2.run(again, nullptr, err), "and carries on when run again");
        CHECK(again.calls == StemJob::windows(n) - 2, "from where it stopped (the first two windows are not run again)");
        bool same = true;
        for (const char* s : {"drums", "bass", "other", "vocals"})
            if (readAll(dir + "/out2/" + s + ".wav") != readAll(dir + "/out/" + s + ".wav")) same = false;
        CHECK(same, "the stems are exactly those of an uninterrupted job");
    }
    {   // a 48 kHz song comes back at 48 kHz (Demucs runs at 44.1 inside)
        const std::string s48 = dir + "/song48.wav";
        std::unique_ptr<WavWriter> w(WavWriter::create(s48.c_str(), 48000, 2, err));
        std::vector<float> s(2 * 48000 * 9);
        for (long i = 0; i < 48000 * 9; i++) s[2 * i] = s[2 * i + 1] = (float)(0.25 * std::sin(2 * M_PI * 440 * i / 48000.0));
        w->write(s.data(), 48000 * 9);
        w->finish();
        w.reset();
        mkdir((dir + "/out3").c_str(), 0755);
        mkdir((dir + "/work3").c_str(), 0755);
        Shares m;
        m.plain = true;
        StemJob job(s48, dir + "/out3", dir + "/work3", 48000);
        CHECK(job.run(m, nullptr, err), "a 48 kHz song splits");
        int rate = 0;
        auto v = readAll(dir + "/out3/vocals.wav", &rate);
        CHECK(rate == 48000 && v.size() == s.size(), "its stems are at 48 kHz, as long as it");
        NEAR(v[2 * 24000 + 7], 0.4 * s[2 * 24000 + 7], 2e-4f, "and right (the vocals' share of the tone, through 44.1 kHz and back)");
    }
    std::string cmd = "rm -rf '" + dir + "'";
    if (std::system(cmd.c_str()) != 0) std::printf("(could not clean %s)\n", dir.c_str());
    DONE("stems_test");
}
