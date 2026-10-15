// JNI for ai.jackknife.killayue.studio.NativeStem, run in the app's ":ai" process.
#include <jni.h>
#include <atomic>
#include <memory>
#include <string>
#include "studio/et_model.h"
#include "studio/stems.h"

namespace {

struct Handle {
    studio::EtStemModel model;
    std::unique_ptr<studio::StemJob> job;
    std::atomic<bool> cancel{false};
};

std::string bytes(JNIEnv* e, jbyteArray a) {
    std::string s((size_t)e->GetArrayLength(a), '\0');
    e->GetByteArrayRegion(a, 0, (jsize)s.size(), reinterpret_cast<jbyte*>(&s[0]));
    return s;
}

void throwIo(JNIEnv* e, const std::string& m) {
    jclass c = e->FindClass("java/io/IOException");
    if (c) e->ThrowNew(c, m.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_ai_jackknife_killayue_studio_NativeStem_nVulkan(JNIEnv*, jclass) {
    return studio::EtStemModel::vulkan() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_ai_jackknife_killayue_studio_NativeStem_nOpen(JNIEnv* e, jclass, jbyteArray model) {
    std::unique_ptr<Handle> h(new Handle());
    std::string err;
    if (!h->model.load(bytes(e, model).c_str(), err)) {
        throwIo(e, "The stems model would not load: " + err);
        return 0;
    }
    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeStem_nSplit(JNIEnv* e, jclass, jlong hp, jbyteArray song, jbyteArray out, jbyteArray work,
                                                                         jint rate) {
    Handle* h = reinterpret_cast<Handle*>(hp);
    h->cancel.store(false);
    h->job.reset(new studio::StemJob(bytes(e, song), bytes(e, out), bytes(e, work), rate));
    std::string err;
    if (!h->job->run(h->model, &h->cancel, err)) throwIo(e, err);
}

JNIEXPORT jdouble JNICALL Java_ai_jackknife_killayue_studio_NativeStem_nProgress(JNIEnv*, jclass, jlong hp) {
    Handle* h = reinterpret_cast<Handle*>(hp);
    return h->job ? h->job->progress() : 0.0;
}

JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeStem_nCancel(JNIEnv*, jclass, jlong hp) {
    reinterpret_cast<Handle*>(hp)->cancel.store(true);
}

JNIEXPORT void JNICALL Java_ai_jackknife_killayue_studio_NativeStem_nClose(JNIEnv*, jclass, jlong hp) { delete reinterpret_cast<Handle*>(hp); }

}  // extern "C"
