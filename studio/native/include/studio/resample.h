// The import resampler: converts a file's sample rate to the Studio's (44.1 kHz songs and files onto a 48 kHz
// phone, 48 kHz songs into Demucs's 44.1 kHz and back). Band-limited windowed sinc (Kaiser, about 120 dB of
// stopband) on an exact rational clock, so nothing folds back into the audible band, 20 Hz to 20 kHz comes
// through flat, and every sample keeps its place in time (an impulse at 0.1 s comes out at 0.1 s). Off the
// audio thread: it allocates once, when made.
#pragma once
#include <vector>

namespace studio {

class Resampler {
public:
    static constexpr int kChunk = 8192;  // the most input frames one process() call takes

    // taps: the filter's length at the lower rate (0 picks the full-quality design; tests shorten it on purpose).
    Resampler(int inRate, int outRate, int channels, int taps = 0);

    int maxOut(int inFrames) const;  // the most frames process() can return for inFrames
    int maxTail() const;             // the most frames finish() can return

    // Interleaved frames in (at most kChunk), resampled frames out; returns how many were written.
    int process(const float* in, int frames, float* out);
    // The end of the input: the rest of the output (the total is exactly ceil(input frames x out / in)).
    int finish(float* out);

private:
    int produce(float* out, bool flushing);

    int ch_, L_, M_;               // out/in = L/M in lowest terms
    double W_;                     // the filter's half-width in input samples
    int span_;                     // input samples one output reads (2W, rounded up)
    int phases_;                   // filter table resolution between input samples
    std::vector<double> table_;    // (phases_ + 1) x span_ coefficients
    std::vector<float> hist_;      // input not yet consumed, interleaved; hist_[0] is input frame histStart_
    long histStart_ = 0, histFrames_ = 0, inTotal_ = 0, n_ = 0;
    bool same_;
};

}  // namespace studio
