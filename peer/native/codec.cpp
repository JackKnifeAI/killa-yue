// HAMMER's native audio path: decode one master, encode short Opus segments in RAM,
// and retain only a bounded set of playback blocks. TLS and Android lifecycle stay outside this module.
#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_DEVICE_IO
#define MA_NO_ENCODING
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MA_NO_ENGINE
#define MA_NO_GENERATION
#include <jni.h>
#include <opus.h>
#include <sys/stat.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "miniaudio.h"
#ifndef S_ISREG
#define S_ISREG(mode) (((mode) & S_IFMT) == S_IFREG)
#endif
#include "studio/resample.h"

namespace {
constexpr int rate = 48000, segmentFrames = 96000, packetFrames = 960, preroll = 4800;
using Bytes = std::vector<unsigned char>;
void need(bool ok, const char* error) {
    if (!ok) throw std::runtime_error(error);
}
void put32(Bytes& b, uint32_t v) {
    for (int n = 0; n < 4; n++) b.push_back(static_cast<unsigned char>(v >> (8 * n)));
}
uint32_t get32(const Bytes& b, size_t& at) {
    need(at + 4 <= b.size(), "Truncated audio segment");
    uint32_t v = 0;
    for (int n = 0; n < 4; n++) v |= uint32_t(b[at++]) << (8 * n);
    return v;
}
struct Decoder {
    ma_decoder decoder{};
    ma_uint64 frames = 0;
    int sampleRate = 0;
    explicit Decoder(const std::string& path) {
        auto config = ma_decoder_config_init(ma_format_f32, 2, 0);
        need(ma_decoder_init_file(path.c_str(), &config, &decoder) == MA_SUCCESS,
             "Unsupported or unreadable master audio");
        sampleRate = static_cast<int>(decoder.outputSampleRate);
        if (sampleRate < 8000 || sampleRate > 192000 ||
            ma_decoder_get_length_in_pcm_frames(&decoder, &frames) != MA_SUCCESS || !frames) {
            ma_decoder_uninit(&decoder);
            throw std::runtime_error("Audio must have a known length, 8–192 kHz");
        }
        if (frames > uint64_t(sampleRate) * 86400) {
            ma_decoder_uninit(&decoder);
            throw std::runtime_error("Audio duration exceeds limit");
        }
    }
    ~Decoder() { ma_decoder_uninit(&decoder); }
    uint64_t outputFrames() const { return (frames * rate + sampleRate - 1) / sampleRate; }
    std::vector<float> window(int64_t first, int count) {
        // Start at a whole second to preserve the rational resampler's phase after seeking.
        int64_t second = std::max<int64_t>(0, first / rate - 1), inputStart = second * sampleRate;
        uint64_t inputEnd = std::min<uint64_t>(
            frames, static_cast<uint64_t>(std::max<int64_t>(0, first + count)) * sampleRate / rate + 4096);
        need(inputStart >= 0 && uint64_t(inputStart) <= frames && inputEnd >= uint64_t(inputStart),
             "Audio seek out of range");
        need(ma_decoder_seek_to_pcm_frame(&decoder, inputStart) == MA_SUCCESS, "Cannot seek master audio");
        studio::Resampler resampler(sampleRate, rate, 2);
        std::vector<float> input(studio::Resampler::kChunk * 2), converted;
        converted.reserve(static_cast<size_t>((inputEnd - inputStart) * rate / sampleRate + 4096) * 2);
        std::vector<float> output(
            static_cast<size_t>(resampler.maxOut(studio::Resampler::kChunk) + resampler.maxTail()) * 2);
        for (uint64_t at = inputStart; at < inputEnd;) {
            ma_uint64 got = 0;
            int want = static_cast<int>(std::min<uint64_t>(studio::Resampler::kChunk, inputEnd - at));
            auto result = ma_decoder_read_pcm_frames(&decoder, input.data(), want, &got);
            need((result == MA_SUCCESS || result == MA_AT_END) && got > 0, "Master ended unexpectedly");
            int made = resampler.process(input.data(), static_cast<int>(got), output.data());
            converted.insert(converted.end(), output.begin(), output.begin() + made * 2);
            at += got;
        }
        int tail = resampler.finish(output.data());
        converted.insert(converted.end(), output.begin(), output.begin() + tail * 2);
        std::vector<float> wanted(static_cast<size_t>(count) * 2, 0);
        for (int n = 0; n < count; n++) {
            int64_t global = first + n, index = global - second * rate;
            if (global >= 0 && uint64_t(global) < outputFrames() && index >= 0 &&
                uint64_t(index * 2 + 1) < converted.size())
                for (int c = 0; c < 2; c++) {
                    float v = converted[static_cast<size_t>(index) * 2 + c];
                    wanted[static_cast<size_t>(n) * 2 + c] =
                        std::isfinite(v) ? std::max(-1.f, std::min(1.f, v)) : 0.f;
                }
        }
        return wanted;
    }
};
std::mutex codecMutex;
std::string sourceKey;
std::unique_ptr<Decoder> source;
std::string fileKey(const std::string& path) {
    struct stat st{};
    need(stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode), "Master unavailable");
    return path + ":" + std::to_string(st.st_size) + ":" + std::to_string(st.st_mtime);
}
Decoder& decoderFor(const std::string& path, const std::string& identity = "") {
    auto key = fileKey(path) + identity;
    if (!source || sourceKey != key) {
        source.reset(new Decoder(path));
        sourceKey = key;
    }
    return *source;
}
// The host cache shares compressed segments among listeners, not permanent alternate audio files.
std::list<std::pair<std::string, Bytes>> encoded;
size_t encodedBytes = 0;
Bytes encode(const std::string& path, const std::string& identity, int index, int bitrate) {
    need(index >= 0 && (bitrate == 48000 || bitrate == 96000 || bitrate == 160000), "Invalid stream quality");
    std::lock_guard<std::mutex> lock(codecMutex);
    std::string cacheKey =
        fileKey(path) + ":" + identity + ":" + std::to_string(index) + ":" + std::to_string(bitrate);
    for (auto it = encoded.begin(); it != encoded.end(); ++it)
        if (it->first == cacheKey) {
            Bytes out = it->second;
            encoded.splice(encoded.begin(), encoded, it);
            return out;
        }
    Decoder& source = decoderFor(path, identity);
    int64_t first = static_cast<int64_t>(index) * segmentFrames;
    need(uint64_t(first) < source.outputFrames(), "Segment outside song");
    int frames = static_cast<int>(std::min<uint64_t>(segmentFrames, source.outputFrames() - first));
    int error = 0;
    OpusEncoder* raw = opus_encoder_create(rate, 2, OPUS_APPLICATION_AUDIO, &error);
    need(raw && error == OPUS_OK, "Opus encoder unavailable");
    std::unique_ptr<OpusEncoder, decltype(&opus_encoder_destroy)> encoder(raw, opus_encoder_destroy);
    need(opus_encoder_ctl(raw, OPUS_SET_BITRATE(bitrate)) == OPUS_OK, "Invalid Opus bitrate");
    opus_encoder_ctl(raw, OPUS_SET_COMPLEXITY(5));
    opus_encoder_ctl(raw, OPUS_SET_VBR(1));
    int lookahead = 0;
    opus_encoder_ctl(raw, OPUS_GET_LOOKAHEAD(&lookahead));
    int skip = preroll + lookahead, packets = (skip + frames + packetFrames - 1) / packetFrames;
    auto pcm = source.window(first - preroll, packets * packetFrames);
    Bytes out;
    put32(out, 0x31504f48);
    put32(out, frames);
    put32(out, skip);
    put32(out, packets);
    unsigned char packet[4000];
    for (int p = 0; p < packets; p++) {
        int bytes = opus_encode_float(raw, pcm.data() + static_cast<size_t>(p) * packetFrames * 2,
                                      packetFrames, packet, sizeof(packet));
        need(bytes > 0, "Opus encode failed");
        put32(out, bytes);
        out.insert(out.end(), packet, packet + bytes);
    }
    need(out.size() <= 524288, "Encoded segment too large");
    encoded.emplace_front(cacheKey, out);
    encodedBytes += out.size();
    while (encodedBytes > 4 * 1024 * 1024) {
        encodedBytes -= encoded.back().second.size();
        encoded.pop_back();
    }
    return out;
}
Bytes decode(const Bytes& bytes) {
    size_t at = 0;
    need(get32(bytes, at) == 0x31504f48, "Invalid Opus segment");
    int frames = get32(bytes, at), skip = get32(bytes, at), packets = get32(bytes, at);
    need(frames > 0 && frames <= segmentFrames && skip >= 0 && skip <= 9600 && packets > 0 &&
             packets <= 120 && (skip + frames) <= packets * packetFrames,
         "Invalid segment dimensions");
    int error = 0;
    OpusDecoder* raw = opus_decoder_create(rate, 2, &error);
    need(raw && error == OPUS_OK, "Opus decoder unavailable");
    std::unique_ptr<OpusDecoder, decltype(&opus_decoder_destroy)> decoder(raw, opus_decoder_destroy);
    std::vector<float> pcm(static_cast<size_t>(packetFrames) * 2);
    Bytes out;
    out.reserve(static_cast<size_t>(frames) * 4);
    int position = 0;
    for (int p = 0; p < packets; p++) {
        uint32_t length = get32(bytes, at);
        need(length > 0 && length <= 4000 && at + length <= bytes.size(), "Invalid Opus packet");
        int got = opus_decode_float(raw, bytes.data() + at, length, pcm.data(), packetFrames, 0);
        at += length;
        need(got == packetFrames, "Unexpected packet duration");
        for (int n = 0; n < got; n++, position++)
            if (position >= skip && position < skip + frames)
                for (int c = 0; c < 2; c++) {
                    float v = pcm[static_cast<size_t>(n) * 2 + c];
                    need(std::isfinite(v), "Invalid decoded sample");
                    int sample = static_cast<int>(std::lrint(std::max(-1.f, std::min(1.f, v)) * 32767.f));
                    out.push_back(static_cast<unsigned char>(sample));
                    out.push_back(static_cast<unsigned char>(sample >> 8));
                }
    }
    need(at == bytes.size() && out.size() == static_cast<size_t>(frames) * 4, "Segment length mismatch");
    return out;
}
struct Cache {
    std::mutex mutex;
    size_t bytes = 0;
    std::list<std::pair<int64_t, Bytes>> blocks;
};
std::mutex cachesMutex;
std::map<jlong, std::shared_ptr<Cache>> caches;
jlong nextCache = 1;
std::shared_ptr<Cache> cacheFor(jlong handle) {
    std::lock_guard<std::mutex> l(cachesMutex);
    auto it = caches.find(handle);
    need(it != caches.end(), "Stream closed");
    return it->second;
}
std::string string(JNIEnv* e, jstring s) {
    need(s != nullptr, "Missing file");
    const char* p = e->GetStringUTFChars(s, nullptr);
    need(p != nullptr, "Cannot read path");
    std::string v(p);
    e->ReleaseStringUTFChars(s, p);
    return v;
}
Bytes bytes(JNIEnv* e, jbyteArray b) {
    need(b != nullptr, "Missing bytes");
    jsize n = e->GetArrayLength(b);
    need(n >= 0 && n <= 524288, "Buffer exceeds limit");
    Bytes out(static_cast<size_t>(n));
    e->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}
