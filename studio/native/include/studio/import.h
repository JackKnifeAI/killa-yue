// Bringing a file into a project: decoded audio is pushed in (at its own rate), resampled to the Studio's
// rate, and written as a float WAV with its peak file beside it (path + ".kpk"). The WAV only takes its name
// when finished, so a cut import never leaves half a file under the real name. Off the audio thread.
#pragma once
#include <memory>
#include <string>
#include "studio/peaks.h"
#include "studio/resample.h"
#include "studio/wav.h"

namespace studio {

class Importer {
public:
    static Importer* open(int rateIn, int channels, int rateOut, const char* path, std::string& err);
    bool push(const float* interleaved, int frames, std::string& err);
    bool finish(std::string& err);
    void cancel();  // stops, and removes everything it wrote
    long frames() const { return frames_; }

private:
    Importer() {}
    bool emit(const float* x, int frames, std::string& err);

    std::string path_;
    int ch_ = 0;
    long frames_ = 0;
    std::unique_ptr<Resampler> rs_;
    std::unique_ptr<WavWriter> wav_;
    std::unique_ptr<PeakWriter> peaks_;
    std::vector<float> buf_;
    bool done_ = false;
};

}  // namespace studio
