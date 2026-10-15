// export_test.cpp — THREADED
#include "studio/engine.h"
#include "studio/export.h"
#include "studio/streamer.h"
#include "studio/wav.h"
#include "test.h"
#include <cmath>
#include <string>
#include <vector>
using namespace studio;

int main() {
    std::string err;
    const int rate = 48000;
    const std::string stemPath = tmpPath("studio_export_stem.wav");
    const char* stem = stemPath.c_str();
    WavWriter* w = WavWriter::create(stem, rate, 2, err);
    std::vector<float> sine(2 * rate);
    for (int i = 0; i < rate; i++) sine[2 * i] = sine[2 * i + 1] = 0.3f * std::sin(2 * M_PI * 220 * i / rate);
    w->write(sine.data(), rate);
    w->finish();
    delete w;
    WavReader* r = WavReader::open(stem, err);
    Streamer s(r, 0, 0, rate);
    s.startThread();
    Engine e(rate, 4096);
    {   // play something loud first: the export must not depend on what played before it
        struct Loud : Source {
            int read(float* out, int frames) override { for (int i = 0; i < 2 * frames; i++) out[i] = 3.f; return frames; }
            int channels() const override { return 2; }
            void seek(long) override {}
        } loud;
        e.post({Msg::Swap, 0, 0, 0, new Session{rate, {{&loud, 0.f, 0.f, false, false}}}});
        e.post({Msg::Play, 0, 0, 0, nullptr});
        std::vector<float> o(2 * 4096);
        for (int k = 0; k < 6; k++) e.process(o.data(), 4096);
        e.post({Msg::Stop, 0, 0, 0, nullptr});
        e.process(o.data(), 4096);
        delete e.takeRetired();
    }
    e.post({Msg::Swap, 0, 0, 0, new Session{rate, {{&s, -6.0206f, 0.f, false, false}}}});
    const std::string outPath = tmpPath("studio_export_mix.wav");
    const char* out = outPath.c_str();
    CHECK(exportMix(e, rate, out, err), "export");
    s.stopThread();
    WavReader* m = WavReader::open(out, err);
    std::vector<float> mix(2 * rate);
    m->read(0, mix.data(), rate);
    float worst = 0;
    for (int i = 0; i < rate; i++) worst = std::fmax(worst, std::fabs(mix[2 * i] - 0.5f * sine[2 * i]));
    CHECK(m->frames() == rate, "exactly the asked length");
    CHECK(worst < 1e-6f, "the export is the stem at -6 dB, not delayed, sample for sample");
    delete m;
    delete r;
    delete e.takeRetired();
    delete e.takeRetired();
    {   // an engine whose queue is full still exports: the export makes room instead of dropping an order
        Engine busy(rate, 4096);
        while (busy.post({Msg::Gain, 0, 0.f, 0, nullptr})) {}
        std::string why;
        CHECK(exportMix(busy, 100, out, why), "a full queue is emptied, not an error");
        WavReader* r2 = WavReader::open(out, why);
        CHECK(r2 && r2->frames() == 100, "and the export has every frame");
        delete r2;
    }
    std::remove(stem);
    std::remove(out);
    DONE("export_test");
}
