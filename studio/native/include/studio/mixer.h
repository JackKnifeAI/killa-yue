// Original Studio mixer utilities and shared sends. All storage is allocated before playback.
#pragma once
#include <array>
#include <atomic>
#include <vector>
#include "studio/spsc.h"
namespace studio {
struct MixerConfig {
    static constexpr int size = 2 + 75 * 32;
    std::array<float, size> data{};
    MixerConfig() { for (int i = 0; i < 75; ++i) data[2 + i * 32 + 6] = -1; }
    bool valid() const;
    const float* strip(int i) const { return data.data() + 2 + i * 32; }
};
class MixerGraph {
public:
    MixerGraph(int rate, int frames);
    bool enqueue(const MixerConfig& c) { return queue_.push(c); } // producer, followed by ordered Msg::Mixer
    void configure(const MixerConfig& config); // callback; session and routing swap together
    void apply(); // callback, once for each Msg::Mixer
    void reset();
    void begin(int frames);
    bool groupSolo() const;
    bool audible(int track, bool mute, bool solo, bool anySolo) const;
    void track(int index, float* stereo, float* master, int frames);
    void finish(float* master, int frames);
    void master(float* stereo, int frames) { chain(74, stereo, frames); }
    int meters(float* out, int max);
private:
    void chain(int strip, float* stereo, int frames);
    void send(int strip, const float* stereo, int frames);
    void level(int strip, float* stereo, int frames, bool audible);
    void meter(int strip, const float* stereo, int frames);
    static void add(float* to, const float* from, int frames);
    MixerConfig config_;
    Spsc<MixerConfig, 8> queue_;
    int frames_;
    std::vector<float> buses_, reverbIn_, delayIn_, wet_, delayLine_;
    std::array<std::vector<float>, 8> comb_;
    std::array<size_t, 8> combPos_{};
    size_t delayPos_ = 0;
    std::array<float, 75> previousL_, previousR_;
    std::array<float, 75> previousRev_{}, previousDelay_{};
    std::array<std::array<float, 8>, 75> previousFx_;
    std::array<bool, 8> groupHeard_{};
    std::atomic<float> meters_[20];
};
}
