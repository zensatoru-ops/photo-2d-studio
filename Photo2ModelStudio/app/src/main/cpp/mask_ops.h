#pragma once
#include <cstdint>
#include <vector>

namespace photo2model {

// All masks are single-channel, 1 byte per pixel: 0 = background, 255 =
// foreground. Row-major, no padding.

// 4-connected flood fill starting at (seedX, seedY). Every pixel reachable
// from the seed without crossing a value boundary is set to newValue.
// No-op if the seed is already newValue or out of bounds.
void FloodFill(uint8_t* mask, int32_t width, int32_t height,
                int32_t seedX, int32_t seedY, uint8_t newValue);

// Flood-fills from every border background pixel to find what's actually
// "outside" the silhouette, then sets everything else to foreground. This
// closes interior holes (a ring of foreground with a background pocket in
// the middle) without touching the outer boundary - exactly the "fill
// small holes" tool from section 5.3's mask editing list.
void FillHoles(uint8_t* mask, int32_t width, int32_t height);

// Connected-component labeling (4-connected) over foreground pixels. Keeps
// only the largest component, zeroing every smaller stray region. This is
// what "smooth contour" / fleck removal reduces to for a binary mask.
void KeepLargestComponent(uint8_t* mask, int32_t width, int32_t height);

// Moore-neighbor boundary tracing on the largest foreground component.
// Returns an empty vector if the mask has no foreground pixels. Output is
// a flat [x0,y0,x1,y1,...] polyline in mask-pixel coordinates, used both
// for the on-screen contour overlay and for the bounding-box measurements
// the consistency validator needs.
std::vector<int32_t> TraceContour(const uint8_t* mask, int32_t width, int32_t height);

}  // namespace photo2model
