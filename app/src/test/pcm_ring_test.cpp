#include "../main/cpp/pcm_ring.h"
#include <cassert>
#include <deque>
#include <random>
#include <iostream>

int main() {
    aura::PcmRing ring;
    ring.reset(127);
    std::deque<unsigned char> expected;
    std::mt19937 random(7);
    for (int pass = 0; pass < 20000; pass++) {
        unsigned char bytes[211];
        size_t count = random() % 211;
        if (random() % 2) {
            for (size_t i = 0; i < count; i++)
                bytes[i] = random();
            size_t n = ring.write(bytes, count);
            assert(n == std::min(count, 127 - expected.size()));
            for (size_t i = 0; i < n; i++)
                expected.push_back(bytes[i]);
        } else {
            size_t n = ring.read(bytes, count);
            assert(n == std::min(count, expected.size()));
            for (size_t i = 0; i < n; i++) {
                assert(bytes[i] == expected.front());
                expected.pop_front();
            }
        }
        assert(ring.size() == expected.size());
    }
    std::cout << "PASS: 20000 bounded PCM ring operations\n";
}
