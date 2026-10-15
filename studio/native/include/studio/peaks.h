// Peak files (.kpk beside each audio file): the min and max of every block, for drawing waveforms at any zoom
// without reading the audio. Five zooms, blocks of 256, 1024, 4096, 16384 and 65536 frames; values as 16-bit.
//   "KPK1", u32 format 1, i64 frames, u32 channels, u32 levels, then per level: u32 block, i64 count,
//   count x channels x (min, max) as int16.
#pragma once
#include <string>
#include <vector>

namespace studio {

class PeakWriter {
public:
    explicit PeakWriter(int channels);
    void add(const float* interleaved, int frames);
    bool write(const char* path, std::string& err);  // written beside as .part, then renamed into place
    long frames() const { return frames_; }

private:
    void close();  // the last, short block

    int ch_;
    long frames_ = 0;
    int inBlock_ = 0;           // frames in the block being filled
    std::vector<float> mn_, mx_;  // that block's min and max, per channel
    std::vector<short> base_;   // finished blocks of 256 frames: (min, max) per channel
};

struct PeakFile {
    struct Level {
        unsigned block = 0;
        long count = 0;
        int channels = 0;
        std::vector<short> data;
        short min(long b, int c) const { return data[((size_t)b * channels + c) * 2]; }
        short max(long b, int c) const { return data[((size_t)b * channels + c) * 2 + 1]; }
    };
    long frames = 0;
    int channels = 0;
    std::vector<Level> levels;
    bool read(const char* path, std::string& err);
};

}  // namespace studio
