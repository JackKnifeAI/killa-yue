// A lock-free queue for exactly one producer thread and one consumer thread (the UI and the audio thread).
#pragma once
#include <atomic>
#include <cstddef>

namespace studio {

template <class T, size_t N>
class Spsc {
    static_assert(N >= 2 && (N & (N - 1)) == 0, "N must be a power of two");

public:
    // Producer only. False when full.
    bool push(const T& v) {
        const size_t t = tail_.load(std::memory_order_relaxed);
        if (t - head_.load(std::memory_order_acquire) == N) return false;
        buf_[t & (N - 1)] = v;
        tail_.store(t + 1, std::memory_order_release);
        return true;
    }

    // Consumer only. False when empty.
    bool pop(T& v) {
        const size_t h = head_.load(std::memory_order_relaxed);
        if (h == tail_.load(std::memory_order_acquire)) return false;
        v = buf_[h & (N - 1)];
        head_.store(h + 1, std::memory_order_release);
        return true;
    }

private:
    T buf_[N];
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
};

}  // namespace studio
