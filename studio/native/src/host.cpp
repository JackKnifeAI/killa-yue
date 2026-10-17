#include "studio/host.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <map>
#include <thread>

#include "studio/export.h"

namespace studio {

Host::Host(int rate, Device* d, bool mic) : rate_(rate), device_(d), mic_(mic), engine_(new Engine(rate, kBlock)) {}

Host* Host::withDevice(Device* d, bool mic, std::string& err) {
    std::unique_ptr<Device> dev(d);
    if (!dev) {
        err = "There is no audio device here.";
        return nullptr;
    }
    if (!dev->open(mic, err)) return nullptr;
    const int rate = dev->rate();  // before release(): argument order is not fixed in C++
    Host* h = new Host(rate, dev.release(), mic);
    h->device_->setRenderer(h);
    h->device_->setInput(mic ? h : nullptr);
    return h;
}

Host* Host::offline(int rate) { return new Host(rate, nullptr, false); }

Host::~Host() {
    if (device_) device_->close();  // no more render() or input() after this
    for (auto& g : gens_)
        for (auto& s : g.streams) disk_.remove(s.get());
    engine_.reset();  // frees the sessions it still holds; the streams and readers go with gens_
}

bool Host::start(std::string& err) { return !device_ || device_->start(err); }

bool Host::post(const Message& m) {
    flushMaster();  // first, so it keeps its place before later edits
    if (m.kind == Msg::Loop) {
        if (!engine_->post(m)) return false;
        loopOn_ = m.track != 0 && m.end > m.frame && m.frame >= 0;
        loopStart_ = m.frame;
        loopEnd_ = m.end;
        return true;
    }
    if (m.kind == Msg::Master && engine_->post(m)) {
        masterDb_ = m.value;
        masterPending_ = false;
        return true;
    }
    return engine_->post(m);
}

void Host::flushMaster() {
    if (masterPending_ && engine_->post({Msg::Master, -1, masterDb_, 0, nullptr})) masterPending_ = false;
}

bool Host::load(const std::vector<TrackSpec>& tracks, float masterDb, std::string& err, const MixerConfig* mixer) {
    reap();
    if ((int)tracks.size() > Engine::kMaxTracks) {
        err = "The Studio plays up to " + std::to_string(Engine::kMaxTracks) + " tracks; this has " + std::to_string(tracks.size()) + ".";
        return false;
    }
    if (mixer && (!mixer->valid() || mixer->data[0] != (float)tracks.size())) {
        err = "Invalid mixer configuration."; return false;
    }
    Generation g;
    std::unique_ptr<Session> session(new Session{rate_, {}});
    if (mixer) { session->mixer = *mixer; session->hasMixer = true; }
    // one reader per file, shared by every clip of it (a loop opens its file once): only the disk thread reads
    std::map<std::string, WavReader*> open;
    for (const auto& t : tracks) {
        std::vector<ClipRef> clips;
        for (const auto& c : t.clips) {
            WavReader*& r = open[c.file];
            if (!r) {
                std::string why;
                std::unique_ptr<WavReader> fresh(WavReader::open(c.file.c_str(), why));
                if (!fresh) {
                    err = c.file + ": " + why;
                    return false;
                }
                if (fresh->rate() != rate_) {
                    err = c.file + " is at " + std::to_string(fresh->rate()) + " Hz; the Studio runs at " + std::to_string(rate_) + " Hz.";
                    return false;
                }
                r = fresh.get();
                g.readers.push_back(std::move(fresh));
            }
            clips.push_back({r, c.start, c.offset, c.length, c.gainDb, c.fadeIn, c.fadeOut});
        }
        g.streams.emplace_back(new Streamer(clips));
        session->tracks.push_back({g.streams.back().get(), t.gainDb, t.pan, t.mute, t.solo});
    }
    // fill the new tracks from where the song is now before they take over, so the change is seamless: when
    // the swap seeks them a moment further on, that place is already in their rings
    const long now = engine_->position();
    std::vector<Streamer*> fresh;
    for (auto& s : g.streams) {
        s->setLoop(loopStart_, loopEnd_, loopOn_);  // in the loop's playing order, as the engine will play them
        s->seek(now);
        fresh.push_back(s.get());
    }
    disk_.add(fresh);
    const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(1000);
    for (auto* s : fresh) {
        const long left = (long)std::chrono::duration_cast<std::chrono::milliseconds>(until - std::chrono::steady_clock::now()).count();
        s->waitReady((int)std::max(0L, left), rate_ / 2);  // half a second ahead; a slow disk only costs a gap
    }
    g.session = session.get();
    if (!engine_->post({Msg::Swap, 0, 0, 0, g.session})) {
        for (auto& s : g.streams) disk_.remove(s.get());
        err = "The Studio is busy; try again.";
        return false;
    }
    session.release();  // the engine has it now
    gens_.push_back(std::move(g));
    masterDb_ = masterDb;
    masterPending_ = true;
    flushMaster();
    return true;
}

int Host::filesOpen() const {
    size_t n = 0;
    for (const auto& g : gens_) n += g.readers.size();
    return (int)n;
}

void Host::reap() {
    while (Session* s = engine_->takeRetired()) {
        for (auto it = gens_.begin(); it != gens_.end(); ++it) {
            if (it->session != s) continue;
            for (auto& st : it->streams) disk_.remove(st.get());
            gens_.erase(it);  // its streams and readers
            break;
        }
        delete s;
    }
}

Status Host::status() {
    reap();
    performance().collect();
    flushMaster();
    return engine_->status();
}

DeviceStats Host::deviceStats() { return device_ ? device_->stats() : DeviceStats(); }

// audio thread: is a measurement running? It starts on a callback's microphone side, so both counts start at
// the same callback, and a withdrawn one is let go of here.
bool Host::calibrating(bool startHere, long position) {
    int st = cal_.load(std::memory_order_acquire);
    if (st == kArmed && startHere) {
        if (cal_.compare_exchange_strong(st, kRunning, std::memory_order_acq_rel)) {
            calIn_ = calOut_ = 0;
            calInBase_ = position;
            calOutBase_ = -1;  // the next render's position
            return true;
        }
    }
    if (st == kWithdrawn) {
        cal_.store(kIdle, std::memory_order_release);
        return false;
    }
    return st == kRunning;
}

void Host::input(const float* in, int frames, int channels, long position) {
    float peak = 0;
    for (int i = 0; i < frames; i++) peak = std::max(peak, std::fabs(in[(size_t)i * channels]));
    float old = inputPeak_.load();
    while (peak > old && !inputPeak_.compare_exchange_weak(old, peak)) {
    }
    if (!external_.load()) {
        recorder_.input(in, frames, channels, position);
        performance().input(in, frames, channels);
    }
    if (!calibrating(true, position)) return;
    // by the microphone's own position, so a backlog in reading it changes nothing
    for (int i = 0; i < frames; i++) {
        const long at = position + i - calInBase_;
        if (at >= 0 && at < (long)heard_.size()) heard_[(size_t)at] = in[(size_t)i * channels];
    }
    calIn_ = std::max(calIn_, position + frames - calInBase_);
}

void Host::render(float* out, int frames, long position) {
    engine_->process(out, frames);
    // Capture the first callback that actually plays, after queued transport commands take effect.
    if (recorder_.active() && recordAnchor_.load() < 0 && engine_->playing())
        recordAnchor_.store(position + recordDelay_.load());
    if (!calibrating(false, position)) return;
    if (calOutBase_ < 0) calOutBase_ = position;
    calOut_ = position - calOutBase_;  // the probes go by the output's own position too
    for (size_t k = 0; k < probeAt_.size(); k++) {
        const long at = probeAt_[k], P = (long)probes_[k].size();
        const long from = std::max(at, calOut_), to = std::min(at + P, calOut_ + frames);
        for (long f = from; f < to; f++) {
            const float v = probes_[k][(size_t)(f - at)];
            out[2 * (f - calOut_)] += v;
            out[2 * (f - calOut_) + 1] += v;
        }
    }
    calOut_ += frames;
    // done when the last probe has played and the microphone has had time to hear it (or its buffer is full)
    const long need = std::min((long)heard_.size(), calEnd_ + std::max(0L, calOutBase_ - calInBase_));
    if (calOut_ >= calEnd_ && calIn_ >= need) cal_.store(kDone, std::memory_order_release);  // hands the buffers back
}

// measureLatency stops waiting: takes the buffers back if the audio thread never started, or withdraws
RoundTrip Host::giveUp(const char* why, std::string& err) {
    int st = kArmed;
    if (cal_.compare_exchange_strong(st, kIdle, std::memory_order_acq_rel)) {
        err = calCancel_.load() ? "The measurement was cancelled." : "The microphone sent nothing. Is another app using it?";
        calReport_ = err;
        return {false, 0};
    }
    if (st == kRunning && cal_.compare_exchange_strong(st, kWithdrawn, std::memory_order_acq_rel)) {
        err = calCancel_.load() ? "The measurement was cancelled." : why;
        calReport_ = err;
        return {false, 0};
    }
    // it finished at this very moment (Done): hand the buffers back and report why we stopped waiting
    cal_.store(kIdle, std::memory_order_release);
    err = calCancel_.load() ? "The measurement was cancelled." : why;
    return {false, 0};
}

bool Host::exportMix(const char* path, long frames, bool limiter, std::string& err) {
    if (device_) {
        err = "an export runs on an off-line Studio, never on the one playing";
        return false;
    }
    reap();
    performance().collect();
    flushMaster();
    exportCancel_.store(false);
    exportDone_.store(0);
    ExportControl ctl;
    ctl.done = &exportDone_;
    ctl.cancel = &exportCancel_;
    return studio::exportMix(*engine_, frames, path, err, &ctl, limiter);
}

RoundTrip Host::measureLatency(int probes, std::string& err) {
    if (!device_ || !mic_) {
        err = "Measuring latency needs the microphone open.";
        return {false, 0};
    }
    int st = kIdle;
    // just after a cancel the audio thread lets go on its next callback: give it a moment
    for (int i = 0; i < 50 && cal_.load(std::memory_order_acquire) == kWithdrawn; i++)
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    if (!cal_.compare_exchange_strong(st, kPreparing, std::memory_order_acq_rel)) {
        err = st == kWithdrawn ? "The audio device has not come back since the last measurement." : "A measurement is already running.";
        return {false, 0};
    }
    calCancel_.store(false);
    calReport_.clear();
    probes = std::max(3, std::min(probes, 20));
    const long gap = rate_ * 2 / 5;  // 0.4 s between probes
    const long reach = rate_;        // round trips up to a second: each probe is its own noise, so they never mix up
    probes_.clear();
    probeAt_.clear();
    for (int k = 0; k < probes; k++) {
        probes_.push_back(probeSignal(rate_, (unsigned)k + 1));
        probeAt_.push_back(rate_ / 2 + k * gap);  // the first after half a second: the streams settle first
    }
    const long P = (long)probes_[0].size();
    calEnd_ = probeAt_.back() + reach + P;
    heard_.assign((size_t)(calEnd_ + rate_), 0.f);  // a second more: the microphone's count may be behind the output's
    cal_.store(kArmed, std::memory_order_release);
    const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(2000 + 1000L * calEnd_ / rate_);
    while (cal_.load(std::memory_order_acquire) != kDone) {
        if (calCancel_.load(std::memory_order_acquire)) return giveUp("The measurement was cancelled.", err);
        if (std::chrono::steady_clock::now() > until) return giveUp("The audio device stopped during the measurement.", err);
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    std::vector<long> delays;
    float peak = 0.f;
    double sum = 0.0;
    for (long i = 0; i < std::min(calIn_, (long)heard_.size()); i++) {
        peak = std::max(peak, std::fabs(heard_[(size_t)i]));
        sum += (double)heard_[(size_t)i] * heard_[(size_t)i];
    }
    char line[160];
    if (peak <= 0.f)
        calReport_ = "microphone silent: every sample it sent was zero";
    else {
        std::snprintf(line, sizeof line, "microphone peak %.1f dBFS, average %.1f dBFS", 20.0 * std::log10(peak),
                      10.0 * std::log10(sum / (double)std::max(calIn_, 1L) + 1e-20));
        calReport_ = line;
    }
    // Each probe is looked for where its sound must land on the microphone's count: from 0.4 s before the
    // place with the same position as when it was played (the streams' counts started at different moments,
    // so the answer can even be negative) to a second after. The answer is microphone position minus output
    // position: what moves a recording into line with what was playing.
    const long lead = rate_ * 2 / 5;
    for (size_t k = 0; k < probeAt_.size(); k++) {
        const long played = calOutBase_ + probeAt_[k];  // the output position the probe started at
        const long from = std::max(0L, played - calInBase_ - lead), to = std::min((long)heard_.size(), played - calInBase_ + reach + P);
        const Found f = to - from >= P ? findProbe(heard_.data() + from, to - from, probes_[k]) : Found{-1, false, 0.f, 0.f};
        const long rt = f.delay < 0 ? -1 : calInBase_ + from + f.delay - played;
        delays.push_back(f.sure ? rt : kNotFound);
        if (f.sure)
            std::snprintf(line, sizeof line, "; probe %zu: %ld (match %.2f, clarity %.0f)", k + 1, rt, f.score, f.clarity);
        else
            std::snprintf(line, sizeof line, "; probe %zu: not found (best %.2f at %ld, clarity %.0f)", k + 1, f.score, rt, f.clarity);
        calReport_ += line;
    }
    cal_.store(kIdle, std::memory_order_release);
    RoundTrip rt = roundTrip(delays, rate_);
    if (!rt.ok) err = "The probes were not heard clearly. Turn the volume up, keep the phone still and quiet, and try again.";
    return rt;
}

}  // namespace studio
