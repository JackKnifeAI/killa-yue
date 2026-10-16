#include "studio/recorder.h"

#include <sys/statvfs.h>

#include <algorithm>
#include <chrono>
#include <cmath>
namespace studio {
bool Recorder::start(const char* path, int rate, std::string& err) {
    if (worker_.joinable()) {
        err = "A take is already open.";
        return false;
    }
    std::string p(path), dir = p.substr(0, p.find_last_of('/'));
    struct statvfs st{};
    if (statvfs(dir.c_str(), &st) || static_cast<unsigned long long>(st.f_bavail) * st.f_frsize < 64ull * 1024 * 1024) {
        err = "Recording needs at least 64 MB free.";
        return false;
    }
    writer_.reset(WavWriter::create(path, rate, 1, err));
    if (!writer_) return false;
    writer_->keepPartial();
    if (!writer_->checkpoint()) {
        writer_.reset();
        err = "Cannot checkpoint the take.";
        return false;
    }
    rate_ = rate;
    read_ = 0;
    write_ = 0;
    first_ = -1;
    frames_ = 0;
    failure_ = 0;
    expected_ = -1;
    done_ = false;
    accept_ = true;
    worker_ = std::thread(&Recorder::run, this);
    return true;
}
void Recorder::input(const float* in, int frames, int channels, long position) {
    inside_.fetch_add(1, std::memory_order_seq_cst);
    if (!accept_.load(std::memory_order_seq_cst)) {
        inside_.fetch_sub(1, std::memory_order_seq_cst);
        return;
    }
    if (first_.load(std::memory_order_relaxed) < 0) first_.store(position);
    if (expected_ >= 0 && position != expected_) {
        failure_ = 2;
        accept_ = false;
        inside_.fetch_sub(1, std::memory_order_seq_cst);
        return;
    }
    expected_ = position + frames;
    long w = write_.load(std::memory_order_relaxed), r = read_.load(std::memory_order_acquire);
    if (frames < 0 || channels < 1 || w - r + frames > (long)ring_.size()) {
        failure_ = 1;
        accept_ = false;
    } else {
        float peak = 0;
        for (int i = 0; i < frames; i++) {
            float v = in[(size_t)i * channels];
            if (!std::isfinite(v)) v = 0;
            ring_[(w + i) & (ring_.size() - 1)] = v;
            peak = std::max(peak, std::fabs(v));
        }
        write_.store(w + frames, std::memory_order_release);
        float old = peak_.load();
        while (peak > old && !peak_.compare_exchange_weak(old, peak)) {
        }
    }
    inside_.fetch_sub(1, std::memory_order_seq_cst);
}
void Recorder::run() {
    float b[4096];
    long checkpointAt = 0;
    while (!done_.load() || read_.load() < write_.load()) {
        long r = read_.load(std::memory_order_relaxed), w = write_.load(std::memory_order_acquire);
        int n = (int)std::min(4096L, w - r);
        if (!n) {
            std::this_thread::sleep_for(std::chrono::milliseconds(3));
            continue;
        }
        for (int i = 0; i < n; i++) b[i] = ring_[(r + i) & (ring_.size() - 1)];
        if (!writer_->write(b, n)) {
            failure_ = 3;
            accept_ = false;
            break;
        }
        frames_.fetch_add(n);
        read_.store(r + n, std::memory_order_release);
        if (frames_.load() - checkpointAt >= rate_) {
            if (!writer_->checkpoint()) {
                failure_ = 3;
                accept_ = false;
                break;
            }
            checkpointAt = frames_.load();
        }
    }
    if (!writer_->finish()) failure_ = 3;
}
long Recorder::stop(std::string& err) {
    accept_.store(false, std::memory_order_seq_cst);
    while (inside_.load(std::memory_order_seq_cst)) std::this_thread::yield();
    done_ = true;
    if (worker_.joinable()) worker_.join();
    writer_.reset();
    int f = failure_.load();
    if (f)
        err = f == 1   ? "Recording stopped: disk could not keep up."
              : f == 2 ? "Recording stopped after an audio route discontinuity."
                       : "Recording stopped: storage write failed.";
    return frames_.load();
}
}  // namespace studio
