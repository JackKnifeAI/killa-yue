// Model-free checks using the packaged portable GGML backend. No scheduler fallback.
// The parent app supervises this disposable process and imposes a wall-clock limit.
#include "ggml.h"
#include "ggml-backend.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static bool matrix(ggml_backend_t backend, ggml_type type, int columns) {
    constexpr int inner = 256, rows = 16;
    const char * name = ggml_type_name(type);
    std::printf("KYTEST {\"test\":\"%s_%d\",\"status\":\"started\"}\n", name, columns);
    ggml_context * ctx = ggml_init({1024 * 1024, nullptr, true});
    if (!ctx) return false;
    auto * a = ggml_new_tensor_2d(ctx, type, inner, rows);
    auto * b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, inner, columns);
    auto * out = ggml_mul_mat(ctx, a, b);
    if (!ggml_backend_supports_op(backend, out)) {
        std::printf("KYTEST {\"test\":\"%s_%d\",\"status\":\"unsupported\"}\n", name, columns);
        ggml_free(ctx);
        return false;
    }
    auto * graph = ggml_new_graph_custom(ctx, 32, false);
    ggml_build_forward_expand(graph, out);
    auto * buffer = ggml_backend_alloc_ctx_tensors(ctx, backend);
    if (!buffer) { ggml_free(ctx); return false; }
    std::vector<float> av(inner * rows), bv(inner * columns), got(rows * columns);
    for (size_t i = 0; i < av.size(); ++i)
        av[i] = (int((i * 17 + i / inner * 3) % 31) - 15) / 16.0f;
    // Exactly representable by the CPU backend's intermediate Q8 activation format.
    for (int c = 0; c < columns; ++c) for (int k = 0; k < inner; ++k)
        bv[c * inner + k] = (k % 3 ? 1 : -1) * (c + 1) / 8.0f;
    std::vector<unsigned char> packed(ggml_nbytes(a)), readback(packed.size());
    if (type == GGML_TYPE_F32) std::memcpy(packed.data(), av.data(), packed.size());
    else {
        const auto * traits = ggml_get_type_traits(type);
        traits->from_float_ref(av.data(), packed.data(), av.size());
        traits->to_float(packed.data(), av.data(), av.size());
    }
    ggml_backend_tensor_set(a, packed.data(), 0, packed.size());
    ggml_backend_tensor_get(a, readback.data(), 0, readback.size());
    ggml_backend_tensor_set(b, bv.data(), 0, bv.size() * sizeof(float));
    bool pass = packed == readback;
    const bool submitted = ggml_backend_graph_compute(backend, graph) == GGML_STATUS_SUCCESS;
    pass = pass && submitted;
    float max_error = 0;
    bool finite = true;
    if (submitted) {
        ggml_backend_tensor_get(out, got.data(), 0, got.size() * sizeof(float));
        for (int c = 0; c < columns; ++c) for (int r = 0; r < rows; ++r) {
            double expected = 0;
            for (int k = 0; k < inner; ++k)
                expected += double(av[r * inner + k]) * bv[c * inner + k];
            const float actual = got[c * rows + r];
            const double error = std::fabs(double(actual) - expected);
            finite = finite && std::isfinite(actual);
            if (std::isfinite(error)) max_error = std::fmax(max_error, float(error));
            pass = pass && std::isfinite(actual) && error <= 0.03 + 0.002 * std::fabs(expected);
        }
    }
    std::printf("KYTEST {\"test\":\"%s_%d\",\"status\":\"%s\",\"finite\":%s,\"max_error\":%.8g}\n",
                name, columns, pass ? "pass" : "fail", finite ? "true" : "false", max_error);
    ggml_backend_buffer_free(buffer);
    ggml_free(ctx);
    return pass;
}

int main(int argc, char ** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc != 3 || (std::strcmp(argv[2], "CPU") && std::strcmp(argv[2], "Vulkan0"))) return 64;
    ggml_backend_load_all_from_path(argv[1]);
    ggml_backend_t backend = ggml_backend_init_by_name(argv[2], nullptr);
    if (!backend) {
        std::puts("KYTEST {\"test\":\"backend\",\"status\":\"unavailable\"}");
        return 2;
    }
    auto * device = ggml_backend_get_device(backend);
    const bool cpu = std::strcmp(argv[2], "CPU") == 0;
    if ((ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_CPU) != cpu) return 3;
    if (cpu) {
        auto reg = ggml_backend_dev_backend_reg(device);
        auto threads = reinterpret_cast<void (*)(ggml_backend_t, int)>(
            ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads"));
        if (!threads) return 3;
        threads(backend, 1);
    }
    for (auto type : {GGML_TYPE_F32, GGML_TYPE_F16, GGML_TYPE_Q5_K, GGML_TYPE_Q6_K})
        for (int columns : {1, 8}) {
            if (!matrix(backend, type, columns)) { ggml_backend_free(backend); return 1; }
        }
    ggml_backend_free(backend);
    return 0;
}
