#include "studio/latency.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace studio {

std::vector<float> probeSignal(int rate, unsigned seed) {
    const int n = rate / 20;  // 50 ms
    std::vector<float> p((size_t)n);
    // white noise from a fixed generator (the same probe on every phone), kept to 400 Hz - 6 kHz, where phone
    // speakers and microphones both work
    uint32_t x = 0x9E3779B9u ^ (seed * 0x85EBCA6Bu);
    if (x == 0) x = 1;  // xorshift never leaves zero
    const float hp = std::exp(-2.f * (float)M_PI * 400.f / rate), lp = 1.f - std::exp(-2.f * (float)M_PI * 6000.f / rate);
    float prevIn = 0.f, high = 0.f, low1 = 0.f, low2 = 0.f;
    for (int i = 0; i < n; i++) {
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        const float w = (float)x / 4294967296.f * 2.f - 1.f;
        high = hp * (high + w - prevIn);
        prevIn = w;
        low1 += lp * (high - low1);
        low2 += lp * (low1 - low2);
        p[i] = low2;
    }
    // 5 ms raised-cosine fades, so it starts and stops without a click
    const int fade = rate / 200;
    for (int i = 0; i < fade && i < n / 2; i++) {
        const float g = 0.5f - 0.5f * std::cos((float)M_PI * i / fade);
        p[i] *= g;
        p[n - 1 - i] *= g;
    }
    float peak = 0.f;
    for (float v : p) peak = std::max(peak, std::fabs(v));
    for (float& v : p) v *= 0.4f / peak;
    return p;
}

Found findProbe(const float* r, long frames, const std::vector<float>& probe) {
    const long P = (long)probe.size();
    if (P == 0 || frames < P) return {-1, false};
    double pe = 0.0;
    for (float v : probe) pe += (double)v * v;
    const double pn = std::sqrt(pe);
    const long lags = frames - P + 1;
    std::vector<float> ncc((size_t)lags);  // |normalised correlation| at every lag: 1 is a perfect copy
    double e = 0.0;
    for (long k = 0; k < P; k++) e += (double)r[k] * r[k];
    for (long L = 0; L < lags; L++) {
        if (L > 0) e += (double)r[L + P - 1] * r[L + P - 1] - (double)r[L - 1] * r[L - 1];
        double c = 0.0;
        for (long k = 0; k < P; k++) c += (double)r[L + k] * probe[k];
        const double rn = std::sqrt(std::max(e, 0.0));
        ncc[L] = rn > 1e-9 * pn ? (float)(std::fabs(c) / (pn * rn)) : 0.f;
    }
    float m = 0.f, mean = 0.f;
    for (float v : ncc) {
        m = std::max(m, v);
        mean += v;
    }
    mean /= (float)lags;
    if (m <= 0.f) return {-1, false};
    // the first arrival: the earliest place that reaches half the best match, then its own peak (within 1 ms);
    // a reflection can be louder than the direct sound, but it always comes later
    long at = 0;
    while (ncc[at] < 0.5f * m) at++;
    long best = at;
    const long end = std::min(lags, at + std::max(1L, P / 50));  // the probe lasts 50 ms, so P / 50 is 1 ms
    for (long L = at; L < end; L++)
        if (ncc[L] > ncc[best]) best = L;
    // sure: the match stands far above the correlation's own noise floor, and is a real likeness
    return {best, m > 10.f * mean && m > 0.15f};
}

RoundTrip roundTrip(const std::vector<long>& delays, int rate) {
    std::vector<long> d;
    for (long v : delays)
        if (v >= 0) d.push_back(v);
    if (d.size() < 3) return {false, 0};
    std::sort(d.begin(), d.end());
    return {d.back() - d.front() <= rate / 1000, d[d.size() / 2]};
}

}  // namespace studio