jbyteArray array(JNIEnv* e, const Bytes& bytes) {
    auto a = e->NewByteArray(static_cast<jsize>(bytes.size()));
    if (a)
        e->SetByteArrayRegion(a, 0, static_cast<jsize>(bytes.size()),
                              reinterpret_cast<const jbyte*>(bytes.data()));
    return a;
}
void failure(JNIEnv* e, const std::exception& ex) {
    if (!e->ExceptionCheck()) e->ThrowNew(e->FindClass("java/io/IOException"), ex.what());
}
}  // namespace
#define JNI_METHOD(name) Java_ai_jackknife_killayue_peer_HammerNative_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(frames)(JNIEnv* e, jclass, jstring path) {
    try {
        std::lock_guard<std::mutex> l(codecMutex);
        return static_cast<jlong>(decoderFor(string(e, path)).outputFrames());
    } catch (const std::exception& x) {
        failure(e, x);
        return 0;
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(encode)(JNIEnv* e, jclass, jstring path, jstring identity,
                                                           jint segment, jint bitrate) {
    try {
        return array(e, encode(string(e, path), string(e, identity), segment, bitrate));
    } catch (const std::exception& x) {
        failure(e, x);
        return nullptr;
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(decode)(JNIEnv* e, jclass, jbyteArray packet) {
    try {
        return array(e, decode(bytes(e, packet)));
    } catch (const std::exception& x) {
        failure(e, x);
        return nullptr;
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(range)(JNIEnv* e, jclass, jstring path, jlong at,
                                                          jint length) {
    try {
        need(at >= 0 && length > 0 && length <= 262144, "Invalid source range");
        std::ifstream file(string(e, path), std::ios::binary);
        need(bool(file), "Source unavailable");
        file.seekg(at);
        Bytes b(static_cast<size_t>(length));
        file.read(reinterpret_cast<char*>(b.data()), length);
        need(file.gcount() == length, "Source changed");
        return array(e, b);
    } catch (const std::exception& x) {
        failure(e, x);
        return nullptr;
    }
}
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(cacheCreate)(JNIEnv* e, jclass) {
    try {
        std::lock_guard<std::mutex> l(cachesMutex);
        need(caches.size() < 8, "Too many native streams");
        jlong id = nextCache++;
        caches[id] = std::make_shared<Cache>();
        return id;
    } catch (const std::exception& x) {
        failure(e, x);
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(cachePut)(JNIEnv* e, jclass, jlong id, jlong key,
                                                       jbyteArray input) {
    try {
        auto c = cacheFor(id);
        auto b = bytes(e, input);
        std::lock_guard<std::mutex> l(c->mutex);
        for (auto it = c->blocks.begin(); it != c->blocks.end(); ++it)
            if (it->first == key) {
                c->bytes -= it->second.size();
                c->blocks.erase(it);
                break;
            }
        c->bytes += b.size();
        c->blocks.emplace_front(key, std::move(b));
        while (c->blocks.size() > 8 || c->bytes > 3 * 1024 * 1024) {
            c->bytes -= c->blocks.back().second.size();
            c->blocks.pop_back();
        }
    } catch (const std::exception& x) {
        failure(e, x);
    }
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(cacheRead)(JNIEnv* e, jclass, jlong id, jlong key,
                                                        jbyteArray output, jint offset, jint start,
                                                        jint count) {
    try {
        auto c = cacheFor(id);
        need(output && offset >= 0 && start >= 0 && count >= 0 && offset <= e->GetArrayLength(output) - count,
             "Invalid buffer read");
        std::lock_guard<std::mutex> l(c->mutex);
        for (auto it = c->blocks.begin(); it != c->blocks.end(); ++it)
            if (it->first == key) {
                need(static_cast<size_t>(start) <= it->second.size(), "Read beyond block");
                int n = std::min(count, static_cast<int>(it->second.size()) - start);
                e->SetByteArrayRegion(output, offset, n,
                                      reinterpret_cast<const jbyte*>(it->second.data() + start));
                c->blocks.splice(c->blocks.begin(), c->blocks, it);
                return n;
            }
        return -1;
    } catch (const std::exception& x) {
        failure(e, x);
        return -1;
    }
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(cacheBytes)(JNIEnv* e, jclass, jlong id) {
    try {
        auto c = cacheFor(id);
        std::lock_guard<std::mutex> l(c->mutex);
        return static_cast<jint>(c->bytes);
    } catch (const std::exception& x) {
        failure(e, x);
        return 0;
    }
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(cacheHas)(JNIEnv* e, jclass, jlong id, jlong key) {
    try {
        auto c = cacheFor(id);
        std::lock_guard<std::mutex> l(c->mutex);
        for (auto& b : c->blocks)
            if (b.first == key) return JNI_TRUE;
        return JNI_FALSE;
    } catch (const std::exception& x) {
        failure(e, x);
        return JNI_FALSE;
    }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(cacheClose)(JNIEnv*, jclass, jlong id) {
    std::lock_guard<std::mutex> l(cachesMutex);
    caches.erase(id);
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(autoBitrate)(JNIEnv*, jclass, jlong elapsed, jint previous) {
    if (elapsed > 1200) return 48000;
    if (elapsed > 600) return 96000;
    if (elapsed < 300) return 160000;
    return previous;
}
