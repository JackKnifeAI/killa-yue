#include "studio/disk.h"
#include <algorithm>
#include <chrono>

namespace studio {

Disk::Disk() : thread_(&Disk::run, this) {}

Disk::~Disk() {
    stop_.store(true);
    thread_.join();
    for (auto* s : clips_) s->fed_.store(false);
}

void Disk::add(Streamer* s) {
    std::lock_guard<std::mutex> l(mu_);
    if (std::find(clips_.begin(), clips_.end(), s) != clips_.end()) return;
    clips_.push_back(s);
    s->fed_.store(true, std::memory_order_release);
}

void Disk::add(const std::vector<Streamer*>& many) {
    std::lock_guard<std::mutex> l(mu_);
    for (auto* s : many) {
        if (std::find(clips_.begin(), clips_.end(), s) != clips_.end()) continue;
        clips_.push_back(s);
        s->fed_.store(true, std::memory_order_release);
    }
}

void Disk::remove(Streamer* s) {
    std::lock_guard<std::mutex> l(mu_);  // waits out a pass in progress, so the thread is done with s
    clips_.erase(std::remove(clips_.begin(), clips_.end(), s), clips_.end());
    s->fed_.store(false, std::memory_order_release);
}

void Disk::run() {
    while (!stop_.load(std::memory_order_relaxed)) {
        bool worked = false;
        {
            // one step for every clip in turn, so no clip waits behind another's whole read-ahead
            std::lock_guard<std::mutex> l(mu_);
            for (auto* s : clips_) worked |= s->fill();
        }
        if (!worked) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
}

}  // namespace studio
