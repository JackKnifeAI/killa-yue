// One disk thread for the whole Studio: it fills every clip's ring in turn, so 16 tracks read ahead on one
// thread instead of 16 (and the audio thread never waits for it).
#pragma once
#include <atomic>
#include <mutex>
#include <thread>
#include <vector>
#include "studio/streamer.h"

namespace studio {

class Disk {
public:
    Disk();
    ~Disk();  // stops the thread; clips still added are left alone (not freed)

    // UI thread. A clip is fed from add() until remove() returns; after that the disk thread never touches it
    // again, so it can be freed.
    void add(Streamer* s);
    void add(const std::vector<Streamer*>& many);  // one wait for the thread, however many
    void remove(Streamer* s);

private:
    void run();

    std::mutex mu_;  // the list, and a pass over it (the disk thread holds it while it fills)
    std::vector<Streamer*> clips_;
    std::atomic<bool> stop_{false};
    std::thread thread_;
};

}  // namespace studio
