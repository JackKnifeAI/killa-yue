// Mixing primitives for the Studio engine: levels, the pan law, one track into the stereo bus, and the
// master's lookahead safety limiter. Nothing here allocates after construction.
#pragma once
#include <vector>

namespace studio {

float dbToGain(float db);

// Constant-power pan. Mono: left = cos(t), right = sin(t), t = (pan + 1) * pi / 4 (centre is -3 dB each side).
// Stereo: left = min(1, sqrt2 * cos t), right = min(1, sqrt2 * sin t) (centre is unity, a side fades out).
void panGains(float pan, int channels, float& left, float& right);

struct TrackMix {
    float gain;        // linear
    float panL, panR;  // from panGains
    bool audible;      // not muted, and soloed if anything is
    int channels;      // of the source: 1 or 2
};

// Adds frames of an interleaved source (t.channels channels) into interleaved stereo out.
void mixTrack(const TrackMix& t, const float* source, int frames, float* outStereo);

// A lookahead peak limiter: the output never exceeds the ceiling, and a signal that stays below it passes
// unchanged, delayed by latency() frames.
class Limiter {
public:
    explicit Limiter(int rate, float ceilingDb = -0.3f, float lookaheadMs = 1.5f, float releaseMs = 80.f);
    int latency() const { return look_; }
    void reset();  // forget everything: as new (for an export, so it never depends on what played before)
    void process(float* interleavedStereo, int frames);

private:
    int look_, n_ = 0, d_ = 0;    // lookahead in frames; window ring index; delay ring index
    float ceiling_, release_, g_ = 1.f;
    std::vector<float> delay_;    // 2 * look_ delayed samples
    std::vector<float> need_;     // per incoming frame: the gain that frame needs, (look_ + 1) of them
    std::vector<float> mins_;     // the smallest need over the lookahead, (look_ + 1) of them
};

}  // namespace studio
