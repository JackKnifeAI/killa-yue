#pragma once
#include <array>
#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "studio/chamber.h"
#include "studio/spsc.h"
namespace studio {
class PerformanceAudio {
    struct Sample {
        std::vector<float> x;
        int refs = 1;
    };
    struct Pad {
        Sample* s = nullptr;
        float tune = 0, attack = .002f, decay = .08f, sustain = 1, release = .08f, cutoff = 20000;
        int mode = 0, choke = 0;
        bool reverse = false;
    };
    struct Voice {
        Sample* s = nullptr;
        Pad pad;
        int key = 0, source = 0;
        long stopAfter = -1;
        double at = 0, step = 1;
        float velocity = 0, env = 0, filterL = 0, filterR = 0, coefficient = 1;
        long age = 0;
        bool released = false;
    };
    struct Event {
        int kind = 0, key = 0;
        long frame = 0;
        float value = 0;
        bool on = false;
        Sample* sample = nullptr;
        std::array<float, 32> data{};
    };
    Spsc<Event, 256> events_;
    std::unique_ptr<Spsc<ChamberConfig, 8>> chamberQueue_;
    ChamberConfig chamber_;
    bool chamberLoaded_ = false;
    struct Held {
        bool on = false;
        int source = 0;
        float velocity = 0;
        double next = 0;
    };
    std::array<Held, 16> held_{};
    struct Hit {
        long frame = 0;
        int pad = 0;
        float velocity = 0;
        int bank = 0;
    };
    Spsc<Hit, 2048> hits_;
    std::atomic<long> droppedHits_{0};
    std::atomic<bool> panic_{false};
    double liveBeat_ = 0, currentBeat_ = 0;
    long currentFrame_ = 0;
    bool currentPlaying_ = false;
    void live(int key, float velocity, bool on);
    void hit(int source, int key, float velocity, long frame);
    Spsc<float, 8192> monitorRing_;
    std::atomic<bool> monitoring_{false};
    Spsc<Sample*, 512> retired_;
    std::array<Pad, 16> pads_{};
    std::array<Voice, 64> voices_{};
    std::array<std::array<float, 32>, 16> steps_{};
    int rate_, beats_ = 4, unit_ = 4, length_ = 16;
    double bpm_ = 120, swing_ = 0;
    std::array<long, 64> tempoFrames_{};
    std::array<double, 64> tempoBpms_{}, tempoBeats_{};
    int tempoCount_ = 1;
    double beatAt(long frame) const;
    long frameAt(double beat) const;
    bool click_ = false, pattern_ = false, wasPlaying_ = false;
    float clickEnv_ = 0;
    double clickPhase_ = 0;
    int clickHz_ = 1200;
    float stealL_ = 0, stealR_ = 0;
    int stealFrames_ = 0;
    long previous_ = -1;
    std::atomic<size_t> bytes_{0};
    void release(Sample* s);
    void drain();
    void fire(int pad, float velocity, bool on, int identity = -1, long duration = -1);
    void clearVoices();

   public:
    explicit PerformanceAudio(int rate) : chamberQueue_(new Spsc<ChamberConfig, 8>), rate_(rate) {
        tempoBpms_[0] = 120;
    }
    ~PerformanceAudio();
    static long stepFrame(long step, int rate, double bpm, double swing);
    bool load(int pad, const char* path, long offset, long length, std::string& err);
    bool kit(std::string& err);
    bool trigger(int pad, float velocity, bool on);
    bool configure(int pad, const float* values, int n);
    bool transport(double bpm, int beats, int unit, bool click, bool pattern, int length, double swing);
    bool patternRow(int pad, const float* values);
    bool tempoPoint(int index, long frame, double bpm);
    bool allOff();
    void reset();  // audio thread: deterministic export/transport reset
    bool chamber(const float* data, int count);
    int hits(double* out, int size);
    void monitor(bool on) { monitoring_.store(on); }
    void input(const float* in, int n, int channels) {
        if (monitoring_.load())
            for (int i = 0; i < n; i++) monitorRing_.push(in[(size_t)i * channels]);
    }
    void mix(float* out, int frames, long position, bool playing);
    void collect();
    size_t bytes() const { return bytes_.load(); }
};
}  // namespace studio
