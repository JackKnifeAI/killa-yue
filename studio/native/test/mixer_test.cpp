#include "studio/engine.h"
#include "test.h"
#include <cmath>
#include <vector>
using namespace studio;
struct Signal : Source {
    float l, r; int ch; bool impulse; long at = 0;
    Signal(float left, float right, int channels = 2, bool pulse = false) : l(left), r(right), ch(channels), impulse(pulse) {}
    int read(float* out, int frames) override {
        for (int i = 0; i < frames; ++i, ++at) {
            out[ch*i] = !impulse || at == 0 ? l : 0;
            if (ch == 2) out[ch*i+1] = !impulse || at == 0 ? r : 0;
        }
        return frames;
    }
    int channels() const override { return ch; }
    void seek(long frame) override { at = frame; }
};
static float* strip(MixerConfig& c, int id) { return c.data.data() + 2 + 32*id; }
static void start(Engine& e, Signal& a, Signal* b = nullptr) {
    auto* s = new Session{48000, {{&a, 0, 0, false, false}}};
    if (b) s->tracks.push_back({b, 0, 0, false, false});
    CHECK(e.post({Msg::Swap, 0, 0, 0, s}), "load");
    CHECK(e.post({Msg::Limiter, 0, 0, 0, nullptr}), "bypass safety limiter for measurements");
    CHECK(e.post({Msg::Play, 0, 0, 0, nullptr}), "play");
}
int main() {
    {
        Engine e(48000, 256); Signal a(.2f, .4f), b(.1f, .1f); start(e, a, &b);
        MixerConfig c; c.data[0] = 2; c.data[1] = 1;
        strip(c, 0)[6] = 0; strip(c, 64)[0] = -6.0206f;
        strip(c, 0)[7] = 1; strip(c, 0)[8] = 2; strip(c, 0)[9] = 0; // mono width
        strip(c, 74)[7] = 1; strip(c, 74)[8] = 3; // polarity on master
        CHECK(e.mixer(c), "valid group and inserts accepted");
        float out[512]; e.process(out, 256);
        NEAR(out[500], -.25, 1e-6, "track width, group gain, direct track and master insert follow routing");
        NEAR(out[501], -.25, 1e-6, "stereo chain");
        float meters[20]; e.mixerMeters(meters, 10);
        NEAR(meters[0], .15, 1e-6, "group meter is post gain and inserts");
        strip(c, 64)[3] = 1; CHECK(e.mixer(c), "group solo queued");
        e.process(out, 256); e.process(out, 256);
        NEAR(out[500], -.15, 1e-6, "group solo includes its members");
        strip(c, 64)[2] = 1; CHECK(e.mixer(c), "group mute queued");
        e.process(out, 256); e.process(out, 256);
        NEAR(out[500], 0, 1e-6, "group mute wins over solo");
        strip(c, 64)[2] = strip(c, 64)[3] = 0;
        CHECK(e.mixer(c), "clear group switches");
        e.post({Msg::Solo, 0, 1, 0, nullptr}); e.process(out, 256); e.process(out, 256);
        NEAR(out[500], -.15, 1e-6, "track solo is heard through unsoloed group");
    }
    {
        Engine e(48000, 256); Signal a(.4f, 0, 1); start(e, a);
        float out[512], m[2]; e.process(out, 256); e.meters(m, 1);
        NEAR(m[0], .4 * std::sqrt(.5), 1e-6, "true mono source meter indexes only its samples");
        NEAR(m[1], m[0], 1e-6, "mono center meter right");
    }
    {
        Engine e(48000, 256); Signal a(.2f, .1f, 2, true); start(e, a);
        MixerConfig c; c.data[0] = 1; strip(c, 0)[5] = 1;
        CHECK(e.mixer(c), "delay send accepted");
        std::vector<float> audio(2*36001); e.process(audio.data(), 36001);
        NEAR(audio[0], .2, 1e-6, "dry impulse retained");
        NEAR(audio[2*12000], .2, 1e-6, "first delay at 250 ms");
        NEAR(audio[2*24000], .06, 1e-6, "delay feedback 0.30");
        NEAR(audio[2*36000], .018, 1e-6, "second feedback repeat");
        e.post({Msg::Seek, 0, 0, 0, nullptr}); e.post({Msg::Reset, 0, 0, 0, nullptr});
        std::vector<float> again(audio.size()); e.process(again.data(), 36001);
        CHECK(audio == again, "seek/reset makes wet rendering deterministic");
    }
    {
        Engine e(48000, 256); Signal a(.2f, .1f, 2, true); start(e, a);
        MixerConfig c; c.data[0] = 1; strip(c, 0)[4] = 1; CHECK(e.mixer(c), "reverb send accepted");
        std::vector<float> out(2*192000); e.process(out.data(), 192000);
        double tail = 0, end = 0;
        for (size_t i = 4000; i < out.size(); ++i) tail += out[i]*out[i];
        for (size_t i = out.size()-2000; i < out.size(); ++i) end += out[i]*out[i];
        CHECK(tail > .001 && end < 1e-12, "reverb has a decaying tail within export allowance");
    }
    {
        Engine e(48000, 16); MixerConfig c;
        c.data[1] = 1; strip(c, 64)[6] = 0;
        CHECK(!e.mixer(c), "group feedback rejected");
        strip(c, 64)[6] = -1; strip(c, 72)[4] = 1;
        CHECK(!e.mixer(c), "return feedback rejected");
        strip(c, 72)[4] = 0; strip(c, 0)[4] = NAN;
        CHECK(!e.mixer(c), "nonfinite controls rejected");
        c = MixerConfig();
        for (int i = 0; i < 8; ++i) CHECK(e.mixer(c), "bounded config slots accept");
        CHECK(!e.mixer(c), "full config queue reports refusal");
        float out[32]; e.process(out, 16);
        CHECK(e.mixer(c), "consumer releases config capacity");
    }
    DONE("mixer_test");
}
