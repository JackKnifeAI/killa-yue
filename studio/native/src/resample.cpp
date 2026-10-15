#include "studio/resample.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace studio {

namespace {

long gcd(long a, long b) {
    while (b) {
        long t = a % b;
        a = b;
        b = t;
    }
    return a;
}

double besselI0(double x) {  // the Kaiser window's I0, by its series
    double sum = 1, term = 1;
    for (int k = 1; k < 64; k++) {
        term *= (x / (2 * k)) * (x / (2 * k));
        sum += term;
        if (term < 1e-17 * sum) break;
    }
    return sum;
}

const double kStopDb = 120;      // stopband attenuation
const int kMaxExactPhases = 4096;  // ratios with more phases than this use an interpolated table

}  // namespace

Resampler::Resampler(int inRate, int outRate, int channels, int taps)
    : in_(inRate), out_(outRate), ch_(std::max(1, std::min(channels, 8))), same_(inRate == outRate) {
    const long g = gcd(outRate, inRate);
    L_ = (int)(outRate / g);
    M_ = (int)(inRate / g);
    if (same_) return;
    const double lower = std::min(inRate, outRate);
    const double fp = std::min(20000.0, 0.4535 * lower);  // flat up to here
    const double fs = 0.5 * lower;                        // gone from here (the lower rate's Nyquist)
    const double wc = 0.5 * (fp + fs) / inRate;           // cutoff in cycles per input sample
    double n;                                             // filter length in input samples
    if (taps > 0) n = taps * (double)inRate / lower;
    else n = (kStopDb - 7.95) / (14.36 * (fs - fp) / inRate);  // Kaiser's estimate
    W_ = n / 2;
    const int S = (int)std::ceil(W_);
    span_ = 2 * S;
    const double beta = 0.1102 * (kStopDb - 8.7);
    const double i0b = besselI0(beta);
    phases_ = L_ <= kMaxExactPhases ? L_ : kMaxExactPhases;
    const int rows = L_ <= kMaxExactPhases ? phases_ : phases_ + 1;  // the interpolated table has an end row
    table_.assign((size_t)rows * span_, 0.0);
    for (int p = 0; p < rows; p++) {
        const double frac = (double)p / phases_;
        double sum = 0;
        for (int j = 0; j < span_; j++) {
            const double tau = frac + S - 1 - j;  // output position minus input position, in input samples
            double h = 0;
            if (std::fabs(tau) < W_) {
                const double x = 2 * wc * tau;
                const double sinc = x == 0 ? 1.0 : std::sin(M_PI * x) / (M_PI * x);
                const double r = tau / W_;
                h = 2 * wc * sinc * besselI0(beta * std::sqrt(1 - r * r)) / i0b;
            }
            table_[(size_t)p * span_ + j] = h;
            sum += h;
        }
        for (int j = 0; j < span_; j++) table_[(size_t)p * span_ + j] /= sum;  // DC through at exactly 1
    }
    hist_.assign((size_t)(span_ + kChunk + 4) * ch_, 0.f);
}

int Resampler::maxOut(int inFrames) const {
    if (same_) return inFrames;
    return (int)((long)(inFrames + span_ + 2) * L_ / M_) + 2;
}

int Resampler::maxTail() const {
    if (same_) return 0;
    return (int)((long)(span_ + 2) * L_ / M_) + 2;
}

int Resampler::process(const float* in, int frames, float* out) {
    if (same_) {
        std::memcpy(out, in, sizeof(float) * (size_t)frames * ch_);
        return frames;
    }
    frames = std::min(frames, kChunk);
    std::memcpy(hist_.data() + (size_t)histFrames_ * ch_, in, sizeof(float) * (size_t)frames * ch_);
    histFrames_ += frames;
    inTotal_ += frames;
    const int made = produce(out, false);
    // keep only what the next output still needs
    const long need = (long)((n_ * (long long)M_) / L_) - (span_ / 2) + 1;
    const long drop = std::max(0L, std::min(need - histStart_, histFrames_));
    if (drop > 0) {
        std::memmove(hist_.data(), hist_.data() + (size_t)drop * ch_, sizeof(float) * (size_t)(histFrames_ - drop) * ch_);
        histStart_ += drop;
        histFrames_ -= drop;
    }
    return made;
}

int Resampler::finish(float* out) {
    if (same_) return 0;
    return produce(out, true);
}

int Resampler::produce(float* out, bool flushing) {
    const int S = span_ / 2;
    const long total = (long)(((long long)inTotal_ * L_ + M_ - 1) / M_);  // ceil(in x L / M)
    int made = 0;
    double acc[8];
    while (true) {
        const long long num = (long long)n_ * M_;
        const long ip = (long)(num / L_);
        const long rem = (long)(num % L_);
        if (flushing ? n_ >= total : ip + S >= inTotal_) break;
        const long base = ip - S + 1;  // the first input frame this output reads
        const double* row = L_ <= kMaxExactPhases ? &table_[(size_t)rem * span_] : nullptr;
        for (int c = 0; c < ch_; c++) acc[c] = 0;
        if (row) {
            for (int j = 0; j < span_; j++) {
                const long k = base + j;
                if (k < histStart_ || k >= histStart_ + histFrames_) continue;  // before the start or after the end: silence
                const float* x = &hist_[(size_t)(k - histStart_) * ch_];
                for (int c = 0; c < ch_; c++) acc[c] += row[j] * x[c];
            }
        } else {
            // an odd ratio: interpolate between the two nearest of phases_ rows
            const double pos = (double)rem / L_ * phases_;
            const int q = std::min((int)pos, phases_ - 1);
            const double r = pos - q;
            const double* a = &table_[(size_t)q * span_];
            const double* b = &table_[(size_t)(q + 1) * span_];
            for (int j = 0; j < span_; j++) {
                const long k = base + j;
                if (k < histStart_ || k >= histStart_ + histFrames_) continue;
                const double h = a[j] + (b[j] - a[j]) * r;
                const float* x = &hist_[(size_t)(k - histStart_) * ch_];
                for (int c = 0; c < ch_; c++) acc[c] += h * x[c];
            }
        }
        for (int c = 0; c < ch_; c++) out[(size_t)made * ch_ + c] = (float)acc[c];
        made++;
        n_++;
    }
    return made;
}

}  // namespace studio
