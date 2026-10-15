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

    // hand-built files in the other formats the Studio reads
    auto le = [](std::string& b, unsigned v, int n) { for (int i = 0; i < n; i++) b.push_back((char)((v >> (8 * i)) & 0xff)); };
    auto file = [&](const char* name, const std::string& bytes) {
        FILE* f = std::fopen(name, "wb");
        std::fwrite(bytes.data(), 1, bytes.size(), f);
        std::fclose(f);
    };
    auto pcm = [&](int bits, int ch, bool extensible, bool listChunk, const std::vector<int>& samples, unsigned claimed) {
        std::string b = "RIFF____WAVE";
        if (listChunk) { b += "LIST"; le(b, 3, 4); b += "abc"; b.push_back(0); }  // odd size, padded
        b += "fmt ";
        le(b, extensible ? 40 : 16, 4);
        le(b, extensible ? 0xFFFE : 1, 2);
        le(b, ch, 2);
        le(b, 48000, 4);
        le(b, 48000 * ch * bits / 8, 4);
        le(b, ch * bits / 8, 2);
        le(b, bits, 2);
        if (extensible) { le(b, 22, 2); le(b, bits, 2); le(b, 0, 4); le(b, 1, 2); b += std::string(14, '\0'); }
        b += "data";
        le(b, claimed ? claimed : (unsigned)(samples.size() * bits / 8), 4);
        for (int v : samples) le(b, (unsigned)v, bits / 8);
        return b;
    };
    float v[4];
    file("/tmp/studio_w16.wav", pcm(16, 1, false, false, {16384, -32768, 0}, 0));
    WavReader* m16 = WavReader::open("/tmp/studio_w16.wav", err);
    CHECK(m16 && m16->channels() == 1 && m16->frames() == 3 && m16->read(0, v, 3) == 3 && v[0] == 0.5f && v[1] == -1.f, "16-bit mono");
    delete m16;
    file("/tmp/studio_w24.wav", pcm(24, 2, true, true, {0x400000, 0xC00000, 0x7FFFFF, 0x800000}, 0));
    WavReader* s24 = WavReader::open("/tmp/studio_w24.wav", err);
    CHECK(s24 && s24->channels() == 2 && s24->frames() == 2 && s24->read(0, v, 2) == 2 && v[0] == 0.5f && v[1] == -0.5f && v[3] == -1.f,
          "24-bit stereo, extensible format, after an odd-sized chunk");
    delete s24;
    file("/tmp/studio_lies.wav", pcm(16, 1, false, false, {1, 2, 3}, 2000000));
    WavReader* lies = WavReader::open("/tmp/studio_lies.wav", err);
    CHECK(lies && lies->frames() == 3, "a header that claims more audio than the file holds is believed only as far as the file goes");
    delete lies;

    // the RF64 form, as written past 4 GB (here forced small), reads back
    WavWriter* big = WavWriter::create("/tmp/studio_rf64.wav", 48000, 2, err, 16);
    float some[8] = {0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f, 0.4f, -0.4f};
    CHECK(big && big->write(some, 4) && big->finish(), "an RF64 file is written");
    delete big;
    FILE* head = std::fopen("/tmp/studio_rf64.wav", "rb");
    char tag[5] = {0};
    std::fread(tag, 1, 4, head);
    std::fclose(head);
    WavReader* rf = WavReader::open("/tmp/studio_rf64.wav", err);
    float back4[8];
    CHECK(std::string(tag) == "RF64" && rf && rf->frames() == 4 && rf->read(0, back4, 4) == 4 && back4[7] == -0.4f, "and read back through its ds64 chunk");
    delete rf;
    for (const char* f : {"/tmp/studio_w16.wav", "/tmp/studio_w24.wav", "/tmp/studio_lies.wav", "/tmp/studio_rf64.wav"}) std::remove(f);
    DONE("wav_test");
}
