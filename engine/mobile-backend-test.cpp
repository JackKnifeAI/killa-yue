#include "backend.h"
#include <cstdio>
#include <cstring>

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    if (!backend_set_device("Vulkan0") || !g_backend_cpu_ar) return 2;
    for (int pass = 0; pass < 3; ++pass) {
        BackendPair kv = backend_init("KV"), lm = backend_init("LM");
        BackendPair nar = backend_init("NAR"), vae = backend_init("VAE");
        if (kv.has_gpu || lm.has_gpu || kv.backend != lm.backend ||
            !nar.has_gpu || !vae.has_gpu || nar.backend != vae.backend ||
            nar.backend == lm.backend || std::strcmp(ggml_backend_name(lm.backend), "CPU")) return 1;
        backend_release(kv.backend, kv.cpu_backend);
        backend_release(nar.backend, nar.cpu_backend);
        if (std::strcmp(ggml_backend_name(lm.backend), "CPU") ||
            std::strcmp(ggml_backend_name(vae.backend), "Vulkan0")) return 1;
        backend_release(lm.backend, lm.cpu_backend);
        backend_release(vae.backend, vae.cpu_backend);
        if (g_backend_refs || g_ar_backend_refs || g_backend_cache.backend || g_ar_backend_cache.backend) return 1;
    }
    if (!backend_set_device("CPU") || g_backend_cpu_ar) return 1;
    BackendPair cpu = backend_init("NAR");
    if (cpu.has_gpu || std::strcmp(ggml_backend_name(cpu.backend), "CPU")) return 1;
    backend_release(cpu.backend, cpu.cpu_backend);
    std::puts("PASS S20 stage routing, simultaneous caches, reference lifetimes and explicit CPU mode");
    return 0;
}
