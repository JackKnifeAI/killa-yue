#include "studio/engine.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace studio {

Engine::Engine(int rate, int maxFrames)
    : rate_(rate), maxFrames_(maxFrames), scratch_(2 * (size_t)maxFrames, 0.f), limiter_(rate) {}

Engine::~Engine() {
    delete cur_;
    if (hasPending_) delete pending_.session;
    Session* s;
    while (retired_.pop(s)) delete s;
}

bool Engine::post(const Message& m) { return in_.push(m); }

Session* Engine::takeRetired() {
    Session* s = nullptr;
    return retired_.pop(s) ? s : nullptr;
}

Status Engine::status() const {
    return Status{position_.load(), underruns_.load(), playingFlag_.load(), peakL_.load(), peakR_.load()};
}

bool Engine::swap(Session* s) {
    if (s && (int)s->tracks.size() > kMaxTracks) {  // refused: handed back for freeing
        return retired_.push(s);
    }
    if (cur_ && !retired_.push(cur_)) return false;  // no room to hand the old one back: try next block
    cur_ = s;
    return true;
}

void Engine::apply(const Message& m) {
    TrackSlot* t = cur_ && m.track >= 0 && m.track < (int)cur_->tracks.size() ? &cur_->tracks[m.track] : nullptr;
    switch (m.kind) {
        case Msg::Gain: if (t) t->gainDb = m.value; break;
        case Msg::Pan: if (t) t->pan = std::max(-1.f, std::min(1.f, m.value)); break;
        case Msg::Mute: if (t) t->mute = m.value != 0.f; break;
        case Msg::Solo: if (t) t->solo = m.value != 0.f; break;
        case Msg::Master: master_ = dbToGain(m.value); break;
        case Msg::Play: playing_ = true; break;
        case Msg::Stop: playing_ = false; break;
        case Msg::Seek:
            pos_ = m.frame;
            if (cur_) for (auto& s : cur_->tracks) s.source->seek(m.frame);
            break;
        case Msg::Offline:
            if (cur_) for (auto& s : cur_->tracks) s.source->setOffline(m.value != 0.f);
            break;
        case Msg::Swap: break;  // handled by the caller
    }
}

void Engine::process(float* out, int frames) {
    // edits, in order; a Swap that cannot hand back the old session waits, and so does everything after it
    if (hasPending_ && swap(pending_.session)) hasPending_ = false;
    Message m;
    while (!hasPending_ && in_.pop(m)) {
        if (m.kind == Msg::Swap) {
            if (!swap(m.session)) {
                pending_ = m;
                hasPending_ = true;
            }
        } else {
            apply(m);
        }
    }
    std::memset(out, 0, sizeof(float) * 2 * (size_t)frames);
    if (playing_ && cur_ && frames <= maxFrames_) {
        bool anySolo = false;
        for (auto& s : cur_->tracks) anySolo |= s.solo;
        bool short_ = false;
        for (auto& s : cur_->tracks) {
            const int got = s.source->read(scratch_.data(), frames);
            if (got < frames) short_ = true;
            TrackMix t;
            t.gain = dbToGain(s.gainDb);
            t.channels = s.source->channels();
            panGains(s.pan, t.channels, t.panL, t.panR);
            t.audible = !s.mute && (!anySolo || s.solo);
            mixTrack(t, scratch_.data(), frames, out);
        }
        if (short_) underruns_.fetch_add(1, std::memory_order_relaxed);
        if (master_ != 1.f) for (int i = 0; i < 2 * frames; i++) out[i] *= master_;
    }
    limiter_.process(out, frames);  // also when stopped, so its delay drains without a click
    float pl = 0.f, pr = 0.f;
    for (int i = 0; i < frames; i++) {
        pl = std::max(pl, std::fabs(out[2 * i]));
        pr = std::max(pr, std::fabs(out[2 * i + 1]));
    }
    peakL_.store(pl, std::memory_order_relaxed);
    peakR_.store(pr, std::memory_order_relaxed);
    if (playing_) pos_ += frames;
    position_.store(pos_, std::memory_order_relaxed);
    playingFlag_.store(playing_ ? 1 : 0, std::memory_order_relaxed);
}

bool Engine::waitSourcesReady(int ms) {
    bool ok = true;
    if (cur_) for (auto& s : cur_->tracks) ok = s.source->waitReady(ms) && ok;
    return ok;
}

bool Engine::setOffline(bool on) { return post({Msg::Offline, 0, on ? 1.f : 0.f, 0, nullptr}); }

}  // namespace studio
