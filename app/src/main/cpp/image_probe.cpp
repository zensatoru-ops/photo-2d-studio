#include "image_probe.h"

#include <cstdio>
#include <cstring>
#include <vector>

namespace photo2model {

namespace {

uint32_t ReadBE32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) |
           static_cast<uint32_t>(p[3]);
}

uint16_t ReadBE16(const uint8_t* p) {
    return static_cast<uint16_t>((p[0] << 8) | p[1]);
}

std::vector<uint8_t> ReadHeaderBytes(const std::string& path, size_t maxBytes) {
    std::vector<uint8_t> buf;
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return buf;
    buf.resize(maxBytes);
    size_t got = fread(buf.data(), 1, maxBytes, f);
    fclose(f);
    buf.resize(got);
    return buf;
}

// PNG: 8-byte signature, then first chunk is always IHDR (length, type,
// width, height, bit depth, color type, ...).
ImageInfo ProbePng(const std::vector<uint8_t>& buf) {
    ImageInfo info;
    static const uint8_t kSig[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    if (buf.size() < 33 || memcmp(buf.data(), kSig, 8) != 0) return info;
    if (memcmp(&buf[12], "IHDR", 4) != 0) return info;

    info.width = static_cast<int32_t>(ReadBE32(&buf[16]));
    info.height = static_cast<int32_t>(ReadBE32(&buf[20]));
    uint8_t colorType = buf[25];
    switch (colorType) {
        case 0: info.channels = 1; break;  // grayscale
        case 2: info.channels = 3; break;  // RGB
        case 3: info.channels = 1; break;  // palette (indexed)
        case 4: info.channels = 2; break;  // grayscale + alpha
        case 6: info.channels = 4; break;  // RGBA
        default: info.channels = 0; break;
    }
    info.format = ImageFormat::kPng;
    info.valid = info.width > 0 && info.height > 0 && info.channels > 0;
    return info;
}

// JPEG: scan markers after the 0xFFD8 start-of-image until a SOF marker
// (baseline 0xC0, progressive 0xC2, and the less common extended variants).
ImageInfo ProbeJpeg(const std::vector<uint8_t>& buf) {
    ImageInfo info;
    if (buf.size() < 4 || buf[0] != 0xFF || buf[1] != 0xD8) return info;

    size_t pos = 2;
    while (pos + 4 <= buf.size()) {
        if (buf[pos] != 0xFF) { pos++; continue; }
        uint8_t marker = buf[pos + 1];

        // Markers with no payload length to skip.
        if (marker == 0xD8 || marker == 0xD9 || (marker >= 0xD0 && marker <= 0xD7)) {
            pos += 2;
            continue;
        }

        if (pos + 4 > buf.size()) break;
        uint16_t segLen = ReadBE16(&buf[pos + 2]);

        bool isSof = (marker >= 0xC0 && marker <= 0xCF) &&
                     marker != 0xC4 && marker != 0xC8 && marker != 0xCC;

        if (isSof) {
            if (pos + 9 > buf.size()) break;
            info.height = ReadBE16(&buf[pos + 5]);
            info.width = ReadBE16(&buf[pos + 7]);
            info.channels = buf[pos + 9];
            info.format = ImageFormat::kJpeg;
            info.valid = info.width > 0 && info.height > 0 && info.channels > 0;
            return info;
        }

        if (marker == 0xDA) break;  // start-of-scan - no SOF found before entropy data
        pos += 2 + segLen;
    }
    return info;
}

// WebP: RIFF container. VP8X (extended) carries explicit canvas dimensions.
// Plain lossy VP8 stores dimensions in its own bitstream header. VP8L
// (lossless) uses a bit-packed header this parser does not decode - callers
// get valid=false for that case and fall back to a full decode on the
// Kotlin side.
ImageInfo ProbeWebp(const std::vector<uint8_t>& buf) {
    ImageInfo info;
    if (buf.size() < 30) return info;
    if (memcmp(&buf[0], "RIFF", 4) != 0 || memcmp(&buf[8], "WEBP", 4) != 0) return info;

    const char* chunkId = reinterpret_cast<const char*>(&buf[12]);

    if (memcmp(chunkId, "VP8X", 4) == 0) {
        // Canvas width/height are 24-bit little-endian, minus one, at offset 24/27.
        uint32_t w = (buf[24] | (buf[25] << 8) | (buf[26] << 16)) + 1;
        uint32_t h = (buf[27] | (buf[28] << 8) | (buf[29] << 16)) + 1;
        info.width = static_cast<int32_t>(w);
        info.height = static_cast<int32_t>(h);
        info.channels = (buf[20] & 0x10) ? 4 : 3;  // alpha flag bit
        info.format = ImageFormat::kWebp;
        info.valid = w > 0 && h > 0;
        return info;
    }

    if (memcmp(chunkId, "VP8 ", 4) == 0 && buf.size() >= 30) {
        // Lossy bitstream: 3-byte frame tag, 3-byte start code (0x9D012A),
        // then 14-bit width/height with 2-bit scale prefix each.
        const uint8_t* bs = &buf[20];
        if (bs[3] == 0x9D && bs[4] == 0x01 && bs[5] == 0x2A) {
            uint16_t w = static_cast<uint16_t>(bs[6] | (bs[7] << 8)) & 0x3FFF;
            uint16_t h = static_cast<uint16_t>(bs[8] | (bs[9] << 8)) & 0x3FFF;
            info.width = w;
            info.height = h;
            info.channels = 3;
            info.format = ImageFormat::kWebp;
            info.valid = w > 0 && h > 0;
        }
        return info;
    }

    return info;  // VP8L or unrecognized chunk - not handled here
}

}  // namespace

ImageInfo ProbeImage(const std::string& path) {
    std::vector<uint8_t> header = ReadHeaderBytes(path, 64);
    if (header.empty()) return ImageInfo{};

    if (header.size() >= 3 && header[0] == 0xFF && header[1] == 0xD8) {
        // JPEG SOF can be past 64 bytes if EXIF/ICC blocks precede it,
        // so re-read with a wider window for this format only.
        std::vector<uint8_t> wider = ReadHeaderBytes(path, 65536);
        return ProbeJpeg(wider);
    }
    if (header.size() >= 8 && header[0] == 0x89 && header[1] == 'P') {
        return ProbePng(header);
    }
    if (header.size() >= 30 && memcmp(&header[0], "RIFF", 4) == 0) {
        return ProbeWebp(header);
    }
    return ImageInfo{};
}

}  // namespace photo2model
