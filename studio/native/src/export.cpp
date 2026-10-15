#include "studio/export.h"
#include <algorithm>
#include <memory>
#include <vector>
#include "studio/wav.h"

namespace studio {

bool exportMix(Engine& e, long frames, const char* path, int rate, std::string& error) {
    std::unique_ptr<WavWriter> w(WavWriter::create(path, rate, 2, error));
    if (!w) return false;
    e.setOffline(true);
    e.post({Msg::Seek, 0, 0.f, 0, nullptr});
    e.post({Msg::Play, 0, 0.f, 0, nullptr});
    const int block = 4096;
    std::vector<float> buf(2 * block);
    long skip = e.latency(), left = frames;
    bool ok = true;
    while (left > 0 && ok) {
        e.process(buf.data(), block);
        const long drop = std::min<long>(skip, block);
        skip -= drop;
        const long n = std::min<long>(block - drop, left);
        if (n > 0) ok = w->write(buf.data() + 2 * drop, (int)n);
        left -= n;
    }
    e.post({Msg::Stop, 0, 0.f, 0, nullptr});
    e.setOffline(false);
    if (!ok) error = "could not write the export (is the storage full?)";
    if (ok && !w->finish()) {
        ok = false;
        error = "could not finish the export file";
    }
    return ok;
}

}  // namespace studio
