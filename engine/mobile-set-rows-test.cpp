#include "ggml.h"
#include "ggml-backend.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

int main(int argc, char ** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    const bool i32 = argc > 1 && std::strcmp(argv[1], "--i32") == 0;
    ggml_backend_load_all();
    auto gpu = ggml_backend_init_by_name("Vulkan0", nullptr);
    if (!gpu) return 2;
    auto ctx = ggml_init({4 * 1024 * 1024, nullptr, true});
    constexpr int width = 128, capacity = 4096, heads = 8, position = 62;
    auto cache = ggml_new_tensor_4d(ctx, GGML_TYPE_F16, width, capacity, heads, 1);
    auto values = ggml_new_tensor_4d(ctx, GGML_TYPE_F32, width, 1, heads, 1);
    auto indices = ggml_new_tensor_3d(ctx, i32 ? GGML_TYPE_I32 : GGML_TYPE_I64, 1, 1, 1);
    auto result = ggml_set_rows(ctx, cache, values, indices);
    auto graph = ggml_new_graph_custom(ctx, 32, false);
    ggml_build_forward_expand(graph, result);
    auto buffer = ggml_backend_alloc_ctx_tensors(ctx, gpu);
    if (!buffer) return 3;
    ggml_backend_buffer_clear(buffer, 0);
    std::vector<float> input(width * heads);
    for (size_t i = 0; i < input.size(); ++i) input[i] = (int(i % 31) - 15) / 16.0f;
    const int64_t index64 = position;
    const int32_t index32 = position;
    ggml_backend_tensor_set(values, input.data(), 0, input.size() * sizeof(float));
    ggml_backend_tensor_set(indices, i32 ? static_cast<const void *>(&index32) : &index64, 0, ggml_nbytes(indices));
    std::printf("SET_ROWS index=%s supported=%d\n", i32 ? "i32" : "i64", ggml_backend_supports_op(gpu, result));
    if (ggml_backend_graph_compute(gpu, graph) != GGML_STATUS_SUCCESS) return 4;
    std::puts("Graph submitted");
    std::vector<ggml_fp16_t> output(width * capacity * heads);
    ggml_backend_tensor_get(cache, output.data(), 0, output.size() * sizeof(ggml_fp16_t));
    bool correct = true;
    for (int h = 0; h < heads; ++h) {
        for (int r = 0; r < capacity; ++r) {
            for (int k = 0; k < width; ++k) {
                const float expected = r == position ? input[h * width + k] : 0.0f;
                const float actual = ggml_fp16_to_fp32(output[(h * capacity + r) * width + k]);
                correct = correct && std::isfinite(actual) && actual == expected;
            }
        }
    }
    std::printf("%s cache row write and untouched rows\n", correct ? "PASS" : "FAIL");
    ggml_backend_buffer_free(buffer);
    ggml_free(ctx);
    ggml_backend_free(gpu);
    return correct ? 0 : 1;
}
