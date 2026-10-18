#include "context-budget.h"
#include <cstdio>
#include <limits>
#include <initializer_list>

int main() {
    struct Case { size_t prefix; int request, context, expected; };
    const Case cases[] = {
        {62, 4096, 4096, 4033}, // S20's formerly failing Short songs preset
        {62, 4096, 8192, 4096}, // Normal mode keeps the existing ceiling
        {561, 250, 4096, 250}, // Ten-second semantic stage remains unchanged
        {4094, 4096, 4096, 1},
        {4095, 4096, 4096, 0},
        {4096, 4096, 4096, 0},
        {0, 1, 2, 1},
        {0, 4096, 0, 0},
        {0, 4096, 1, 0},
        {0, 4096, -1, 0},
        {0, 0, 4096, 0},
        {0, -1, 4096, 0},
        {std::numeric_limits<size_t>::max(), 4096, 4096, 0},
        {static_cast<size_t>(std::numeric_limits<int>::max()), 4096, 4096, 0},
    };
    for (const auto & c : cases) {
        int actual = yue2_context_token_budget(c.prefix, c.request, c.context);
        if (actual != c.expected) {
            std::fprintf(stderr, "prefix=%zu request=%d context=%d: expected %d, got %d\n",
                         c.prefix, c.request, c.context, c.expected, actual);
            return 1;
        }
    }
    // Exercise boundary combinations and guarantee that successful budgets
    // always fit, including the end token, without increasing the request.
    for (int context = 2; context <= 8192; context += 31) {
        for (size_t prefix : {size_t(0), size_t(context / 2), size_t(context - 2), size_t(context - 1)}) {
            for (int request : {1, 250, 4096, 8192}) {
                int actual = yue2_context_token_budget(prefix, request, context);
                if (actual < 0 || actual > request || (actual && prefix + actual + 1 > size_t(context))) return 2;
            }
        }
    }
    std::puts("PASS context budgets, overflow rejection, and boundary combinations");
    return 0;
}
