#include "studio/device.h"

#ifndef __ANDROID__
namespace studio {
Device* Device::create() { return nullptr; }
}  // namespace studio
#else

#include <oboe/Oboe.h>
#include <pthread.h>
#include <sched.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>

namespace studio {
namespace {

class OboeDevice;

// Full duplex: Oboe drains and primes the microphone, then hands both sides to us in the output callback.
class Duplex : public oboe::FullDuplexStream {
public:
    explicit Duplex(OboeDevice* d) : dev_(d) {}
    oboe::DataCallbackResult onBothStreamsReady(const void* in, int nIn, void* out, int nOut) override;

private:
    OboeDevice* dev_;
};

class OboeDevice : public Device, public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    OboeDevice() : duplex_(this) {}
    ~OboeDevice() override { close(); }

    bool open(bool withInput, std::string& err) override {
        std::lock_guard<std::mutex> l(mu_);
        wantInput_ = withInput;
        closing_ = false;
        if (!openStreams(0, err)) return false;
        if (!watcher_.joinable()) watcher_ = std::thread(&OboeDevice::watch, this);
        return true;
    }

    int rate() const override { return rate_; }
    int burst() const override { return burst_; }
    void setRenderer(Renderer* r) override { renderer_ = r; }
    void setInput(InputSink* s) override { sink_ = s; }

    bool start(std::string& err) override {
        std::lock_guard<std::mutex> l(mu_);
        return startStreams(err);
    }

    void close() override {
        {
            std::lock_guard<std::mutex> l(mu_);
            closing_ = true;
            closeStreams();
        }
        wake_.notify_all();
        if (watcher_.joinable()) watcher_.join();
    }

    DeviceStats stats() override {
        std::lock_guard<std::mutex> l(mu_);
        DeviceStats s;
        s.rate = rate_;
        s.burst = burst_;
        s.inputChannels = in_ ? in_->getChannelCount() : 0;
        s.running = running_;
        s.reopened = reopened_;
        s.error = error_;
        s.xruns = xrunsBefore_;
        if (out_) {
            s.bufferFrames = out_->getBufferSizeInFrames();
            s.exclusive = out_->getSharingMode() == oboe::SharingMode::Exclusive;
            auto x = out_->getXRunCount();
            if (x) s.xruns += x.value();
            auto lat = out_->calculateLatencyMillis();
            if (lat) s.outputLatencyMs = lat.value();
        }
        const long n = callbacks_.load(std::memory_order_relaxed);
        s.callbacks = n;
        s.slowCallbacks = slow_.load(std::memory_order_relaxed);
        s.callbackMaxMs = maxNs_.load(std::memory_order_relaxed) / 1e6;
        s.callbackAvgMs = n ? totalNs_.load(std::memory_order_relaxed) / 1e6 / (double)n : 0.0;
        s.callbackPolicy = policy_.load(std::memory_order_relaxed);
        s.callbackPriority = priority_.load(std::memory_order_relaxed);
        return s;
    }

    // output only: Oboe calls this; with the microphone, Duplex calls callback() instead
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audio, int32_t frames) override {
        callback(nullptr, 0, audio, frames);
        return oboe::DataCallbackResult::Continue;
    }

