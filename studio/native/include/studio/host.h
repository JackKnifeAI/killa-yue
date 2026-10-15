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
    bool start(std::string& err);  // starts the device (nothing to start without one)

    // Opens every clip's file and swaps the tracks in at the next block, at the song's position. The old tracks
    // are freed once the engine lets go of them. On failure (a file missing, damaged or at another rate) the
    // old tracks keep playing and err says which file and why.
    bool load(const std::vector<TrackSpec>& tracks, std::string& err);
    bool post(const Message& m) { return engine_->post(m); }
    Status status();  // also frees the tracks the engine has let go of
    DeviceStats deviceStats();
    int sessionsHeld() const { return (int)gens_.size(); }  // the current one plus any not yet let go of
    int filesOpen() const;  // over every session held

    // Audio thread (the device calls these; offline, call render() yourself).
    void render(float* out, int frames) override;
    void input(const float* in, int frames, int channels) override;

    // Plays `probes` probes (0.4 s apart) and finds them in the microphone. Blocks for about probes * 0.4 s.
    // Needs a device opened with the microphone. Play stays as it was; the probes are added on top.
    RoundTrip measureLatency(int probes, std::string& err);

private:
    Host(int rate, Device* d, bool mic);
    void reap();
    bool calibrating(bool startHere);

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

    // calibration: measureLatency fills these, then raises calActive_. The audio thread raises calBusy_ while
    // it uses them and lowers both when done (or when calActive_ is withdrawn after a timeout); a new
    // measurement waits until calBusy_ is down, so the buffers are never changed under the audio thread.
    std::atomic<bool> calActive_{false}, calBusy_{false};
    std::vector<float> probe_, heard_;
    std::vector<long> probeAt_;
    long calOut_ = 0, calIn_ = 0, calEnd_ = 0;
};

}  // namespace studio
