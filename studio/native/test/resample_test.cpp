// resample_test.cpp
// The import resampler: band-limited (no aliasing, no images), flat in the audible band, every sample in its
// place, and the same whatever sizes the input arrives in.
#include "studio/resample.h"
#include "test.h"
#include <cmath>
#include <random>
#include <vector>
using namespace studio;

// all of the input through a resampler in one go (plus finish)
static std::vector<float> run(Resampler& r, const std::vector<float>& in, int channels) {
    std::vector<float> out;
    const long frames = (long)in.size() / channels;
    std::vector<float> buf((size_t)r.maxOut(Resampler::kChunk) * channels);
    for (long at = 0; at < frames; at += Resampler::kChunk) {
        const int n = (int)std::min<long>(Resampler::kChunk, frames - at);
        const int got = r.process(in.data() + at * channels, n, buf.data());
        out.insert(out.end(), buf.begin(), buf.begin() + (size_t)got * channels);
    }
    std::vector<float> tail((size_t)r.maxTail() * channels);
    const int got = r.finish(tail.data());
    out.insert(out.end(), tail.begin(), tail.begin() + (size_t)got * channels);
    return out;
}

static std::vector<float> sine(double hz, int rate, long frames, double amp) {
    std::vector<float> s((size_t)frames);
    for (long i = 0; i < frames; i++) s[i] = (float)(amp * std::sin(2 * M_PI * hz * i / rate));
    return s;
}

// worst error against the ideal sine at the output rate, away from the ends (in dB re the amplitude)
static double errorDb(const std::vector<float>& out, double hz, int rate, double amp) {
    double worst = 0;
    const long n = (long)out.size(), skip = rate / 10;
    for (long i = skip; i < n - skip; i++) worst = std::max(worst, std::fabs(out[i] - amp * std::sin(2 * M_PI * hz * i / rate)));
    return 20 * std::log10(worst / amp + 1e-30);
}

static double rmsDb(const std::vector<float>& out, int rate) {
    double s = 0;
    long n = 0;
    for (long i = rate / 10; i < (long)out.size() - rate / 10; i++, n++) s += (double)out[i] * out[i];
    return 10 * std::log10(s / std::max(n, 1L) + 1e-30);
}

