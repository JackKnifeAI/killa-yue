// latency_test.cpp
// Round-trip latency: the Studio plays a probe and finds it in what the microphone heard. These recordings are
// made up here with what a phone does to sound: quiet, noisy, echoing, band-limited, upside down.
#include "studio/latency.h"
#include "test.h"
#include <cmath>
#include <random>
#include <vector>
using namespace studio;

static std::vector<float> heard(const std::vector<float>& probe, long frames, long delay, float level, float noise, unsigned seed) {
    std::vector<float> r((size_t)frames, 0.f);
    std::mt19937 rng(seed);
    std::normal_distribution<float> n(0.f, 1.f);
    for (long i = 0; i < frames; i++) r[i] = noise * n(rng);
    for (size_t k = 0; k < probe.size() && delay + (long)k < frames; k++) r[delay + k] += level * probe[k];
    return r;
}

int main() {
    const int rate = 48000;
    const std::vector<float> probe = probeSignal(rate);
    CHECK(probe.size() >= (size_t)rate / 50 && probe.size() <= (size_t)rate / 10, "the probe lasts 20 to 100 ms");
    float peak = 0.f;
    for (float v : probe) peak = std::max(peak, std::fabs(v));
    CHECK(peak <= 0.5f && peak > 0.2f, "the probe is clearly audible and never clips");
    CHECK(std::fabs(probe.front()) < 0.01f && std::fabs(probe.back()) < 0.01f, "it fades in and out, so it does not click");

    {   // quiet and noisy (about 0 dB signal-to-noise over the probe)
        auto r = heard(probe, rate, 9137, 0.05f, 0.02f, 1);
        Found f = findProbe(r.data(), (long)r.size(), probe);
        CHECK(f.delay == 9137, "a quiet probe in noise is found to the frame");
        CHECK(f.sure, "and the finder is sure of it");
    }
    {   // a room: a reflection 7 ms later, louder than the direct sound (a microphone facing away), is not the answer
        auto r = heard(probe, rate, 5000, 0.1f, 0.002f, 2);
        auto echo = heard(probe, rate, 5000 + 336, 0.12f, 0.f, 3);
        for (size_t i = 0; i < r.size(); i++) r[i] += echo[i];
        Found f = findProbe(r.data(), (long)r.size(), probe);
        CHECK(f.delay == 5000, "the first arrival is found, not the echo");
    }
    {   // a phone speaker: no bass and no treble (a crude band-pass), and the microphone upside down
        auto r = heard(probe, rate, 12001, -0.2f, 0.001f, 4);
        std::vector<float> b(r.size(), 0.f);
        float lo = 0.f, hp = 0.f, prev = 0.f;
        for (size_t i = 0; i < r.size(); i++) {
            lo += 0.35f * (r[i] - lo);            // low-pass around 4 kHz
            hp = 0.97f * (hp + lo - prev);        // high-pass around 250 Hz
            prev = lo;
            b[i] = hp;
        }
        Found f = findProbe(b.data(), (long)b.size(), probe);
        CHECK(std::labs(f.delay - 12001) <= 2, "through a band-limited speaker and an inverted microphone, within 2 frames");
        CHECK(f.sure, "and still sure");
    }
    {   // nothing heard (the volume was down, or another app has the microphone)
        auto r = heard(probe, rate, 0, 0.f, 0.01f, 5);
        Found f = findProbe(r.data(), (long)r.size(), probe);
        CHECK(!f.sure, "plain noise is not mistaken for the probe");
        std::vector<float> zero((size_t)rate, 0.f);
        CHECK(!findProbe(zero.data(), (long)zero.size(), probe).sure, "silence is not mistaken for the probe");
    }
    {   // a short recording, or a probe that arrives too late to be complete, is not found
        auto r = heard(probe, rate / 10, 0, 0.1f, 0.f, 6);
        Found f = findProbe(r.data(), (long)probe.size() / 2, probe);
        CHECK(!f.sure, "a recording shorter than the probe finds nothing");
    }
    {   // every probe is its own noise: one probe is never mistaken for another
        const std::vector<float> second = probeSignal(rate, 2);
        CHECK(second.size() == probe.size() && second != probe, "a second probe has the same length and different noise");
        auto r = heard(second, rate, 7000, 0.1f, 0.001f, 8);
        CHECK(!findProbe(r.data(), (long)r.size(), probe).sure, "the first probe is not found where only the second was played");
        CHECK(findProbe(r.data(), (long)r.size(), second).delay == 7000, "the second is found where it was played");
    }
    {   // the measurement: several probes, the median, and a refusal when they disagree
        CHECK(roundTrip({1000, 1001, 999, 1000, 1002}, rate).ok, "agreeing probes give a measurement");
        CHECK(roundTrip({1000, 1001, 999, 1000, 1002}, rate).frames == 1000, "the median of five");
        CHECK(!roundTrip({1000, 1400, 999, 2000, 1002}, rate).ok, "with two of five far off, it is refused");
        CHECK(!roundTrip({1000, 1001}, rate).ok, "fewer than three probes are not enough");
        // as on the S25: the first probe, while the streams were still settling, came 427 frames late
        CHECK(roundTrip({1636, 1209, 1209, 1209, 1209}, rate).ok && roundTrip({1636, 1209, 1209, 1209, 1209}, rate).frames == 1209,
              "one stray probe out of five does not spoil the four that agree");
        CHECK(!roundTrip({1000, 1001, 1002, 3000, 3001, 3002}, rate).ok, "two camps of three is no answer");
        CHECK(roundTrip({1000, 1001, kNotFound, 1000}, rate).ok && roundTrip({1000, 1001, kNotFound, 1000}, rate).frames == 1000,
              "a probe that was not found is left out");
        // counted by the streams' own positions the answer can be below zero (the microphone's count started first)
        CHECK(roundTrip({-1200, -1201, -1199}, rate).ok && roundTrip({-1200, -1201, -1199}, rate).frames == -1200,
              "a round trip below zero is an answer, not a miss");
    }
    DONE("latency_test");
}
