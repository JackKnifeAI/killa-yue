// wav_test.cpp
#include "studio/wav.h"
#include "test.h"
#include <cstdio>
#include <string>
#include <vector>
using namespace studio;

int main() {
    std::string err;
    const char* path = "/tmp/studio_wav_test.wav";
    WavWriter* w = WavWriter::create(path, 48000, 2, err);
    CHECK(w != nullptr, "create");
    std::vector<float> data(2 * 48000);
    for (size_t i = 0; i < data.size(); i++) data[i] = (float)i / data.size() - 0.5f;
    CHECK(w->write(data.data(), 48000) && w->finish(), "write and finish");
    delete w;
    WavReader* r = WavReader::open(path, err);
    CHECK(r && r->frames() == 48000 && r->channels() == 2 && r->rate() == 48000, "header read back");
    std::vector<float> back(2 * 48000);
    r->read(0, back.data(), 48000);
    CHECK(back == data, "float samples read back bit for bit");
    delete r;
    FILE* f = std::fopen("/tmp/studio_not_a_wav.wav", "wb");
    std::fputs("hello", f);
    std::fclose(f);
    CHECK(WavReader::open("/tmp/studio_not_a_wav.wav", err) == nullptr && !err.empty(), "a broken file is refused with a reason");
    std::remove(path);
    DONE("wav_test");
}
