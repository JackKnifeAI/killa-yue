// engine_test.cpp
#include "studio/engine.h"
#include "test.h"
#include <vector>
using namespace studio;

#include <cmath>
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

    {   // a block bigger than the engine was made for is rendered in pieces, in time with the sources
        Engine big(48000, 256);
        Constant c(0.25f, 48000);
        big.post({Msg::Swap, 0, 0, 0, new Session{48000, {{&c, 0.f, 0.f, false, false}}}});
        big.post({Msg::Play, 0, 0, 0, nullptr});
        std::vector<float> o(2 * 1024);
        big.process(o.data(), 1024);
        bool full = true;
        for (int i = 100; i < 1024; i++) full = full && o[2 * i] == 0.25f;
        CHECK(full && c.at == 1024 && big.status().position == 1024, "a 1024-frame block from a 256-frame engine plays in full and keeps time");
    }
    {   // a track swapped in while playing joins at the song's position, not at its own start
        Engine sw(48000, 256);
        Constant a1(0.1f, 480000), b1(0.1f, 480000);
        sw.post({Msg::Swap, 0, 0, 0, new Session{48000, {{&a1, 0.f, 0.f, false, false}}}});
        sw.post({Msg::Play, 0, 0, 0, nullptr});
        std::vector<float> o(512);
        for (int k = 0; k < 4; k++) sw.process(o.data(), 256);
        sw.post({Msg::Swap, 0, 0, 0, new Session{48000, {{&a1, 0.f, 0.f, false, false}, {&b1, 0.f, 0.f, false, false}}}});
        sw.process(o.data(), 256);
        CHECK(b1.at == 5 * 256 && a1.at == 5 * 256, "an added track plays in time with the rest");
        delete sw.takeRetired();
    }
    {   // fader moves glide across a block instead of jumping (no zipper noise, no clicks)
        Engine gl(48000, 256);
        Constant a1(0.25f, 480000), b1(0.25f, 480000);
        gl.post({Msg::Swap, 0, 0, 0, new Session{48000, {{&a1, 0.f, 0.f, false, false}, {&b1, 0.f, 0.f, false, false}}}});
        gl.post({Msg::Play, 0, 0, 0, nullptr});
        std::vector<float> o(512), all;
        for (int k = 0; k < 3; k++) gl.process(o.data(), 256);
        gl.post({Msg::Mute, 1, 1.f, 0, nullptr});
        for (int k = 0; k < 3; k++) {
            gl.process(o.data(), 256);
            for (int i = 0; i < 256; i++) all.push_back(o[2 * i]);
        }
        float step = 0;
        for (size_t i = 1; i < all.size(); i++) step = std::fmax(step, std::fabs(all[i] - all[i - 1]));
        CHECK(step < 0.01f, "muting a track fades it over one block");
        NEAR(all.back(), 0.25, 1e-6, "and then it is silent");
    }
    {   // a mono recording is panned with the mono law, though it arrives as two channels
        struct MonoAsStereo : Constant {
            MonoAsStereo() : Constant(0.5f, 480000) {}
            int panChannels() const override { return 1; }
        } m;
        Engine pm(48000, 256);
        pm.post({Msg::Swap, 0, 0, 0, new Session{48000, {{&m, 0.f, 0.f, false, false}}}});
        pm.post({Msg::Play, 0, 0, 0, nullptr});
        std::vector<float> o(512);
        pm.process(o.data(), 256);
        pm.process(o.data(), 256);
        NEAR(o[2 * 200], 0.5 * std::sqrt(0.5), 1e-6, "a mono source at centre is -3 dB on each side");
    }
    {   // a fader set to nonsense is ignored rather than passed on
        Engine nf(48000, 256);
        Constant a1(0.25f, 480000);
        nf.post({Msg::Swap, 0, 0, 0, new Session{48000, {{&a1, 0.f, 0.f, false, false}}}});
        nf.post({Msg::Play, 0, 0, 0, nullptr});
        nf.post({Msg::Gain, 0, NAN, 0, nullptr});
        nf.post({Msg::Master, 0, INFINITY, 0, nullptr});
        std::vector<float> o(512);
        nf.process(o.data(), 256);
        nf.process(o.data(), 256);
        NEAR(o[2 * 200], 0.25, 1e-6, "NaN and infinite levels are ignored");
    }
    DONE("engine_test");
}
