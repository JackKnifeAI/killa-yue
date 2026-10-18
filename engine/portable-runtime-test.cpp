// Run with LD_LIBRARY_PATH containing the portable libs and libc++_shared.so.
// Arguments: BACKEND_DIRECTORY [JNI_LIBRARY]. CPU-only, well below 10 MiB working data.
#include "ggml.h"
#include "ggml-backend.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <string>
#include <vector>

static bool matrix(ggml_backend_t backend, ggml_type type, int columns) {
    const int inner = 256, rows = 16;
    ggml_context * ctx = ggml_init({1024 * 1024, nullptr, true});
    auto * a = ggml_new_tensor_2d(ctx, type, inner, rows);
    auto * b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, inner, columns);
    auto * out = ggml_mul_mat(ctx, a, b);
    auto * graph = ggml_new_graph_custom(ctx, 32, false);
    ggml_build_forward_expand(graph, out);
    auto * buffer = ggml_backend_alloc_ctx_tensors(ctx, backend);
    if (!buffer) { ggml_free(ctx); return false; }
    std::vector<float> av(inner * rows), bv(inner * columns), got(rows * columns);
    for (size_t i = 0; i < av.size(); ++i) av[i] = (int((i * 17 + i / inner * 3) % 31) - 15) / 16.0f;
    // Values are exactly representable by the CPU's intermediate Q8 activation format.
    for (int c = 0; c < columns; ++c) for (int k = 0; k < inner; ++k)
        bv[c * inner + k] = (k % 3 ? 1 : -1) * (c + 1) / 8.0f;
    std::vector<unsigned char> packed(ggml_nbytes(a));
    if (type == GGML_TYPE_F32) std::memcpy(packed.data(), av.data(), packed.size());
    else {
        const auto * traits = ggml_get_type_traits(type);
        traits->from_float_ref(av.data(), packed.data(), av.size());
        traits->to_float(packed.data(), av.data(), av.size());
    }
    ggml_backend_tensor_set(a, packed.data(), 0, packed.size());
    ggml_backend_tensor_set(b, bv.data(), 0, bv.size() * sizeof(float));
    bool pass = ggml_backend_graph_compute(backend, graph) == GGML_STATUS_SUCCESS;
    ggml_backend_tensor_get(out, got.data(), 0, got.size() * sizeof(float));
    float max_error = 0;
    for (int c = 0; c < columns; ++c) for (int r = 0; r < rows; ++r) {
        float expected = 0;
        for (int k = 0; k < inner; ++k) expected += av[r * inner + k] * bv[c * inner + k];
        float error = std::fabs(got[c * rows + r] - expected);
        max_error = std::fmax(max_error, error);
        pass = pass && std::isfinite(got[c * rows + r]) && error < 0.002f + 0.0002f * std::fabs(expected);
    }
    std::printf("%s %s columns=%d max_error=%.7g\n", pass ? "PASS" : "FAIL", ggml_type_name(type), columns, max_error);
    ggml_backend_buffer_free(buffer);
    ggml_free(ctx);
    return pass;
}

int main(int argc, char ** argv) {
    if (argc < 2 || argc > 3) return 2;
    setenv("GGML_DISABLE_VULKAN", "1", 1);
    unsetenv("GGML_BACKEND_PATH");
    if (argc == 3) {
        void * jni = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
        if (!jni) { std::fprintf(stderr, "JNI load failed: %s\n", dlerror()); return 3; }
        if (!dlsym(jni, "Java_ai_jackknife_killayue_EngineHost_runEngine")) return 3;
        std::puts("PASS JNI library dependencies and exported entry point");
    }
    ggml_backend_load_all_from_path(argv[1]);
    if (ggml_backend_dev_count() != 1 || !ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
        std::fprintf(stderr, "Expected exactly one CPU device\n"); return 4;
    }
    ggml_backend_t cpu = ggml_backend_init_by_name("CPU", nullptr);
    if (!cpu) return 4;
    auto reg = ggml_backend_dev_backend_reg(ggml_backend_get_device(cpu));
    auto threads = reinterpret_cast<void (*)(ggml_backend_t, int)>(ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads"));
    if (!threads) return 4;
    threads(cpu, 1);
    bool pass = true;
    for (auto type : {GGML_TYPE_F32, GGML_TYPE_F16, GGML_TYPE_Q5_K, GGML_TYPE_Q6_K, GGML_TYPE_Q8_0})
        for (int columns : {1, 8}) pass = matrix(cpu, type, columns) && pass;
    std::ifstream maps("/proc/self/maps");
    if (!maps) return 5;
    std::string line, selected;
    while (std::getline(maps, line)) {
        if (line.find("libkycpu-") != std::string::npos) selected = line.substr(line.find_last_of('/') + 1);
        if (line.find("vulkan") != std::string::npos || line.find("libturnip") != std::string::npos) {
            std::printf("FAIL CPU process loaded GPU library: %s\n", line.c_str()); pass = false;
        }
    }
    if (selected.empty()) pass = false;
    std::printf("CPU module: %s\n", selected.c_str());
    ggml_backend_free(cpu);
    std::printf("%s CPU runtime and GPU isolation\n", pass ? "PASS" : "FAIL");
    return pass ? 0 : 1;
}
