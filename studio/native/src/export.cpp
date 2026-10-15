#include "studio/export.h"
#include <algorithm>
#include <memory>
#include <vector>
#include "studio/wav.h"

namespace studio {

bool exportMix(Engine& e, long frames, const char* path, std::string& error) {
    std::unique_ptr<WavWriter> w(WavWriter::create(path, e.rate(), 2, error));
    if (!w) return false;
    const int block = 4096;
    std::vector<float> buf(2 * block);
    // the export runs the engine itself, so a full queue is emptied by rendering a block (silence: not playing
    // yet), never by dropping an order: a lost Offline(false) would leave sources waiting for the disk
    auto order = [&](const Message& m) {
        while (!e.post(m)) e.process(buf.data(), block);
    };
    order({Msg::Offline, 0, 1.f, 0, nullptr});
    order({Msg::Reset, 0, 0.f, 0, nullptr});  // never depend on what played before
    order({Msg::Seek, 0, 0.f, 0, nullptr});
    order({Msg::Play, 0, 0.f, 0, nullptr});
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
    order({Msg::Stop, 0, 0.f, 0, nullptr});
    order({Msg::Offline, 0, 0.f, 0, nullptr});
    if (!ok) error = "could not write the export (is the storage full?)";
    if (ok && !w->finish()) {
        ok = false;
        error = "could not finish the export file";
    }
    return ok;
}

}  // namespace studio
