// Original Studio mixer racks and shared sends. All storage is allocated before playback.
#pragma once
#include <array>
#include <atomic>
#include <vector>
#include <memory>
#include "studio/effects.h"
#include "studio/automation.h"
#include "studio/spsc.h"
namespace studio {
struct MixerConfig {
    static constexpr int size = kAutomationOffset + kAutomationSize;
    std::array<float, size> data{};
    MixerConfig() { for (int i = 0; i < 75; ++i) data[2 + i * kMixerStride + 6] = -1; }
    bool valid() const;
    const float* strip(int i) const { return data.data() + 2 + i * kMixerStride; }
};
class MixerGraph {
public:
    MixerGraph(int rate, int frames);
    bool enqueue(const MixerConfig& c) { return queue_->push(c); } // producer, followed by ordered Msg::Mixer
    void configure(const MixerConfig& config); // callback; session and routing swap together
    void apply(); // callback, once for each Msg::Mixer
    void reset();
    void begin(int frames);
    bool automated() const { return config_.data[kAutomationOffset] > 0; }
    long boundary(long at, long end) const { return AutomationData::next(config_.data.data(), at, end); }
    void automate(long at, int frames);
    float control(int strip, int parameter, float base, bool start = false) const;

    bool groupSolo() const;
    bool audible(int track, bool mute, bool solo, bool anySolo) const;
    void track(int index, float* stereo, float* master, int frames, bool heard = true);
    void finish(float* master, int frames);
    void master(float* stereo, int frames) { chain(74, stereo, frames); }
    int meters(float* out, int max);
private:
    void chain(int strip, float* stereo, int frames);
    void gate(int strip, float* stereo, int frames, bool heard);
    void send(int strip, const float* stereo, int frames);
    void level(int strip, float* stereo, int frames, bool audible);
    void meter(int strip, const float* stereo, int frames);
    static void add(float* to, const float* from, int frames);
    MixerConfig config_;
    std::unique_ptr<Spsc<MixerConfig, 8>> queue_;
    EffectsRack effects_;
    int frames_;
    std::vector<float> buses_, reverbIn_, delayIn_, wet_, delayLine_;
    std::array<std::vector<float>, 8> comb_;
    std::array<size_t, 8> combPos_{};
    size_t delayPos_ = 0;
    std::array<std::array<float, 4>, 75> autoStart_, autoEnd_;
    std::array<float, 75> previousL_, previousR_, previousGate_;
    std::array<float, 75> previousRev_{}, previousDelay_{};
    std::array<bool, 8> groupHeard_{};
    std::atomic<float> meters_[20];
};
}
