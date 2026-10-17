// Original DSP. EQ coefficient equations: W3C Audio EQ Cookbook / Robert Bristow-Johnson.
#pragma once
#include <array>
#include <cstdint>
#include <vector>
namespace studio {
constexpr int kEffectParams=8, kEffectStride=12, kMixerStride=8+8*kEffectStride, kWetInserts=16;
struct EffectConfig {
    int kind=0; bool bypass=false; uint32_t id=0;
    std::array<float,kEffectParams> p{};
    static EffectConfig read(const float* data);
    bool valid() const;
};
struct Biquad {
    std::array<double,5> c{{1,0,0,0,0}}, step{};
    double z1[2]{}, z2[2]{};
    bool ready=false;
    void reset();
    // mode 0 lowpass, 1 highpass, 2 bandpass, 3 bell, 4 low shelf, 5 high shelf.
    void prepare(int mode, double hz, double q, double gainDb, int rate, int frames);
    void tick(float& l,float& r);
};
class EffectsRack {
public:
    explicit EffectsRack(int rate);
    void configure(const float* mixerData); // callback, bounded; never allocates or clears large buffers
    void reset();
    void process(int strip, float* audio, int frames);
private:
    struct Ring {
        std::vector<float> data;
        size_t pos=0, written=0;
        explicit Ring(size_t length=1):data(length){}
        void reset() { pos=written=0; }
        float read(size_t delay) const;
        void put(float value);
    };
    struct Wet {
        uint32_t id=0; int kind=0;
        Ring left, right, preL, preR;
        std::array<Ring,8> comb;
        std::array<Ring,4> allpass;
        std::array<float,8> damping{};
        explicit Wet(int rate);
        void reset();
        void tick(const EffectConfig& c, int rate, float inL,float inR,float& wetL,float& wetR,float delayMs);
    };
    struct State {
        EffectConfig config;
        std::array<Biquad,5> filters;
        int wet=-1;
        float gain=1, previous=0, enabled=1;
        bool ready=false;
        void reset();
    };
    int rate_;
    std::vector<State> states_;
    std::vector<Wet> wet_;
    std::array<int,75> counts_{};
};
}
