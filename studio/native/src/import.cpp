#include "studio/import.h"
#include <cstdio>

namespace studio {

Importer* Importer::open(int rateIn, int channels, int rateOut, const char* path, std::string& err) {
    if (channels < 1 || channels > 2 || rateIn < 8000 || rateIn > 384000) {
        err = "the Studio takes mono or stereo audio at 8 to 384 kHz";
        return nullptr;
    }
    std::unique_ptr<Importer> im(new Importer());
    im->path_ = path;
    im->ch_ = channels;
    im->rs_.reset(new Resampler(rateIn, rateOut, channels));
    im->wav_.reset(WavWriter::create(path, rateOut, channels, err));
    if (!im->wav_) return nullptr;
    im->peaks_.reset(new PeakWriter(channels));
    im->buf_.resize((size_t)im->rs_->maxOut(Resampler::kChunk) * channels);
    return im.release();
}

bool Importer::emit(const float* x, int frames, std::string& err) {
    if (frames <= 0) return true;
    if (!wav_->write(x, frames)) {
        err = "the disk is full, or the file could not be written";
        return false;
    }
    peaks_->add(x, frames);
    frames_ += frames;
    return true;
}

bool Importer::push(const float* x, int frames, std::string& err) {
    if (done_ || !wav_) {
        err = "the import is over";
        return false;
    }
    for (int at = 0; at < frames;) {
        const int n = std::min(Resampler::kChunk, frames - at);
        const int got = rs_->process(x + (size_t)at * ch_, n, buf_.data());
        if (!emit(buf_.data(), got, err)) return false;
        at += n;
    }
    return true;
}

bool Importer::finish(std::string& err) {
    if (done_ || !wav_) {
        err = "the import is over";
        return false;
    }
    std::vector<float> tail((size_t)rs_->maxTail() * ch_ + 1);
    const int got = rs_->finish(tail.data());
    if (!emit(tail.data(), got, err)) return false;
    if (!wav_->finish()) {
        err = "the file could not be finished";
        return false;
    }
    done_ = true;
    return peaks_->write((path_ + ".kpk").c_str(), err);
}

void Importer::cancel() {
    wav_.reset();  // an unfinished WAV removes its .part
    if (done_) {
        std::remove(path_.c_str());
        std::remove((path_ + ".kpk").c_str());
    }
    done_ = true;
}

}  // namespace studio
