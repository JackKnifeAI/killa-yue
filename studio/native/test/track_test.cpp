// track_test.cpp — THREADED
// A track is a row of clips, read ahead into one ring by the disk thread, which mixes them there: the audio
// thread reads one ring per track however many clips it holds.
#include "studio/disk.h"
#include "test.h"
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>
using namespace studio;

struct Ramp : Reader {  // frame i of the file is base + i on every channel
    long n;
    int ch;
    float base;
    std::atomic<int> delayUs{0};
    Ramp(long len, int channels, float b) : n(len), ch(channels), base(b) {}
    long frames() const override { return n; }
    int channels() const override { return ch; }
    int read(long at, float* out, int frames) override {
        if (delayUs) std::this_thread::sleep_for(std::chrono::microseconds(delayUs.load()));
        int k = (int)std::max(0L, std::min<long>(frames, n - at));
        for (int i = 0; i < k; i++)
            for (int c = 0; c < ch; c++) out[ch * i + c] = base + (float)(at + i);
        return k;
    }
};

int main() {
    Disk disk;
    Ramp x(10000, 1, 0.f), y(10000, 1, 50000.f), z(10000, 2, 90000.f);
    Streamer track({{&x, 0, 0, 1000, 0.f}, {&y, 1500, 100, 1000, 0.f}}, 4096);
    track.setOffline(true);  // these checks are about which frame plays where, not about the scheduler
    disk.add(&track);
    CHECK(track.channels() == 2, "a track always reads out stereo");
    CHECK(track.panChannels() == 1, "a track of mono clips is panned as mono");

    std::vector<float> out(2 * 1000);
    bool exact = true;
    int got = 0;
    for (long at = 0; at < 3000; at += 1000) {
        got = track.read(out.data(), 1000);
        for (int i = 0; i < 1000; i++) {
            const long p = at + i;
            float want = 0.f;
            if (p < 1000) want = (float)p;
            else if (p >= 1500 && p < 2500) want = 50000.f + (float)(p - 1500 + 100);
            if (out[2 * i] != want || out[2 * i + 1] != want) exact = false;
        }
    }
    CHECK(exact, "two clips on one track play in their places, with silence between them");
    CHECK(got == 1000, "a fed track reports every frame as there");

    track.seek(1600);
    track.read(out.data(), 4);
    CHECK(out[0] == 50000.f + 200.f, "seeking a track seeks every clip on it");

    {   // overlapping clips are heard together
        Ramp x2(10000, 1, 0.f);  // a reader of its own here; clips could share one, as one thread reads them all
        Streamer t({{&x2, 0, 0, 1000, 0.f}, {&z, 500, 0, 1000, -6.0206f}}, 4096);
        t.setOffline(true);
        disk.add(&t);
        CHECK(t.panChannels() == 2, "a track with a stereo clip is panned as stereo");
        t.seek(600);
        t.read(out.data(), 1);
        NEAR(out[0], 600.f + 0.5f * (90000.f + 100.f), 0.05f, "where clips overlap, both are heard, each at its own level");
        disk.remove(&t);
    }
    {   // a track with no clips is silent and never short
        Streamer empty(std::vector<ClipRef>{}, 4096);
        disk.add(&empty);
        out[0] = 7.f;
        CHECK(empty.read(out.data(), 300) == 300 && out[0] == 0.f, "an empty track is silence, never short");
        CHECK(empty.panChannels() == 1, "an empty track pans as mono");
        disk.remove(&empty);
    }
    {   // one clip that starves makes the track short, so the engine counts the underrun
        Ramp slow(100000, 1, 0.f), x3(10000, 1, 0.f);
        slow.delayUs = 300000;
        Streamer t({{&x3, 0, 0, 10000, 0.f}, {&slow, 0, 0, 100000, 0.f}}, 4096);
        disk.add(&t);
        CHECK(t.read(out.data(), 256) < 256, "a track whose disk is slow comes up short, so the engine counts it");
        disk.remove(&t);
    }
    {   // memory is one ring per track, not one per clip
        std::vector<ClipRef> many;
        for (int k = 0; k < 50; k++) many.push_back({&x, 2000L * k, 0, 1000, 0.f});
        Streamer fifty(many, 1 << 17);
        CHECK(fifty.ringBytes() == (size_t)(1 << 17) * 2 * sizeof(float), "fifty clips share one ring");
    }
    disk.remove(&track);
    DONE("track_test");
}
