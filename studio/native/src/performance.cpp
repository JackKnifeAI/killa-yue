#include "studio/performance.h"

#include <algorithm>
#include <cmath>
#include <memory>

#include "studio/wav.h"
namespace studio {
namespace {
constexpr double pi = 3.14159265358979323846;
constexpr size_t budget = 128ull * 1024 * 1024;
float hermite(const std::vector<float>& x, double at, int ch) {
    long n = (long)x.size() / 2, i = (long)at;
    float t = (float)(at - i);
    auto v = [&](long k) { return x[2 * (size_t)std::max(0L, std::min(n - 1, k)) + ch]; };
    float a = v(i - 1), b = v(i), c = v(i + 1), d = v(i + 2);
    return b + .5f * t * (c - a + t * (2 * a - 5 * b + 4 * c - d + t * (3 * (b - c) + d - a)));
}
}  // namespace
long PerformanceAudio::stepFrame(long step, int rate, double bpm, double swing) {
    return std::llround((step + ((step & 1) ? swing : 0)) * rate * 15.0 / bpm);
}
void PerformanceAudio::collect() {
    Sample* s;
    while (retired_.pop(s)) {
        bytes_.fetch_sub(s->x.size() * sizeof(float));
        delete s;
    }
}
void PerformanceAudio::release(Sample* s) {
    if (s && --s->refs == 0) retired_.push(s);
}
void PerformanceAudio::clearVoices() {
    for (auto& v : voices_) {
        release(v.s);
        v = Voice{};
    }
}
PerformanceAudio::~PerformanceAudio() {
    drain();
    clearVoices();
    for (auto& p : pads_) release(p.s);
    collect();
}
bool PerformanceAudio::load(int pad, const char* path, long offset, long length, std::string& err) {
    collect();
    if (pad < 0 || pad >= 16 || offset < 0 || length < 0) {
        err = "Invalid sample range.";
        return false;
    }
    std::unique_ptr<WavReader> r(WavReader::open(path, err));
    if (!r) return false;
    if (r->rate() != rate_ || offset >= r->frames()) {
        err = "Use audio imported into this project first.";
        return false;
    }
    length = length ? std::min(length, r->frames() - offset) : r->frames() - offset;
    if (length <= 0 || (unsigned long long)length > budget / 8 || bytes_.load() + length * 8 > budget) {
        err = "The pad bank has a 128 MB sample limit. Use a shorter slice.";
        return false;
    }
    std::unique_ptr<Sample> s(new Sample);
    s->x.resize((size_t)length * 2);
    float buf[8192];
    for (long at = 0; at < length;) {
        int n = (int)std::min(4096L, length - at);
        if (r->read(offset + at, buf, n) != n) {
            err = "Cannot read the sample.";
            return false;
        }
        for (int i = 0; i < n; i++)
            for (int c = 0; c < 2; c++) {
                float v = buf[i * r->channels() + (r->channels() == 1 ? 0 : c)];
                s->x[2 * (at + i) + c] = std::isfinite(v) ? v : 0;
            }
        at += n;
    }
    Event e;
    e.kind = 1;
    e.key = pad;
    e.sample = s.get();
    if (!events_.push(e)) {
        err = "The sampler is busy; try again.";
        return false;
    }
    bytes_.fetch_add(s->x.size() * sizeof(float));
    s.release();
    return true;
}
bool PerformanceAudio::kit(std::string& err) {
    collect();
    // Deterministic original drum synthesis; no bundled recordings or sample licences.
    for (int k = 0; k < 16; k++) {
        int n = rate_ / 2;
        std::unique_ptr<Sample> s(new Sample);
        s->x.resize((size_t)n * 2);
        if (bytes_.load() + s->x.size() * 4 > budget) {
            err = "The sampler memory budget is full.";
            return false;
        }
        unsigned noise = 12345u + k * 719;
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double)i / rate_;
            noise = noise * 1664525u + 1013904223u;
            float z = (float)((noise >> 8) / 8388608.0 - 1);
            double f = (k % 4 == 0 ? 45 + 110 * std::exp(-t * 35) : k % 4 == 3 ? 120 + 30 * k : 180);
            phase += 2 * pi * f / rate_;
            float v;
            if (k % 4 == 0)
                v = (float)(.8 * std::sin(phase) * std::exp(-t * 12));
            else if (k % 4 == 1)
                v = (float)((.65 * z + .2 * std::sin(phase)) * std::exp(-t * 24));
            else if (k % 4 == 2)
                v = (float)(.3 * z * std::exp(-t * (k < 8 ? 95 : 30)));
            else
                v = (float)(.5 * std::sin(phase) * std::exp(-t * 18));
            s->x[2 * i] = s->x[2 * i + 1] = v;
        }
        Event e;
        e.kind = 1;
        e.key = k;
        e.sample = s.get();
        if (!events_.push(e)) {
            err = "The sampler is busy.";
            return false;
        }
        bytes_.fetch_add(s->x.size() * 4);
        s.release();
    }
    return true;
}
bool PerformanceAudio::trigger(int pad, float v, bool on) {
    if (pad < 0 || pad >= 16 || !std::isfinite(v)) return false;
    Event e;
    e.kind = 2;
    e.key = pad;
    e.value = std::clamp(v, 0.f, 1.f);
    e.on = on;
    return events_.push(e);
}
bool PerformanceAudio::configure(int pad, const float* v, int n) {
    if (pad < 0 || pad >= 16 || n != 10) return false;
    for (int i = 0; i < n; i++)
        if (!std::isfinite(v[i])) return false;
    Event e;
    e.kind = 3;
    e.key = pad;
    std::copy(v, v + n, e.data.begin());
    return events_.push(e);
}
bool PerformanceAudio::transport(double bpm, int beats, int unit, bool click, bool pattern, int length, double swing) {
    if (!(bpm >= 20 && bpm <= 400) || beats < 1 || beats > 16 || (unit != 2 && unit != 4 && unit != 8 && unit != 16) ||
        (length != 16 && length != 32) || !(swing >= 0 && swing <= .75))
        return false;
    Event e;
    e.kind = 4;
    e.data[0] = (float)bpm;
    e.data[1] = (float)beats;
    e.data[2] = (float)unit;
    e.data[3] = click ? 1.f : 0.f;
    e.data[4] = pattern ? 1.f : 0.f;
    e.data[5] = (float)length;
    e.data[6] = (float)swing;
    return events_.push(e);
}
bool PerformanceAudio::patternRow(int pad, const float* v) {
    if (pad < 0 || pad >= 16) return false;
    Event e;
    e.kind = 5;
    e.key = pad;
    for (int i = 0; i < 32; i++) {
        if (!(v[i] >= 0 && v[i] <= 1)) return false;
        e.data[i] = v[i];
    }
    return events_.push(e);
}
bool PerformanceAudio::tempoPoint(int index, long frame, double bpm) {
    if (index < 0 || index >= 64 || frame < 0 || !(bpm >= 20 && bpm <= 400)) return false;
    Event e;
    e.kind = 7;
    e.key = index;
    e.frame = frame;
    e.value = (float)bpm;
    return events_.push(e);
}
double PerformanceAudio::beatAt(long frame) const {
    int k = 0;
    while (k + 1 < tempoCount_ && tempoFrames_[k + 1] <= frame) k++;
    return tempoBeats_[k] + (frame - tempoFrames_[k]) * tempoBpms_[k] / (rate_ * 60.);
}
long PerformanceAudio::frameAt(double beat) const {
    int k = 0;
    while (k + 1 < tempoCount_ && tempoBeats_[k + 1] <= beat) k++;
    return std::llround(tempoFrames_[k] + (beat - tempoBeats_[k]) * rate_ * 60 / tempoBpms_[k]);
}
bool PerformanceAudio::allOff() {
    Event e;
    e.kind = 6;
    return events_.push(e);
}
void PerformanceAudio::drain() {
    Event e;
    while (events_.pop(e)) {
        if (e.kind == 1) {
            release(pads_[e.key].s);
            pads_[e.key].s = e.sample;
        } else if (e.kind == 2)
            fire(e.key, e.value, e.on);
        else if (e.kind == 3) {
            Pad& p = pads_[e.key];
            p.tune = std::clamp(e.data[0], -24.f, 24.f);
            p.attack = std::clamp(e.data[1], 0.f, 10.f);
            p.decay = std::clamp(e.data[2], 0.f, 10.f);
            p.sustain = std::clamp(e.data[3], 0.f, 1.f);
            p.release = std::clamp(e.data[4], .002f, 10.f);
            p.cutoff = std::clamp(e.data[5], 20.f, 24000.f);
            p.mode = std::clamp((int)e.data[6], 0, 2);
            p.choke = std::clamp((int)e.data[7], 0, 16);
            p.reverse = e.data[8] != 0;
        } else if (e.kind == 4) {
            bpm_ = e.data[0];
            tempoBpms_[0] = bpm_;
            beats_ = (int)e.data[1];
            unit_ = (int)e.data[2];
            click_ = e.data[3] != 0;
            pattern_ = e.data[4] != 0;
            length_ = (int)e.data[5];
            swing_ = e.data[6];
        } else if (e.kind == 5)
            steps_[e.key] = e.data;
        else if (e.kind == 6) {
            for (auto& v : voices_)
                if (v.s) v.released = true;
        } else if (e.kind == 7) {
            if (e.key == 0) {
                tempoCount_ = 1;
                tempoFrames_[0] = 0;
                tempoBpms_[0] = e.value;
                tempoBeats_[0] = 0;
            } else if (e.key == tempoCount_ && e.frame >= tempoFrames_[e.key - 1]) {
                tempoFrames_[e.key] = e.frame;
                tempoBpms_[e.key] = e.value;
                tempoBeats_[e.key] = tempoBeats_[e.key - 1] + (e.frame - tempoFrames_[e.key - 1]) * tempoBpms_[e.key - 1] / (rate_ * 60.);
                tempoCount_++;
            }
        }
    }
}
void PerformanceAudio::fire(int key, float velocity, bool on) {
    Pad p = pads_[key];
    if (!on) {
        if (p.mode != 0)
            for (auto& v : voices_)
                if (v.s && v.key == key) v.released = true;
        return;
    }
    if (!p.s || velocity <= 0) return;
    if (p.choke)
        for (auto& v : voices_)
            if (v.s && v.pad.choke == p.choke) {
                v.released = true;
                v.pad.release = .002f;
            }
    Voice* target = nullptr;
    for (auto& v : voices_)
        if (!v.s) {
            target = &v;
            break;
        }
    if (!target) {
        target = &*std::max_element(voices_.begin(), voices_.end(), [](const Voice& a, const Voice& b) { return a.age < b.age; });
        stealL_ = target->filterL * target->env * target->velocity;
        stealR_ = target->filterR * target->env * target->velocity;
        stealFrames_ = std::max(1, rate_ / 500);
        release(target->s);
    }
    *target = Voice{};
    target->s = p.s;
    p.s->refs++;
    target->pad = p;
    target->key = key;
    target->velocity = velocity;
    target->coefficient = 1 - std::exp(-2 * (float)pi * std::min(p.cutoff, rate_ * .45f) / rate_);
    target->at = p.reverse ? (double)p.s->x.size() / 2 - 1 : 0;
    target->step = std::pow(2., p.tune / 12.) * (p.reverse ? -1 : 1);
}
void PerformanceAudio::mix(float* out, int frames, long position, bool playing) {
    drain();
    if (wasPlaying_ && (!playing || position != previous_))
        for (auto& v : voices_)
            if (v.s) v.released = true;
    wasPlaying_ = playing;
    for (int i = 0; i < frames; i++) {
        long f = position + i;
        if (playing) {
            double quarter = beatAt(f);
            long beat = (long)std::llround(quarter * unit_ / 4);
            if ((click_ || position < 0) && frameAt(beat * 4.0 / unit_) == f) {
                clickEnv_ = .22f;
                clickHz_ = beat % beats_ == 0 ? 1800 : 1200;
                clickPhase_ = 0;
            }
            if (pattern_ && f >= 0) {
                long approx = (long)std::floor(quarter * 4);
                for (long s = std::max(0L, approx - 1); s <= approx + 1; s++)
                    if (frameAt((s + ((s & 1) ? swing_ : 0)) / 4) == f)
                        for (int p = 0; p < 16; p++)
                            if (steps_[p][s % length_] > 0) fire(p, steps_[p][s % length_], true);
            }
        }
        float l = 0, r = 0;
        for (auto& v : voices_)
            if (v.s) {
                long n = (long)v.s->x.size() / 2;
                if (v.at < 0 || v.at >= n) {
                    if (v.pad.mode == 2 && !v.released) {
                        v.at = std::fmod(v.at + n * 4., (double)n);
                    } else {
                        release(v.s);
                        v.s = nullptr;
                        continue;
                    }
                }
                if (v.released)
                    v.env = std::max(0.f, v.env - 1.f / (std::max(.002f, v.pad.release) * rate_));
                else {
                    float t = (float)v.age / rate_;
                    if (t < v.pad.attack)
                        v.env = v.pad.attack > 0 ? t / v.pad.attack : 1;
                    else if (t < v.pad.attack + v.pad.decay)
                        v.env = 1 - (1 - v.pad.sustain) * (t - v.pad.attack) / std::max(.00001f, v.pad.decay);
                    else
                        v.env = v.pad.sustain;
                }
                if (v.released && v.env <= 0) {
                    release(v.s);
                    v.s = nullptr;
                    continue;
                }
                float coeff = v.coefficient;
                v.filterL += coeff * (hermite(v.s->x, v.at, 0) - v.filterL);
                v.filterR += coeff * (hermite(v.s->x, v.at, 1) - v.filterR);
                float endFade =
                    v.pad.mode == 2
                        ? 1.f
                        : (float)std::min(1., (v.step > 0 ? n - v.at : v.at + 1) / std::max(1., rate_ * .002 * std::fabs(v.step)));
                l += v.filterL * v.env * v.velocity * endFade;
                r += v.filterR * v.env * v.velocity * endFade;
                v.at += v.step;
                v.age++;
            }
        if (stealFrames_ > 0) {
            float g = (float)stealFrames_ / std::max(1, rate_ / 500);
            l += stealL_ * g;
            r += stealR_ * g;
            stealFrames_--;
        }
        if (clickEnv_ > 1e-5f) {
            float c = clickEnv_ * (float)std::sin(clickPhase_);
            l += c;
            r += c;
            clickPhase_ += 2 * pi * clickHz_ / rate_;
            clickEnv_ *= .992f;
        }
        float heard = 0;
        if (monitorRing_.pop(heard) && monitoring_.load()) {
            l += heard;
            r += heard;
        }
        out[2 * i] += l;
        out[2 * i + 1] += r;
    }
    previous_ = playing ? position + frames : position;
}
}  // namespace studio
