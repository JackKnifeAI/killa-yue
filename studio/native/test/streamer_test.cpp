// streamer_test.cpp — THREADED
#include "studio/streamer.h"
#include "test.h"
#include <atomic>
#include <chrono>
#include <thread>
#include <cmath>
#include <vector>
using namespace studio;

struct Ramp : Reader { // sample i of the file is i (both channels); can be made slow
    long n; std::atomic<int> delayUs{0};
    explicit Ramp(long len) : n(len) {}
    long frames() const override { return n; }
    int channels() const override { return 2; }
    int read(long at, float* out, int frames) override {
        if (delayUs) std::this_thread::sleep_for(std::chrono::microseconds(delayUs.load()));
        int k = (int)std::max(0L, std::min<long>(frames, n - at));
        for (int i = 0; i < k; i++) out[2 * i] = out[2 * i + 1] = (float)(at + i);
        return k;
    }
};

int main() {
    Ramp file(480000);
    Streamer s(&file, 1000, 500, 100000, 1 << 14); // the clip starts at project frame 1000, plays file frames 500..100499
    s.startThread();
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    std::vector<float> out(2 * 512);
    bool exact = true;
    for (long at = 0; at < 120000; at += 512) {
        s.read(out.data(), 512);
        for (int i = 0; i < 512; i++) {
            long p = at + i;
            float want = (p >= 1000 && p < 101000) ? (float)(p - 1000 + 500) : 0.f;
            if (out[2 * i] != want) exact = false;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(10667)); // real time: 512 frames at 48 kHz (a 341 ms ring of margin)
    }
    CHECK(exact, "every frame is the right file frame, with silence around the clip");
    CHECK(s.starved() == 0, "a disk that keeps up never starves");
    {   // seeking to where a track already is (inside its clip) costs nothing
        Streamer same(&file, 0, 0, 480000, 1 << 14);
        same.startThread();
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        std::vector<float> o(2 * 512);
        for (int k = 0; k < 20; k++) {
            same.read(o.data(), 512);
            std::this_thread::sleep_for(std::chrono::microseconds(2667));
        }
        same.seek(20 * 512);
        same.read(o.data(), 512);
        CHECK(same.starved() == 0 && o[0] == (float)(20 * 512), "seeking to where a track already is costs nothing");
        same.stopThread();
    }

    file.delayUs = 200000; // a disk that stalls for 0.2 s
    s.seek(50000);
    auto t0 = std::chrono::steady_clock::now();
    int got = s.read(out.data(), 512);
    auto us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count();
    CHECK(us < 1000, "reading never waits for the disk");
    CHECK(got == 0 && out[0] == 0.f && s.starved() > 0, "missing audio is silence, counted as starved");
    file.delayUs = 0;
    std::this_thread::sleep_for(std::chrono::milliseconds(400));
    s.read(out.data(), 512); // time kept moving during the stall: this block starts at 50000 + 512
    CHECK(out[0] == (float)(50000 + 512 - 1000 + 500), "after the stall it plays in time, from where the song has got to");
    s.stopThread();
    {   // a small ring still fills, and a clip's own level is applied
        Ramp small(48000);
        Streamer tiny(&small, 0, 0, 48000, 1024, -6.0206f);
        tiny.setOffline(true);  // reads wait for the disk: this checks the filling and the level, not the scheduler
        tiny.startThread();
        std::vector<float> o(2 * 256);
        bool ok = true;
        for (int k = 0; k < 40; k++) {
            tiny.read(o.data(), 256);
            for (int i = 0; i < 256; i++) ok = ok && std::fabs(o[2 * i] - 0.5f * (k * 256 + i)) <= 1e-3f * (k * 256 + i);
        }
        CHECK(ok && tiny.starved() == 0, "a 1024-frame ring fills, and the clip's -6 dB is applied");
        tiny.stopThread();
    }
    DONE("streamer_test");
}
