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
// deaf: the microphone stream runs but never delivers a frame.
struct Loopback : Device {
    int delay;
    bool deaf = false;
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
                if (!deaf) in->input(mic.data(), 192, 1);  // the microphone first, as the real device does
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

// An output device that renders in real time and keeps the left channel of everything it played.
struct Pacer : Device {
    Renderer* r = nullptr;
    std::thread t;
    std::atomic<bool> stop{false};
    std::vector<float> played;  // read after close()
    ~Pacer() override { close(); }
    bool open(bool, std::string&) override { return true; }
    int rate() const override { return 48000; }
    int burst() const override { return 192; }
    void setRenderer(Renderer* x) override { r = x; }
    void setInput(InputSink*) override {}
    bool start(std::string&) override {
        t = std::thread([this] {
            std::vector<float> out(2 * 192);
            auto next = std::chrono::steady_clock::now();
            while (!stop) {
                r->render(out.data(), 192);
                for (int i = 0; i < 192; i++) played.push_back(out[2 * i]);
                next += std::chrono::microseconds(4000);
                std::this_thread::sleep_until(next);
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

        // a loop: the same file many times on one track opens it once
        std::vector<ClipSpec> loop;
        for (int k = 0; k < 200; k++) loop.push_back({quarter, (long)k * rate, 0, rate, 0.f});
        CHECK(h->load({{0.f, 0.f, false, false, loop}, {0.f, 0.f, false, false, {{half, 0, 0, rate, 0.f}}}}, err), "a 200-clip loop loads");
        play(*h, 1000);
        h->status();
        CHECK(h->filesOpen() == 2, "and its file is opened once, not 200 times");
        auto l = play(*h, 4000);
        NEAR(l[3500], 0.75f, 1e-4f, "the loop and the other track both play");

        // more tracks than the engine plays is refused by load itself, so edits never go to the wrong track
        std::vector<TrackSpec> many(Engine::kMaxTracks + 1, TrackSpec{0.f, 0.f, false, false, {}});
        CHECK(!h->load(many, err) && err.find(std::to_string(Engine::kMaxTracks)) != std::string::npos, "65 tracks are refused, saying the limit");
        auto m = play(*h, 2000);
        NEAR(m[1500], 0.75f, 1e-4f, "and the tracks that were playing play on");

        // the master level always arrives, even when the engine's queue was full at the time
        for (int k = 0; k < 255; k++) h->post({Msg::Gain, 1, 0.f, 0, nullptr});
        CHECK(h->load({{0.f, 0.f, false, false, loop}, {0.f, 0.f, false, false, {{half, 0, 0, rate, 0.f}}}}, -6.0206f, err), "a load with a master level, into a full queue");
        play(*h, 512);   // the engine drains the queue
        h->status();     // the master level that did not fit goes now
        auto lv = play(*h, 4000);
        NEAR(lv[3500], 0.375f, 1e-3f, "the master level arrived");

        h->post({Msg::Mute, 0, 1.f, 0, nullptr});
        auto d = play(*h, 4000);
        NEAR(d[3500], 0.25f, 1e-4f, "edits reach the engine");
        CHECK(h->deviceStats().rate == 0, "a host with no device has no device stats");
        RoundTrip rt = h->measureLatency(5, err);
        CHECK(!rt.ok && !err.empty(), "latency needs a device with a microphone");
    }
    {   // new tracks loaded while playing take over without a gap: 8 tracks of 1/32, then 8 of 1/16
        std::vector<std::string> files;
        for (int k = 0; k < 16; k++) files.push_back(wav(("host-cont-" + std::to_string(k) + ".wav").c_str(), rate, 2, k < 8 ? 0.03125f : 0.0625f, 5 * rate));
        auto spec = [&](int first) {
            std::vector<TrackSpec> t;
            for (int k = first; k < first + 8; k++) t.push_back({0.f, 0.f, false, false, {{files[k], 0, 0, 5L * rate, 0.f}}});
            return t;
        };
        std::string err;
        Pacer* pacer = new Pacer;
        std::unique_ptr<Host> h(Host::withDevice(pacer, false, err));
        CHECK(h->load(spec(0), err), "eight tracks load");
        h->post({Msg::Play, 0, 0, 0, nullptr});
        h->start(err);
        std::this_thread::sleep_for(std::chrono::milliseconds(400));
        const long before = h->status().underruns;
        CHECK(h->load(spec(8), err), "eight new tracks load while playing");
        std::this_thread::sleep_for(std::chrono::milliseconds(400));
        const long after = h->status().underruns;
        pacer->close();
        const std::vector<float>& p = pacer->played;
        long gaps = 0, switched = 0;
        for (size_t i = (size_t)rate / 5; i < p.size(); i++) {  // after the first 200 ms of starting up
            if (p[i] < 0.2f) gaps++;
            if (p[i] > 0.4f) switched++;
        }
        CHECK(switched > 0, "the new tracks were heard");
        CHECK(gaps == 0, "not one silent frame when the tracks changed");
        CHECK(after == before, "and no underrun");
        h.reset();
        for (auto& f : files) std::remove(f.c_str());
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
    }
    for (int delay : {19200, 24000, 40000}) {  // 400 ms (the probes' spacing), 500 ms and 833 ms: Bluetooth territory
        std::string err;
        std::unique_ptr<Host> h(Host::withDevice(new Loopback(delay), true, err));
        h->start(err);
        RoundTrip rt = h->measureLatency(5, err);
        CHECK(rt.ok && rt.frames == delay, ("a long round trip is measured exactly, not mistaken for a short one: " + std::to_string(delay)).c_str());
    }
    {
        std::string err;
        std::unique_ptr<Host> deaf(Host::withDevice(new Loopback(2000), false, err));
        RoundTrip rt = deaf->measureLatency(5, err);
        CHECK(!rt.ok && err.find("microphone") != std::string::npos, "without the microphone, it says so");

        Loopback* silent = new Loopback(2000);
        silent->deaf = true;
        std::unique_ptr<Host> mute(Host::withDevice(silent, true, err));
        mute->start(err);
        err.clear();
        rt = mute->measureLatency(3, err);
        CHECK(!rt.ok && err.find("microphone sent nothing") != std::string::npos, ("a microphone that sends nothing is named: " + err).c_str());
        err.clear();
        rt = mute->measureLatency(3, err);
        CHECK(!rt.ok && err.find("microphone sent nothing") != std::string::npos, "and trying again gives the same answer, not \"already running\"");

        std::unique_ptr<Host> slow(Host::withDevice(new Loopback(2000), true, err));
        slow->start(err);
        std::string cerr;
        RoundTrip crt{true, 0};
        auto t0 = std::chrono::steady_clock::now();
        std::thread worker([&] { crt = slow->measureLatency(5, cerr); });
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
        slow->cancelLatency();
        worker.join();
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
        CHECK(!crt.ok && cerr.find("cancelled") != std::string::npos && ms < 600, "a measurement can be cancelled, and returns at once");
        rt = slow->measureLatency(5, err);
        CHECK(rt.ok && rt.frames == 2000, "and the next one works");
    }
    std::remove(quarter.c_str());
    std::remove(half.c_str());
    std::remove(other.c_str());
    DONE("host_test");
}
