// stress_test.cpp — THREADED
// Sixteen stereo tracks played for ten minutes at real-time pace (192-frame blocks), while another thread
// changes levels, pans, mutes and solos every 5 ms and seeks every 20 s. No underrun is allowed outside the
// 300 ms after a seek (those are counted and printed), and no block may cost more than 2 ms of the audio
// thread's CPU time (its budget is 4 ms). Wall time per block and blocks over budget are reported too: a wait
// inside process() costs no CPU and shows only there (ops is shared with other work, so wall time is printed,
// not asserted; the phone run in plan 1b asserts it). Slow: run with STRESS=1 (STRESS_SECONDS to shorten it).
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <ctime>
#include <memory>
#include <random>
#include <string>
#include <thread>
#include <vector>
#include "studio/engine.h"
#include "studio/streamer.h"
#include "studio/wav.h"
#include "test.h"
using namespace studio;

struct Looping : Reader {  // a file read round and round, so a short file can feed a long clip
    std::unique_ptr<WavReader> r;
    explicit Looping(WavReader* w) : r(w) {}
    long frames() const override { return 1L << 40; }
    int channels() const override { return r->channels(); }
    int read(long at, float* out, int frames) override {
        int done = 0;
        while (done < frames) {
            const long a = (at + done) % r->frames();
            const int n = (int)std::min<long>(frames - done, r->frames() - a);
            const int got = r->read(a, out + (size_t)done * channels(), n);
            if (got <= 0) break;
            done += got;
        }
        return done;
    }
};

static double cpuMs() {
    timespec t;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

int main() {
    if (!std::getenv("STRESS")) { std::printf("stress_test: skipped (STRESS=1 to run)\n"); return 0; }
    const int rate = 48000, block = 192, tracks = 16;
    const long seconds = std::getenv("STRESS_SECONDS") ? std::atol(std::getenv("STRESS_SECONDS")) : 600;
    std::string err;
    const std::string stemPath = tmpPath("studio_stress_stem.wav");
    const char* stem = stemPath.c_str();
    {
        std::unique_ptr<WavWriter> w(WavWriter::create(stem, rate, 2, err));
        std::vector<float> s(2 * (size_t)rate * 30);
        for (int i = 0; i < rate * 30; i++) s[2 * i] = s[2 * i + 1] = 0.05f * (float)std::sin(2 * M_PI * 110 * i / rate);
        w->write(s.data(), rate * 30);
        w->finish();
    }
    std::vector<std::unique_ptr<Looping>> readers;
    std::vector<std::unique_ptr<Streamer>> streams;
    auto* session = new Session{rate, {}};
    for (int t = 0; t < tracks; t++) {
        readers.emplace_back(new Looping(WavReader::open(stem, err)));
        streams.emplace_back(new Streamer(readers.back().get(), 0, 0, seconds * rate + rate));
        streams.back()->startThread();
        session->tracks.push_back({streams.back().get(), -12.f, 0.f, false, false});
    }
    Engine e(rate, block);
    e.post({Msg::Swap, 0, 0, 0, session});
    e.post({Msg::Play, 0, 0, 0, nullptr});
    std::this_thread::sleep_for(std::chrono::milliseconds(300));

    std::atomic<bool> done{false};
    std::atomic<long> lastSeekMs{-100000};
    const auto t0 = std::chrono::steady_clock::now();
    auto msNow = [&] { return (long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count(); };
    std::thread ui([&] {
        std::mt19937 rng(7);
        long nextSeek = 20000;
        while (!done) {
            const int t = (int)(rng() % tracks);
            switch (rng() % 4) {
                case 0: e.post({Msg::Gain, t, -30.f + (rng() % 30), 0, nullptr}); break;
                case 1: e.post({Msg::Pan, t, ((int)(rng() % 201) - 100) / 100.f, 0, nullptr}); break;
                case 2: e.post({Msg::Mute, t, (float)(rng() % 2), 0, nullptr}); break;
                default: e.post({Msg::Solo, t, (float)(rng() % 8 == 0), 0, nullptr}); break;
            }
            if (msNow() >= nextSeek) {
                lastSeekMs = msNow();
                e.post({Msg::Seek, 0, 0, (long)(rng() % (seconds * rate / 2)), nullptr});
                nextSeek += 20000;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
    });

    const long blocks = seconds * rate / block;
    std::vector<float> out(2 * block), cpu(blocks), wall(blocks);
    long bad = 0, afterSeek = 0, prev = 0, late = 0;
    const double budgetMs = block * 1000.0 / rate;
    auto deadline = std::chrono::steady_clock::now();
    for (long b = 0; b < blocks; b++) {
        deadline += std::chrono::microseconds(block * 1000000L / rate);
        std::this_thread::sleep_until(deadline);
        const double c0 = cpuMs();
        const auto w0 = std::chrono::steady_clock::now();
        e.process(out.data(), block);
        cpu[b] = (float)(cpuMs() - c0);
        wall[b] = (float)(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - w0).count());
        if (wall[b] > budgetMs) late++;  // a wait inside process() shows here, not in CPU time
        const long u = e.status().underruns;
        if (u != prev) (msNow() - lastSeekMs < 300 ? afterSeek : bad) += u - prev;
        prev = u;
    }
    done = true;
    ui.join();
    for (auto& s : streams) s->stopThread();
    std::vector<float> sorted(cpu), wsorted(wall);
    std::sort(sorted.begin(), sorted.end());
    std::sort(wsorted.begin(), wsorted.end());
    std::printf("stress: %ld blocks; CPU slowest %.3f ms, p99 %.3f; wall slowest %.3f ms, p99 %.3f, %ld over the %.1f ms budget; "
                "underruns %ld (and %ld within 300 ms of a seek)\n",
                blocks, sorted.back(), sorted[(size_t)(blocks * 0.99)], wsorted.back(), wsorted[(size_t)(blocks * 0.99)], late, budgetMs, bad,
                afterSeek);
    CHECK(bad == 0, "no underruns while playing, outside the moment after a seek");
    CHECK(sorted.back() < 2.0f, "no block costs more than 2 ms of the audio thread's time");
    std::remove(stem);
    DONE("stress_test");
}
