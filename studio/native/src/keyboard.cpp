#include "studio/keyboard.h"

#include <algorithm>
#include <cmath>

#include "studio/disk.h"
#include "studio/wav.h"
namespace studio {
struct Keyboard::Bank {
    struct Fade {
        float lastL = 0, lastR = 0, left = 0, right = 0;
        int frames = 0;
    };
    std::array<Fade, KEYS> fades{};
    int layers = 1, release = 4000;
    std::vector<std::unique_ptr<WavReader>> readers;
    std::vector<std::unique_ptr<Streamer>> streams;
    std::vector<std::vector<float>> attacks;  // 8192 frames per key, independent of sample duration
    Disk disk;  // destroyed first, so its worker stops before the streams and readers are freed
};
// Within a layer, velocity still shapes level: each layer covers 1/layers of the range, quietest at -6 dB.
float Keyboard::layerGain(float velocity, int layers) {
    if (layers <= 1) return velocity;
    float span = 1.f / layers, within = std::fmod(velocity, span) / span;
    if (velocity >= 1.f) within = 1.f;
    return std::pow(10.f, (within - 1.f) * 6.f / 20.f);
}
void Keyboard::collect() {
    Bank* b;
    while (retired_.pop(b)) {
        delete b;
        held_--;
    }
}
Keyboard::~Keyboard() {
    collect();
    delete bank_;
    Event e;
    while (events_.pop(e))
        if (e.bank) delete e.bank;
}
bool Keyboard::load(const std::vector<std::string>& files, int layers, int releaseMs, std::string& error) {
    collect();
    if (layers < 1 || layers > MAX_LAYERS || files.size() != (size_t)(KEYS * layers) || releaseMs < 5 ||
        releaseMs > 5000) {
        error = "A keyboard bank needs 88 keys of 1 to 4 velocity layers.";
        return false;
    }
    if (held_ >= 8 || !events_.hasRoom()) {
        error = "The keyboard is busy; try again.";
        return false;
    }
    std::unique_ptr<Bank> bank(new Bank);
    bank->layers = layers;
    bank->release = std::max(1, rate_ * releaseMs / 1000);
    // Read-ahead and the cached attack shrink for big banks, so a four-layer piano stays near 25 MB.
    const long attackFrames = files.size() > 96 ? 4096 : 8192, ring = files.size() > 96 ? 8192 : 16384;
    for (const auto& file : files) {
        std::unique_ptr<WavReader> r(WavReader::open(file.c_str(), error));
        if (!r) return false;
        if (r->rate() != rate_ || r->frames() < 1) {
            error = "Keyboard sample rate or length does not match.";
            return false;
        }
        const int prefix = (int)std::min(attackFrames, r->frames());
        std::vector<float> raw((size_t)prefix * r->channels()), attack((size_t)prefix * 2);
        if (r->read(0, raw.data(), prefix) != prefix) {
            error = "Could not read the instrument attack.";
            return false;
        }
        for (int i = 0; i < prefix; i++)
            for (int c = 0; c < 2; c++) {
                float value = raw[i * r->channels() + (r->channels() == 1 ? 0 : c)];
                attack[2 * i + c] = std::isfinite(value) ? value : 0.f;
            }
        bank->attacks.push_back(std::move(attack));
        bank->streams.emplace_back(new Streamer(r.get(), 0, 0, r->frames(), ring));
        bank->readers.push_back(std::move(r));
    }
    std::vector<Streamer*> streams;
    for (auto& s : bank->streams) streams.push_back(s.get());
    bank->disk.add(streams);
    for (auto* s : streams)
        if (!s->waitReady(3000, 2048)) {
            error = "Could not buffer the instrument samples.";
            return false;
        }
    Event e;
    e.bank = bank.get();
    if (!events_.push(e)) {
        error = "The keyboard is busy; try again.";
        return false;
    }
    bank.release();
    held_++;
    return true;
}
bool Keyboard::note(int key, float velocity, bool on) {
    if (key < LOW || key >= LOW + KEYS || !std::isfinite(velocity) || velocity < 0 || velocity > 1) return false;
    Event e;
    e.key = key - LOW;
    e.velocity = velocity;
    e.on = on && velocity > 0;
    return events_.push(e);
}
void Keyboard::clear() {
    for (size_t i = 0; i < voices_.size(); i++) {
        voices_[i] = Voice{};
        if (bank_) {
            for (int l = 0; l < bank_->layers; l++) bank_->streams[i * bank_->layers + l]->seek(0);
            bank_->fades[i] = Bank::Fade{};
        }
    }
}
void Keyboard::mix(float* out, int frames) {
    bool panic = panic_.exchange(false);
    if (panic) clear();
    Event e;
    while (events_.pop(e)) {
        if (e.bank) {
            if (bank_) retired_.push(bank_);
            bank_ = e.bank;
            clear();
        } else if (!panic && bank_) {
            Voice& v = voices_[e.key];
            if (e.on) {
                const int layer = std::min(bank_->layers - 1, (int)(e.velocity * bank_->layers));
                if (v.active && v.layer != layer) bank_->streams[e.key * bank_->layers + v.layer]->seek(0);
                bank_->streams[e.key * bank_->layers + layer]->seek(0);
                auto& fade = bank_->fades[e.key];
                float l = v.active ? fade.lastL : 0.f, r = v.active ? fade.lastR : 0.f;
                v = Voice{};
                v.active = true;
                v.layer = layer;
                v.velocity = e.velocity;
                fade.left = l;
                fade.right = r;
                fade.frames = std::max(1, rate_ / 500);
            } else if (v.active && !v.released) {
                v.released = true;
                v.release = 0;
            }
        }
    }
    if (!bank_) return;
    for (int done = 0; done < frames;) {
        int n = std::min(512, frames - done);
        for (size_t key = 0; key < voices_.size(); key++) {
            Voice& v = voices_[key];
            if (!v.active) continue;
            const size_t slot = key * bank_->layers + v.layer;
            Streamer& stream = *bank_->streams[slot];
            int got = stream.read(scratch_.data(), n);
            const auto& attack = bank_->attacks[slot];
            const long prefix = (long)attack.size() / 2;
            if (got < n && v.age + n > prefix) underruns_.fetch_add(1, std::memory_order_relaxed);
            // Rewinding the ring is asynchronous. The bounded attack bridges that refill without waiting.
            for (int i = 0; i < n && v.age + i < prefix; i++)
                for (int c = 0; c < 2; c++) scratch_[2 * i + c] = attack[2 * (v.age + i) + c];
            const long end = bank_->readers[slot]->frames(), releaseFrames = bank_->release;
            for (int i = 0; i < n; i++) {
                float env = std::min(1.f, (float)v.age / std::max(1, rate_ / 500));
                if (v.released) {
                    // an exponential-feeling release: a damper settling rather than a straight ramp
                    float left = std::max(0.f, 1.f - (float)v.release++ / releaseFrames);
                    env *= left * left;
                }
                env *= layerGain(v.velocity, bank_->layers);
                float l = std::isfinite(scratch_[2 * i]) ? scratch_[2 * i] * env : 0.f;
                float r = std::isfinite(scratch_[2 * i + 1]) ? scratch_[2 * i + 1] * env : 0.f;
                auto& fade = bank_->fades[key];
                if (fade.frames > 0) {
                    float f = (float)fade.frames / std::max(1, rate_ / 500);
                    l += fade.left * f;
                    r += fade.right * f;
                    fade.frames--;
                }
                fade.lastL = l;
                fade.lastR = r;
                out[2 * (done + i)] += l;
                out[2 * (done + i) + 1] += r;
                v.age++;
            }
            if (v.age >= end || (v.released && v.release >= releaseFrames)) {
                v.active = false;
                stream.seek(0);
            }
        }
        done += n;
    }
}
}  // namespace studio
