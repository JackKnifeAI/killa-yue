// Runs llama.cpp's server inside the app's ":writer" process for the songwriter (WriterHost).
//
// llama.cpp builds its server as a library (llama-server-impl) with an entry point, llama_server(argc, argv).
// This bridge sends the server's output to a log file the app reads and calls it. The server listens on
// 127.0.0.1 only, with an API key the app makes up for each run.
#include <jni.h>
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
#include <string>
#include <vector>

int llama_server(int argc, char ** argv);

static std::string str(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_jackknife_killayue_WriterHost_runWriter(JNIEnv * env, jclass, jobjectArray args, jstring logPath) {
    int fd = open(str(env, logPath).c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        dup2(fd, 1);
        dup2(fd, 2);
        close(fd);
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IOLBF, 0);
    }
    std::vector<std::string> keep;
    for (jsize i = 0; i < env->GetArrayLength(args); i++) keep.push_back(str(env, (jstring) env->GetObjectArrayElement(args, i)));
    std::vector<char *> argv;
    for (auto & s : keep) argv.push_back((char *) s.c_str());
    argv.push_back(nullptr);
    int code = llama_server((int) keep.size(), argv.data());
    fprintf(stderr, "[App] Songwriter exited with code %d\n", code);
    fflush(stderr);
    return code;
}
