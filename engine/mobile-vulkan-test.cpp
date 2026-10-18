// Build with build-mobile-vulkan-test.sh, then run on the target phone with
// the same Vulkan loader/driver environment as the app.
#include "ggml.h"
#include "ggml-backend.h"
#include "weight-ctx.h"

#include <cmath>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

static int failures = 0;
static void check(bool pass, const char * name) {
    std::printf("%s %s\n", pass ? "PASS" : "FAIL", name);
    if (!pass) ++failures;
}

int main(int argc, char ** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    // Model a constrained device even when this regression test runs on a
    // desktop GPU. This affects only this test process, never the application.
    setenv("GGML_VK_FORCE_MAX_BUFFER_SIZE", "134217728", 1);
    ggml_backend_load_all();
    ggml_backend_t gpu = ggml_backend_init_by_name("Vulkan0", nullptr);
    if (!gpu) { std::fprintf(stderr, "Vulkan0 unavailable\n"); return 2; }

    ggml_context * meta = ggml_init({4 * 1024 * 1024, nullptr, true});
    ggml_tensor * large = ggml_new_tensor_2d(meta, GGML_TYPE_F32, 512, 65537);
    ggml_tensor * ops[] = {
        large,
        ggml_view_2d(meta, large, 512, 65537, large->nb[1], 0),
        ggml_reshape_1d(meta, large, 512LL * 65537),
        ggml_permute(meta, large, 1, 0, 2, 3),
        ggml_transpose(meta, large),
    };
    for (auto * op : ops) {
        check(ggml_backend_supports_op(gpu, op), ggml_op_name(op->op));
    }
    check(!ggml_backend_supports_op(gpu, ggml_add(meta, large, large)),
          "oversized ADD still rejected");
    check(!ggml_backend_supports_op(gpu, ggml_rms_norm(meta, large, 1e-5f)),
          "oversized RMS_NORM still rejected");
    ggml_free(meta);
    if (argc > 1 && std::strcmp(argv[1], "--metadata-only") == 0) {
        ggml_backend_free(gpu);
        return failures ? 1 : 0;
    }
    if (argc > 1 && std::strcmp(argv[1], "--weights") == 0) {
        for (int pass = 0; pass < 3; ++pass) {
            WeightCtx weights;
            wctx_init(&weights, 5);
            auto * q5 = ggml_new_tensor_2d(weights.ctx, GGML_TYPE_Q5_K, 256, 16);
            auto * q6 = ggml_new_tensor_2d(weights.ctx, GGML_TYPE_Q6_K, 256, 16);
            auto * large = ggml_new_tensor_2d(weights.ctx, GGML_TYPE_F16, 512, 131073);
            std::vector<float> values(256 * 16), reference(values.size());
            for (size_t i = 0; i < values.size(); ++i) values[i] = (int(i % 31) - 15) / 16.0f;
            std::vector<unsigned char> packed5(ggml_nbytes(q5)), packed6(ggml_nbytes(q6));
            ggml_get_type_traits(GGML_TYPE_Q5_K)->from_float_ref(values.data(), packed5.data(), values.size());
            ggml_get_type_traits(GGML_TYPE_Q6_K)->from_float_ref(values.data(), packed6.data(), values.size());
            // Two partial copies exercise the same offsets as fused weights.
            const size_t half = packed5.size() / 2;
            weights.pending.push_back({q5, packed5.data(), half, 0});
            weights.pending.push_back({q5, packed5.data() + half, half, half});
            weights.pending.push_back({q6, packed6.data(), packed6.size(), 0});
            check(wctx_alloc(&weights, gpu), "mixed weight allocation");
            check(large->buffer && ggml_backend_buffer_is_host(large->buffer), "oversized matrix remains in CPU memory");
            check(q5->type == GGML_TYPE_F16 && q6->type == GGML_TYPE_F16 &&
                  !ggml_backend_buffer_is_host(q5->buffer) && !ggml_backend_buffer_is_host(q6->buffer),
                  "Q5_K and Q6_K expand to GPU F16");
            std::vector<ggml_fp16_t> expected(values.size()), actual(values.size());
            for (auto * t : {q5, q6}) {
                const ggml_type source_type = t == q5 ? GGML_TYPE_Q5_K : GGML_TYPE_Q6_K;
                ggml_get_type_traits(source_type)->to_float(t == q5 ? packed5.data() : packed6.data(), reference.data(), reference.size());
                ggml_fp32_to_fp16_row(reference.data(), expected.data(), reference.size());
                ggml_backend_tensor_get(t, actual.data(), 0, actual.size() * sizeof(ggml_fp16_t));
                check(actual == expected, "expanded data equals CPU dequantization including fused offsets");
            }
            check(wctx_buffer_bytes(&weights) >= ggml_nbytes(q5) + ggml_nbytes(q6) + ggml_nbytes(large), "memory accounting includes both buffers");
            wctx_free(&weights);
            check(!weights.ctx && !weights.buffer && !weights.cpu_buffer, "both weight buffers released");
        }
        ggml_backend_free(gpu);
        return failures ? 1 : 0;
    }

    // Execute a real GPU kernel directly (no scheduler or CPU fallback) and
    // compare against a simple exact reference, beyond just supports_op().
    ggml_context * ctx = ggml_init({4 * 1024 * 1024, nullptr, true});
    int columns = 8;
    int inner = 256, rows = 16;
    ggml_type type = GGML_TYPE_F32;
    bool add = false;
    bool dequant = false;
    bool expect_fallback = false;
    bool benchmark = false;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--cols") == 0 && i + 1 < argc) columns = std::atoi(argv[++i]);
        else if (std::strcmp(argv[i], "--inner") == 0 && i + 1 < argc) inner = std::atoi(argv[++i]);
        else if (std::strcmp(argv[i], "--rows") == 0 && i + 1 < argc) rows = std::atoi(argv[++i]);
        else if (std::strcmp(argv[i], "--add") == 0) add = true;
        else if (std::strcmp(argv[i], "--dequant") == 0) dequant = true;
        else if (std::strcmp(argv[i], "--expect-fallback") == 0) expect_fallback = true;
        else if (std::strcmp(argv[i], "--benchmark") == 0) benchmark = true;
        else if (std::strcmp(argv[i], "--type") == 0 && i + 1 < argc) {
            const char * name = argv[++i];
            if (std::strcmp(name, "f32") == 0) type = GGML_TYPE_F32;
            else if (std::strcmp(name, "f16") == 0) type = GGML_TYPE_F16;
            else if (std::strcmp(name, "q5_k") == 0) type = GGML_TYPE_Q5_K;
            else if (std::strcmp(name, "q6_k") == 0) type = GGML_TYPE_Q6_K;
            else return 4;
        } else return 4;
    }
    if (columns < 1 || columns > 256) return 4;
    if (inner < 256 || inner > 8192 || inner % 256 || rows < 1 || rows > 16384) return 4;
    if (add && type != GGML_TYPE_F32) return 4;
    std::printf("MATRIX type=%s inner=%d rows=%d columns=%d\n", ggml_type_name(type), inner, rows, columns);
    ggml_tensor * a = ggml_new_tensor_2d(ctx, type, inner, rows);
    ggml_tensor * b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, inner, columns);
    ggml_tensor * indices = dequant ? ggml_new_tensor_1d(ctx, GGML_TYPE_I32, rows) : nullptr;
    ggml_tensor * result = dequant ? ggml_get_rows(ctx, a, indices) : (add ? ggml_add(ctx, a, a) : ggml_mul_mat(ctx, a, b));
    if (expect_fallback) {
        check(!ggml_backend_supports_op(gpu, result), "unsafe operation delegates to CPU");
        ggml_free(ctx);
        ggml_backend_free(gpu);
        return failures ? 1 : 0;
    }
    check(ggml_backend_supports_op(gpu, result), "GPU supports requested operation");
    if (failures) { ggml_free(ctx); ggml_backend_free(gpu); return 1; }
    ggml_cgraph * graph = ggml_new_graph_custom(ctx, 32, false);
    ggml_build_forward_expand(graph, result);
    ggml_backend_buffer_t buffer = ggml_backend_alloc_ctx_tensors(ctx, gpu);
    if (!buffer) { std::fprintf(stderr, "GPU allocation failed\n"); return 3; }
    std::vector<float> av(inner * rows), bv(inner * columns), out(add || dequant ? inner * rows : rows * columns);
    for (size_t i = 0; i < av.size(); ++i) av[i] = benchmark ? 0.5f : (int((i * 17 + i / inner * 3) % 31) - 15) / 16.0f;
    for (size_t i = 0; i < bv.size(); ++i) bv[i] = benchmark ? 0.25f : (int((i * 13 + i / inner * 5) % 23) - 11) / 16.0f;
    std::vector<unsigned char> packed(ggml_nbytes(a)), roundtrip(packed.size());
    if (type == GGML_TYPE_F32) std::memcpy(packed.data(), av.data(), packed.size());
    else {
        const ggml_type_traits * traits = ggml_get_type_traits(type);
        traits->from_float_ref(av.data(), packed.data(), av.size());
        traits->to_float(packed.data(), av.data(), av.size());
    }
    ggml_backend_tensor_set(a, packed.data(), 0, packed.size());
    ggml_backend_tensor_get(a, roundtrip.data(), 0, roundtrip.size());
    check(packed == roundtrip, "GPU buffer upload/readback");
    ggml_backend_tensor_set(b, bv.data(), 0, bv.size() * sizeof(float));
    if (indices) {
        std::vector<int32_t> idx(rows);
        for (int i = 0; i < rows; ++i) idx[i] = i;
        ggml_backend_tensor_set(indices, idx.data(), 0, idx.size() * sizeof(int32_t));
    }
    for (int repeat = 0; repeat < (benchmark ? 3 : 1); ++repeat) {
        const auto start = std::chrono::steady_clock::now();
        check(ggml_backend_graph_compute(gpu, graph) == GGML_STATUS_SUCCESS, "GPU graph submitted");
        ggml_backend_tensor_get(result, out.data(), 0, out.size() * sizeof(float));
        std::printf("GPU iteration %d: %.3f ms\n", repeat + 1,
                    std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
    }
    bool correct = true;
    float max_error = 0;
    for (size_t i = 0; i < out.size(); ++i) {
        float expected = dequant ? av[i] : (add ? av[i] * 2 : 0);
        if (!add && !dequant) {
            if (benchmark) expected = inner * 0.125f;
            else for (int k = 0; k < inner; ++k) expected += av[(i % rows) * inner + k] * bv[(i / rows) * inner + k];
        }
        const float error = std::fabs(out[i] - expected);
        max_error = std::fmax(max_error, error);
        if (i < 8) std::printf("OUTPUT %zu: got=%.6g expected=%.6g\n", i, out[i], expected);
        correct = correct && std::isfinite(out[i]) && error < 0.03f + 0.002f * std::fabs(expected);
    }
    std::printf("Maximum absolute error: %.6g\n", max_error);
    check(correct, "GPU output equals reference");
    ggml_backend_buffer_free(buffer);
    ggml_free(ctx);
    ggml_backend_free(gpu);
    return failures ? 1 : 0;
}
