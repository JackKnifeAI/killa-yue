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
    // Channels of the material itself, for the pan law (a mono file read out as two channels is still mono).
    virtual int panChannels() const { return channels(); }
    // Called on the engine's thread; must not block.
    virtual void seek(long frame) = 0;
    // Off-line only (export): read may wait for data instead of starving.
    virtual void setOffline(bool) {}
    // Off-line only: waits up to ms for the read-ahead to hold data for the current position.
    virtual bool waitReady(int) { return true; }
    // Called on the engine's thread; must not block. From the next seek on (or now), play [start, end) round
    // and round once the playhead reaches it; off (on false) plays straight on.
    virtual void setLoop(long, long, bool) {}
    virtual ~Source() {}
};

struct TrackSlot {
    Source* source;
    float gainDb, pan;
    bool mute, solo;
    float appliedL = -1.f, appliedR = -1.f;  // the gains last used (the engine's own; -1 until first played)
};

struct Session {
    int rate;
    std::vector<TrackSlot> tracks;
};

enum class Msg : uint8_t { Gain, Pan, Mute, Solo, Master, Play, Stop, Seek, Swap, Offline, Reset, Loop, Limiter };

struct Message {
    Msg kind;
    int track;
    float value;
    long frame;
    Session* session;
    long end = 0;  // Loop: the loop's end (frame is its start, track is 1 for on)
};

struct Status {
    long position;
    long underruns;
    long refused;  // sessions refused (more than kMaxTracks tracks)
    int playing;
    float peakL, peakR;
};

class Engine {
public:
    static constexpr int kMaxTracks = 64;

    Engine(int rate, int maxFrames);
    ~Engine();  // frees the current session and any retired ones not yet taken; sources are not owned

    // UI thread. Reset makes the limiter as new (exports post it so they never depend on what played before).
    bool post(const Message& m);
    void process(float* outStereo, int frames);  // audio thread
    Session* takeRetired();                   // UI thread: a session the engine no longer uses, or nullptr
    // Peaks are the loudest moment since the last status() call (reading clears them), so a slow screen never
    // misses one.
    Status status() const;

    // Queues Msg::Offline: from that point in the queue on, sources wait for the disk instead of starving
    // (export only). Being a queued edit, it reaches the sessions swapped in before it.
    bool setOffline(bool on);
    // Off-line use only, while process() is not running on another thread.
    bool waitSourcesReady(int ms);
    int latency() const { return limiterOn_ ? limiter_.latency() : 0; }
    long position() const { return position_.load(std::memory_order_relaxed); }  // any thread; clears nothing
    // Each track's loudest moment (left, right) since the last call, after its fader and pan; clears them. Any
    // thread. Returns the number of tracks (out holds 2 x max).
    int meters(float* out, int max);
    int rate() const { return rate_; }

private:
    void apply(const Message& m);
    bool swap(Session* s);
    void render(float* out, int frames, float& peakL, float& peakR);

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
    bool limiterOn_ = true;  // Msg::Limiter: off for stems (each track exactly as it sounds before the master)
    long pos_ = 0;
    long loopStart_ = 0, loopEnd_ = 0;
    bool loopOn_ = false;
    std::atomic<long> position_{0}, underruns_{0}, refused_{0};
    std::atomic<int> playingFlag_{0};
    mutable std::atomic<float> peakL_{0.f}, peakR_{0.f};
    std::atomic<float> meter_[2 * kMaxTracks];  // per track, raised by the audio thread, cleared by meters()
    std::atomic<int> meterTracks_{0};
};

}  // namespace studio
