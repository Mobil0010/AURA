#pragma once
#include <algorithm>
#include <cstddef>
#include <vector>

namespace aura {
class PcmRing {
    std::vector<unsigned char> data;
    size_t head = 0, count = 0;

  public:
    void reset(size_t capacity) {
        data.resize(capacity);
        head = count = 0;
    }

    size_t size() const {
        return count;
    }

    size_t write(const unsigned char *bytes, size_t wanted) {
        size_t n = std::min(wanted, data.size() - count);
        if (!n)
            return 0;
        size_t tail = (head + count) % data.size(), first = std::min(n, data.size() - tail);
        std::copy_n(bytes, first, data.data() + tail);
        std::copy_n(bytes + first, n - first, data.data());
        count += n;
        return n;
    }

    size_t read(unsigned char *bytes, size_t wanted) {
        size_t n = std::min(wanted, count);
        if (!n)
            return 0;
        size_t first = std::min(n, data.size() - head);
        std::copy_n(data.data() + head, first, bytes);
        std::copy_n(data.data(), n - first, bytes + first);
        head = (head + n) % data.size();
        count -= n;
        return n;
    }
};
} // namespace aura
