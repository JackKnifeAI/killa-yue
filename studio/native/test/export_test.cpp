// export_test.cpp
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
    const char* stem = "/tmp/studio_export_stem.wav";
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
    e.post({Msg::Swap, 0, 0, 0, new Session{rate, {{&s, -6.0206f, 0.f, false, false}}}});
    const char* out = "/tmp/studio_export_mix.wav";
    CHECK(exportMix(e, rate, out, rate, err), "export");
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
    std::remove(stem);
    std::remove(out);
    DONE("export_test");
}
