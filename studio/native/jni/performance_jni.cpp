#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "studio/host.h"
#include "studio/peaks.h"
#include "studio/stretch.h"
namespace {
studio::Host* H(jlong h) { return reinterpret_cast<studio::Host*>(h); }
void io(JNIEnv* e, const std::string& s) { e->ThrowNew(e->FindClass("java/io/IOException"), s.c_str()); }
std::string path(JNIEnv* e, jbyteArray b) {
    std::string s((size_t)e->GetArrayLength(b), '\0');
    if (!s.empty()) e->GetByteArrayRegion(b, 0, (jsize)s.size(), reinterpret_cast<jbyte*>(&s[0]));
    return s;
}
}  // namespace
extern "C" {
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nChamber(JNIEnv* e,jclass,jlong h,jfloatArray values){
    int count=e->GetArrayLength(values);if(count!=studio::ChamberConfig::kSize){io(e,"Invalid Chamber data.");return;}
    std::vector<float> data((size_t)count);e->GetFloatArrayRegion(values,0,count,data.data());
    if(!H(h)->performance().chamber(data.data(),count))io(e,"The Chamber is busy or its configuration is invalid.");
}
JNIEXPORT jint JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nPadHits(JNIEnv* e,jclass,jlong h,jdoubleArray values){
    int count=e->GetArrayLength(values);if(count<5||count>1+4*256)return 0;
    std::vector<double> data((size_t)count);int n=H(h)->performance().hits(data.data(),count);e->SetDoubleArrayRegion(values,0,1+4*n,data.data());return n;
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nPadsOff(JNIEnv*,jclass,jlong h){H(h)->performance().allOff();}

JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nKeyboard(JNIEnv* e,jclass,jlong h,jobjectArray paths,jint layers,jint releaseMs) {
    const jsize n=e->GetArrayLength(paths);
    if(layers<1||layers>studio::Keyboard::MAX_LAYERS||n!=studio::Keyboard::KEYS*layers){io(e,"Invalid keyboard bank.");return;}
    std::vector<std::string> files;
    for(int i=0;i<n;i++){auto bytes=(jbyteArray)e->GetObjectArrayElement(paths,i);if(!bytes){io(e,"Missing keyboard sample.");return;}files.push_back(path(e,bytes));e->DeleteLocalRef(bytes);}
    std::string error;if(!H(h)->keyboard().load(files,layers,releaseMs,error))io(e,error);
}
JNIEXPORT jboolean JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nNote(JNIEnv*,jclass,jlong h,jint key,jfloat velocity,jboolean on) {return H(h)->keyboard().note(key,velocity,on);}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nNotesOff(JNIEnv*,jclass,jlong h) {H(h)->keyboard().allOff();}

JNIEXPORT jint JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nProcessingLatency(JNIEnv*, jclass, jlong h) {
    return H(h)->processingLatency();
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nTempos(JNIEnv* e, jclass, jlong h, jlongArray frames,
                                                                              jdoubleArray bpms) {
    int n = e->GetArrayLength(frames);
    if (n < 1 || n > 64 || e->GetArrayLength(bpms) != n) {
        io(e, "Invalid tempo map.");
        return;
    }
    jlong f[64];
    jdouble b[64];
    e->GetLongArrayRegion(frames, 0, n, f);
    e->GetDoubleArrayRegion(bpms, 0, n, b);
    for (int k = 0; k < n; k++)
        if (!H(h)->performance().tempoPoint(k, f[k], b[k])) {
            io(e, "The tempo queue is full.");
            return;
        }
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nMonitor(JNIEnv*, jclass, jlong h, jboolean on) {
    H(h)->performance().monitor(on);
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nKit(JNIEnv* e, jclass, jlong h) {
    std::string err;
    if (!H(h)->performance().kit(err)) io(e, err);
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nSample(JNIEnv* e, jclass, jlong h, jint pad, jbyteArray b,
                                                                              jlong offset, jlong length) {
    std::string err, s = path(e, b);
    if (!H(h)->performance().load(pad, s.c_str(), offset, length, err)) io(e, err);
}
JNIEXPORT jboolean JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nPad(JNIEnv*, jclass, jlong h, jint pad, jfloat velocity,
                                                                               jboolean on) {
    return H(h)->performance().trigger(pad, velocity, on);
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nPerformance(JNIEnv* e, jclass, jlong h, jfloatArray data) {
    if (e->GetArrayLength(data) != 7 + 16 * 42) {
        io(e, "Invalid sampler controls.");
        return;
    }
    std::vector<float> v((size_t)e->GetArrayLength(data));
    e->GetFloatArrayRegion(data, 0, (jsize)v.size(), v.data());
    auto& p = H(h)->performance();
    bool ok = p.transport(v[0], (int)v[1], (int)v[2], v[3] != 0, v[4] != 0, (int)v[5], v[6]);
    for (int k = 0; k < 16; k++) {
        ok = p.configure(k, v.data() + 7 + k * 42, 10) && ok;
        ok = p.patternRow(k, v.data() + 17 + k * 42) && ok;
    }
    if (!ok) io(e, "The audio control queue is full; try again.");
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nCountIn(JNIEnv* e, jclass, jlong h, jlong frames) {
    if (!H(h)->post({studio::Msg::CountIn, -1, 0, (long)frames, nullptr})) io(e, "The audio queue is full.");
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nRecord(JNIEnv* e, jclass, jlong h, jbyteArray b, jlong count,
                                                                              jboolean ext) {
    std::string err, s = path(e, b);
    if (!H(h)->record(s.c_str(), count, ext, err)) io(e, err);
}
JNIEXPORT jstring JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nStopRecord(JNIEnv* e, jclass, jlong h, jlongArray result) {
    std::string err;
    long n = H(h)->stopRecord(err);
    jlong v[] = {n, H(h)->recordFirst(), H(h)->recordAnchor()};
    if (e->GetArrayLength(result) >= 3) e->SetLongArrayRegion(result, 0, 3, v);
    return err.empty() ? nullptr : e->NewStringUTF(err.c_str());
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nRecordInfo(JNIEnv* e, jclass, jlong h, jlongArray out) {
    jlong v[] = {H(h)->recordFrames(), H(h)->recordFirst(), H(h)->recordAnchor()};
    if (e->GetArrayLength(out) >= 3) e->SetLongArrayRegion(out, 0, 3, v);
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nRecordStatus(JNIEnv* e, jclass, jlong h, jfloatArray result) {
    jfloat v[] = {H(h)->inputPeak(), (float)H(h)->recordFrames(), (float)H(h)->recordFailure()};
    if (e->GetArrayLength(result) >= 3) e->SetFloatArrayRegion(result, 0, 3, v);
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nExternal(JNIEnv* e, jclass, jlong h, jfloatArray input, jint n,
                                                                                jlong at) {
    if (n < 0 || n > e->GetArrayLength(input)) return;
    // Called by the capture worker, never the audio callback.
    std::vector<float> v(n);
    e->GetFloatArrayRegion(input, 0, n, v.data());
    H(h)->pushExternal(v.data(), n, at);
}
JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeEngine_nRecover(JNIEnv* e, jclass, jbyteArray b) {
    std::string err, s = path(e, b);
    if (!studio::Host::recover(s.c_str(), err)) io(e, err);
}
}

extern "C" JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeStretch_nRun(JNIEnv* e, jclass, jbyteArray in, jbyteArray out,
                                                                                       jlong offset, jlong frames, jdouble ratio,
                                                                                       jdouble pitch) {
    std::string err, a = path(e, in), b = path(e, out);
    if (!studio::stretchFile(a.c_str(), b.c_str(), offset, frames, ratio, pitch, err)) io(e, err);
}

extern "C" JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeStretch_nPeaks(JNIEnv* e, jclass, jbyteArray file) {
    std::string name = path(e, file), err;
    std::unique_ptr<studio::WavReader> r(studio::WavReader::open(name.c_str(), err));
    if (!r) {
        io(e, err);
        return;
    }
    studio::PeakWriter w(r->channels());
    float b[8192];
    for (long at = 0; at < r->frames();) {
        int n = (int)std::min(4096L, r->frames() - at);
        if (r->read(at, b, n) != n) {
            io(e, "Cannot read peaks.");
            return;
        }
        w.add(b, n);
        at += n;
    }
    if (!w.write((name + ".kpk").c_str(), err)) io(e, err);
}
