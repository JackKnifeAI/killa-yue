// meters_test.cpp
// Per-track meters: each track's loudest moment since the screen last looked, after its fader and pan.
#include "studio/engine.h"
#include "test.h"
#include <vector>
using namespace studio;

struct Level : Source {  // a steady level on both channels
    float v;
    explicit Level(float x) : v(x) {}
    int read(float* out, int frames) override {
        for (int i = 0; i < 2 * frames; i++) out[i] = v;
        return frames;
    }
    int channels() const override { return 2; }
    void seek(long) override {}
};

int main() {
    Level a(0.5f), b(0.25f), c(0.1f);
    Engine e(48000, 256);
    auto* s = new Session{48000, {}};
    s->tracks.push_back({&a, 0.f, 0.f, false, false});
    s->tracks.push_back({&b, -6.0206f, 0.f, false, false});
    s->tracks.push_back({&c, 0.f, 0.f, true, false});
    e.post({Msg::Swap, 0, 0, 0, s});
    e.post({Msg::Play, 0, 0, 0, nullptr});
    std::vector<float> out(2 * 256);
    for (int k = 0; k < 4; k++) e.process(out.data(), 256);
    float m[Engine::kMaxTracks * 2];
    const int n = e.meters(m, Engine::kMaxTracks);
    CHECK(n == 3, "a meter for every track");
    NEAR(m[0], 0.5f, 1e-4f, "track 1's left meter: its level");
    NEAR(m[2], 0.125f, 1e-4f, "track 2's meter is after its fader (-6 dB)");
    NEAR(m[4], 0.f, 1e-6f, "a muted track's meter is still");
    e.meters(m, Engine::kMaxTracks);
    e.post({Msg::Pan, 0, -1.f, 0, nullptr});
    e.process(out.data(), 256);  // the pan glides over one block
    e.meters(m, Engine::kMaxTracks);
    for (int k = 0; k < 3; k++) e.process(out.data(), 256);
    e.meters(m, Engine::kMaxTracks);
    CHECK(m[1] < 1e-6f && std::fabs(m[0] - 0.5f) < 1e-4f, "hard left (a stereo track balances): the right meter is still, the left at unity");
    e.post({Msg::Stop, 0, 0, 0, nullptr});
    for (int k = 0; k < 4; k++) e.process(out.data(), 256);
    e.meters(m, Engine::kMaxTracks);
    for (int k = 0; k < 2; k++) e.process(out.data(), 256);
    e.meters(m, Engine::kMaxTracks);
    NEAR(m[0], 0.f, 1e-6f, "stopped: the meters fall to nothing");
    DONE("meters_test");
}
