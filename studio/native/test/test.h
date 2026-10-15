#pragma once
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
static int g_pass = 0, g_fail = 0;
#define CHECK(cond, what) do { if (cond) g_pass++; else { g_fail++; std::printf("FAIL %s (%s:%d)\n", what, __FILE__, __LINE__); } } while (0)
#define NEAR(a, b, eps, what) CHECK(std::fabs((double)(a) - (double)(b)) <= (eps), what)
#define DONE(name) do { std::printf("%s: %d passed, %d failed\n", name, g_pass, g_fail); return g_fail ? 1 : 0; } while (0)

// A scratch file path: $TMPDIR when set (Android has no /tmp), else /tmp.
inline std::string tmpPath(const char* name) {
    const char* d = std::getenv("TMPDIR");
    return std::string(d && *d ? d : "/tmp") + "/" + name;
}
