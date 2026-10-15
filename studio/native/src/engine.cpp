#include "studio/engine.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace studio {

Engine::Engine(int rate, int maxFrames) : rate_(rate), maxFrames_(maxFrames), scratch_(2 * (size_t)maxFrames, 0.f), limiter_(rate) {
    for (auto& m : meter_) m.store(0.f, std::memory_order_relaxed);
}

Engine::~Engine() {
    delete cur_;
    if (hasPending_) delete pending_.session;
    Session* s;
    while (retired_.pop(s)) delete s;
    Message m;  // sessions still waiting in the queue were never taken over: they are ours to free too
    while (in_.pop(m)) if (m.kind == Msg::Swap) delete m.session;
}

bool Engine::post(const Message& m) { return in_.push(m); }

Session* Engine::takeRetired() {
    Session* s = nullptr;
    return retired_.pop(s) ? s : nullptr;
}

Status Engine::status() const {
    return Status{position_.load(), underruns_.load(), refused_.load(), playingFlag_.load(), peakL_.exchange(0.f), peakR_.exchange(0.f)};
}

bool Engine::swap(Session* s) {
    if (s && (int)s->tracks.size() > kMaxTracks) {  // refused: counted, and handed back for freeing
        if (!retired_.push(s)) return false;
        refused_.fetch_add(1, std::memory_order_relaxed);
        return true;
    }
    if (cur_ && !retired_.push(cur_)) return false;  // no room to hand the old one back: try next block
    cur_ = s;
    meterTracks_.store(cur_ ? (int)cur_->tracks.size() : 0, std::memory_order_relaxed);
    // its tracks join at the song's position, with the loop (a source already there ignores the seek)
    if (cur_)
        for (auto& t : cur_->tracks) {
            t.source->setLoop(loopStart_, loopEnd_, loopOn_);
            t.source->seek(pos_);
        }
    return true;
}

void Engine::apply(const Message& m) {
    TrackSlot* t = cur_ && m.track >= 0 && m.track < (int)cur_->tracks.size() ? &cur_->tracks[m.track] : nullptr;
    switch (m.kind) {
        case Msg::Gain: if (t && std::isfinite(m.value)) t->gainDb = m.value; break;
        case Msg::Pan: if (t && std::isfinite(m.value)) t->pan = std::max(-1.f, std::min(1.f, m.value)); break;
        case Msg::Mute: if (t) t->mute = m.value != 0.f; break;
        case Msg::Solo: if (t) t->solo = m.value != 0.f; break;
        case Msg::Master: if (std::isfinite(m.value)) master_ = dbToGain(m.value); break;
        case Msg::Reset: limiter_.reset(); break;
        case Msg::Play: playing_ = true; break;
        case Msg::Stop: playing_ = false; break;
        case Msg::Seek:
            pos_ = m.frame;
            if (cur_) for (auto& s : cur_->tracks) s.source->seek(m.frame);
            break;
        case Msg::Loop:
            loopOn_ = m.track != 0 && m.end > m.frame && m.frame >= 0;
            loopStart_ = m.frame;
            loopEnd_ = m.end;
            if (cur_) for (auto& s : cur_->tracks) s.source->setLoop(loopStart_, loopEnd_, loopOn_);
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
    // a block bigger than the engine was made for is rendered in pieces
    float pl = 0.f, pr = 0.f;
    for (int done = 0; done < frames;) {
        int n = std::min(maxFrames_, frames - done);
        // the loop's end falls inside this block: render up to it, then carry on from the loop's start (the
        // sources wrap by themselves, reading ahead in playing order)
        const bool wraps = playing_ && loopOn_ && pos_ < loopEnd_;
        if (wraps && pos_ + n > loopEnd_) n = (int)(loopEnd_ - pos_);
        render(out + 2 * done, n, pl, pr);
        done += n;
        if (wraps && pos_ == loopEnd_) pos_ = loopStart_;
    }
    // raise the held peaks; the reader takes and clears them (lock-free: a reader only ever lowers them to 0)
    float held = peakL_.load(std::memory_order_relaxed);
    while (pl > held && !peakL_.compare_exchange_weak(held, pl, std::memory_order_relaxed)) {}
    held = peakR_.load(std::memory_order_relaxed);
    while (pr > held && !peakR_.compare_exchange_weak(held, pr, std::memory_order_relaxed)) {}
    position_.store(pos_, std::memory_order_relaxed);
    playingFlag_.store(playing_ ? 1 : 0, std::memory_order_relaxed);
}

static void raise(std::atomic<float>& held, float v) {
    float h = held.load(std::memory_order_relaxed);
    while (v > h && !held.compare_exchange_weak(h, v, std::memory_order_relaxed)) {}
}

int Engine::meters(float* out, int max) {
    const int n = std::min(max, meterTracks_.load(std::memory_order_relaxed));
    for (int i = 0; i < 2 * n; i++) out[i] = meter_[i].exchange(0.f, std::memory_order_relaxed);
    return n;
}

void Engine::render(float* out, int frames, float& pl, float& pr) {
    std::memset(out, 0, sizeof(float) * 2 * (size_t)frames);
    if (playing_ && cur_) {
        bool anySolo = false;
        for (auto& s : cur_->tracks) anySolo |= s.solo;
        bool short_ = false;
        for (auto& s : cur_->tracks) {
            const int got = s.source->read(scratch_.data(), frames);
            if (got < frames) short_ = true;
            float l = 0.f, r = 0.f;
            if (!s.mute && (!anySolo || s.solo)) {
                panGains(s.pan, s.source->panChannels(), l, r);
                const float g = dbToGain(s.gainDb);
                l *= g;
                r *= g;
            }
            if (s.appliedL < 0.f) {  // first time played: start where it should be
                s.appliedL = l;
                s.appliedR = r;
            }
            mixGliding(scratch_.data(), s.source->channels(), frames, out, s.appliedL, s.appliedR, l, r);
            // the track's meter: its loudest sample at the louder of the two gains it glided between
            const int t = (int)(&s - &cur_->tracks[0]);
            const float gl = std::max(s.appliedL, l), gr = std::max(s.appliedR, r);
            float ml = 0.f, mr = 0.f;
            if (gl > 0.f || gr > 0.f)
                for (int i = 0; i < frames; i++) {
                    ml = std::max(ml, std::fabs(scratch_[2 * i]));
                    mr = std::max(mr, std::fabs(scratch_[2 * i + (s.source->channels() > 1 ? 1 : 0)]));
                }
            raise(meter_[2 * t], ml * gl);
            raise(meter_[2 * t + 1], mr * gr);
            s.appliedL = l;
            s.appliedR = r;
        }
        if (short_) underruns_.fetch_add(1, std::memory_order_relaxed);
        if (master_ != 1.f) for (int i = 0; i < 2 * frames; i++) out[i] *= master_;
    }
    limiter_.process(out, frames);  // also when stopped, so its delay drains without a click
    for (int i = 0; i < frames; i++) {
        pl = std::max(pl, std::fabs(out[2 * i]));
        pr = std::max(pr, std::fabs(out[2 * i + 1]));
    }
    if (playing_) pos_ += frames;
}

bool Engine::waitSourcesReady(int ms) {
    bool ok = true;
    if (cur_) for (auto& s : cur_->tracks) ok = s.source->waitReady(ms) && ok;
    return ok;
}

bool Engine::setOffline(bool on) { return post({Msg::Offline, 0, on ? 1.f : 0.f, 0, nullptr}); }

}  // namespace studio
