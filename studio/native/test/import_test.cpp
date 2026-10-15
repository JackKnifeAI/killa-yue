// import_test.cpp
// Importing a file into a project: resampled to the Studio's rate, written as a float WAV with its peak file.
#include "studio/import.h"
#include "studio/peaks.h"
#include "studio/wav.h"
#include "test.h"
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>
using namespace studio;

int main() {
    const std::string out = tmpPath("import-test.wav");
    std::string err;
    {   // a 44.1 kHz stereo file into a 48 kHz project
        std::unique_ptr<Importer> im(Importer::open(44100, 2, 48000, out.c_str(), err));
        CHECK(im != nullptr, "an import opens");
        std::vector<float> chunk(2 * 3000);
        long done = 0;
        for (int k = 0; k < 30; k++) {  // 90000 frames = 2.04 s, in pieces
            for (int i = 0; i < 3000; i++, done++) {
                chunk[2 * i] = (float)(0.5 * std::sin(2 * M_PI * 1000 * done / 44100.0));
                chunk[2 * i + 1] = 0.25f;
            }
            CHECK(im->push(chunk.data(), 3000, err), "pushes take any size");
        }
        CHECK(im->finish(err), "and it finishes");
        CHECK(im->frames() == (long)std::ceil(90000 * 48000.0 / 44100.0), "the result is exactly as long as the source");
    }
    std::unique_ptr<WavReader> r(WavReader::open(out.c_str(), err));
    CHECK(r && r->rate() == 48000 && r->channels() == 2, "the file is a 48 kHz stereo WAV");
    std::vector<float> s(2 * (size_t)r->frames());
    r->read(0, s.data(), (int)r->frames());
    NEAR(s[2 * 48000], (float)(0.5 * std::sin(2 * M_PI * 1000 * 48000 / 48000.0)), 1e-4f, "the tone is in its place at 1 s");
    NEAR(s[2 * 48000 + 1], 0.25f, 1e-5f, "the other channel too");
    PeakFile pk;
    CHECK(pk.read((out + ".kpk").c_str(), err) && pk.frames == r->frames(), "its peak file sits beside it, as long as it");
    {   // cancelled: nothing is left behind
        const std::string gone = tmpPath("import-cancel.wav");
        std::unique_ptr<Importer> im(Importer::open(44100, 1, 48000, gone.c_str(), err));
        std::vector<float> c(1000, 0.1f);
        im->push(c.data(), 1000, err);
        im->cancel();
        im.reset();
        FILE* a = std::fopen(gone.c_str(), "rb");
        FILE* b = std::fopen((gone + ".part").c_str(), "rb");
        FILE* k = std::fopen((gone + ".kpk").c_str(), "rb");
        CHECK(!a && !b && !k, "a cancelled import leaves no file behind");
        if (a) std::fclose(a);
        if (b) std::fclose(b);
        if (k) std::fclose(k);
    }
    {   // the same rate is a straight copy
        const std::string same = tmpPath("import-same.wav");
        std::unique_ptr<Importer> im(Importer::open(48000, 1, 48000, same.c_str(), err));
        std::vector<float> c(4800);
        for (int i = 0; i < 4800; i++) c[i] = (float)i / 4800.f;
        im->push(c.data(), 4800, err);
        im->finish(err);
        std::unique_ptr<WavReader> rr(WavReader::open(same.c_str(), err));
        std::vector<float> back(4800);
        rr->read(0, back.data(), 4800);
        CHECK(back == c, "a file already at the Studio's rate comes in sample for sample");
        std::remove(same.c_str());
        std::remove((same + ".kpk").c_str());
    }
    std::remove(out.c_str());
    std::remove((out + ".kpk").c_str());
    DONE("import_test");
}
