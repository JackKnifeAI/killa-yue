// loop_test.cpp — THREADED
// Loop playback: the read-ahead follows the playing order, so at the loop's end the start is already waiting;
// the engine cuts its block exactly at the loop end and carries on from the loop start, pass after pass.
#include "studio/engine.h"
#include "studio/streamer.h"
#include "test.h"
#include <chrono>
#include <thread>
#include <vector>
using namespace studio;

struct Ramp : Reader {  // frame i is (i + 1) * 1e-6 on both channels: small (no limiting), never zero, unique
    long n;
    explicit Ramp(long len) : n(len) {}
    long frames() const override { return n; }
    int channels() const override { return 2; }
    int read(long at, float* out, int frames) override {
        int k = (int)std::max(0L, std::min<long>(frames, n - at));
        for (int i = 0; i < k; i++) out[2 * i] = out[2 * i + 1] = (float)((at + i + 1) * 1e-6);
        return k;
    }
};

// which source frame plays at play index i, from s0, looping [ls, le) once reached
static long expect(long i, long s0, long ls, long le) {
    if (s0 >= le || i < le - s0) return s0 + i;
    return ls + (i - (le - s0)) % (le - ls);
}

int main() {
    const long ls = 10000, le = 58000;  // a 1 s loop at 48 kHz
    {   // the order of frames, exact, for 60 passes (off-line: reads wait for the disk)
        Ramp file(200000);
        Streamer s(&file, 0, 0, 200000, 1 << 14);
        s.startThread();
        s.setOffline(true);
        s.setLoop(ls, le, true);
        s.seek(0);
        std::vector<float> out(2 * 512);
        bool exact = true;
        const long total = le + 60 * (le - ls);
        for (long at = 0; at < total; at += 512) {
            s.read(out.data(), 512);
            for (int i = 0; i < 512; i++)
                if (out[2 * i] != (float)((expect(at + i, 0, ls, le) + 1) * 1e-6)) exact = false;
        }
        CHECK(exact, "60 passes of a 1 s loop: every frame the right one, the wrap seamless");
        CHECK(s.starved() == 0, "nothing missing");
        s.stopThread();
    }
    {   // in real time the wrap never starves: the start of the loop is read ahead before the end arrives
        Ramp file(200000);
        Streamer s(&file, 0, 0, 200000, 1 << 14);
        s.startThread();
        s.setLoop(ls, le, true);
        s.seek(9000);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        std::vector<float> out(2 * 512);
        for (long at = 0; at < 5 * 48000; at += 512) {  // five passes at real pace
            s.read(out.data(), 512);
            std::this_thread::sleep_for(std::chrono::microseconds(10667));
        }
        CHECK(s.starved() == 0, "five passes in real time: never short at the wrap");
        s.stopThread();
    }
    {   // the engine: its position wraps, and what it plays follows the loop exactly
        Ramp file(200000);
        Streamer src(&file, 0, 0, 200000, 1 << 14);
        src.startThread();
        Engine e(48000, 512);
        auto* session = new Session{48000, {}};
        session->tracks.push_back({&src, 0.f, 0.f, false, false});
        e.post({Msg::Swap, 0, 0, 0, session});
        e.post({Msg::Loop, 1, 0, ls, nullptr, le});
        e.post({Msg::Seek, 0, 0, 40000, nullptr});
        e.setOffline(true);
        e.post({Msg::Play, 0, 0, 0, nullptr});
        const int lat = e.latency();
        std::vector<float> out(2 * 700);  // blocks that do not divide the loop: the wrap falls inside a block
        std::vector<float> played;
        for (int b = 0; b < 300; b++) {
            e.process(out.data(), 700);
            for (int i = 0; i < 700; i++) played.push_back(out[2 * i]);
        }
        bool exact = true;
        for (long i = lat; i < (long)played.size(); i++)
            if (std::fabs(played[i] - (float)((expect(i - lat, 40000, ls, le) + 1) * 1e-6)) > 1e-9f) exact = false;
        CHECK(exact, "the engine plays 40000 to the loop end, then the loop, over and over, wrapping inside blocks");
        const long pos = e.status().position;
        CHECK(pos >= ls && pos < le, "its position stays inside the loop");
        e.post({Msg::Loop, 0, 0, ls, nullptr, le});  // loop off: on through the end
        long last = 0;
        for (int b = 0; b < 200; b++) {
            e.process(out.data(), 700);
            last = e.status().position;
        }
        CHECK(last > le, "with the loop off, play goes on past the loop's end");
        src.stopThread();
    }
    DONE("loop_test");
}
