#include "studio/stems.h"
#include <algorithm>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <unistd.h>
#include "studio/import.h"
#include "studio/wav.h"

namespace studio {

constexpr const char* StemJob::kNames[4];

namespace {

const uint32_t kMagic = 0x4b535431;  // "KST1"

std::string raw(const std::string& work, int s) { return work + "/" + StemJob::kNames[s] + ".f32"; }

bool truncateTo(const std::string& path, long bytes) {
    FILE* f = std::fopen(path.c_str(), "ab");
    if (!f) return false;
    std::fclose(f);
    return ::truncate(path.c_str(), bytes) == 0;
}

}  // namespace

long StemJob::windows(long frames44) { return frames44 <= 0 ? 0 : (frames44 + kStride - 1) / kStride; }

StemJob::StemJob(const std::string& song, const std::string& out, const std::string& work, int outRate)
    : song_(song), out_(out), work_(work), outRate_(outRate) {}

bool StemJob::prepare(std::string& err) {
    std::unique_ptr<WavReader> r(WavReader::open(song_.c_str(), err));
    if (!r) return false;
    if (r->rate() == 44100) {
        in44_ = song_;
    } else {
        // Demucs listens at 44.1 kHz: the song through the Studio's resampler (once; kept for a resumed job)
        in44_ = work_ + "/in44.wav";
        std::unique_ptr<WavReader> done(WavReader::open(in44_.c_str(), err));
        if (!done) {
            std::unique_ptr<Importer> im(Importer::open(r->rate(), r->channels(), 44100, in44_.c_str(), err));
            if (!im) return false;
            std::vector<float> buf((size_t)8192 * r->channels());
            for (long at = 0; at < r->frames();) {
                const int k = r->read(at, buf.data(), 8192);
                if (k <= 0 || !im->push(buf.data(), k, err)) return k > 0 ? false : (err = "the song could not be read", false);
                at += k;
            }
            if (!im->finish(err)) return false;
            std::remove((in44_ + ".kpk").c_str());
        }
    }
    std::unique_ptr<WavReader> in(WavReader::open(in44_.c_str(), err));
    if (!in) return false;
    n_ = in->frames();
    // separate.py: ref = wav.mean(0); (wav - ref.mean()) / ref.std(), the spread unbiased
    const int ch = in->channels();
    std::vector<float> buf((size_t)65536 * ch);
    double sum = 0, sq = 0;
    for (int pass = 0; pass < 2; pass++)
        for (long at = 0; at < n_;) {
            const int k = in->read(at, buf.data(), 65536);
            if (k <= 0) break;
            for (int i = 0; i < k; i++) {
                const double mono = ch > 1 ? 0.5 * ((double)buf[(size_t)i * ch] + buf[(size_t)i * ch + 1]) : buf[i];
                if (pass == 0) sum += mono;
                else sq += (mono - mean_) * (mono - mean_);
            }
            at += k;
            if (pass == 0 && at >= n_) mean_ = sum / n_;
        }
    sd_ = n_ > 1 ? std::sqrt(sq / (n_ - 1)) : 1.0;
    if (!(sd_ > 1e-9)) sd_ = 1.0;  // silence: nothing to scale
    return true;
}

bool StemJob::loadState() {
    FILE* f = std::fopen((work_ + "/state.bin").c_str(), "rb");
    if (!f) return false;
    uint32_t magic = 0;
    long n = 0, done = 0, written = 0, tail = 0;
    double mean = 0, sd = 0;
    bool ok = std::fread(&magic, 4, 1, f) == 1 && magic == kMagic && std::fread(&n, 8, 1, f) == 1 && n == n_ &&
              std::fread(&mean, 8, 1, f) == 1 && std::fread(&sd, 8, 1, f) == 1 && std::fread(&done, 8, 1, f) == 1 &&
              std::fread(&written, 8, 1, f) == 1 && std::fread(&tail, 8, 1, f) == 1 && tail >= 0 && tail <= kWindow;
    if (ok) {
        acc_.assign(8 * (size_t)tail, 0.f);
        wsum_.assign((size_t)tail, 0.f);
        ok = std::fread(acc_.data(), sizeof(float), acc_.size(), f) == acc_.size() &&
             std::fread(wsum_.data(), sizeof(float), wsum_.size(), f) == wsum_.size();
    }
    std::fclose(f);
    if (!ok) return false;
    // the stems' partial files back to the checkpoint (a crash may have written past it)
    for (int s = 0; s < 4; s++)
        if (!truncateTo(raw(work_, s), written * 2 * (long)sizeof(float))) return false;
    mean_ = mean;
    sd_ = sd;
    done_ = done;
    written_ = written;
    return true;
}

bool StemJob::saveState(std::string& err) {
    const std::string path = work_ + "/state.bin", tmp = path + ".tmp";
    FILE* f = std::fopen(tmp.c_str(), "wb");
    if (!f) {
        err = "cannot write the stems checkpoint";
        return false;
    }
    const long tail = (long)wsum_.size();
    bool ok = std::fwrite(&kMagic, 4, 1, f) == 1 && std::fwrite(&n_, 8, 1, f) == 1 && std::fwrite(&mean_, 8, 1, f) == 1 &&
              std::fwrite(&sd_, 8, 1, f) == 1 && std::fwrite(&done_, 8, 1, f) == 1 && std::fwrite(&written_, 8, 1, f) == 1 &&
              std::fwrite(&tail, 8, 1, f) == 1 && std::fwrite(acc_.data(), sizeof(float), acc_.size(), f) == acc_.size() &&
              std::fwrite(wsum_.data(), sizeof(float), wsum_.size(), f) == wsum_.size();
    ok = std::fflush(f) == 0 && fsync(fileno(f)) == 0 && ok;
    ok = std::fclose(f) == 0 && ok;
    if (!ok || std::rename(tmp.c_str(), path.c_str()) != 0) {
        err = "cannot write the stems checkpoint (is the storage full?)";
        return false;
    }
    return true;
}

bool StemJob::run(StemModel& m, std::atomic<bool>* cancel, std::string& err) {
    if (!prepare(err)) return false;
    const long total = windows(n_);
    if (!loadState()) {  // a fresh job
        done_ = 0;
        written_ = 0;
        acc_.clear();
        wsum_.clear();
        for (int s = 0; s < 4; s++) std::remove(raw(work_, s).c_str());
    }
    std::unique_ptr<WavReader> in(WavReader::open(in44_.c_str(), err));
    if (!in) return false;
    const int ch = in->channels();
    const int S = kWindow;
    std::vector<float> weight(S);
    for (int i = 0; i < S / 2; i++) weight[i] = (float)(i + 1) / (float)(S / 2);
    for (int i = S / 2; i < S; i++) weight[i] = (float)(S - i) / (float)(S / 2);
    std::vector<float> window(2 * (size_t)S), sep(8 * (size_t)S), readBuf((size_t)S * ch), outBuf;
    std::vector<float> acc(8 * (size_t)S), wsum((size_t)S);
    FILE* stems[4];
    for (int s = 0; s < 4; s++) stems[s] = std::fopen(raw(work_, s).c_str(), "ab");
    bool ok = stems[0] && stems[1] && stems[2] && stems[3];
    if (!ok) err = "cannot write the stems";
    for (long k = done_; ok && k < total; k++) {
        if (cancel && cancel->load()) {
            err = "the stems job was cancelled";
            ok = false;
            break;
        }
        const long o = k * kStride, len = std::min<long>(S, n_ - o), start = o - (S - len) / 2;
        // the window, normalised; outside the song is silence
        std::fill(window.begin(), window.end(), 0.f);
        const long from = std::max(0L, start), to = std::min(n_, start + S);
        if (to > from) {
            const int got = in->read(from, readBuf.data(), (int)(to - from));
            for (int i = 0; i < got; i++)
                for (int c = 0; c < 2; c++) {
                    const float v = readBuf[(size_t)i * ch + (ch > 1 ? c : 0)];
                    window[(size_t)c * S + (from - start) + i] = (float)((v - mean_) / sd_);
                }
        }
        if (!m.run(window.data(), sep.data(), err)) {
            ok = false;
            break;
        }
        // overlap-add into this window's span: what was carried over first, then this window
        std::fill(acc.begin(), acc.end(), 0.f);
        std::fill(wsum.begin(), wsum.end(), 0.f);
        const long carried = (long)wsum_.size();
        for (long i = 0; i < carried && i < S; i++) {
            wsum[i] = wsum_[i];
            for (int c = 0; c < 8; c++) acc[(size_t)c * S + i] = acc_[(size_t)c * carried + i];
        }
        const long trim = (S - len) / 2;  // center_trim
        for (long i = 0; i < len; i++) {
            wsum[i] += weight[i];
            for (int c = 0; c < 8; c++) acc[(size_t)c * S + i] += weight[i] * sep[(size_t)c * S + trim + i];
        }
        // what no later window touches is final: up to the next window's start (or all of it, at the end)
        const long final_ = k + 1 < total ? kStride : len;
        outBuf.assign(2 * (size_t)final_, 0.f);
        for (int s = 0; s < 4 && ok; s++) {
            for (long i = 0; i < final_; i++)
                for (int c = 0; c < 2; c++) outBuf[2 * i + c] = (float)(acc[(size_t)(s * 2 + c) * S + i] / wsum[i] * sd_ + mean_);
            ok = std::fwrite(outBuf.data(), sizeof(float), outBuf.size(), stems[s]) == outBuf.size() && std::fflush(stems[s]) == 0;
        }
        if (!ok) {
            err = "cannot write the stems (is the storage full?)";
            break;
        }
        // carry the overlap to the next window
        const long tail = k + 1 < total ? len - kStride : 0;
        acc_.assign(8 * (size_t)std::max(0L, tail), 0.f);
        wsum_.assign((size_t)std::max(0L, tail), 0.f);
        for (long i = 0; i < tail; i++) {
            wsum_[i] = wsum[kStride + i];
            for (int c = 0; c < 8; c++) acc_[(size_t)c * tail + i] = acc[(size_t)c * S + kStride + i];
        }
        written_ += final_;
        done_ = k + 1;
        for (int s = 0; s < 4; s++) fsync(fileno(stems[s]));
        if (!saveState(err)) {
            ok = false;
            break;
        }
        progress_.store(0.9 * done_ / total);
    }
    for (int s = 0; s < 4; s++)
        if (stems[s]) std::fclose(stems[s]);
    if (!ok) return false;
    return finish(err);
}

bool StemJob::finish(std::string& err) {
    // each stem from 44.1 kHz to the project's rate, as a WAV with its peak file
    for (int s = 0; s < 4; s++) {
        FILE* f = std::fopen(raw(work_, s).c_str(), "rb");
        if (!f) {
            err = "a stem went missing";
            return false;
        }
        const std::string out = out_ + "/" + kNames[s] + ".wav";
        std::unique_ptr<Importer> im(Importer::open(44100, 2, outRate_, out.c_str(), err));
        if (!im) {
            std::fclose(f);
            return false;
        }
        std::vector<float> buf(2 * 8192);
        size_t got;
        bool ok = true;
        while (ok && (got = std::fread(buf.data(), sizeof(float), buf.size(), f)) > 0) ok = im->push(buf.data(), (int)(got / 2), err);
        std::fclose(f);
        if (!ok || !im->finish(err)) return false;
        progress_.store(0.9 + 0.1 * (s + 1) / 4);
    }
    for (int s = 0; s < 4; s++) std::remove(raw(work_, s).c_str());
    std::remove((work_ + "/state.bin").c_str());
    std::remove((work_ + "/in44.wav").c_str());
    progress_.store(1.0);
    return true;
}

}  // namespace studio
