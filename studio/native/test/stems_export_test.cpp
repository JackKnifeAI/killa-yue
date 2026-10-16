// stems_export_test.cpp — THREADED
// Exporting from a project: progress that only goes up, a cancel that leaves nothing behind, the master limiter
// bypassed for stems, and stems that add up to the mix.
#include "studio/host.h"
#include "test.h"
#include <atomic>
#include <cmath>
#include <cstdio>
#include <memory>
#include <thread>
#include <vector>
using namespace studio;

static std::string wav(const char* name, int rate, float freq, float amp, long frames) {
    std::string path = tmpPath(name), err;
    std::unique_ptr<WavWriter> w(WavWriter::create(path.c_str(), rate, 2, err));
    std::vector<float> s(2 * (size_t)frames);
    for (long i = 0; i < frames; i++) s[2 * i] = s[2 * i + 1] = (float)(amp * std::sin(2 * M_PI * freq * i / rate));
    w->write(s.data(), (int)frames);
    w->finish();
    return path;
}

static std::vector<float> readAll(const std::string& path) {
    std::string err;
    std::unique_ptr<WavReader> r(WavReader::open(path.c_str(), err));
    std::vector<float> s(2 * (size_t)(r ? r->frames() : 0));
    if (r) r->read(0, s.data(), (int)r->frames());
    return s;
}

static bool exists(const std::string& p) {
    FILE* f = std::fopen(p.c_str(), "rb");
    if (f) std::fclose(f);
    return f != nullptr;
}

int main() {
    const int rate = 48000;
    const std::string a = wav("sx-a.wav", rate, 220, 0.7f, 3 * rate), b = wav("sx-b.wav", rate, 330, 0.6f, 3 * rate);
    std::string err;
    std::vector<TrackSpec> both = {{0.f, 0.f, false, false, {{a, 0, 0, 3L * rate, 0.f}}}, {-3.f, 0.3f, false, false, {{b, 0, 0, 3L * rate, 0.f}}}};
    const std::string mix = tmpPath("sx-mix.wav"), raw = tmpPath("sx-raw.wav"), s1 = tmpPath("sx-s1.wav"), s2 = tmpPath("sx-s2.wav");
    {
        std::unique_ptr<Host> h(Host::offline(rate));
        h->load(both, err);
        std::atomic<bool> done{false};
        std::vector<long> seen;
        std::thread watch([&] {
            while (!done) {
                seen.push_back(h->exportProgress());
                std::this_thread::sleep_for(std::chrono::microseconds(200));
            }
        });
        CHECK(h->exportMix(mix.c_str(), 3L * rate, true, err), "the mix exports");
        done = true;
        watch.join();
        bool up = true;
        for (size_t i = 1; i < seen.size(); i++) up = up && seen[i] >= seen[i - 1];
        CHECK(up && h->exportProgress() == 3L * rate, "progress only goes up, and ends at the length");
        float peak = 0;
        for (float v : readAll(mix)) peak = std::max(peak, std::fabs(v));
        CHECK(peak <= 0.9661f, "with the limiter: never above its ceiling (-0.3 dB)");
        CHECK(h->exportMix(raw.c_str(), 3L * rate, false, err), "and again with the limiter bypassed");
        peak = 0;
        for (float v : readAll(raw)) peak = std::max(peak, std::fabs(v));
        CHECK(peak > 1.0f, "bypassed: the sum goes over full scale, as it is (a float file keeps it)");
    }
    {   // stems: each track alone, after its fader, limiter bypassed; together they are the mix
        std::unique_ptr<Host> h(Host::offline(rate));
        h->load({both[0]}, err);
        h->exportMix(s1.c_str(), 3L * rate, false, err);
        h->load({both[1]}, err);
        h->exportMix(s2.c_str(), 3L * rate, false, err);
        auto x = readAll(s1), y = readAll(s2), m = readAll(raw);
        double worst = 0;
        for (size_t i = 0; i < m.size() && i < x.size() && i < y.size(); i++) worst = std::max(worst, (double)std::fabs(x[i] + y[i] - m[i]));
        CHECK(x.size() == m.size() && worst < 1e-6, "the stems add up to the mix (within 1e-6)");
    }
    {   // cancelled: nothing is left behind
        std::unique_ptr<Host> h(Host::offline(rate));
        h->load(both, err);
        const std::string gone = tmpPath("sx-gone.wav");
        std::thread cancel([&] {
            while (h->exportProgress() < rate / 2) std::this_thread::sleep_for(std::chrono::microseconds(100));
            h->cancelExport();
        });
        const bool ok = h->exportMix(gone.c_str(), 3L * rate, true, err);
        cancel.join();
        CHECK(!ok && err.find("cancel") != std::string::npos, "a cancelled export says so");
        CHECK(!exists(gone) && !exists(gone + ".part"), "and leaves no file behind");
        CHECK(h->exportMix(gone.c_str(), rate, true, err) && exists(gone), "the next export works");
        std::remove(gone.c_str());
    }
    for (auto& p : {a, b, mix, raw, s1, s2}) std::remove(p.c_str());
    DONE("stems_export_test");
}
