// engine_test.cpp
#include "studio/engine.h"
#include "test.h"
#include <vector>
using namespace studio;

struct Constant : Source { // a stereo source of a fixed value, of a given length
    float value; long length, at = 0;
    Constant(float v, long len) : value(v), length(len) {}
    int read(float* out, int frames) override {
        int n = (int)std::min<long>(frames, length - at);
        if (n < 0) n = 0;
        for (int i = 0; i < 2 * frames; i++) out[i] = i < 2 * n ? value : 0.f;
        at += n;
        return n;
    }
    int channels() const override { return 2; }
    void seek(long f) override { at = f; }
};

int main() {
    Engine e(48000, 256);
    Constant a(0.25f, 48000), b(0.25f, 48000);
    auto* s = new Session{48000, {{&a, 0.f, 0.f, false, false}, {&b, 0.f, 0.f, false, false}}};
    e.post({Msg::Swap, 0, 0, 0, s});
    e.post({Msg::Play, 0, 0, 0, nullptr});
    std::vector<float> out(512);
    e.process(out.data(), 256); // two stereo tracks at 0.25, centred, unity: 0.5, delayed by the limiter
    e.process(out.data(), 256);
    NEAR(out[2 * 200], 0.5, 1e-6, "two tracks sum");
    e.post({Msg::Gain, 1, -120.f, 0, nullptr});
    e.process(out.data(), 256);
    e.process(out.data(), 256);
    NEAR(out[2 * 200], 0.25, 1e-5, "a gain change applies at the next block, without a restart");
    e.post({Msg::Solo, 1, 1.f, 0, nullptr});
    e.process(out.data(), 256);
    e.process(out.data(), 256);
    NEAR(out[2 * 200], 0.0, 1e-5, "soloing the silent track silences the other");
    CHECK(e.status().position == 6 * 256, "position counts frames played");
    CHECK(e.status().underruns == 0, "no underruns while sources keep up");
    Constant shortOne(0.1f, 100);
    e.post({Msg::Swap, 0, 0, 0, new Session{48000, {{&shortOne, 0.f, 0.f, false, false}}}});
    e.process(out.data(), 256);
    CHECK(e.status().underruns == 1, "a source that runs dry while playing is counted");
    Session* old = e.takeRetired();
    CHECK(old == s, "the replaced session comes back for freeing off the audio thread");
    delete old;
    e.post({Msg::Stop, 0, 0, 0, nullptr});
    e.process(out.data(), 256);
    e.process(out.data(), 256);
    CHECK(e.status().playing == 0, "stopped");
    delete e.takeRetired();
    DONE("engine_test");
}
