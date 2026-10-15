// JNI for ai.jackknife.killayue.studio.NativeEngine: a thin layer over studio::Host, which holds the logic
// (and its tests).
#include <jni.h>
#include <string>
#include <vector>
#include "studio/host.h"

using studio::Host;

namespace {

Host* host(jlong h) { return reinterpret_cast<Host*>(h); }

void throwIo(JNIEnv* e, const std::string& m) {
    jclass c = e->FindClass("java/io/IOException");
    if (c) e->ThrowNew(c, m.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nOpen(JNIEnv* e, jclass, jboolean device, jboolean mic, jint rate) {
    std::string err;
    Host* h = device ? Host::withDevice(studio::Device::create(), mic, err) : Host::offline(rate);
    if (!h) throwIo(e, err.empty() ? "The audio device could not be opened." : err);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nClose(JNIEnv*, jclass, jlong h) { delete host(h); }

JNIEXPORT jint JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nRate(JNIEnv*, jclass, jlong h) { return host(h)->rate(); }

JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nStart(JNIEnv* e, jclass, jlong h) {
    std::string err;
    if (!host(h)->start(err)) throwIo(e, err);
}

JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nLoad(JNIEnv* e, jclass, jlong h, jfloatArray gain, jfloatArray pan,
                                                                          jbooleanArray mute, jbooleanArray solo, jintArray clipTrack,
                                                                          jobjectArray files, jlongArray start, jlongArray offset,
                                                                          jlongArray length, jfloatArray clipGain) {
    const jsize tracks = e->GetArrayLength(gain), clips = e->GetArrayLength(clipTrack);
    std::vector<jfloat> g(tracks), p(tracks), cg(clips);
    std::vector<jboolean> m(tracks), s(tracks);
    std::vector<jint> ct(clips);
    std::vector<jlong> st(clips), of(clips), ln(clips);
    e->GetFloatArrayRegion(gain, 0, tracks, g.data());
    e->GetFloatArrayRegion(pan, 0, tracks, p.data());
    e->GetBooleanArrayRegion(mute, 0, tracks, m.data());
    e->GetBooleanArrayRegion(solo, 0, tracks, s.data());
    e->GetIntArrayRegion(clipTrack, 0, clips, ct.data());
    e->GetLongArrayRegion(start, 0, clips, st.data());
    e->GetLongArrayRegion(offset, 0, clips, of.data());
    e->GetLongArrayRegion(length, 0, clips, ln.data());
    e->GetFloatArrayRegion(clipGain, 0, clips, cg.data());
    std::vector<studio::TrackSpec> spec;
    for (jsize i = 0; i < tracks; i++) spec.push_back({g[i], p[i], m[i] != 0, s[i] != 0, {}});
    for (jsize k = 0; k < clips; k++) {
        if (ct[k] < 0 || ct[k] >= tracks) {
            throwIo(e, "A clip belongs to no track.");
            return;
        }
        jstring js = (jstring)e->GetObjectArrayElement(files, k);
        const char* c = js ? e->GetStringUTFChars(js, nullptr) : nullptr;
        spec[ct[k]].clips.push_back({c ? c : "", (long)st[k], (long)of[k], (long)ln[k], cg[k]});
        if (c) e->ReleaseStringUTFChars(js, c);
        if (js) e->DeleteLocalRef(js);
    }
    std::string err;
    if (!host(h)->load(spec, err)) throwIo(e, err);
}

JNIEXPORT jboolean JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nPost(JNIEnv*, jclass, jlong h, jint kind, jint track, jfloat value,
                                                                              jlong frame) {
    using studio::Msg;
    // only the edits Java may send: never Swap (sessions come from load) or Offline (export owns it)
    static const Msg kinds[] = {Msg::Gain, Msg::Pan, Msg::Mute, Msg::Solo, Msg::Master, Msg::Play, Msg::Stop, Msg::Seek};
    Msg m;
    if (kind >= 0 && kind < (jint)(sizeof kinds / sizeof kinds[0])) m = kinds[kind];
    else if (kind == (jint)Msg::Reset) m = Msg::Reset;
    else return JNI_FALSE;
    return host(h)->post({m, track, value, (long)frame, nullptr}) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nStatus(JNIEnv* e, jclass, jlong h, jdoubleArray out) {
    const studio::Status s = host(h)->status();
    const studio::DeviceStats d = host(h)->deviceStats();
    const jdouble v[] = {(double)s.position, (double)s.underruns, (double)s.refused, (double)s.playing, s.peakL, s.peakR,
                         (double)d.rate, (double)d.burst, (double)d.bufferFrames, (double)d.inputChannels, (double)d.reopened,
                         d.running ? 1.0 : 0.0, d.exclusive ? 1.0 : 0.0, (double)d.xruns, (double)d.callbacks,
                         (double)d.slowCallbacks, d.callbackMaxMs, d.callbackAvgMs, d.outputLatencyMs};
    const jsize n = (jsize)(sizeof v / sizeof v[0]);
    if (e->GetArrayLength(out) >= n) e->SetDoubleArrayRegion(out, 0, n, v);
    return d.error.empty() ? nullptr : e->NewStringUTF(d.error.c_str());
}

JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nRender(JNIEnv* e, jclass, jlong h, jfloatArray out, jint frames) {
    std::vector<float> buf(2 * (size_t)frames);
    host(h)->render(buf.data(), frames);
    e->SetFloatArrayRegion(out, 0, 2 * frames, buf.data());
}

JNIEXPORT jlong JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nLatency(JNIEnv* e, jclass, jlong h, jint probes) {
    std::string err;
    const studio::RoundTrip rt = host(h)->measureLatency(probes, err);
    if (!rt.ok) {
        throwIo(e, err);
        return -1;
    }
    return rt.frames;
}

}  // extern "C"