    void callback(const void* in, int nIn, void* out, int nOut) {
        const auto t0 = std::chrono::steady_clock::now();
        if (policy_.load(std::memory_order_relaxed) < 0) {  // once: is this thread real-time?
            int pol = 0;
            sched_param sp{};
            if (pthread_getschedparam(pthread_self(), &pol, &sp) == 0) {
                priority_.store(sp.sched_priority, std::memory_order_relaxed);
                policy_.store(pol, std::memory_order_relaxed);
            }
        }
        if (in && nIn > 0 && sink_) sink_->input(static_cast<const float*>(in), nIn, inChannels_);
        if (renderer_) renderer_->render(static_cast<float*>(out), nOut);
        else std::fill(static_cast<float*>(out), static_cast<float*>(out) + 2 * nOut, 0.f);
        if (tuner_) tuner_->tune();  // grows the buffer after an underrun (safe to call here)
        const long ns = (long)std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count();
        callbacks_.store(callbacks_.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);  // one writer
        totalNs_.store(totalNs_.load(std::memory_order_relaxed) + ns, std::memory_order_relaxed);
        if (ns > maxNs_.load(std::memory_order_relaxed)) maxNs_.store(ns, std::memory_order_relaxed);
        if (rate_ > 0 && ns > (long)burst_ * 1000000000L / rate_) slow_.store(slow_.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
    }

    // the route changed (or the stream died): Oboe has closed the stream; the watcher thread brings it back
    void onErrorBeforeClose(oboe::AudioStream* s, oboe::Result) override {
        if (s->getDirection() == oboe::Direction::Output) {  // its count is gone once Oboe closes it
            auto x = s->getXRunCount();
            if (x) lostXruns_.store(x.value());
        }
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result e) override {
        {
            std::lock_guard<std::mutex> l(mu_);
            if (closing_) return;
            error_ = std::string("The audio stream stopped (") + oboe::convertToText(e) + "); reopening it.";
            broken_ = true;
        }
        wake_.notify_all();
    }

private:
    bool openStreams(int keepRate, std::string& err) {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setErrorCallback(this);
        if (keepRate > 0) {
            // after a route change: the same rate as before, converted by Oboe if the new device differs
            b.setSampleRate(keepRate)->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        }
        b.setFormatConversionAllowed(true)->setChannelConversionAllowed(true);
        if (wantInput_) b.setDataCallback(&duplex_);
        else b.setDataCallback(this);
        oboe::Result r = b.openStream(out_);
        if (r != oboe::Result::OK) {
            err = std::string("Could not open the audio output: ") + oboe::convertToText(r);
            out_.reset();
            return false;
        }
        rate_ = out_->getSampleRate();
        burst_ = out_->getFramesPerBurst();
        out_->setBufferSizeInFrames(2 * burst_);
        tuner_.reset(new oboe::LatencyTuner(*out_));
        if (wantInput_) {
            oboe::AudioStreamBuilder ib;
            ib.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Exclusive)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setSampleRate(rate_)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setFormatConversionAllowed(true)
                ->setChannelConversionAllowed(true)
                ->setInputPreset(oboe::InputPreset::Unprocessed)  // no gain control or noise suppression
                ->setErrorCallback(this);
            r = ib.openStream(in_);
            if (r != oboe::Result::OK) {
                err = std::string("Could not open the microphone: ") + oboe::convertToText(r);
                in_.reset();
                out_->close();
                out_.reset();
                tuner_.reset();
                return false;
            }
            inChannels_ = in_->getChannelCount();
            duplex_.setSharedInputStream(in_);
            duplex_.setSharedOutputStream(out_);
        }
        return true;
    }

    bool startStreams(std::string& err) {
        if (!out_) {
            err = "The audio stream is not open.";
            return false;
        }
        const oboe::Result r = wantInput_ ? duplex_.start() : out_->requestStart();
        if (r != oboe::Result::OK) {
            err = std::string("Could not start the audio: ") + oboe::convertToText(r);
            return false;
        }
        running_ = true;
        return true;
    }

    void closeStreams() {
        if (wantInput_ && out_ && in_) duplex_.stop();
        else if (out_) out_->requestStop();
        if (out_) {
            auto x = out_->getXRunCount();
            if (x) xrunsBefore_ += x.value();
            out_->close();
        }
        if (in_) in_->close();
        out_.reset();
        in_.reset();
        tuner_.reset();
        running_ = false;
    }

    void watch() {
        std::unique_lock<std::mutex> l(mu_);
        while (!closing_) {
            wake_.wait(l, [this] { return closing_ || broken_; });
            if (closing_) break;
            broken_ = false;
            const bool wasRunning = running_;
            const int keep = rate_;
            // Oboe closed the stream that failed; stop and close the other side too, then open both again
            if (out_) {
                auto x = out_->getXRunCount();  // only answers if the output was not the one that failed
                if (x) xrunsBefore_ += x.value();
                out_->requestStop();
                out_->close();
            }
            if (in_) {
                in_->requestStop();
                in_->close();
            }
            out_.reset();
            in_.reset();
            tuner_.reset();
            running_ = false;
            xrunsBefore_ += lostXruns_.exchange(0);
            std::string err;
            for (int attempt = 0; attempt < 20 && !closing_; attempt++) {
                if (openStreams(keep, err) && (!wasRunning || startStreams(err))) {
                    reopened_++;
                    error_.clear();
                    break;
                }
                error_ = err;
                l.unlock();
                std::this_thread::sleep_for(std::chrono::milliseconds(250));  // the new device may still be appearing
                l.lock();
            }
        }
    }

    std::mutex mu_;  // the streams' lifetime; never taken on the audio thread
    std::condition_variable wake_;
    std::thread watcher_;
    std::shared_ptr<oboe::AudioStream> out_, in_;
    std::unique_ptr<oboe::LatencyTuner> tuner_;
    Duplex duplex_;
    Renderer* renderer_ = nullptr;
    InputSink* sink_ = nullptr;
    bool wantInput_ = false, closing_ = false, broken_ = false, running_ = false;
    int rate_ = 0, burst_ = 0, inChannels_ = 1, reopened_ = 0;
    long xrunsBefore_ = 0;
    std::atomic<long> lostXruns_{0};
    std::string error_;
    std::atomic<long> callbacks_{0}, slow_{0}, maxNs_{0}, totalNs_{0};
    std::atomic<int> policy_{-1}, priority_{-1};
};

oboe::DataCallbackResult Duplex::onBothStreamsReady(const void* in, int nIn, void* out, int nOut) {
    dev_->callback(in, nIn, out, nOut);
    return oboe::DataCallbackResult::Continue;
}

}  // namespace

Device* Device::create() { return new OboeDevice(); }

}  // namespace studio
#endif
