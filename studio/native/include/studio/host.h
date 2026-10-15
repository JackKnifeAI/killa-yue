// Everything the app holds for one open Studio: the engine, the disk thread, the audio device, and the tracks
// of every session until the engine has let go of them. The JNI bridge is a thin layer over this, so all of
// its logic is tested on a computer.
#pragma once
#include <atomic>
#include <list>
#include <memory>
#include <string>
#include <vector>
#include "studio/device.h"
#include "studio/disk.h"
#include "studio/engine.h"
#include "studio/latency.h"
#include "studio/wav.h"

namespace studio {

struct ClipSpec {
    std::string file;  // a WAV at the Studio's rate
    long start, offset, length;
    float gainDb;
};

struct TrackSpec {
    float gainDb, pan;
    bool mute, solo;
    std::vector<ClipSpec> clips;
};

class Host : public Renderer, public InputSink {
public:
    static constexpr int kBlock = 512;  // the engine's block; bigger device callbacks are rendered in pieces

    // A Studio playing through a device (owned), at the device's own rate, with the microphone when mic.
    static Host* withDevice(Device* d, bool mic, std::string& err);
    // A Studio with no device: render() is called by hand (export, tests).
    static Host* offline(int rate);
    ~Host() override;

    int rate() const { return rate_; }
    bool hasDevice() const { return device_ != nullptr; }
    bool start(std::string& err);  // starts the device (nothing to start without one)

    // Opens every clip's file and swaps the tracks in at the next block, at the song's position. The old tracks
    // are freed once the engine lets go of them. On failure (a file missing, damaged or at another rate) the
    // old tracks keep playing and err says which file and why.
    bool load(const std::vector<TrackSpec>& tracks, std::string& err) { return load(tracks, masterDb_, err); }
    // The same, with the master level that goes with these tracks (it reaches the engine even if its queue
    // is full now: then it follows at the next status() or post()).
    bool load(const std::vector<TrackSpec>& tracks, float masterDb, std::string& err);
    bool post(const Message& m);
    Status status();  // also frees the tracks the engine has let go of
    DeviceStats deviceStats();
    int sessionsHeld() const { return (int)gens_.size(); }  // the current one plus any not yet let go of
    int filesOpen() const;  // over every session held

    // Audio thread (the device calls these).
    void render(float* out, int frames, long position) override;
    void input(const float* in, int frames, int channels, long position) override;
    // Off-line: render by hand (a host with no device; never while a device is calling render()).
    void render(float* out, int frames) { render(out, frames, offlinePos_); offlinePos_ += frames; }

    // Plays `probes` probes (0.4 s apart, each its own noise) and finds them in the microphone; round trips up
    // to a second are measured. Blocks for about probes * 0.4 + 1.6 s. Needs a device opened with the
    // microphone. Play stays as it was; the probes are added on top.
    RoundTrip measureLatency(int probes, std::string& err);
    // Any thread: makes a running measureLatency return at once ("cancelled").
    void cancelLatency() { calCancel_.store(true, std::memory_order_release); }
    // What the last measurement heard, in words: the microphone's level, and each probe's match. Call it from
    // the thread that ran measureLatency, after it returned.
    std::string latencyReport() const { return calReport_; }

private:
    Host(int rate, Device* d, bool mic);
    void reap();
    bool calibrating(bool startHere, long position);
    RoundTrip giveUp(const char* why, std::string& err);

    struct Generation {
        Session* session;  // owned by the engine until it hands it back
        std::vector<std::unique_ptr<WavReader>> readers;
        std::vector<std::unique_ptr<Streamer>> streams;
    };

    int rate_;
    std::unique_ptr<Device> device_;
    bool mic_;
    Disk disk_;
    std::unique_ptr<Engine> engine_;
    std::list<Generation> gens_;
    float masterDb_ = 0.f;
    bool masterPending_ = false;  // a master level that did not fit in the engine's queue yet
    void flushMaster();

    // calibration, one state shared by measureLatency and the audio thread (compare-and-swap):
    //   Idle -> Preparing (measureLatency claims it and fills the buffers) -> Armed -> Running (the audio thread
    //   starts on a callback's microphone side) -> Done (it hands the buffers back) -> Idle.
    //   Withdrawn: measureLatency gave up while Running; the audio thread lets go on its next call (-> Idle).
    // The buffers belong to measureLatency in Preparing and Done, to the audio thread in Running.
    enum { kIdle, kPreparing, kArmed, kRunning, kDone, kWithdrawn };
    std::atomic<int> cal_{kIdle};
    std::atomic<bool> calCancel_{false};
    std::vector<std::vector<float>> probes_;
    std::vector<float> heard_;
    std::vector<long> probeAt_;
    long calOut_ = 0, calIn_ = 0, calEnd_ = 0;
    long calInBase_ = 0, calOutBase_ = 0;  // the streams' positions where the measurement started
    long offlinePos_ = 0;
    std::string calReport_;
};

}  // namespace studio
