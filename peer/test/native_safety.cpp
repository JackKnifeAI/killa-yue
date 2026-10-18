// Standalone sanitizer harness: descriptor lifetime and adversarial Opus framing.
#include "../native/codec.cpp"
#include <fcntl.h>
#include <iostream>
#include <random>

int main(int argc, char **argv) {
    if (argc != 2)
        return 2;
    const std::string path = std::string(argv[1]) + "/fixture.wav";
    Bytes wav;
    auto text = [&](const char *p) { wav.insert(wav.end(), p, p + 4); };
    auto put16 = [&](uint16_t n) {
        wav.push_back(n & 255);
        wav.push_back(n >> 8);
    };
    text("RIFF");
    put32(wav, 36 + 48000 * 4);
    text("WAVE");
    text("fmt ");
    put32(wav, 16);
    put16(1);
    put16(2);
    put32(wav, 48000);
    put32(wav, 192000);
    put16(4);
    put16(16);
    text("data");
    put32(wav, 48000 * 4);
    for (int n = 0; n < 48000; n++) {
        int16_t sample = static_cast<int16_t>(5000 * std::sin(2 * 3.14159265 * 220 * n / 48000));
        put16(sample);
        put16(sample);
    }
    {
        std::ofstream out(path, std::ios::binary);
        out.write(reinterpret_cast<const char *>(wav.data()), wav.size());
    }
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    need(fd >= 0, "fixture open failed");
    {
        Decoder fromFd("", fd);
        close(fd);
        fd = -1;
        need(fromFd.outputFrames() == 48000, "descriptor frame count differs");
        need(fromFd.window(1000, 4096).size() == 8192, "duplicated descriptor does not survive caller close");
    }
    fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    Bytes valid = encode("descriptor", "fixture", 0, 96000, fd);
    close(fd);
    need(decode(valid).size() == 192000, "descriptor Opus round trip differs");
    std::mt19937 rng(7839);
    for (int n = 0; n < 10000; n++) {
        Bytes bad;
        if (n % 2 == 0) {
            bad = valid;
            for (int k = 0; k < 4; k++)
                bad[rng() % bad.size()] ^= static_cast<unsigned char>(1 + rng() % 255);
            if (n % 4 == 0)
                bad.resize(rng() % bad.size());
        } else {
            bad.resize(rng() % 8192);
            for (auto &v : bad)
                v = static_cast<unsigned char>(rng());
        }
        size_t output = 0;
        try {
            output = decode(bad).size();
        } catch (const std::exception &) {
        }
        need(output <= 384000, "unbounded decode output");
    }
    source.reset();
    encoded.clear();
    std::cout << "Native safety: descriptor lifetime + round trip + 10000 malformed packets passed\n";
}
