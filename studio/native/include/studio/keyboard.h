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
        int layer = 0;
        long age = 0, release = 0;
        float velocity = 0;
    };
    int rate_;
    Bank* bank_ = nullptr;
    std::array<Voice, KEYS> voices_{};
    std::array<float, 1024> scratch_{};
    Spsc<Event, 256> events_;
    Spsc<Bank*, 64> retired_;
    int held_ = 0;  // UI thread: all allocated banks, including those queued/retired
    std::atomic<bool> panic_{false};
    std::atomic<long> underruns_{0};
    void clear();
    static float layerGain(float velocity, int layers);

   public:
    // MIDI 21..108, the 88 keys of a piano; each key has 1 to 4 velocity layers, softest first.
    static constexpr int LOW = 21, KEYS = 88, MAX_LAYERS = 4;
    explicit Keyboard(int rate) : rate_(rate) {}
    ~Keyboard();                                                           // after the audio callback has stopped
    // files[key * layers + layer]; release in milliseconds after the key is let go (or the pedal lifts)
    bool load(const std::vector<std::string>& files, int layers, int releaseMs, std::string& error);  // UI/worker, never concurrent
    bool note(int key, float velocity, bool on);
    void allOff() { panic_.store(true); }
    void mix(float* stereo, int frames);  // audio thread
    void collect();                       // UI thread
    long underruns() const { return underruns_.load(); }
};
}  // namespace studio
