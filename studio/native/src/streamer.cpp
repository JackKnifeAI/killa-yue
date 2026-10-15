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

Streamer::Streamer(Reader* r, long clipStart, long fileOffset, long length, int ringFrames)
    : reader_(r), start_(clipStart), offset_(fileOffset), length_(length), cap_(powerOfTwo(ringFrames)), ring_(2 * cap_, 0.f) {}

Streamer::~Streamer() { stopThread(); }

void Streamer::startThread() {
    stop_.store(false);
    if (!thread_.joinable()) thread_ = std::thread(&Streamer::run, this);
}

void Streamer::stopThread() {
    stop_.store(true);
    if (thread_.joinable()) thread_.join();
}

long Streamer::overlap(long from, long to) const {
    return std::max(0L, std::min(to, clipEnd()) - std::max(from, start_));
}

void Streamer::seek(long projectFrame) {
    if (projectFrame == pos_) return;  // already there: nothing to refill
    pos_ = projectFrame;
    seekTo_.store(projectFrame, std::memory_order_relaxed);
    wanted_ = generation_.fetch_add(1, std::memory_order_acq_rel) + 1;
}

// Copies what the ring holds from project frame at (catching up first if time moved on during a stall).
long Streamer::take(long at, float* out, long frames) {
    if (ringGeneration_.load(std::memory_order_acquire) != wanted_) return 0;
    const long base = base_.load(std::memory_order_relaxed);
    long r = read_.load(std::memory_order_relaxed);
    const long w = written_.load(std::memory_order_acquire);
    if (base + r < at) r = std::min(w, r + (at - (base + r)));
    long n = 0;
    if (base + r == at) {
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
    long copied = take(pos_, out, frames);
    // off-line (export) waits for the disk instead of starving, while the clip still has audio to come
    while (copied < frames && offline_.load(std::memory_order_relaxed) && overlap(pos_ + copied, pos_ + frames) > 0 &&
           !stop_.load(std::memory_order_relaxed)) {
        std::this_thread::sleep_for(std::chrono::microseconds(200));
        copied += take(pos_ + copied, out + 2 * copied, frames - copied);
    }
    const long missing = overlap(pos_ + copied, pos_ + frames);
    if (missing > 0) starved_.fetch_add(missing, std::memory_order_relaxed);
    pos_ += frames;
    return frames - (int)missing;
}

bool Streamer::waitReady(int ms) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    while (std::chrono::steady_clock::now() < until) {
        if (ringGeneration_.load(std::memory_order_acquire) == wanted_) {
            const long base = base_.load(), w = written_.load(std::memory_order_acquire);
            const long want = std::min(clipEnd(), pos_ + 4096);
            if (base + w >= want || pos_ >= clipEnd()) return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return false;
}

void Streamer::run() {
    const long kChunk = 4096;
    std::vector<float> chunk(2 * kChunk), raw(2 * kChunk);  // reused: nothing is allocated per chunk
    while (!stop_.load(std::memory_order_relaxed)) {
        const long g = generation_.load(std::memory_order_acquire);
        if (g != ringGeneration_.load(std::memory_order_relaxed)) {
            // a seek: empty the ring and refill from the new place (the audio thread is not reading meanwhile)
            const long r = read_.load(std::memory_order_acquire);
            written_.store(r, std::memory_order_relaxed);
            base_.store(seekTo_.load(std::memory_order_relaxed) - r, std::memory_order_relaxed);
            ringGeneration_.store(g, std::memory_order_release);
            continue;
        }
        const long w = written_.load(std::memory_order_relaxed);
        const long free = cap_ - (w - read_.load(std::memory_order_acquire));
        const long f = base_.load(std::memory_order_relaxed) + w;  // next project frame to fill
        if (f >= clipEnd() || free < kChunk) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
        }
        // silence up to the clip's start, then the clip
        const long n = f < start_ ? std::min(kChunk, start_ - f) : std::min(kChunk, clipEnd() - f);
        std::fill(chunk.begin(), chunk.begin() + 2 * n, 0.f);
        if (f >= start_) {
            const long at = f - start_ + offset_;
            const int ch = reader_->channels();
            const int got = reader_->read(at, raw.data(), (int)n);
            for (int k = 0; k < got; k++) {
                chunk[2 * k] = raw[(size_t)k * ch];
                chunk[2 * k + 1] = raw[(size_t)k * ch + (ch > 1 ? 1 : 0)];
            }
        }
        const long m = n;
        if (generation_.load(std::memory_order_acquire) != g) continue;  // a seek arrived meanwhile: drop it
        for (long k = 0; k < m; k++) {
            const long i = (w + k) & (cap_ - 1);
            ring_[2 * i] = chunk[2 * k];
            ring_[2 * i + 1] = chunk[2 * k + 1];
        }
        written_.store(w + m, std::memory_order_release);
    }
}

}  // namespace studio
