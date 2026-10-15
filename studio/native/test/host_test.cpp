// host_test.cpp — THREADED
// The Studio as the app holds it: tracks loaded from files, swapped in and freed, played by a device, and the
// round-trip latency measured through a loopback.
#include "studio/host.h"
#include "test.h"
#include <atomic>
#include <chrono>
#include <cstdio>
#include <random>
#include <thread>
#include <vector>
using namespace studio;

static std::string wav(const char* name, int rate, int channels, float value, long frames) {
    std::string path = tmpPath(name), err;
    std::unique_ptr<WavWriter> w(WavWriter::create(path.c_str(), rate, channels, err));
    std::vector<float> s((size_t)frames * channels, value);
    w->write(s.data(), (int)frames);
    w->finish();
    return path;
}

// renders `frames` through an offline host and returns the left channel
static std::vector<float> play(Host& h, long frames) {
    std::vector<float> out(2 * (size_t)frames), left((size_t)frames);
    for (long done = 0; done < frames; done += 256) {
        h.render(out.data() + 2 * done, (int)std::min(256L, frames - done));
        std::this_thread::sleep_for(std::chrono::microseconds(500));  // give the disk thread a moment, as real time would
    }
    for (long i = 0; i < frames; i++) left[i] = out[2 * i];
    return left;
}

// A loopback: what it plays comes back to the microphone `delay` frames later, quieter and with noise.
struct Loopback : Device {
    int delay;
    Renderer* r = nullptr;
    InputSink* in = nullptr;
    std::thread t;
    std::atomic<bool> stop{false};
    explicit Loopback(int d) : delay(d) {}
    ~Loopback() override { close(); }
    bool open(bool, std::string&) override { return true; }
    int rate() const override { return 48000; }
    int burst() const override { return 192; }
    void setRenderer(Renderer* x) override { r = x; }
    void setInput(InputSink* s) override { in = s; }
    bool start(std::string&) override {
        t = std::thread([this] {
            std::vector<float> line((size_t)delay + 192, 0.f), out(2 * 192), mic(192);
            std::mt19937 rng(7);
            std::normal_distribution<float> noise(0.f, 0.003f);
            long n = 0;
            while (!stop) {
                for (int i = 0; i < 192; i++) mic[i] = 0.3f * line[(size_t)((n + i) % (long)line.size())] + noise(rng);
                in->input(mic.data(), 192, 1);  // the microphone first, as the real device does
                r->render(out.data(), 192);
                for (int i = 0; i < 192; i++) line[(size_t)((n + i + delay) % (long)line.size())] = out[2 * i];
                n += 192;
                std::this_thread::sleep_for(std::chrono::microseconds(200));  // faster than real time
            }
        });
        return true;
    }
    void close() override {
        stop = true;
        if (t.joinable()) t.join();
    }
    DeviceStats stats() override { return DeviceStats(); }
};

int main() {
    const int rate = 48000;
    const std::string quarter = wav("host-a.wav", rate, 2, 0.25f, rate), half = wav("host-b.wav", rate, 2, 0.5f, rate),
                      other = wav("host-44k.wav", 44100, 2, 0.5f, 44100);
    {
        std::unique_ptr<Host> h(Host::offline(rate));
        std::string err;
        CHECK(h->load({{0.f, 0.f, false, false, {{quarter, 0, 0, rate, 0.f}}}}, err), "a track loads");
        h->post({Msg::Play, 0, 0, 0, nullptr});
        auto a = play(*h, 4000);
        NEAR(a[3000], 0.25f, 1e-4f, "and plays its file");

        CHECK(!h->load({{0.f, 0.f, false, false, {{"/no/such/file.wav", 0, 0, rate, 0.f}}}}, err), "a missing file is refused");
        CHECK(err.find("/no/such/file.wav") != std::string::npos, "and the reason names the file");
        CHECK(!h->load({{0.f, 0.f, false, false, {{other, 0, 0, 44100, 0.f}}}}, err), "a file at another rate is refused");
        CHECK(err.find("44100") != std::string::npos && err.find("48000") != std::string::npos, "and the reason gives both rates");
        auto b = play(*h, 1000);
        NEAR(b[500], 0.25f, 1e-4f, "after a refused load the old tracks play on");

        CHECK(h->load({{0.f, 0.f, false, false, {{half, 0, 0, rate, 0.f}}}, {-120.f, 0.f, true, false, {}}}, err), "new tracks load");
        auto c = play(*h, 4000);
        NEAR(c[3500], 0.5f, 1e-4f, "and replace the old ones at the next block");
        h->status();
        CHECK(h->sessionsHeld() == 1, "the old tracks are freed once the engine lets go of them");

        h->post({Msg::Mute, 0, 1.f, 0, nullptr});
        auto d = play(*h, 4000);
        NEAR(d[3500], 0.f, 1e-6f, "edits reach the engine");
        CHECK(h->deviceStats().rate == 0, "a host with no device has no device stats");
        RoundTrip rt = h->measureLatency(5, err);
        CHECK(!rt.ok && !err.empty(), "latency needs a device with a microphone");
    }
    {   // the round trip through a loopback 2000 frames long, while a song plays underneath
        std::string err;
        std::unique_ptr<Host> h(Host::withDevice(new Loopback(2000), true, err));
        CHECK(h != nullptr, "a host opens on a device");
        CHECK(h->load({{-30.f, 0.f, false, false, {{half, 0, 0, rate, 0.f}}}}, err), "with tracks");
        h->post({Msg::Play, 0, 0, 0, nullptr});
        CHECK(h->start(err), "and starts");
        RoundTrip rt = h->measureLatency(5, err);
        CHECK(rt.ok && rt.frames == 2000, "the loopback's 2000 frames are measured exactly");
        std::unique_ptr<Host> deaf(Host::withDevice(new Loopback(2000), false, err));
        rt = deaf->measureLatency(5, err);
        CHECK(!rt.ok && err.find("microphone") != std::string::npos, "without the microphone, it says so");
    }
    std::remove(quarter.c_str());
    std::remove(half.c_str());
    std::remove(other.c_str());
    DONE("host_test");
}