int main() {
    {   // equal rates: a straight copy
        Resampler r(48000, 48000, 2);
        std::vector<float> in(2 * 10000);
        std::mt19937 rng(1);
        for (auto& v : in) v = std::uniform_real_distribution<float>(-1, 1)(rng);
        CHECK(run(r, in, 2) == in, "48 kHz to 48 kHz is a bit-exact copy");
    }
    {   // the length is exact
        Resampler r(44100, 48000, 1);
        CHECK(run(r, std::vector<float>(44100 * 3 + 17, 0.f), 1).size() == (size_t)std::ceil((44100.0 * 3 + 17) * 48000 / 44100),
              "the output is ceil(frames x 48000 / 44100) long");
    }
    {   // every sample in its place: an impulse at 0.1 s lands at 0.1 s
        Resampler r(44100, 48000, 1);
        std::vector<float> in(44100, 0.f);
        in[4410] = 1.f;
        auto out = run(r, in, 1);
        long peak = 0;
        for (long i = 0; i < (long)out.size(); i++)
            if (std::fabs(out[i]) > std::fabs(out[peak])) peak = i;
        CHECK(peak == 4800, "an impulse at 0.1 s comes out at 0.1 s");
    }
    {   // DC passes at exactly unity
        Resampler r(44100, 48000, 1);
        auto out = run(r, std::vector<float>(44100, 0.5f), 1);
        NEAR(out[24000], 0.5f, 1e-6f, "a steady level comes through at exactly its level");
    }
    // flat through the audible band, both ways
    for (int way = 0; way < 2; way++) {
        const int in = way ? 48000 : 44100, out = way ? 44100 : 48000;
        double worst = -999;
        for (double hz : {100.0, 1000.0, 5000.0, 10000.0, 15000.0, 18000.0, 20000.0}) {
            Resampler r(in, out, 1);
            worst = std::max(worst, errorDb(run(r, sine(hz, in, in, 0.5), 1), hz, out, 0.5));
        }
        // -78 dB of error is within +-0.001 dB of level, and the phase is exact
        CHECK(worst < -78, way ? "48 to 44.1 kHz: 100 Hz to 20 kHz come out exact (error under -78 dB)"
                               : "44.1 to 48 kHz: 100 Hz to 20 kHz come out exact (error under -78 dB)");
        std::printf("  %s: worst passband error %.1f dB\n", way ? "48->44.1" : "44.1->48", worst);
    }
    {   // nothing above the new Nyquist folds back: a 23 kHz tone at 48 kHz has no place at 44.1 kHz
        Resampler r(48000, 44100, 1);
        const double db = rmsDb(run(r, sine(23000, 48000, 48000, 1.0), 1), 44100);
        std::printf("  23 kHz into 44.1 kHz: %.1f dBFS\n", db);
        CHECK(db < -110, "48 to 44.1 kHz: a 23 kHz tone is gone (below -110 dBFS), not folded into the band");
    }
    {   // and going up makes no images: a 19 kHz tone at 44.1 kHz is only 19 kHz at 48 kHz (its image would be at 25.1)
        Resampler r(44100, 48000, 1);
        auto o = run(r, sine(19000, 44100, 44100, 0.5), 1);
        for (long i = 0; i < (long)o.size(); i++) o[i] -= (float)(0.5 * std::sin(2 * M_PI * 19000 * i / 48000.0));
        const double db = rmsDb(o, 48000);
        std::printf("  44.1->48, 19 kHz tone, everything else: %.1f dBFS\n", db);
        CHECK(db < -100, "44.1 to 48 kHz: no images (all but the tone below -100 dBFS)");
    }
    {   // the same output whatever sizes the input comes in
        std::mt19937 rng(7);
        std::vector<float> in(2 * 30000);
        for (auto& v : in) v = std::uniform_real_distribution<float>(-1, 1)(rng);
        Resampler a(44100, 48000, 2), b(44100, 48000, 2);
        auto whole = run(a, in, 2);
        std::vector<float> pieces, buf((size_t)b.maxOut(Resampler::kChunk) * 2);
        for (long at = 0; at < 30000;) {
            const int n = (int)std::min<long>(1 + rng() % 3000, 30000 - at);
            const int got = b.process(in.data() + at * 2, n, buf.data());
            pieces.insert(pieces.end(), buf.begin(), buf.begin() + (size_t)got * 2);
            at += n;
        }
        std::vector<float> tail((size_t)b.maxTail() * 2);
        const int got = b.finish(tail.data());
        pieces.insert(pieces.end(), tail.begin(), tail.begin() + (size_t)got * 2);
        CHECK(pieces == whole, "random input sizes give exactly the same output");
    }
    {   // the stopband check can fail: a short filter lets the 23 kHz tone through
        Resampler r(48000, 44100, 1, 16);
        CHECK(rmsDb(run(r, sine(23000, 48000, 48000, 1.0), 1), 44100) > -110, "a deliberately short filter fails the stopband check");
    }
    {   // other rates: 22.05 kHz up to 48 kHz, 96 kHz down to 48 kHz
        Resampler up(22050, 48000, 1), down(96000, 48000, 1);
        CHECK(errorDb(run(up, sine(1000, 22050, 22050, 0.5), 1), 1000, 48000, 0.5) < -78, "22.05 to 48 kHz is exact too");
        CHECK(errorDb(run(down, sine(1000, 96000, 96000, 0.5), 1), 1000, 48000, 0.5) < -78, "96 to 48 kHz is exact too");
    }
    DONE("resample_test");
}
