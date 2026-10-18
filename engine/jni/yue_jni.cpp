// Runs the yue2.cpp server inside the app's ":engine" process instead of as a child process,
// so Android's limits on apps' child processes never apply to it.
//
// yue-server.cpp is compiled with -Dmain=yue_server_main into libyueengine.so. This bridge sets the
// environment (Vulkan driver, gentle-mode settings), sends the engine's stdout and stderr to a log file
// that the app's screen process reads, lowers the priority in gentle mode, and calls the engine's main.
#include <jni.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <unistd.h>
#include <string>
#include <vector>

// yue-server.cpp is C++, so its renamed main has C++ linkage
int yue_server_main(int argc, char ** argv);

static std::string str(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_jackknife_killayue_EngineHost_runEngine(JNIEnv * env, jclass, jobjectArray args, jobjectArray vars,
                                                   jstring logPath, jint nice) {
    // An app process may inherit environment variables. Only this launch picks a driver.
    for (const char * name : {"VK_DRIVER_FILES", "VK_ICD_FILENAMES", "GGML_DISABLE_VULKAN",
                             "GGML_BACKEND_PATH", "YUE_BACKEND_DIR", "TU_KGSL_PRIORITY"}) unsetenv(name);
    // environment: "NAME=value" pairs, set before the Vulkan loader reads them
    for (jsize i = 0; i < env->GetArrayLength(vars); i++) {
        std::string kv = str(env, (jstring) env->GetObjectArrayElement(vars, i));
        size_t eq = kv.find('=');
        if (eq != std::string::npos) setenv(kv.substr(0, eq).c_str(), kv.substr(eq + 1).c_str(), 1);
    }
    // the engine's output goes to the log file, line by line
    int fd = open(str(env, logPath).c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        dup2(fd, 1);
        dup2(fd, 2);
        close(fd);
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IOLBF, 0);
    }
    if (nice > 0) setpriority(PRIO_PROCESS, 0, nice);  // threads the engine starts inherit it
    std::vector<std::string> keep;
    for (jsize i = 0; i < env->GetArrayLength(args); i++) keep.push_back(str(env, (jstring) env->GetObjectArrayElement(args, i)));
    std::vector<char *> argv;
    for (auto & s : keep) argv.push_back((char *) s.c_str());
    argv.push_back(nullptr);
    int code = yue_server_main((int) keep.size(), argv.data());
    fprintf(stderr, "[App] Engine exited with code %d\n", code);
    fflush(stderr);
    return code;
}
