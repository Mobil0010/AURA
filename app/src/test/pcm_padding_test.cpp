#include "../main/cpp/pcm_padding.h"
#include <cassert>
#include <cstdint>
#include <vector>
#include <random>
#include <iostream>

int main() {
    std::mt19937 random(42);
    for (int input = 2; input <= 4; input++)
        for (int wire = input; wire <= 4; wire++) {
            std::vector<unsigned char> bytes(1000 * wire), original(1000 * input);
            for (auto &b : original)
                b = random();
            std::copy(original.begin(), original.end(), bytes.begin());
            aura::padPcm(bytes.data(), 1000, input, wire);
            for (int i = 0; i < 1000; i++) {
                for (int b = 0; b < wire - input; b++)
                    assert(bytes[i * wire + b] == 0);
                for (int b = 0; b < input; b++)
                    assert(bytes[i * wire + wire - input + b] == original[i * input + b]);
            }
        }
    std::cout << "PASS: 6000 lossless USB sample padding cases\n";
}
