#include "studio/mixer.h"
#include "studio/mix.h"
#include <algorithm>
#include <cmath>
#include <cstring>
namespace studio {
static bool range(float x, float lo, float hi) { return std::isfinite(x) && x >= lo && x <= hi; }
static bool integer(float x, int lo, int hi) { return range(x, (float)lo, (float)hi) && x == std::floor(x); }
bool MixerConfig::valid() const {
    if (!integer(data[0], 0, 64) || !integer(data[1], 0, 8)) return false;
    for (int i = 0; i < 75; ++i) {
        const float* s = strip(i);
        if (!range(s[0], -60, 12) || !range(s[1], -1, 1) || !integer(s[2], 0, 1) ||
            !integer(s[3], 0, 1) || !range(s[4], 0, 1) || !range(s[5], 0, 1) ||
            !integer(s[6], -1, (int)data[1] - 1) || !integer(s[7], 0, 8)) return false;
        if (i >= 64 && s[6] != -1) return false;
        if (i >= 72 && (s[4] != 0 || s[5] != 0)) return false;
        for (int k = 0; k < (int)s[7]; ++k) {
            const float* f = s + 8 + k * 3;
            if (!integer(f[0], 1, 3) || !integer(f[2], 0, 1)) return false;
            if (f[0] == 1 && !range(f[1], -24, 24)) return false;
            if (f[0] == 2 && !range(f[1], 0, 2)) return false;
            if (f[0] == 3 && f[1] != 0) return false;
        }
    }
    return true;
}
MixerGraph::MixerGraph(int rate, int frames)
    : frames_(frames), buses_(16 * (size_t)frames), reverbIn_(2 * (size_t)frames),
      delayIn_(2 * (size_t)frames), wet_(2 * (size_t)frames), delayLine_(2 * (size_t)std::max(1, rate / 4)) {
    // Four decaying parallel delays per side; deliberately unequal lengths avoid a single echo.
    const float times[8] = {.0297f, .0371f, .0411f, .0437f, .0307f, .0383f, .0421f, .0451f};
    for (int i = 0; i < 8; ++i) comb_[i].resize(std::max(1, (int)(rate * times[i])));
    for (auto& m : meters_) m.store(0.f);
    reset();
}
void MixerGraph::apply() {
    MixerConfig next;
    if (!queue_.pop(next)) return;
    configure(next);
}
void MixerGraph::configure(const MixerConfig& next) {
    for (int id = 0; id < 75; ++id)
        for (int k = 0; k < 8; ++k)
            if (config_.strip(id)[8 + 3*k] != next.strip(id)[8 + 3*k]) previousFx_[id][k] = NAN;
    config_ = next;
}
void MixerGraph::reset() {
    std::fill(delayLine_.begin(), delayLine_.end(), 0.f);
    for (auto& c : comb_) std::fill(c.begin(), c.end(), 0.f);
    combPos_.fill(0); delayPos_ = 0;
    previousL_.fill(-1); previousR_.fill(-1);
    previousRev_.fill(-1); previousDelay_.fill(-1);
    for (auto& s : previousFx_) s.fill(NAN);
}
void MixerGraph::begin(int frames) {
    for (int g = 0; g < 8; ++g) std::fill_n(buses_.data() + 2 * g * frames_, 2 * frames, 0.f);
    std::fill_n(reverbIn_.data(), 2 * frames, 0.f);
    std::fill_n(delayIn_.data(), 2 * frames, 0.f);
    groupHeard_.fill(false);
}
bool MixerGraph::groupSolo() const {
    for (int g = 0; g < (int)config_.data[1]; ++g) if (config_.strip(64 + g)[3]) return true;
    return false;
}
bool MixerGraph::audible(int t, bool mute, bool solo, bool anySolo) const {
    int g = (int)config_.strip(t)[6];
    const float* group = g < 0 ? nullptr : config_.strip(64 + g);
    return !mute && (!group || !group[2]) && (!anySolo || solo || (group && group[3]));
}
void MixerGraph::add(float* to, const float* from, int frames) {
    for (int i = 0; i < 2 * frames; ++i) to[i] += from[i];
}
void MixerGraph::chain(int id, float* audio, int frames) {
    const float* s = config_.strip(id);
    for (int k = 0; k < (int)s[7]; ++k) {
        const float* f = s + 8 + 3 * k;
        const int kind = (int)f[0];
        const float target = f[2] ? 1.f : (kind == 1 ? dbToGain(f[1]) : (kind == 2 ? f[1] : -1.f));
        float& old = previousFx_[id][k];
        if (!std::isfinite(old)) old = target;
        for (int i = 0; i < frames; ++i) {
            const float v = old + (target - old) * (float)(i + 1) / frames;
            float& l = audio[2 * i]; float& r = audio[2 * i + 1];
            if (kind == 2) { float mid = .5f * (l + r), side = .5f * (l - r) * v; l = mid + side; r = mid - side; }
            else { l *= v; r *= v; }
        }
        old = target;
    }
    for (int k = (int)s[7]; k < 8; ++k) previousFx_[id][k] = NAN;
}
void MixerGraph::send(int id, const float* audio, int frames) {
    const float* s = config_.strip(id);
    float& rev = previousRev_[id]; float& delay = previousDelay_[id];
    if (rev < 0) rev = s[4];
    if (delay < 0) delay = s[5];
    for (int i = 0; i < frames; ++i) {
        float a = rev + (s[4] - rev) * (float)(i + 1) / frames;
        float b = delay + (s[5] - delay) * (float)(i + 1) / frames;
        for (int c = 0; c < 2; ++c) { reverbIn_[2*i+c] += audio[2*i+c] * a; delayIn_[2*i+c] += audio[2*i+c] * b; }
    }
    rev = s[4]; delay = s[5];
}
void MixerGraph::track(int id, float* audio, float* out, int frames) {
    chain(id, audio, frames);
    send(id, audio, frames);
    int g = (int)config_.strip(id)[6];
    if (g >= 0) {
        add(buses_.data() + 2 * g * frames_, audio, frames);
        groupHeard_[g] = true;
    } else add(out, audio, frames);
}
void MixerGraph::level(int id, float* audio, int frames, bool audible_) {
    const float* s = config_.strip(id);
    float l = 0, r = 0;
    if (audible_ && !s[2]) { panGains(s[1], 2, l, r); float gain = dbToGain(s[0]); l *= gain; r *= gain; }
    float& oldL = previousL_[id]; float& oldR = previousR_[id];
    if (oldL < 0) { oldL = l; oldR = r; }
    for (int i = 0; i < frames; ++i) {
        audio[2*i] *= oldL + (l-oldL) * (float)(i+1)/frames;
        audio[2*i+1] *= oldR + (r-oldR) * (float)(i+1)/frames;
    }
    oldL = l; oldR = r;
}
void MixerGraph::meter(int id, const float* audio, int frames) {
    for (int c = 0; c < 2; ++c) {
        float peak = 0;
        for (int i = 0; i < frames; ++i) peak = std::max(peak, std::fabs(audio[2*i+c]));
        auto& held = meters_[2*(id-64)+c]; float old = held.load(std::memory_order_relaxed);
        while (peak > old && !held.compare_exchange_weak(old, peak, std::memory_order_relaxed)) {}
    }
}
void MixerGraph::finish(float* out, int frames) {
    for (int g = 0; g < (int)config_.data[1]; ++g) {
        float* audio = buses_.data() + 2*g*frames_;
        level(64+g, audio, frames, groupHeard_[g]);
        chain(64+g, audio, frames); meter(64+g, audio, frames); send(64+g, audio, frames); add(out, audio, frames);
    }
    // Wet-only stereo feedback delay, 250 ms, feedback 0.30.
    for (int i = 0; i < frames; ++i) {
        for (int c = 0; c < 2; ++c) {
            float value = delayLine_[delayPos_ + c];
            wet_[2*i+c] = value;
            float next = delayIn_[2*i+c] + .30f*value;
            delayLine_[delayPos_+c] = std::fabs(next) < 1e-20f ? 0.f : next;
        }
        delayPos_ = (delayPos_ + 2) % delayLine_.size();
    }
    level(73, wet_.data(), frames, true); chain(73, wet_.data(), frames); meter(73, wet_.data(), frames); add(out, wet_.data(), frames);
    for (int i = 0; i < frames; ++i) {
        wet_[2*i] = wet_[2*i+1] = 0;
        for (int k = 0; k < 8; ++k) {
            auto& line = comb_[k]; size_t& pos = combPos_[k]; float value = line[pos];
            float next = reverbIn_[2*i+k/4] + .72f*value;
            line[pos] = std::fabs(next) < 1e-20f ? 0.f : next;
            wet_[2*i+k/4] += .25f*value;
            pos = (pos+1) % line.size();
        }
    }
    level(72, wet_.data(), frames, true); chain(72, wet_.data(), frames); meter(72, wet_.data(), frames); add(out, wet_.data(), frames);
}
int MixerGraph::meters(float* out, int max) {
    int n = std::min(10, max);
    for (int i = 0; i < 2*n; ++i) out[i] = meters_[i].exchange(0.f, std::memory_order_relaxed);
    return n;
}
}
