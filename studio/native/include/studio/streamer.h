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

// One clip of a track: file frames from offset, played from project frame start for length frames, at its own level.
struct ClipRef {
    Reader* reader;
    long start, offset, length;
    float gainDb;
};

class Streamer : public Source {
public:
    // A track: its clips are mixed into one ring by the feeding thread (where clips overlap, both are heard),
    // so the audio thread reads one ring however many clips there are. Clips may share a reader as long as one
    // thread reads them all (one Disk feeds them, as in the Studio).
    explicit Streamer(const std::vector<ClipRef>& clips, int ringFrames = 1 << 17);
    // A track of one clip: file frames from fileOffset, starting at project frame clipStart, for length frames.
    Streamer(Reader* r, long clipStart, long fileOffset, long length, int ringFrames = 1 << 17, float gainDb = 0.f);
    ~Streamer() override;
    // A thread of its own (tests and tools). The Studio feeds every clip from one shared Disk thread instead;
    // a clip is fed by one or the other, never both.
    void startThread();
    void stopThread();

    int read(float* out, int frames) override;  // always stereo
    int channels() const override { return 2; }
    int panChannels() const override { return panChannels_; }  // mono when every clip is mono, so it pans as mono
    void seek(long projectFrame) override;
    void setOffline(bool on) override { offline_.store(on); }
    bool waitReady(int ms) override;

    long starved() const { return starved_.load(); }
    size_t ringBytes() const { return ring_.size() * sizeof(float); }

private:
    friend class Disk;
    void run();
    bool fill();  // the feeding thread: one step of reading ahead; false when there was nothing to do
    long clipEnd() const { return end_; }
    long overlap(long from, long to) const;  // frames of [from, to) between the first clip's start and the last's end
    long take(long at, float* out, long frames);

    struct Clip {
        Reader* reader;
        long start, offset, length;
        float gain;  // the clip's own level, applied on the feeding thread
    };
    std::vector<Clip> clips_;
    long start_ = 0, end_ = 0;                // from the first clip's start to the last one's end
    int panChannels_ = 1;
    const long cap_;                          // ring frames, a power of two
    std::vector<float> ring_;                 // 2 * cap_
    std::atomic<long> written_{0}, read_{0};  // frames ever written / read in the ring
    std::atomic<long> base_{0};               // project frame of ring frame 0 in this generation
    std::atomic<long> generation_{1}, ringGeneration_{0}, seekTo_{0};
    std::atomic<bool> stop_{false}, offline_{false};
    std::atomic<bool> fed_{false};            // a thread is feeding this clip (off-line reads wait only then)
    std::vector<float> chunk_, raw_;          // the feeding thread's buffers, made once
    std::atomic<long> starved_{0};
    long pos_ = 0;                            // project frame of the next read (audio thread only)
    long wanted_ = 1;                         // generation the audio thread is waiting for (audio thread only)
    std::thread thread_;
};

}  // namespace studio
