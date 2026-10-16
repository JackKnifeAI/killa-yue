#include "studio/peaks.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

namespace studio {

namespace {

const unsigned kBlocks[5] = {256, 1024, 4096, 16384, 65536};

short quantise(float v) {
    if (!(v == v)) v = 0;  // NaN draws as silence
    if (v > 1.f) v = 1.f;
    if (v < -1.f) v = -1.f;
    return (short)std::lround(v * 32767.f);
}

}  // namespace

PeakWriter::PeakWriter(int channels) : ch_(channels), mn_(channels, 2.f), mx_(channels, -2.f) {}

void PeakWriter::add(const float* x, int frames) {
    for (int i = 0; i < frames; i++) {
        for (int c = 0; c < ch_; c++) {
            const float v = x[(size_t)i * ch_ + c];
            if (v < mn_[c]) mn_[c] = v;
            if (v > mx_[c]) mx_[c] = v;
        }
        if (++inBlock_ == 256) close();
    }
    frames_ += frames;
}

void PeakWriter::close() {
    if (inBlock_ == 0) return;
    for (int c = 0; c < ch_; c++) {
        base_.push_back(quantise(mn_[c]));
        base_.push_back(quantise(mx_[c]));
        mn_[c] = 2.f;
        mx_[c] = -2.f;
    }
    inBlock_ = 0;
}

bool PeakWriter::write(const char* path, std::string& err) {
    close();
    const std::string part = std::string(path) + ".part";
    FILE* f = std::fopen(part.c_str(), "wb");
    if (!f) {
        err = std::string("cannot write ") + part;
        return false;
    }
    const uint32_t format = 1, channels = (uint32_t)ch_, levels = 5;
    const int64_t frames = frames_;
    bool ok = std::fwrite("KPK1", 1, 4, f) == 4 && std::fwrite(&format, 4, 1, f) == 1 && std::fwrite(&frames, 8, 1, f) == 1 &&
              std::fwrite(&channels, 4, 1, f) == 1 && std::fwrite(&levels, 4, 1, f) == 1;
    const long baseCount = (long)(base_.size() / (2 * (size_t)ch_));
    std::vector<short> level;
    for (int l = 0; l < 5 && ok; l++) {
        // each zoom from the finest blocks: min of mins, max of maxes (quantising keeps the order, so this is exact)
        const long per = kBlocks[l] / 256, count = (baseCount + per - 1) / per;
        level.assign((size_t)count * ch_ * 2, 0);
        for (long b = 0; b < count; b++)
            for (int c = 0; c < ch_; c++) {
                short mn = 32767, mx = -32767;
                for (long k = b * per; k < std::min(baseCount, (b + 1) * per); k++) {
                    mn = std::min(mn, base_[((size_t)k * ch_ + c) * 2]);
                    mx = std::max(mx, base_[((size_t)k * ch_ + c) * 2 + 1]);
                }
                level[((size_t)b * ch_ + c) * 2] = mn;
                level[((size_t)b * ch_ + c) * 2 + 1] = mx;
            }
        const uint32_t block = kBlocks[l];
        const int64_t n = count;
        ok = std::fwrite(&block, 4, 1, f) == 1 && std::fwrite(&n, 8, 1, f) == 1 &&
             std::fwrite(level.data(), sizeof(short), level.size(), f) == level.size();
    }
    ok = std::fclose(f) == 0 && ok;
    if (!ok || std::rename(part.c_str(), path) != 0) {
        std::remove(part.c_str());
        err = std::string("cannot write ") + path;
        return false;
    }
    return true;
}

bool PeakFile::read(const char* path, std::string& err) {
    FILE* f = std::fopen(path, "rb");
    if (!f) {
        err = std::string("cannot open ") + path;
        return false;
    }
    char magic[4];
    uint32_t format = 0, ch = 0, nl = 0;
    int64_t fr = 0;
    bool ok = std::fread(magic, 1, 4, f) == 4 && std::memcmp(magic, "KPK1", 4) == 0 && std::fread(&format, 4, 1, f) == 1 && format == 1 &&
              std::fread(&fr, 8, 1, f) == 1 && std::fread(&ch, 4, 1, f) == 1 && std::fread(&nl, 4, 1, f) == 1 && fr >= 0 && ch >= 1 &&
              ch <= 8 && nl <= 16;
    levels.clear();
    for (uint32_t l = 0; l < nl && ok; l++) {
        Level lv;
        int64_t count = 0;
        ok = std::fread(&lv.block, 4, 1, f) == 1 && std::fread(&count, 8, 1, f) == 1 && lv.block > 0 && count >= 0 &&
             count == (fr + lv.block - 1) / lv.block;
        if (!ok) break;
        lv.count = (long)count;
        lv.channels = (int)ch;
        lv.data.resize((size_t)count * ch * 2);
        ok = std::fread(lv.data.data(), sizeof(short), lv.data.size(), f) == lv.data.size();
        levels.push_back(lv);
    }
    std::fclose(f);
    if (!ok) {
        err = std::string(path) + " is not a peak file it can read";
        levels.clear();
        return false;
    }
    frames = (long)fr;
    channels = (int)ch;
    return true;
}

}  // namespace studio
