#include "studio/host.h"
#include <algorithm>
#include <chrono>
#include <thread>

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

bool Host::load(const std::vector<TrackSpec>& tracks, std::string& err) {
    reap();
    Generation g;
    std::unique_ptr<Session> session(new Session{rate_, {}});
    for (const auto& t : tracks) {
        std::vector<ClipRef> clips;
        for (const auto& c : t.clips) {
            std::string why;
            std::unique_ptr<WavReader> r(WavReader::open(c.file.c_str(), why));
            if (!r) {
                err = c.file + ": " + why;
                return false;
            }
            if (r->rate() != rate_) {
                err = c.file + " is at " + std::to_string(r->rate()) + " Hz; the Studio runs at " + std::to_string(rate_) + " Hz.";
                return false;
            }
            clips.push_back({r.get(), c.start, c.offset, c.length, c.gainDb});
            g.readers.push_back(std::move(r));
        }
        g.streams.emplace_back(new Streamer(clips));
        session->tracks.push_back({g.streams.back().get(), t.gainDb, t.pan, t.mute, t.solo});
    }
    for (auto& s : g.streams) disk_.add(s.get());
    g.session = session.get();
    if (!engine_->post({Msg::Swap, 0, 0, 0, g.session})) {
        for (auto& s : g.streams) disk_.remove(s.get());
        err = "The Studio is busy; try again.";
        return false;
    }
    session.release();  // the engine has it now
    gens_.push_back(std::move(g));
    return true;
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
    return engine_->status();
}

DeviceStats Host::deviceStats() { return device_ ? device_->stats() : DeviceStats(); }

// audio thread: is a measurement running? Starts one on a callback's microphone side (so both counts start
// at the same callback) and lets go of the buffers if the measurement was withdrawn.
bool Host::calibrating(bool startHere) {
    if (!calBusy_.load(std::memory_order_relaxed)) {
        if (!startHere || !calActive_.load(std::memory_order_acquire)) return false;
        calBusy_.store(true, std::memory_order_relaxed);
        calIn_ = calOut_ = 0;
        return true;
    }
    if (calActive_.load(std::memory_order_acquire)) return true;
    calBusy_.store(false, std::memory_order_release);
    return false;
}

void Host::input(const float* in, int frames, int channels) {
    if (!calibrating(true)) return;
    for (int i = 0; i < frames && calIn_ < (long)heard_.size(); i++) heard_[(size_t)calIn_++] = in[(size_t)i * channels];
}

void Host::render(float* out, int frames) {
    engine_->process(out, frames);
    if (!calibrating(false)) return;
    const long P = (long)probe_.size();
    for (long at : probeAt_) {
        const long from = std::max(at, calOut_), to = std::min(at + P, calOut_ + frames);
        for (long f = from; f < to; f++) {
            const float v = probe_[(size_t)(f - at)];
            out[2 * (f - calOut_)] += v;
            out[2 * (f - calOut_) + 1] += v;
        }
    }
    calOut_ += frames;
    if (calOut_ >= calEnd_ && calIn_ >= calEnd_) {
        calBusy_.store(false, std::memory_order_relaxed);
        calActive_.store(false, std::memory_order_release);  // hands the buffers back to measureLatency
    }
}

RoundTrip Host::measureLatency(int probes, std::string& err) {
    if (!device_ || !mic_) {
        err = "Measuring latency needs the microphone open.";
        return {false, 0};
    }
    if (calActive_.load(std::memory_order_acquire) || calBusy_.load(std::memory_order_acquire)) {
        err = "A measurement is already running.";
        return {false, 0};
    }
    probes = std::max(3, std::min(probes, 20));
    const long gap = rate_ * 2 / 5;  // 0.4 s between probes: room for the longest round trip worth measuring
    probe_ = probeSignal(rate_);
    probeAt_.clear();
    for (int k = 0; k < probes; k++) probeAt_.push_back(rate_ / 5 + k * gap);
    calEnd_ = probeAt_.back() + gap;
    heard_.assign((size_t)calEnd_, 0.f);
    calActive_.store(true, std::memory_order_release);
    const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(2000 + 1000L * calEnd_ / rate_);
    while (calActive_.load(std::memory_order_acquire)) {
        if (std::chrono::steady_clock::now() > until) {
            // the device stopped calling; withdraw, and the audio thread lets go of the buffers on its next call
            calActive_.store(false, std::memory_order_release);
            err = "The audio device stopped during the measurement.";
            return {false, 0};
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    std::vector<long> delays;
    const long P = (long)probe_.size();
    for (long at : probeAt_) {
        const long len = std::min(gap - rate_ / 50 + P, calEnd_ - at);
        const Found f = findProbe(heard_.data() + at, len, probe_);
        delays.push_back(f.sure ? f.delay : -1);
    }
    RoundTrip rt = roundTrip(delays, rate_);
    if (!rt.ok) err = "The probes were not heard clearly. Turn the volume up, keep the phone still and quiet, and try again.";
    return rt;
}

}  // namespace studio
