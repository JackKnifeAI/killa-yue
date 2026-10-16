// THREADED: input producer and disk writer exercise the ring under ThreadSanitizer.
#include "studio/performance.h"

#include <sys/wait.h>
#include <unistd.h>

#include <cassert>
#include <cmath>
#include <cstdio>
#include <memory>
#include <thread>
#include <vector>

#include "studio/host.h"
#include "studio/recorder.h"
#include "studio/wav.h"
int main() {
    std::string err;
    {
        studio::PerformanceAudio p(48000);
        assert(p.kit(err));
        assert(p.trigger(0, 1, true));
        float out[512] = {};
        p.mix(out, 256, 0, false);
        float energy = 0;
        for (float v : out) energy += v * v;
        assert(energy > 0.01);
        p.allOff();
        for (int k = 0; k < 100; k++) {
            std::fill(out, out + 512, 0);
            p.mix(out, 256, k * 256, false);
        }
        energy = 0;
        for (float v : out) energy += v * v;
        assert(energy < 1e-8);
        for (int j = 0; j < 128; j++) {
            assert(p.trigger(j % 16, .4, true));
            if (j % 32 == 31) {
                std::fill(out, out + 512, 0);
                p.mix(out, 256, j * 256, false);
            }
        }
        for (float v : out) assert(std::isfinite(v));
        assert(studio::PerformanceAudio::stepFrame(1, 48000, 120, .5) == 9000);
    }
    {
        const char* file = "/tmp/killa-pad-timing.wav";
        std::unique_ptr<studio::WavWriter> w(studio::WavWriter::create(file, 48000, 1, err));
        std::vector<float> impulse(12000);
        impulse[0] = .5f;
        assert(w->write(impulse.data(), 12000) && w->finish());
        w.reset();
        studio::PerformanceAudio p(48000);
        assert(p.load(0, file, 0, 12000, err));
        float config[10] = {0, 0, 0, 1, .01f, 24000, 0, 0, 0, 0};
        assert(p.configure(0, config, 10));
        float steps[32] = {};
        steps[1] = 1;
        assert(p.patternRow(0, steps));
        assert(p.transport(120, 4, 4, false, true, 16, .5));
        float b[512];
        long first = -1;
        for (long at = 0; at < 12000; at += 256) {
            std::fill(b, b + 512, 0);
            p.mix(b, 256, at, true);
            for (int i = 0; i < 256; i++)
                if (first < 0 && std::fabs(b[2 * i]) > 1e-5) first = at + i;
        }
        assert(first == 9000);
        studio::PerformanceAudio tempo(48000);
        assert(tempo.load(0, file, 0, 12000, err));
        assert(tempo.configure(0, config, 10));
        assert(tempo.patternRow(0, steps));
        assert(tempo.transport(120, 4, 4, false, true, 16, 0));
        assert(tempo.tempoPoint(0, 0, 120));
        assert(tempo.tempoPoint(1, 96000, 60));
        long second = -1;
        for (long at = 0; at < 120000; at += 256) {
            std::fill(b, b + 512, 0);
            tempo.mix(b, 256, at, true);
            for (int i = 0; i < 256; i++)
                if (second < 0 && at + i > 96000 && std::fabs(b[2 * i]) > 1e-5) second = at + i;
        }
        assert(second == 108000);
        std::remove(file);
    }
    {
        std::unique_ptr<studio::Host> h(studio::Host::offline(48000));
        assert(h->record("/tmp/killa-anchor-test.wav", 48000, false, err));
        float idle[512];
        for (int i = 0; i < 10; i++) h->render(idle, 256);
        assert(h->recordAnchor() == -1);
        assert(h->post({studio::Msg::CountIn, -1, 0, 48000, nullptr}));
        assert(h->post({studio::Msg::Play, -1, 0, 0, nullptr}));
        float out[512];
        for (int i = 0; i < 187; i++) h->render(out, 256);
        assert(h->status().position == 0);
        assert(h->recordAnchor() == 2560 + 48000);
        h->stopRecord(err);
        std::remove("/tmp/killa-anchor-test.wav");
        h->render(out, 256);
        assert(h->status().position == 128);
    }
    {
        studio::Recorder rec;
        assert(rec.start("/tmp/killa-record-test.wav", 48000, err));
        std::vector<float> tone(4800);
        for (int i = 0; i < 4800; i++) tone[i] = .2f * std::sin(i * .1f);
        for (int i = 0; i < 100; i++) {
            rec.input(tone.data(), 4800, 1, 1234 + i * 4800);
            std::this_thread::yield();
        }
        assert(rec.stop(err) == 480000);
        assert(err.empty());
        assert(rec.first() == 1234);
        std::unique_ptr<studio::WavReader> r(studio::WavReader::open("/tmp/killa-record-test.wav", err));
        assert(r && r->frames() == 480000);
        std::remove("/tmp/killa-record-test.wav");
        assert(rec.start("/tmp/killa-record-route.wav", 48000, err));
        rec.input(tone.data(), 100, 1, 1000);
        rec.input(tone.data(), 100, 1, 0);
        assert(rec.failure() == 2);
        assert(rec.stop(err) == 100);
        assert(!err.empty());
        std::remove("/tmp/killa-record-route.wav");
    }
    // A killed process cannot run any destructor: recover checkpointed bytes without any clean close.
    const char* recovered = "/tmp/killa-record-recover.wav";
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        std::string e;
        auto* w = studio::WavWriter::create(recovered, 48000, 1, e);
        assert(w);
        w->keepPartial();
        float x[1000] = {};
        assert(w->write(x, 1000) && w->checkpoint());
        assert(w->write(x, 1000) && w->checkpoint());
        _exit(0);
    }
    int status = 0;
    waitpid(child, &status, 0);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    err.clear();
    assert(studio::WavWriter::recover(recovered, err));
    std::unique_ptr<studio::WavReader> r(studio::WavReader::open(recovered, err));
    assert(r && r->frames() == 2000);
    std::remove(recovered);
    puts("performance: 64 voices, exact swung onset, count-in, recording, route discontinuity, crash recovery passed");
}
