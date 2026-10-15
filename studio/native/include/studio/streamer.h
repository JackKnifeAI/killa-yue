// Clip audio for the engine, read ahead by a disk thread into a lock-free ring so that the audio thread never
// waits for the disk. A seek starts a new "generation": the audio thread plays silence (counted as starved)
// until the disk thread has refilled from the new place, and time keeps moving meanwhile, so the track
// stays in step with the others.
#pragma once
#include <atomic>
#include <thread>
#include <vector>
#include "studio/engine.h"

namespace studio {

struct Reader {
    virtual long frames() const = 0;
    virtual int channels() const = 0;  // 1 or 2
    // Reads frames from file frame at, interleaved; returns how many there were.
    virtual int read(long at, float* out, int frames) = 0;
    virtual ~Reader() {}
};

class Streamer : public Source {
public:
    // The clip plays file frames from fileOffset, starting at project frame clipStart, for length frames.
    Streamer(Reader* r, long clipStart, long fileOffset, long length, int ringFrames = 1 << 17, float gainDb = 0.f);
    ~Streamer() override;
    void startThread();
    void stopThread();

    int read(float* out, int frames) override;  // always stereo
    int channels() const override { return 2; }
    int panChannels() const override { return reader_->channels(); }  // a mono file is panned as mono
    void seek(long projectFrame) override;
    void setOffline(bool on) override { offline_.store(on); }
    bool waitReady(int ms) override;

    long starved() const { return starved_.load(); }

private:
    void run();
    long clipEnd() const { return start_ + length_; }
    long overlap(long from, long to) const;  // frames of [from, to) inside the clip
    long take(long at, float* out, long frames);

    Reader* reader_;
    const long start_, offset_, length_;
    const long cap_;                          // ring frames, a power of two
    const float gain_;                        // the clip's own level, applied on the disk thread
    std::vector<float> ring_;                 // 2 * cap_
    std::atomic<long> written_{0}, read_{0};  // frames ever written / read in the ring
    std::atomic<long> base_{0};               // project frame of ring frame 0 in this generation
    std::atomic<long> generation_{1}, ringGeneration_{0}, seekTo_{0};
    std::atomic<bool> stop_{false}, offline_{false};
    std::atomic<long> starved_{0};
    long pos_ = 0;                            // project frame of the next read (audio thread only)
    long wanted_ = 1;                         // generation the audio thread is waiting for (audio thread only)
    std::thread thread_;
};

}  // namespace studio
