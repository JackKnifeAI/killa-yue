#pragma once
#include <array>
#include <cmath>
namespace studio {
struct ChamberConfig {
    static constexpr int kChain = 128, kSize = 5 + 32 + 1 + kChain + 4 * 16 * 32;
    std::array<float, kSize> data{};
    ChamberConfig() { data[37] = 1; }
    bool valid() const {
        for (float f : data)
            if (!std::isfinite(f)) return false;
        auto integer = [](float value, int low, int high) {
            return value == std::floor(value) && value >= low && value <= high;
        };
        if (!integer(data[0], 0, 3) || !integer(data[1], 0, 1) || !integer(data[2], 0, 1) || !integer(data[3], 0, 15) ||
            !integer(data[4], 0, 3) || !integer(data[37], 1, kChain))
            return false;
        for (int i = 5; i < 37; i++)
            if (!integer(data[i], 0, 1)) return false;
        for (int i = 0; i < kChain; i++)
            if (!integer(data[38 + i], 0, 3)) return false;
        for (int i = 38 + kChain; i < kSize; i++)
            if (data[i] < 0 || data[i] > 1) return false;
        return true;
    }
    bool audible(int pad) const {
        if (data[5 + pad] != 0) return false;
        bool solo = false;
        for (int i = 0; i < 16; i++) solo |= data[21 + i] != 0;
        return !solo || data[21 + pad] != 0;
    }
    int bank(long step, int length) const {
        if (step < 0) return -1;
        if (data[1] == 0) return (int)data[0];
        long index = step / length;
        return index >= (int)data[37] ? -1 : (int)data[38 + index];
    }
    float step(int bank, int pad, int at) const { return data[38 + kChain + (bank * 16 + pad) * 32 + at]; }
};
}  // namespace studio
