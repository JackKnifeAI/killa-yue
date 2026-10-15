// spsc_test.cpp — THREADED
#include "studio/spsc.h"
#include "test.h"
#include <thread>
using studio::Spsc;

int main() {
    Spsc<int, 4> q;
    int v = 0;
    CHECK(q.push(1) && q.push(2) && q.push(3) && q.push(4), "holds N items");
    CHECK(!q.push(5), "refuses when full");
    CHECK(q.pop(v) && v == 1, "first in, first out");
    Spsc<long, 1024> big;
    const long n = 2000000;
    std::thread producer([&] { for (long i = 0; i < n; i++) while (!big.push(i)) {} });
    long expect = 0, got = 0;
    bool inOrder = true;
    while (expect < n) if (big.pop(got)) { if (got != expect) inOrder = false; expect++; }
    producer.join();
    CHECK(inOrder, "two million items cross threads in order");
    DONE("spsc_test");
}
