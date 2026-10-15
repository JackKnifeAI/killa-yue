#include "studio/mix.h"
#include "test.h"
#include <vector>
#include <random>
using namespace studio;

int main() {
    float l, r;
    panGains(0.f, 1, l, r);
    NEAR(l, std::sqrt(0.5), 1e-6, "mono centre is -3 dB each side");
    NEAR(l * l + r * r, 1.0, 1e-6, "mono constant power at centre");
    panGains(0.37f, 1, l, r);
    NEAR(l * l + r * r, 1.0, 1e-6, "mono constant power anywhere");
    panGains(-1.f, 1, l, r);
    NEAR(r, 0.0, 1e-6, "hard left");
    panGains(0.f, 2, l, r);
    NEAR(l, 1.0, 1e-6, "stereo centre is unity");
    NEAR(r, 1.0, 1e-6, "stereo centre is unity (right)");
    panGains(1.f, 2, l, r);
    NEAR(l, 0.0, 1e-6, "stereo hard right silences the left");
    NEAR(dbToGain(-6.0206f), 0.5, 1e-5, "-6.02 dB is half");

    // a stereo track at -6 dB, centred
    TrackMix t{dbToGain(-6.0206f), 1.f, 1.f, true, 2};
    float src[4] = {0.8f, -0.4f, 0.2f, 0.1f}, out[4] = {0, 0, 0, 0};
    mixTrack(t, src, 2, out);
    NEAR(out[0], 0.4, 1e-5, "gain applied, left");
    NEAR(out[1], -0.2, 1e-5, "gain applied, right");
    t.audible = false;
    mixTrack(t, src, 2, out);
    NEAR(out[0], 0.4, 1e-5, "a silent track adds nothing");

    // the limiter: never above the ceiling, transparent below it
    const int rate = 48000;
    Limiter lim(rate);
    const float ceiling = dbToGain(-0.3f);
    const int many = 1000000; // enough frames to meet the rare rounding case
    std::vector<float> loud(2 * (size_t)many);
    std::mt19937 rng(1);
    std::uniform_real_distribution<float> u(-4.f, 4.f); // four stems at full scale and then some
    for (auto& s : loud) s = u(rng);
    lim.process(loud.data(), many);
    float peak = 0;
    for (float s : loud) peak = std::fmax(peak, std::fabs(s));
    CHECK(peak <= ceiling + 1e-6f, "loud input never exceeds the ceiling");

    Limiter clean(rate);
    std::vector<float> quiet(2 * 4800), in(2 * 4800);
    for (int i = 0; i < 4800; i++) quiet[2 * i] = quiet[2 * i + 1] = in[2 * i] = in[2 * i + 1] = 0.5f * std::sin(2 * M_PI * 440 * i / rate);
    clean.process(quiet.data(), 4800);
    int d = clean.latency();
    float worst = 0;
    for (int i = d; i < 4800; i++) worst = std::fmax(worst, std::fabs(quiet[2 * i] - in[2 * (i - d)]));
    CHECK(worst == 0.f, "below the ceiling the signal passes bit for bit, only delayed");
    CHECK(d == 72, "1.5 ms of lookahead at 48 kHz is 72 frames");

    // after a loud burst, once the release is over, quiet audio is bit-exact again
    Limiter after(rate);
    std::vector<float> burst(2 * 4800, 3.f);
    after.process(burst.data(), 4800);
    const int n2 = 2 * rate;
    std::vector<float> q(2 * n2), qin(2 * n2);
    for (int i = 0; i < n2; i++) q[2 * i] = q[2 * i + 1] = qin[2 * i] = qin[2 * i + 1] = 0.5f * std::sin(2 * M_PI * 440 * i / rate);
    after.process(q.data(), n2);
    float worst2 = 0;
    for (int i = rate; i < n2; i++) worst2 = std::fmax(worst2, std::fabs(q[2 * i] - qin[2 * (i - d)]));
    CHECK(worst2 == 0.f, "a second after a burst, quiet audio passes bit for bit again");

    // the ceiling holds exactly, not just to within a millionth
    // steady loud levels (square waves summed) take the rounding guard's path
    bool exact = true;
    for (float v : {1.3f, 2.7f, 3.f, 3.7f, 1.0001f, 7.77f}) {
        Limiter flat(rate);
        std::vector<float> sq(2 * 4800);
        for (int i = 0; i < 4800; i++) sq[2 * i] = sq[2 * i + 1] = (i / 50) % 2 ? v : -v;
        flat.process(sq.data(), 4800);
        for (float s : sq) exact = exact && std::fabs(s) <= ceiling;
    }
    CHECK(exact && peak <= ceiling, "loud input never exceeds the ceiling, not even by a rounding step");

    // NaN and infinity never reach the speakers
    Limiter safe(rate);
    std::vector<float> bad(2 * 200, 0.1f);
    bad[2 * 10] = NAN;
    bad[2 * 11 + 1] = INFINITY;
    bad[2 * 12] = -INFINITY;
    safe.process(bad.data(), 200);
    bool finite = true;
    for (float s : bad) finite = finite && std::isfinite(s) && std::fabs(s) <= ceiling;
    CHECK(finite, "NaN and infinity become silence, and nothing else is disturbed beyond the ceiling");

    // reset: a limiter that has limited starts again as new
    Limiter used(rate), fresh(rate);
    std::vector<float> loud2(2 * 4800, 3.f), a1(2 * 4800), a2(2 * 4800);
    used.process(loud2.data(), 4800);
    used.reset();
    for (int i = 0; i < 4800; i++) a1[2 * i] = a1[2 * i + 1] = a2[2 * i] = a2[2 * i + 1] = 0.25f;
    used.process(a1.data(), 4800);
    fresh.process(a2.data(), 4800);
    CHECK(a1 == a2, "after reset the limiter behaves exactly like a new one");
    DONE("mix_test");
}
