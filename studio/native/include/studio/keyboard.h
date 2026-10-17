// Live sampled keyboard. Disk fills fixed read-ahead rings; the audio callback only reads them.
#pragma once
#include <array>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "studio/spsc.h"
namespace studio {
class Keyboard {
    struct Bank;
    struct Event {
        Bank* bank = nullptr;
        int key = 0;
        float velocity = 0;
        bool on = false;
    };
    struct Voice {
        bool active = false, released = false;
        long age = 0, release = 0;
        float velocity = 0;
    };
    int rate_;
    Bank* bank_ = nullptr;
    std::array<Voice, 49> voices_{};
    std::array<float, 1024> scratch_{};
    Spsc<Event, 256> events_;
    Spsc<Bank*, 64> retired_;
    int held_ = 0;  // UI thread: all allocated banks, including those queued/retired
    std::atomic<bool> panic_{false};
    std::atomic<long> underruns_{0};
    void clear();

   public:
    explicit Keyboard(int rate) : rate_(rate) {}
    ~Keyboard();                                                           // after the audio callback has stopped
    bool load(const std::vector<std::string>& files, std::string& error);  // UI/worker, never concurrent
    bool note(int key, float velocity, bool on);
    void allOff() { panic_.store(true); }
    void mix(float* stereo, int frames);  // audio thread
    void collect();                       // UI thread
    long underruns() const { return underruns_.load(); }
};
}  // namespace studio
