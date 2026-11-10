#pragma once
#include <cstdint>
#include <string>

namespace photo2model {

enum class ImageFormat : int32_t {
    kUnknown = 0,
    kPng = 1,
    kJpeg = 2,
    kWebp = 3
};

struct ImageInfo {
    bool valid = false;
    int32_t width = 0;
    int32_t height = 0;
    int32_t channels = 0;
    ImageFormat format = ImageFormat::kUnknown;
};

// Reads just enough of the file header to recover dimensions and channel
// count. Never loads full pixel data - this runs before the Kotlin side
// decodes a bitmap, so a 4000x6000 turnaround sheet doesn't cost a decode
// just to find out it's corrupt or the wrong aspect ratio.
ImageInfo ProbeImage(const std::string& path);

}  // namespace photo2model
