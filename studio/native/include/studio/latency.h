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
};

// Looks for one probe in a mono recording.
Found findProbe(const float* recorded, long frames, const std::vector<float>& probe);

struct RoundTrip {
    bool ok;      // at least three probes found, all within 1 ms of each other
    long frames;  // their median
};

// Combines the delays of several probes (-1 for a probe that was not found).
RoundTrip roundTrip(const std::vector<long>& delays, int rate);

}  // namespace studio
