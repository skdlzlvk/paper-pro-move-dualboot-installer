#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace paper {

// Pixel-exact work reduction, not sampling. Quantization still receives
// absolute panel coordinates so ordered dithering is unchanged across tiles.
template <typename Quantize>
std::size_t convertEinkRegion(const unsigned char *source,
                             unsigned char *destination,
                             int width, int height, int stride,
                             int left, int top, int regionWidth,
                             int regionHeight, bool inverted,
                             Quantize quantize)
{
    if (!source || !destination || width <= 0 || height <= 0 ||
        stride < static_cast<std::int64_t>(width) * 4 ||
        regionWidth <= 0 || regionHeight <= 0)
        return 0;
    const int x0 = std::clamp(left, 0, width);
    const int y0 = std::clamp(top, 0, height);
    const int x1 = static_cast<int>(std::clamp<std::int64_t>(
        static_cast<std::int64_t>(left) + regionWidth, 0, width));
    const int y1 = static_cast<int>(std::clamp<std::int64_t>(
        static_cast<std::int64_t>(top) + regionHeight, 0, height));
    if (x1 <= x0 || y1 <= y0)
        return 0;
    for (int y = y0; y < y1; ++y) {
        for (int x = x0; x < x1; ++x) {
            const std::size_t offset =
                static_cast<std::size_t>(y) * stride +
                static_cast<std::size_t>(x) * 4;
            int luminance = (source[offset] * 77 +
                             source[offset + 1] * 150 +
                             source[offset + 2] * 29 + 128) >> 8;
            if (inverted)
                luminance = 255 - luminance;
            const unsigned char value = quantize(luminance, x, y);
            destination[offset] = value;
            destination[offset + 1] = value;
            destination[offset + 2] = value;
            destination[offset + 3] = 0xff;
        }
    }
    return static_cast<std::size_t>(x1 - x0) * (y1 - y0);
}

} // namespace paper
