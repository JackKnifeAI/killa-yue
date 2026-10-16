// WAV files for the Studio: 32-bit float (what the Studio writes), 16- and 24-bit PCM read too; mono or
// stereo; RIFF, or RF64 for files past 4 GB. Writing goes to path.part and is renamed into place once
// finished and flushed to disk, so a crash never leaves a half-written file under the real name.
#pragma once
#include <cstdio>
#include <string>
#include <vector>

#include "studio/streamer.h"

namespace studio {

class WavReader : public Reader {
   public:
    static WavReader* open(const char* path, std::string& error);  // nullptr and a reason if not a WAV it reads
    ~WavReader() override;
    long frames() const override { return frames_; }
    int channels() const override { return channels_; }
    int rate() const { return rate_; }
    int read(long at, float* out, int frames) override;  // disk thread only

   private:
    WavReader() {}
    FILE* f_ = nullptr;
    long frames_ = 0, dataStart_ = 0;
    int channels_ = 0, rate_ = 0, bits_ = 0, format_ = 0;  // format 1 PCM, 3 float
    std::vector<unsigned char> buf_;
};

class WavWriter {
   public:
    static WavWriter* create(const char* path, int rate, int channels, std::string& error, unsigned long long rf64Above = 0xFFFFFFFFull);
    ~WavWriter();
    bool write(const float* interleaved, int frames);
    void keepPartial() { keep_ = true; }
    bool checkpoint();
    static bool recover(const char* path, std::string& error);
    bool finish();  // sizes patched (RF64 past 4 GB), flushed, fsynced, renamed into place

   private:
    WavWriter() {}
    FILE* f_ = nullptr;
    std::string path_;
    int channels_ = 0;
    unsigned long long dataBytes_ = 0, rf64Above_ = 0xFFFFFFFFull;
    bool done_ = false, keep_ = false;
};

}  // namespace studio
