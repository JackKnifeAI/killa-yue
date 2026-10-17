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
    uint32_t ids[600]{}; int idCount = 0, wetCount = 0;
    for (int i = 0; i < 75; ++i) {
        const float* s = strip(i);
        if (!range(s[0], -60, 12) || !range(s[1], -1, 1) || !integer(s[2], 0, 1) ||
            !integer(s[3], 0, 1) || !range(s[4], 0, 1) || !range(s[5], 0, 1) ||
            !integer(s[6], -1, (int)data[1] - 1) || !integer(s[7], 0, 8)) return false;
        if (i >= 64 && s[6] != -1) return false;
        if (i >= 72 && (s[4] != 0 || s[5] != 0)) return false;
        for (int k = 0; k < (int)s[7]; ++k) {
            const float* f = s + 8 + k * kEffectStride;
            if (!integer(f[0], 1, 10) || !integer(f[2], 0, 1) ||
                !integer(f[10], 0, 65535) || !integer(f[11], 0, 15258)) return false;
            EffectConfig effect = EffectConfig::read(f);
            if (!effect.valid() || effect.id > 999999999) return false;
            if (effect.id) {
                for (int n = 0; n < idCount; ++n) if (ids[n] == effect.id) return false;
                ids[idCount++] = effect.id;
            }
            if ((effect.kind == 7 || effect.kind == 8) && ++wetCount > kWetInserts) return false;
        }
    }
    return AutomationData::valid(data.data());
}
MixerGraph::MixerGraph(int rate, int frames)
    : queue_(new Spsc<MixerConfig, 8>()), effects_(rate), frames_(frames), buses_(16 * (size_t)frames), reverbIn_(2 * (size_t)frames),
      delayIn_(2 * (size_t)frames), wet_(2 * (size_t)frames), delayLine_(2 * (size_t)std::max(1, rate / 4)) {
    // Four decaying parallel delays per side; deliberately unequal lengths avoid a single echo.
    const float times[8] = {.0297f, .0371f, .0411f, .0437f, .0307f, .0383f, .0421f, .0451f};
    for (int i = 0; i < 8; ++i) comb_[i].resize(std::max(1, (int)(rate * times[i])));
    for (auto& m : meters_) m.store(0.f);
    configure(config_);
    reset();
}
void MixerGraph::apply() {
    if (!queue_->pop(config_)) return;
    configure(config_);
}
void MixerGraph::configure(const MixerConfig& next) {
    config_ = next;
    effects_.configure(next.data.data());
    for (auto& s : autoStart_) s.fill(NAN);
    for (auto& s : autoEnd_) s.fill(NAN);
}
void MixerGraph::reset() {
    std::fill(delayLine_.begin(), delayLine_.end(), 0.f);
    for (auto& c : comb_) std::fill(c.begin(), c.end(), 0.f);
    combPos_.fill(0); delayPos_ = 0;
    previousL_.fill(-1); previousR_.fill(-1); previousGate_.fill(-1);
    previousRev_.fill(-1); previousDelay_.fill(-1);
    effects_.reset();
}
float MixerGraph::control(int strip, int parameter, float base, bool start) const {
    float value = (start ? autoStart_ : autoEnd_)[strip][parameter];
    return std::isfinite(value) ? value : base;
}
void MixerGraph::automate(long at, int frames) {
    const float* data = config_.data.data();
    for (int i = 0; i < (int)data[kAutomationOffset]; ++i) {
        const float* h = AutomationData::lane(data, i);
        int strip = (int)h[0], slot = (int)h[1], param = (int)h[2];
        if (slot < 0) {
            autoStart_[strip][param] = AutomationData::value(data, i, at);
            autoEnd_[strip][param] = AutomationData::value(data, i, at + frames);
        } else {
            bool stepped = param == 8 || (config_.strip(strip)[8 + slot*kEffectStride] == 10 && param == 0);
            effects_.parameter(strip, slot, param, AutomationData::value(data, i, at + std::max(0, frames - 1), stepped));
        }
    }
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
    effects_.process(id, audio, frames);
}
void MixerGraph::gate(int id, float* audio, int frames, bool heard) {
    float target = heard ? 1.f : 0.f, &previous = previousGate_[id];
    if (previous < 0) previous = target;
    for (int i = 0; i < frames; ++i) {
        float gain = previous + (target - previous) * (float)(i + 1) / frames;
        audio[2*i] *= gain; audio[2*i+1] *= gain;
    }
    previous = target;
}
void MixerGraph::send(int id, const float* audio, int frames) {
    const float* s = config_.strip(id);
    float& rev = previousRev_[id]; float& delay = previousDelay_[id];
    float revTarget = control(id, 2, s[4]), delayTarget = control(id, 3, s[5]);
    if (rev < 0) rev = control(id, 2, s[4], true);
    if (delay < 0) delay = control(id, 3, s[5], true);
    for (int i = 0; i < frames; ++i) {
        float a = rev + (revTarget - rev) * (float)(i + 1) / frames;
        float b = delay + (delayTarget - delay) * (float)(i + 1) / frames;
        for (int c = 0; c < 2; ++c) { reverbIn_[2*i+c] += audio[2*i+c] * a; delayIn_[2*i+c] += audio[2*i+c] * b; }
    }
    rev = revTarget; delay = delayTarget;
}
void MixerGraph::track(int id, float* audio, float* out, int frames, bool heard) {
    chain(id, audio, frames);
    gate(id, audio, frames, heard);
    send(id, audio, frames);
    int g = (int)config_.strip(id)[6];
    if (g >= 0) {
        add(buses_.data() + 2 * g * frames_, audio, frames);
        groupHeard_[g] |= heard;
    } else add(out, audio, frames);
}
void MixerGraph::level(int id, float* audio, int frames, bool audible_) {
    const float* s = config_.strip(id);
    float l = 0, r = 0;
    if (audible_ && !s[2]) { panGains(control(id, 1, s[1]), 2, l, r); float gain = dbToGain(control(id, 0, s[0])); l *= gain; r *= gain; }
    float& oldL = previousL_[id]; float& oldR = previousR_[id];
    if (oldL < 0) {
        oldL = oldR = 0;
        if (audible_ && !s[2]) { panGains(control(id, 1, s[1], true), 2, oldL, oldR);
            float gain = dbToGain(control(id, 0, s[0], true)); oldL *= gain; oldR *= gain; }
    }
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
        chain(64+g, audio, frames); gate(64+g, audio, frames, groupHeard_[g] && !config_.strip(64+g)[2]); meter(64+g, audio, frames); send(64+g, audio, frames); add(out, audio, frames);
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
    level(73, wet_.data(), frames, true); chain(73, wet_.data(), frames); gate(73, wet_.data(), frames, !config_.strip(73)[2]); meter(73, wet_.data(), frames); add(out, wet_.data(), frames);
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
    level(72, wet_.data(), frames, true); chain(72, wet_.data(), frames); gate(72, wet_.data(), frames, !config_.strip(72)[2]); meter(72, wet_.data(), frames); add(out, wet_.data(), frames);
}
int MixerGraph::meters(float* out, int max) {
    int n = std::min(10, max);
    for (int i = 0; i < 2*n; ++i) out[i] = meters_[i].exchange(0.f, std::memory_order_relaxed);
    return n;
}
}
