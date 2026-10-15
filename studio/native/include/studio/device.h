// The Studio's audio device: one low-latency output stream whose callback renders the mix directly, with the
// microphone read in the same callback when asked (full duplex). It reopens itself when the audio route
// changes (headphones, Bluetooth, USB), keeping its sample rate, so the engine never has to change.
// Oboe on Android (device_oboe.cpp); elsewhere create() returns nullptr and tests drive the engine by hand.
#pragma once
#include <string>

namespace studio {

struct Renderer {  // audio thread: fills frames of stereo; must not block, allocate or do I/O
    virtual void render(float* outStereo, int frames) = 0;
    virtual ~Renderer() {}
};

struct InputSink {  // audio thread: what the microphone heard during this callback (frames may be fewer than out)
    virtual void input(const float* in, int frames, int channels) = 0;
    virtual ~InputSink() {}
};

struct DeviceStats {
    int rate = 0, burst = 0, bufferFrames = 0, inputChannels = 0;
    bool running = false, exclusive = false;
    long xruns = 0;               // as the device counts them, summed over every stream it opened
    long callbacks = 0, slowCallbacks = 0;  // slow: longer than one burst
    double callbackMaxMs = 0, callbackAvgMs = 0;
    double outputLatencyMs = -1;  // the device's estimate (-1 when it cannot say)
    int reopened = 0;             // times the stream came back after a route change
    int callbackPolicy = -1;      // the audio callback thread's scheduling policy (1 = SCHED_FIFO: real time)
    int callbackPriority = -1;
    std::string error;            // the last thing that went wrong, in words
};

class Device {
public:
    static Device* create();  // nullptr where there is no device layer (host builds)
    virtual ~Device() {}

    // Opens the stream at the device's own rate (and the microphone at the same rate when withInput). Does not start.
    virtual bool open(bool withInput, std::string& err) = 0;
    virtual int rate() const = 0;
    virtual int burst() const = 0;
    virtual void setRenderer(Renderer* r) = 0;  // before start()
    virtual void setInput(InputSink* s) = 0;    // before start(); nullptr drops the input
    virtual bool start(std::string& err) = 0;
    virtual void close() = 0;  // stops and closes; render() and input() are never called after it returns
    virtual DeviceStats stats() = 0;
};

}  // namespace studio
