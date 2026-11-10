#include "voxel_carve.h"

#include <cmath>

namespace photo2model {

namespace {

bool ProjectAndSample(const ViewConstraint& view, float x, float y, float z) {
    float u, v;
    switch (view.axisMode) {
        case ViewAxisMode::kFront: u = x;       v = 1.0f - y; break;
        case ViewAxisMode::kBack:  u = 1.0f - x; v = 1.0f - y; break;
        case ViewAxisMode::kLeft:  u = z;       v = 1.0f - y; break;
        case ViewAxisMode::kRight: u = 1.0f - z; v = 1.0f - y; break;
        case ViewAxisMode::kTop:   u = x;       v = z;        break;
        default: return false;
    }

    float px = view.minU + u * (view.maxU - view.minU);
    float py = view.minV + v * (view.maxV - view.minV);
    int32_t ipx = static_cast<int32_t>(std::lround(px));
    int32_t ipy = static_cast<int32_t>(std::lround(py));

    if (ipx < 0 || ipx >= view.width || ipy < 0 || ipy >= view.height) {
        return false;  // projects off this view's mask entirely - outside the silhouette
    }
    return view.mask[ipy * view.width + ipx] != 0;
}

}  // namespace

VoxelGrid CarveVisualHull(const std::vector<ViewConstraint>& views, int32_t resolution) {
    VoxelGrid grid;
    grid.resolution = resolution;
    grid.occupancy.assign(static_cast<size_t>(resolution) * resolution * resolution, 0);

    const float step = 1.0f / static_cast<float>(resolution);

    for (int32_t iz = 0; iz < resolution; iz++) {
        float wz = (iz + 0.5f) * step;
        for (int32_t iy = 0; iy < resolution; iy++) {
            float wy = (iy + 0.5f) * step;
            for (int32_t ix = 0; ix < resolution; ix++) {
                float wx = (ix + 0.5f) * step;

                bool occupied = true;
                for (const auto& view : views) {
                    if (!ProjectAndSample(view, wx, wy, wz)) {
                        occupied = false;
                        break;
                    }
                }

                if (occupied) {
                    size_t idx = (static_cast<size_t>(iz) * resolution + iy) * resolution + ix;
                    grid.occupancy[idx] = 1;
                }
            }
        }
    }

    return grid;
}

}  // namespace photo2model
