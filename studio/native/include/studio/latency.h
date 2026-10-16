// Round-trip latency, measured: the Studio plays a probe, records what the microphone hears, and finds the
// probe in it. The delay is how far to move every recording so it lines up with what was playing. Runs off
// the audio thread (it allocates and takes a while).
#pragma once
#include <vector>

namespace studio {

// A burst of shaped noise, about 50 ms: easy to find by correlation, even quiet, echoing and band-limited.
// seed picks the noise: probes with different seeds are told apart by findProbe.
std::vector<float> probeSignal(int rate, unsigned seed = 1);

struct Found {
    long delay;  // frames from the start of the recording to the probe's first arrival; -1 when not found
    bool sure;   // the match stands well clear of everything else in the recording
    float score = 0.f;    // the best normalised correlation (1 is a perfect copy)
    float clarity = 0.f;  // how far it stands above the correlation's average (sure needs more than 10)
};

// Looks for one probe in a mono recording.
Found findProbe(const float* recorded, long frames, const std::vector<float>& probe);

constexpr long kNotFound = -2147483647L - 1;  // a probe that was not heard (any other value, even negative, is an answer)

struct RoundTrip {
    bool ok;      // at least three probes, and two thirds of those found, within 1 ms of the median
    long frames;  // the median of those that agree (one stray probe does not spoil the rest)
};

// Combines the delays of several probes (kNotFound for a probe that was not heard).
RoundTrip roundTrip(const std::vector<long>& delays, int rate);

}  // namespace studio
