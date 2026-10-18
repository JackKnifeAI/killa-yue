// THREADED: Chamber snapshots and live hits cross UI/audio queues in opposite directions.
#include <atomic>
#include <chrono>
#include <thread>

#include "studio/performance.h"
#include "test.h"
using namespace studio;
int main() {
    PerformanceAudio p(48000);
    std::string error;
    CHECK(p.kit(error), "original kit ready");
    ChamberConfig config;
    CHECK(config.valid(), "default Chamber config is valid");
    config.data[4] = 4;
    CHECK(!config.valid(), "repeat is bounded to the saved grid");
    config.data[4] = 3;
    config.data[0] = 2;
    CHECK(p.chamber(config.data.data(), ChamberConfig::kSize), "repeat and selected pattern queued");
    CHECK(p.trigger(0, .7, true), "live pad queued");
    float out[1024];
    for (long frame = 0; frame < 24576; frame += 512) {
        std::fill_n(out, 1024, 0);
        p.mix(out, 512, frame, true);
    }
    double hits[1025];
    int n = p.hits(hits, 1025);
    CHECK(n == 5 && hits[0] == 0, "five sixteenth-note hits captured");
    bool exact = true;
    for (int i = 0; i < n; i++) exact &= hits[1 + 4 * i] == i * 6000 && hits[4 + 4 * i] == 2;
    CHECK(exact, "repeat uses exact audio frames and pattern identity");
    p.trigger(0, 0, false);
    for (long frame = 24576; frame < 48000; frame += 512) {
        std::fill_n(out, 1024, 0);
        p.mix(out, 512, frame, true);
    }
    CHECK(p.hits(hits, 1025) == 0, "release stops native repeats");
    config.data[4] = 0;
    config.data[2] = 1;
    config.data[3] = 0;
    p.chamber(config.data.data(), ChamberConfig::kSize);
    p.trigger(0, 1, true);
    p.trigger(15, .1, true);
    std::fill_n(out, 1024, 0);
    p.mix(out, 512, 48000, true);
    n = p.hits(hits, 1025);
    CHECK(n == 2 && hits[2] == 0 && hits[6] == 0 && hits[3] == .0625 && hits[7] == 1,
          "16 velocity levels share the chosen source pad");
    config.data[5] = 1;
    p.chamber(config.data.data(), ChamberConfig::kSize);
    p.trigger(15, 1, true);
    std::fill_n(out, 1024, 0);
    p.mix(out, 512, 48512, true);
    CHECK(p.hits(hits, 1025) == 0, "muted pads do not enter the recording queue");
    config.data[5] = 0;
    config.data[2] = 0;
    config.data[4] = 3;
    std::atomic<bool> stop{false}, finite{true};
    std::thread audio([&] {
        long frame = 49024;
        float block[1024];
        while (!stop.load()) {
            std::fill_n(block, 1024, 0);
            p.mix(block, 512, frame, true);
            frame += 512;
            for (float f : block)
                if (!std::isfinite(f)) finite.store(false);
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    });
    bool queued = true;
    for (int i = 0; i < 100; i++) {
        config.data[0] = (float)(i % 4);
        while (!p.chamber(config.data.data(), ChamberConfig::kSize))
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        queued &= p.trigger(i % 16, .1, true);
        p.hits(hits, 1025);
        p.collect();
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    p.allOff();
    stop.store(true);
    audio.join();
    CHECK(queued && finite.load(), "concurrent snapshots, triggers, capture and retirement remain safe");
    std::fill_n(out,1024,0);p.mix(out,512,0,false);
    float longRelease[10]={0,0,0,1,10,20000,2,0,0,0};p.configure(0,longRelease,10);p.trigger(0,1,true);
    std::fill_n(out,1024,0);p.mix(out,512,0,false);double beforePanic=0;for(float f:out)beforePanic+=f*f;
    CHECK(beforePanic>.01,"looped pad with ten-second release is sounding before panic");
    // Panic must remain available even with a saturated live-event queue.
    while (p.trigger(0, 1, true)) {
    }
    CHECK(p.allOff(), "panic cannot be refused by a full event queue");
    for (int i = 0; i < 100; i++) {
        std::fill_n(out, 1024, 0);
        p.mix(out, 512, 0, false);
    }
    double energy = 0;
    for (float f : out) energy += f * f;
    CHECK(energy < 1e-12, "panic clears repeats and drains voices");
    DONE("chamber_test");
}
