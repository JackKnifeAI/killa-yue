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
    std::vector<float> loud(2 * rate);
    std::mt19937 rng(1);
    std::uniform_real_distribution<float> u(-4.f, 4.f); // four stems at full scale and then some
    for (auto& s : loud) s = u(rng);
    lim.process(loud.data(), rate);
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
    DONE("mix_test");
}
