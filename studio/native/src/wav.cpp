#include "studio/wav.h"

#include <fcntl.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>

namespace studio {

static uint32_t u32(const unsigned char* p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }
static uint64_t u64(const unsigned char* p) { return u32(p) | (uint64_t)u32(p + 4) << 32; }
static void put32(unsigned char* p, uint32_t v) {
    for (int i = 0; i < 4; i++) p[i] = (unsigned char)(v >> (8 * i));
}
static void put64(unsigned char* p, uint64_t v) {
    put32(p, (uint32_t)v);
    put32(p + 4, (uint32_t)(v >> 32));
}

WavReader* WavReader::open(const char* path, std::string& error) {
    FILE* f = std::fopen(path, "rb");
    if (!f) {
        error = std::string("cannot open ") + path;
        return nullptr;
    }
    WavReader* w = new WavReader();
    w->f_ = f;
    unsigned char h[12];
    if (std::fread(h, 1, 12, f) != 12 || (std::memcmp(h, "RIFF", 4) && std::memcmp(h, "RF64", 4)) || std::memcmp(h + 8, "WAVE", 4)) {
        error = "not a WAV file";
        delete w;
        return nullptr;
    }
    uint64_t dataSize64 = 0;
    bool fmt = false, data = false;
    unsigned char c[8];
    while (std::fread(c, 1, 8, f) == 8) {
        uint64_t size = u32(c + 4);
        const long body = ftello(f);
        if (!std::memcmp(c, "ds64", 4)) {
            unsigned char d[16];
            if (std::fread(d, 1, 16, f) != 16) break;
            dataSize64 = u64(d + 8);
        } else if (!std::memcmp(c, "fmt ", 4)) {
            unsigned char d[40] = {0};
            if (size < 16 || std::fread(d, 1, size < 40 ? size : 40, f) < 16) break;
            w->format_ = d[0] | d[1] << 8;
            w->channels_ = d[2] | d[3] << 8;
            w->rate_ = (int)u32(d + 4);
            w->bits_ = d[14] | d[15] << 8;
            if (w->format_ == 0xFFFE && size >= 26) w->format_ = d[24] | d[25] << 8;  // extensible: the sub-format
            fmt = true;
        } else if (!std::memcmp(c, "data", 4)) {
            if (size == 0xFFFFFFFFu && dataSize64) size = dataSize64;
            w->dataStart_ = body;
            const int block = w->channels_ * w->bits_ / 8;
            fseeko(f, 0, SEEK_END);
            const uint64_t there = (uint64_t)std::max(0L, (long)ftello(f) - body);  // what the file really holds
            if (size > there) size = there;
            w->frames_ = fmt && block > 0 ? (long)(size / block) : 0;
            data = true;
            break;
        }
        if (fseeko(f, body + (long)size + (long)(size & 1), SEEK_SET)) break;
    }
    const bool ok = fmt && data && (w->channels_ == 1 || w->channels_ == 2) &&
                    ((w->format_ == 3 && w->bits_ == 32) || (w->format_ == 1 && (w->bits_ == 16 || w->bits_ == 24)));
    if (!ok) {
        error = !fmt || !data ? "a WAV file without its format or data"
                              : "a WAV format the Studio does not read (it reads 32-bit float and 16- or 24-bit PCM, mono or stereo)";
        delete w;
        return nullptr;
    }
    return w;
}

WavReader::~WavReader() {
    if (f_) std::fclose(f_);
}

int WavReader::read(long at, float* out, int frames) {
    if (at < 0 || at >= frames_) return 0;
    if (at + frames > frames_) frames = (int)(frames_ - at);
    const int block = channels_ * bits_ / 8;
    buf_.resize((size_t)frames * block);
    if (fseeko(f_, dataStart_ + at * block, SEEK_SET)) return 0;
    const int got = (int)(std::fread(buf_.data(), block, frames, f_));
    const int n = got * channels_;
    const unsigned char* p = buf_.data();
    for (int i = 0; i < n; i++) {
        if (format_ == 3) {
            std::memcpy(&out[i], p + 4 * i, 4);
        } else if (bits_ == 16) {
            out[i] = (int16_t)(p[2 * i] | p[2 * i + 1] << 8) / 32768.f;
        } else {
            int32_t v = p[3 * i] | p[3 * i + 1] << 8 | p[3 * i + 2] << 16;
            if (v & 0x800000) v -= 0x1000000;
            out[i] = v / 8388608.f;
        }
    }
    return got;
}

// header: RIFF(12) + JUNK(8+28, becomes ds64 for RF64) + fmt (8+16) + data(8) = 80 bytes
static const int kHeader = 80;

WavWriter* WavWriter::create(const char* path, int rate, int channels, std::string& error, unsigned long long rf64Above) {
    WavWriter* w = new WavWriter();
    w->rf64Above_ = rf64Above;
    w->path_ = path;
    w->channels_ = channels;
    w->f_ = std::fopen((w->path_ + ".part").c_str(), "wb");
    if (!w->f_) {
        error = std::string("cannot write ") + path;
        delete w;
        return nullptr;
    }
    unsigned char h[kHeader] = {0};
    std::memcpy(h, "RIFF", 4);
    std::memcpy(h + 8, "WAVE", 4);
    std::memcpy(h + 12, "JUNK", 4);
    put32(h + 16, 28);
    std::memcpy(h + 48, "fmt ", 4);
    put32(h + 52, 16);
    h[56] = 3;  // IEEE float
    h[58] = (unsigned char)channels;
    put32(h + 60, (uint32_t)rate);
    put32(h + 64, (uint32_t)(rate * channels * 4));
    h[68] = (unsigned char)(channels * 4);
    h[70] = 32;
    std::memcpy(h + 72, "data", 4);
    if (std::fwrite(h, 1, kHeader, w->f_) != (size_t)kHeader) {
        error = "cannot write the WAV header";
        delete w;
        return nullptr;
    }
    return w;
}

WavWriter::~WavWriter() {
    if (f_) {
        std::fclose(f_);
        if (!done_ && !keep_) std::remove((path_ + ".part").c_str());
    }
}

bool WavWriter::write(const float* x, int frames) {
    const size_t n = (size_t)frames * channels_;
    if (std::fwrite(x, sizeof(float), n, f_) != n) return false;
    dataBytes_ += n * sizeof(float);
    return true;
}

bool WavWriter::checkpoint() {
    // the header is rewritten in place: RIFF sizes, or RF64 with its ds64 where JUNK held the room
    const uint64_t riff = dataBytes_ + kHeader - 8;
    unsigned char top[12], junk[36], data[8];
    if (riff <= rf64Above_) {
        std::memcpy(top, "RIFF", 4);
        put32(top + 4, (uint32_t)riff);
        std::memcpy(junk, "JUNK", 4);
        put32(junk + 4, 28);
        std::memset(junk + 8, 0, 28);
        std::memcpy(data, "data", 4);
        put32(data + 4, (uint32_t)dataBytes_);
    } else {
        std::memcpy(top, "RF64", 4);
        put32(top + 4, 0xFFFFFFFFu);
        std::memcpy(junk, "ds64", 4);
        put32(junk + 4, 28);
        put64(junk + 8, riff);
        put64(junk + 16, dataBytes_);
        put64(junk + 24, dataBytes_ / (4 * channels_));
        put32(junk + 32, 0);
        std::memcpy(data, "data", 4);
        put32(data + 4, 0xFFFFFFFFu);
    }
    std::memcpy(top + 8, "WAVE", 4);
    bool ok = !fseeko(f_, 0, SEEK_SET) && std::fwrite(top, 1, 12, f_) == 12 && std::fwrite(junk, 1, 36, f_) == 36 &&
              !fseeko(f_, 72, SEEK_SET) && std::fwrite(data, 1, 8, f_) == 8;
    ok = ok && std::fflush(f_) == 0 && fsync(fileno(f_)) == 0;
    return ok && !fseeko(f_, 0, SEEK_END);
}

bool WavWriter::recover(const char* path, std::string& error) {
    WavWriter w;
    w.path_ = path;
    w.keep_ = true;
    w.f_ = std::fopen((w.path_ + ".part").c_str(), "r+b");
    if (!w.f_) {
        error = "No interrupted take to recover.";
        return false;
    }
    unsigned char h[80];
    if (std::fread(h, 1, 80, w.f_) != 80 || std::memcmp(h + 8, "WAVE", 4) || std::memcmp(h + 48, "fmt ", 4) || h[56] != 3 || h[70] != 32 ||
        (h[58] != 1 && h[58] != 2) || std::memcmp(h + 72, "data", 4)) {
        error = "The interrupted take has an invalid header.";
        return false;
    }
    w.channels_ = h[58];
    if (fseeko(w.f_, 0, SEEK_END)) {
        error = "Cannot read the interrupted take.";
        return false;
    }
    long bytes = (long)ftello(w.f_) - 80;
    if (bytes < 0) {
        error = "Incomplete take header.";
        return false;
    }
    w.dataBytes_ = (unsigned long long)(bytes / (4 * w.channels_)) * (4 * w.channels_);
    if (ftruncate(fileno(w.f_), 80 + (off_t)w.dataBytes_)) {
        error = "Cannot repair the take length.";
        return false;
    }
    return w.finish();
}

bool WavWriter::finish() {
    bool ok = checkpoint();
    ok = std::fclose(f_) == 0 && ok;
    f_ = nullptr;
    ok = ok && std::rename((path_ + ".part").c_str(), path_.c_str()) == 0;
    if (ok) {  // make the rename itself durable, where the folder can be opened (a best effort)
        const size_t slash = path_.rfind('/');
        const std::string dir = slash == std::string::npos ? "." : slash == 0 ? "/" : path_.substr(0, slash);
        const int fd = ::open(dir.c_str(), O_RDONLY | O_DIRECTORY);
        if (fd >= 0) {
            fsync(fd);
            ::close(fd);
        }
    }
    done_ = ok;
    if (!ok && !keep_) std::remove((path_ + ".part").c_str());
    return ok;
}

}  // namespace studio
