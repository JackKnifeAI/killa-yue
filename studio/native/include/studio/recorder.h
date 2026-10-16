#pragma once
#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "studio/wav.h"
namespace studio {
/** One input producer, one disk consumer. Forty-three seconds at 48kHz; no allocation or locks in input. */
class Recorder {
   public:
    Recorder() : ring_(1 << 21) {}
    ~Recorder() {
        std::string e;
        stop(e);
    }
    bool start(const char* path, int rate, std::string& err);
    void input(const float* in, int frames, int channels, long position);
    long stop(std::string& err);
    long first() const { return first_.load(); }
    long frames() const { return frames_.load(); }
    float peak() { return peak_.exchange(0); }
    bool active() const { return accept_.load(); }
    int failure() const { return failure_.load(); }

   private:
    void run();
    std::vector<float> ring_;
    std::unique_ptr<WavWriter> writer_;
    std::thread worker_;
    std::atomic<bool> accept_{false}, done_{true};
    std::atomic<int> inside_{0}, failure_{0};
    std::atomic<long> read_{0}, write_{0}, first_{-1}, frames_{0};
    std::atomic<float> peak_{0};
    int rate_ = 48000;
    long expected_ = -1;
};
}  // namespace studio
