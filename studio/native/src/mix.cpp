#include "studio/mix.h"
#include <algorithm>
#include <cmath>

namespace studio {

float dbToGain(float db) { return std::pow(10.f, db / 20.f); }

void panGains(float pan, int channels, float& left, float& right) {
    pan = std::max(-1.f, std::min(1.f, pan));
    const double t = (pan + 1.0) * M_PI / 4.0;
    if (channels == 1) {
        left = (float)std::cos(t);
        right = (float)std::sin(t);
    } else {
        left = (float)std::min(1.0, std::sqrt(2.0) * std::cos(t));
        right = (float)std::min(1.0, std::sqrt(2.0) * std::sin(t));
    }
    if (std::fabs(left) < 1e-7f) left = 0.f;
    if (std::fabs(right) < 1e-7f) right = 0.f;
}

void mixTrack(const TrackMix& t, const float* source, int frames, float* out) {
    if (!t.audible) return;
    const float l = t.gain * t.panL, r = t.gain * t.panR;
    if (t.channels == 1) {
        for (int i = 0; i < frames; i++) {
            out[2 * i] += source[i] * l;
            out[2 * i + 1] += source[i] * r;
        }
    } else {
        for (int i = 0; i < frames; i++) {
            out[2 * i] += source[2 * i] * l;
            out[2 * i + 1] += source[2 * i + 1] * r;
        }
    }
}

Limiter::Limiter(int rate, float ceilingDb, float lookaheadMs, float releaseMs)
    : look_((int)std::lround(lookaheadMs * rate / 1000.0)),
      ceiling_(dbToGain(ceilingDb)),
      release_((float)(1.0 - std::exp(-1.0 / (releaseMs * rate / 1000.0)))),
      delay_(2 * look_, 0.f),
      need_(look_ + 1, 1.f),
      mins_(look_ + 1, 1.f) {}

void Limiter::process(float* x, int frames) {
    const int w = look_ + 1;
    for (int i = 0; i < frames; i++) {
        const float l = x[2 * i], r = x[2 * i + 1];
        const float peak = std::max(std::fabs(l), std::fabs(r));
        need_[n_] = peak > ceiling_ ? ceiling_ / peak : 1.f;
        // the smallest need over the lookahead, then its average over the lookahead: it reaches the
        // smallest need exactly when that frame comes out, ramping down smoothly before it
        float m = 1.f;
        for (float v : need_) m = std::min(m, v);
        mins_[n_] = m;
        float sum = 0.f;
        for (float v : mins_) sum += v;
        const float a = m == 1.f && sum == (float)w ? 1.f : std::min(1.f, sum / w);
        g_ = a < g_ ? a : g_ + (a - g_) * release_;
        if (g_ > a) g_ = a;
        // out: the frame from look_ frames ago
        float ol = l, orr = r;
        if (look_) {
            ol = delay_[2 * d_];
            orr = delay_[2 * d_ + 1];
            delay_[2 * d_] = l;
            delay_[2 * d_ + 1] = r;
            d_ = (d_ + 1) % look_;
        }
        float g = g_;
        const float op = std::max(std::fabs(ol), std::fabs(orr));
        if (op * g > ceiling_) g = ceiling_ / op;  // rounding guard: a gain, never a clip
        x[2 * i] = ol * g;
        x[2 * i + 1] = orr * g;
        n_ = (n_ + 1) % w;
    }
}

}  // namespace studio
