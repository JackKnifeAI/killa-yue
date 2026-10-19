// THREADED: UI bank replacement, disk read-ahead and the audio callback have separate owners.
#include "studio/keyboard.h"

#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <thread>
#include <vector>

#include "studio/wav.h"
#include "test.h"
using namespace studio;
static double energy(const float* v, int n) {
    double e = 0;
    for (int i = 0; i < n; i++) e += v[i] * v[i];
    return e;
}
int main() {
    std::string error, file = "/tmp/killa-keyboard-" + std::to_string(getpid()) + ".wav";
    std::unique_ptr<WavWriter> w(WavWriter::create(file.c_str(), 48000, 1, error));
    std::vector<float> tone(192000);
    for (size_t i = 0; i < tone.size(); i++) tone[i] = .1f * std::sin(2 * 3.141592653589793 * 440 * i / 48000);
    CHECK(w && w->write(tone.data(), (int)tone.size()) && w->finish(), "sample file written");
    w.reset();
    std::vector<std::string> files(88, file);
    Keyboard keyboard(48000);
    float out[1024] = {};
    CHECK(!keyboard.load(std::vector<std::string>(49, file), 1, 80, error), "a 49-key bank is refused");
    CHECK(keyboard.load(files, 1, 80, error), "bank prepared from storage");
    CHECK(!keyboard.note(20, 1, true) && !keyboard.note(109, 1, true) && !keyboard.note(60, NAN, true),
          "invalid key and velocity rejected");
    CHECK(keyboard.note(21, .5, true) && keyboard.note(108, .5, true), "the lowest and highest piano keys play");
    keyboard.allOff();
    std::fill_n(out, 1024, 0);
    keyboard.mix(out, 512);
    CHECK(keyboard.note(60, .5, true), "note accepted while transport stopped");
    keyboard.mix(out, 512);
    CHECK(energy(out, 1024) > .1, "first key is prebuffered and audible");
    float last = out[1022];
    CHECK(keyboard.note(60, .5, true), "held key retriggers");
    std::fill_n(out, 1024, 0);
    keyboard.mix(out, 512);
    NEAR(out[0], last, 1e-6, "retrigger starts from the previous sample and fades into its attack");
    CHECK(keyboard.note(60, 0, false), "release accepted");
    for (int i = 0; i < 10; i++) {
        std::fill_n(out, 1024, 0);
        keyboard.mix(out, 512);
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    CHECK(energy(out, 1024) < 1e-12, "released note becomes silent");
    CHECK(keyboard.note(60, 1, true), "retrigger accepted");
    std::fill_n(out, 1024, 0);
    keyboard.mix(out, 512);
    CHECK(energy(out, 1024) > .1, "cached attack bridges an immediate retrigger while the disk rewinds");
    keyboard.allOff();
    std::fill_n(out, 1024, 0);
    keyboard.mix(out, 512);
    CHECK(energy(out, 1024) == 0, "panic releases all notes");
    auto missing = files;
    missing[3] = "/missing/killa-keyboard.wav";
    CHECK(!keyboard.load(missing, 1, 80, error), "missing bank fails before replacing current samples");
    // two velocity layers: a soft file of silence and a loud tone; soft notes choose silence
    std::string quiet = "/tmp/killa-keyboard-quiet-" + std::to_string(getpid()) + ".wav";
    std::unique_ptr<WavWriter> q(WavWriter::create(quiet.c_str(), 48000, 1, error));
    std::vector<float> zeros(48000, 0.f);
    CHECK(q && q->write(zeros.data(), (int)zeros.size()) && q->finish(), "quiet layer written");
    q.reset();
    std::vector<std::string> layered;
    for (int k = 0; k < 88; k++) {
        layered.push_back(quiet);
        layered.push_back(file);
    }
    CHECK(keyboard.load(layered, 2, 300, error), "a two-layer bank loads");
    keyboard.mix(out, 1);
    keyboard.note(60, .2f, true);
    std::fill_n(out, 1024, 0);
    keyboard.mix(out, 512);
    CHECK(energy(out, 1024) < 1e-12, "a soft note plays the soft layer");
    keyboard.note(60, .9f, true);
    for (int i = 0; i < 3; i++) {
        std::fill_n(out, 1024, 0);
        keyboard.mix(out, 512);
    }
    CHECK(energy(out, 1024) > .05, "a hard note plays the loud layer");
    NEAR(Keyboard::layerGain(1.f, 2), 1.f, 1e-6, "full velocity is full level");
    CHECK(Keyboard::layerGain(.5f, 2) < .55f, "the bottom of a layer is quieter");
    keyboard.allOff();
    keyboard.mix(out, 1);
    CHECK(keyboard.load(files, 1, 80, error), "back to one layer");
    std::atomic<bool> stop{false};
    std::atomic<bool> finite{true};
    std::thread audio([&] {
        float block[1024];
        while (!stop.load()) {
            std::fill_n(block, 1024, 0);
            keyboard.mix(block, 512);
            for (float f : block)
                if (!std::isfinite(f)) finite.store(false);
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    });
    for (int bank = 0; bank < 4; bank++) {
        CHECK(keyboard.load(files, 1, 80, error), "bank swaps safely while audio runs");
        for (int key = 21; key <= 108; key++) keyboard.note(key, .03, true);
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        keyboard.allOff();
        keyboard.collect();
    }
    stop.store(true);
    audio.join();
    keyboard.collect();
    CHECK(finite.load(), "polyphonic playback and concurrent replacement stay finite");
    unlink(file.c_str());
    unlink(quiet.c_str());
    DONE("keyboard_test");
}
