#include "studio/stretch.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

#include "studio/wav.h"
int main() {
    std::string err;
    const char* in = "/tmp/killa-stretch-in.wav";
    const char* out = "/tmp/killa-stretch-out.wav";
    std::unique_ptr<studio::WavWriter> w(studio::WavWriter::create(in, 48000, 1, err));
    std::vector<float> x(96000);
    for (int i = 0; i < 96000; i++) x[i] = .25f * std::sin(2 * 3.141592653589793 * 440 * i / 48000);
    assert(w->write(x.data(), 96000) && w->finish());
    w.reset();
    assert(studio::stretchFile(in, out, 0, 96000, 1.5, 12, err));
    std::unique_ptr<studio::WavReader> r(studio::WavReader::open(out, err));
    assert(r && r->frames() == 144000);
    x.resize(48000);
    assert(r->read(48000, x.data(), 48000) == 48000);
    // Frequency from interpolated positive zero crossings in the steady middle second.
    double first = -1, last = 0;
    int count = 0;
    for (int i = 1; i < 48000; i++)
        if (x[i - 1] <= 0 && x[i] > 0) {
            double at = i - 1 - x[i - 1] / (x[i] - x[i - 1]);
            if (first < 0) first = at;
            last = at;
            count++;
        }
    double hz = (count - 1) * 48000 / (last - first), cents = 1200 * std::log2(hz / 880);
    assert(std::fabs(cents) < .5);
    std::remove(in);
    std::remove(out);
    std::printf("stretch: exact length; pitch error %.4f cents\n", cents);
}
