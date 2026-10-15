// peaks_test.cpp
// Peak files: the min and max of every block of a track, at several zooms, so a waveform of a whole song draws
// from a few thousand numbers instead of millions of samples.
#include "studio/peaks.h"
#include "test.h"
#include <cstdio>
#include <random>
#include <vector>
using namespace studio;

static short q(float v) {  // the same quantising the writer uses
    if (v > 1.f) v = 1.f;
    if (v < -1.f) v = -1.f;
    return (short)std::lround(v * 32767.f);
}

int main() {
    const long n = 1000003;  // not a multiple of any block
    std::vector<float> x(2 * (size_t)n);
    std::mt19937 rng(3);
    for (long i = 0; i < n; i++) {
        x[2 * i] = std::uniform_real_distribution<float>(-0.9f, 0.9f)(rng) * (float)(i % 5000) / 5000.f;
        x[2 * i + 1] = i == 777777 ? 1.5f : 0.25f * std::sin(i * 0.001f);  // one sample over full scale
    }
    PeakWriter w(2);
    for (long at = 0; at < n;) {  // pushed in uneven pieces
        const int k = (int)std::min<long>(1 + rng() % 9000, n - at);
        w.add(x.data() + 2 * at, k);
        at += k;
    }
    const std::string path = tmpPath("peaks-test.kpk");
    std::string err;
    CHECK(w.write(path.c_str(), err), "the peak file is written");
    PeakFile p;
    CHECK(p.read(path.c_str(), err), "and read back");
    CHECK(p.frames == n && p.channels == 2, "with the right length and channels");
    CHECK(p.levels.size() == 5 && p.levels[0].block == 256 && p.levels[4].block == 65536, "five zooms: blocks of 256 to 65536 frames");
    bool exact = true;
    for (size_t l = 0; l < p.levels.size(); l++) {
        const PeakFile::Level& lv = p.levels[l];
        if ((long)lv.count != (n + lv.block - 1) / lv.block) exact = false;
        for (long b = 0; b < (long)lv.count && exact; b++)
            for (int c = 0; c < 2; c++) {
                float mn = 2, mx = -2;
                for (long i = b * lv.block; i < std::min(n, (b + 1) * (long)lv.block); i++) {
                    mn = std::min(mn, x[2 * i + c]);
                    mx = std::max(mx, x[2 * i + c]);
                }
                if (lv.min(b, c) != q(mn) || lv.max(b, c) != q(mx)) exact = false;
            }
    }
    CHECK(exact, "every block's min and max match the samples, at every zoom, the short last block too");
    CHECK(p.levels[0].max(777777 / 256, 1) == 32767, "a sample over full scale shows as full scale");
    {   // a damaged file is refused, not misread
        FILE* f = std::fopen(path.c_str(), "r+b");
        std::fputs("JUNK", f);
        std::fclose(f);
        PeakFile bad;
        CHECK(!bad.read(path.c_str(), err), "a damaged peak file is refused");
    }
    std::remove(path.c_str());
    DONE("peaks_test");
}
