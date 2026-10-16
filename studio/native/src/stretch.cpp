#include "studio/stretch.h"

#include <signalsmith-stretch.h>
#include <sys/statvfs.h>

#include <algorithm>
#include <cmath>
#include <memory>

#include "studio/wav.h"
namespace studio {
bool stretchFile(const char* input, const char* output, long offset, long frames, double ratio, double semitones, std::string& err) {
    if (!(ratio >= .25 && ratio <= 4) || !(semitones >= -24 && semitones <= 24) || offset < 0 || frames <= 0) {
        err = "Stretch needs a length of 25–400% and pitch of −24 to +24 semitones.";
        return false;
    }
    std::unique_ptr<WavReader> reader(WavReader::open(input, err));
    if (!reader) return false;
    if (offset > reader->frames() || frames > reader->frames() - offset) {
        err = "The sample range is outside the file.";
        return false;
    }
    long total = std::llround(frames * ratio);
    std::string name(output), dir = name.substr(0, name.find_last_of('/'));
    struct statvfs space{};
    if (statvfs(dir.c_str(), &space) || static_cast<unsigned long long>(space.f_bavail) * space.f_frsize <
                                            (unsigned long long)total * reader->channels() * 4 + 64 * 1024 * 1024) {
        err = "Not enough storage for the stretched audio.";
        return false;
    }
    const int ch = reader->channels(), block = 1024;
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    stretch.configure(ch, 16384, 1024);
    stretch.setTransposeSemitones(semitones);
    int il = stretch.inputLatency(), ol = stretch.outputLatency();
    int capacity = std::max(il, block);
    std::vector<std::vector<float>> in(ch, std::vector<float>(capacity)), out(ch, std::vector<float>(std::max(ol, block * 4 + 4)));
    std::vector<float> inter((size_t)std::max(capacity, block * 4 + 4) * ch);
    auto fill = [&](long at, int n) {
        for (auto& c : in) std::fill(c.begin(), c.end(), 0);
        int want = (int)std::max(0L, std::min((long)n, frames - at));
        if (want == 0) return true;
        if (reader->read(offset + at, inter.data(), want) != want) return false;
        for (int c = 0; c < ch; c++)
            for (int i = 0; i < want; i++) in[c][i] = inter[i * ch + c];
        return true;
    };
    if (!fill(0, il)) {
        err = "Cannot read stretch input.";
        return false;
    }
    stretch.seek(in, il, 1 / ratio);
    std::unique_ptr<WavWriter> writer(WavWriter::create(output, reader->rate(), ch, err));
    if (!writer) return false;
    long discard = ol, written = 0;
    auto write = [&](int n) {
        int skip = (int)std::min((long)n, discard);
        discard -= skip;
        n -= skip;
        n = (int)std::min((long)n, total - written);
        for (int i = 0; i < n; i++)
            for (int c = 0; c < ch; c++) inter[i * ch + c] = out[c][i + skip];
        written += n;
        return writer->write(inter.data(), n);
    };
    for (long at = 0; at < frames;) {
        int n = (int)std::min((long)block, frames - at), m = (int)(std::llround((at + n) * ratio) - std::llround(at * ratio));
        if (!fill(il + at, n)) {
            err = "Cannot read stretch input.";
            return false;
        }
        stretch.process(in, n, out, m);
        if (!write(m)) {
            err = "Cannot write stretched audio.";
            return false;
        }
        at += n;
    }
    stretch.flush(out, ol);
    if (!write(ol) || written != total || !writer->finish()) {
        err = "The stretched file could not be finished.";
        return false;
    }
    return true;
}
}  // namespace studio
