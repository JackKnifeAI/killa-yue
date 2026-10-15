#include "studio/streamer.h"
#include <algorithm>
#include <chrono>
#include <cstring>

namespace studio {

static long powerOfTwo(long n) {
    long p = 1;
    while (p < n) p <<= 1;
    return p;
}

Streamer::Streamer(const std::vector<ClipRef>& clips, int ringFrames)
    : cap_(powerOfTwo(std::max(ringFrames, 64))), ring_(2 * cap_, 0.f), chunk_(2 * std::min(4096L, cap_ / 2)), raw_(chunk_.size()) {
    bool first = true;
    for (const auto& c : clips) {
        if (c.length <= 0) continue;
        clips_.push_back({c.reader, c.start, c.offset, c.length, dbToGain(c.gainDb)});
        start_ = first ? c.start : std::min(start_, c.start);
        end_ = first ? c.start + c.length : std::max(end_, c.start + c.length);
        first = false;
        if (c.reader->channels() > 1) panChannels_ = 2;
    }
}

Streamer::Streamer(Reader* r, long clipStart, long fileOffset, long length, int ringFrames, float gainDb)
    : Streamer(std::vector<ClipRef>{{r, clipStart, fileOffset, length, gainDb}}, ringFrames) {}

Streamer::~Streamer() { stopThread(); }

void Streamer::startThread() {
    stop_.store(false);
    fed_.store(true);
    if (!thread_.joinable()) thread_ = std::thread(&Streamer::run, this);
}

void Streamer::stopThread() {
    stop_.store(true);
    if (thread_.joinable()) thread_.join();
    fed_.store(false);
}

long Streamer::overlap(long from, long to) const {
    return std::max(0L, std::min(to, clipEnd()) - std::max(from, start_));
}

long Streamer::audible(const Order& o, long from, long to) const {
    long n = 0;
    for (long i = from; i < to;) {
        const long k = std::min(o.run(i), to - i);
        const long f = o.at(i);
        n += overlap(f, f + k);
        i += k;
    }
    return n;
}

void Streamer::regenerate(long projectFrame) {
    pos_ = projectFrame;
    play_ = 0;
    order_.s0 = projectFrame;
    order_.ls = loopStart_;
    order_.le = loopEnd_;
    order_.on = loopOn_;
    seekTo_.store(projectFrame, std::memory_order_relaxed);
    genLoopStart_.store(loopStart_, std::memory_order_relaxed);
    genLoopEnd_.store(loopEnd_, std::memory_order_relaxed);
    genLoopOn_.store(loopOn_, std::memory_order_relaxed);
    wanted_ = generation_.fetch_add(1, std::memory_order_acq_rel) + 1;  // publishes the values above
}

void Streamer::seek(long projectFrame) {
    if (projectFrame == pos_) return;  // already there: nothing to refill
    // a little ahead, into what the ring already holds (and before any wrap): move on, keeping the read-ahead
    const long d = projectFrame - pos_;
    if (d > 0 && d < order_.run(play_) && ringGeneration_.load(std::memory_order_acquire) == wanted_ &&
        ringStart_.load(std::memory_order_relaxed) + play_ + d < written_.load(std::memory_order_acquire)) {
        play_ += d;
        pos_ = projectFrame;
        return;
    }
    regenerate(projectFrame);
}

void Streamer::setLoop(long start, long end, bool on) {
    if (on && (end <= start || start < 0)) on = false;
    if (on == loopOn_ && (!on || (start == loopStart_ && end == loopEnd_))) return;
    loopStart_ = start;
    loopEnd_ = end;
    loopOn_ = on;
    regenerate(pos_);  // read ahead in the new playing order from where it is now
}

// Copies what the ring holds from play index `play` (catching up first if time moved on during a stall).
long Streamer::take(long play, float* out, long frames) {
    if (ringGeneration_.load(std::memory_order_acquire) != wanted_) return 0;
    const long target = ringStart_.load(std::memory_order_relaxed) + play;
    long r = read_.load(std::memory_order_relaxed);
    const long w = written_.load(std::memory_order_acquire);
    if (r < target) r = std::min(w, target);
    long n = 0;
    if (r == target) {
        n = std::min(frames, w - r);
        for (long k = 0; k < n; k++) {
            const long i = (r + k) & (cap_ - 1);
            out[2 * k] = ring_[2 * i];
            out[2 * k + 1] = ring_[2 * i + 1];
        }
        r += n;
    }
    read_.store(r, std::memory_order_release);
    return n;
}

int Streamer::read(float* out, int frames) {
    std::memset(out, 0, sizeof(float) * 2 * (size_t)frames);
    long copied = take(play_, out, frames);
    // off-line (export) waits for the disk instead of starving, while the clips still have audio to come
    while (copied < frames && offline_.load(std::memory_order_relaxed) && audible(order_, play_ + copied, play_ + frames) > 0 &&
           fed_.load(std::memory_order_acquire)) {
        std::this_thread::sleep_for(std::chrono::microseconds(200));
        copied += take(play_ + copied, out + 2 * copied, frames - copied);
    }
    const long missing = audible(order_, play_ + copied, play_ + frames);
    if (missing > 0) starved_.fetch_add(missing, std::memory_order_relaxed);
    play_ += frames;
    pos_ = order_.at(play_);
    return frames - (int)missing;
}

bool Streamer::waitReady(int ms) { return waitReady(ms, 4096); }

bool Streamer::waitReady(int ms, long ahead) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    while (std::chrono::steady_clock::now() < until) {
        if (ringGeneration_.load(std::memory_order_acquire) == wanted_) {
            const long have = written_.load(std::memory_order_acquire) - ringStart_.load(std::memory_order_relaxed) - play_;
            // enough is buffered, or what is buffered reaches past the clips' end (nothing more will come)
            if (have >= ahead || audible(order_, play_ + std::max(0L, have), play_ + ahead) == 0) return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return false;
}

void Streamer::run() {
    while (!stop_.load(std::memory_order_relaxed))
        if (!fill()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
}

bool Streamer::fill() {
    const long kChunk = (long)chunk_.size() / 2;  // a chunk always fits, whatever the ring's size
    const long g = generation_.load(std::memory_order_acquire);
    if (g != ringGeneration_.load(std::memory_order_relaxed)) {
        // a seek or a new loop: empty the ring and refill in the new playing order (the audio thread is not
        // reading meanwhile)
        const long r = read_.load(std::memory_order_acquire);
        written_.store(r, std::memory_order_relaxed);
        ringStart_.store(r, std::memory_order_relaxed);
        fill_.s0 = seekTo_.load(std::memory_order_relaxed);
        fill_.ls = genLoopStart_.load(std::memory_order_relaxed);
        fill_.le = genLoopEnd_.load(std::memory_order_relaxed);
        fill_.on = genLoopOn_.load(std::memory_order_relaxed);
        ringGeneration_.store(g, std::memory_order_release);
        return true;
    }
    const long w = written_.load(std::memory_order_relaxed);
    const long free = cap_ - (w - read_.load(std::memory_order_acquire));
    const long i = w - ringStart_.load(std::memory_order_relaxed);  // play index of the next frame to fill
    const long f = fill_.at(i);                                     // the project frame it is
    if (free < kChunk || (!fill_.wraps() && f >= clipEnd())) return false;
    // silence before the first clip and between clips; every clip that overlaps the chunk is mixed in; a chunk
    // never crosses the loop's wrap
    const long n = std::min(std::min(kChunk, fill_.run(i)), fill_.wraps() ? kChunk : clipEnd() - f);
    std::fill(chunk_.begin(), chunk_.begin() + 2 * n, 0.f);
    for (const auto& c : clips_) {
        const long from = std::max(f, c.start), to = std::min(f + n, c.start + c.length);
        if (from >= to) continue;
        const int ch = c.reader->channels();
        const int got = c.reader->read(from - c.start + c.offset, raw_.data(), (int)(to - from));
        float* o = chunk_.data() + 2 * (from - f);
        for (int k = 0; k < got; k++) {
            o[2 * k] += c.gain * raw_[(size_t)k * ch];
            o[2 * k + 1] += c.gain * raw_[(size_t)k * ch + (ch > 1 ? 1 : 0)];
        }
    }
    if (generation_.load(std::memory_order_acquire) != g) return true;  // a seek arrived meanwhile: drop it
    for (long k = 0; k < n; k++) {
        const long idx = (w + k) & (cap_ - 1);
        ring_[2 * idx] = chunk_[2 * k];
        ring_[2 * idx + 1] = chunk_[2 * k + 1];
    }
    written_.store(w + n, std::memory_order_release);
    return true;
}

}  // namespace studio
