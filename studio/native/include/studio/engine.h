// The Studio's mixing engine without a device: edits arrive on a lock-free queue and apply at the start of
// the next block; process() renders the current session's tracks through the mixer and the master limiter.
// process() never locks, allocates, frees, throws or does I/O, so a device callback can call it directly.
#pragma once
#include <atomic>
#include <cstdint>
#include <vector>
#include "studio/mix.h"
#include "studio/spsc.h"

namespace studio {

struct Source {
    // Fills frames of interleaved audio (channels() channels); returns the frames that had audio, the rest are zeros.
    virtual int read(float* out, int frames) = 0;
    virtual int channels() const = 0;
    // Called on the engine's thread; must not block.
    virtual void seek(long frame) = 0;
    // Off-line only (export): read may wait for data instead of starving.
    virtual void setOffline(bool) {}
    // Off-line only: waits up to ms for the read-ahead to hold data for the current position.
    virtual bool waitReady(int) { return true; }
    virtual ~Source() {}
};

struct TrackSlot {
    Source* source;
    float gainDb, pan;
    bool mute, solo;
};

struct Session {
    int rate;
    std::vector<TrackSlot> tracks;
};

enum class Msg : uint8_t { Gain, Pan, Mute, Solo, Master, Play, Stop, Seek, Swap, Offline };

struct Message {
    Msg kind;
    int track;
    float value;
    long frame;
    Session* session;
};

struct Status {
    long position;
    long underruns;
    int playing;
    float peakL, peakR;
};

class Engine {
public:
    static constexpr int kMaxTracks = 64;

    Engine(int rate, int maxFrames);
    ~Engine();  // frees the current session and any retired ones not yet taken; sources are not owned

    bool post(const Message& m);              // UI thread
    void process(float* outStereo, int frames);  // audio thread
    Session* takeRetired();                   // UI thread: a session the engine no longer uses, or nullptr
    Status status() const;

    // Queues Msg::Offline: from that point in the queue on, sources wait for the disk instead of starving
    // (export only). Being a queued edit, it reaches the sessions swapped in before it.
    bool setOffline(bool on);
    // Off-line use only, while process() is not running on another thread.
    bool waitSourcesReady(int ms);
    int latency() const { return limiter_.latency(); }

private:
    void apply(const Message& m);
    bool swap(Session* s);

    Spsc<Message, 256> in_;
    Spsc<Session*, 64> retired_;
    Session* cur_ = nullptr;
    Message pending_{};        // a Swap held over because retired_ was full
    bool hasPending_ = false;
    int rate_, maxFrames_;
    std::vector<float> scratch_;
    Limiter limiter_;
    float master_ = 1.f;
    bool playing_ = false;
    long pos_ = 0;
    std::atomic<long> position_{0}, underruns_{0};
    std::atomic<int> playingFlag_{0};
    std::atomic<float> peakL_{0.f}, peakR_{0.f};
};

}  // namespace studio
