// disk_test.cpp — THREADED
// One disk thread feeds every clip: a Studio with 16 tracks reads ahead on one thread, not 16.
#include "studio/disk.h"
#include "test.h"
#include <atomic>
#include <chrono>
#include <mutex>
#include <set>
#include <thread>
#include <vector>
using namespace studio;

static std::mutex g_mu;
static std::set<std::thread::id> g_readers;  // every thread that ever read a file

struct Ramp : Reader {  // sample i of the file is i + base (both channels)
    long n;
    float base;
    std::atomic<long> reads{0};
    Ramp(long len, float b) : n(len), base(b) {}
    long frames() const override { return n; }
    int channels() const override { return 2; }
    int read(long at, float* out, int frames) override {
        reads++;
        {
            std::lock_guard<std::mutex> l(g_mu);
            g_readers.insert(std::this_thread::get_id());
        }
        int k = (int)std::max(0L, std::min<long>(frames, n - at));
        for (int i = 0; i < k; i++) out[2 * i] = out[2 * i + 1] = base + (float)(at + i);
        return k;
    }
};

int main() {
    const int kTracks = 16;
    std::vector<Ramp*> files;
    std::vector<Streamer*> clips;
    Disk disk;
    for (int t = 0; t < kTracks; t++) {
        files.push_back(new Ramp(200000, 1000000.f * t));
        clips.push_back(new Streamer(files.back(), 100 * t, 0, 150000, 1 << 14));
        disk.add(clips.back());
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    std::vector<float> out(2 * 512);
    bool exact = true;
    for (long at = 0; at < 96000; at += 512) {
        for (int t = 0; t < kTracks; t++) {
            clips[t]->read(out.data(), 512);
            for (int i = 0; i < 512; i++) {
                const long p = at + i, start = 100 * t;
                const float want = p >= start ? 1000000.f * t + (float)(p - start) : 0.f;
                if (out[2 * i] != want) exact = false;
            }
        }
        std::this_thread::sleep_for(std::chrono::microseconds(10667));  // real time at 48 kHz
    }
    CHECK(exact, "16 clips on one disk thread: every frame is the right file frame");
    long starved = 0;
    for (auto* c : clips) starved += c->starved();
    CHECK(starved == 0, "one disk thread keeps 16 clips fed in real time");
    {
        std::lock_guard<std::mutex> l(g_mu);
        CHECK(g_readers.size() == 1, "all 16 files were read by one thread");
        CHECK(g_readers.count(std::this_thread::get_id()) == 0, "and never by the audio thread");
    }

    // a seek refills from the new place
    clips[3]->seek(120000);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    clips[3]->read(out.data(), 512);
    CHECK(out[0] == 3000000.f + (float)(120000 - 300), "after a seek the shared thread refills from the new place");

    // once removed, the disk thread never touches a clip again, so it can be freed straight away
    disk.remove(clips[5]);
    const long before = files[5]->reads.load();
    clips[5]->seek(0);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    CHECK(files[5]->reads.load() == before, "a removed clip is never read again");
    delete clips[5];
    clips[5] = nullptr;

    // a clip nobody feeds does not hang an export: off-line reads give up instead of waiting forever
    Ramp lonely(48000, 0.f);
    Streamer orphan(&lonely, 0, 0, 48000, 4096);
    orphan.setOffline(true);
    auto t0 = std::chrono::steady_clock::now();
    orphan.read(out.data(), 512);
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    CHECK(ms < 100 && orphan.starved() == 512, "an off-line read of a clip no disk thread feeds returns, starved");

    for (auto* c : clips)
        if (c) disk.remove(c);
    for (auto* c : clips) delete c;
    for (auto* f : files) delete f;
    DONE("disk_test");
}
