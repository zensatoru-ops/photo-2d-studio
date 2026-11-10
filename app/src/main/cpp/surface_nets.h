#pragma once
#include <cstdint>
#include <vector>

#include "voxel_carve.h"

namespace photo2model {

struct Mesh {
    std::vector<float> vertices;   // x,y,z interleaved, world space [0,1]^3
    std::vector<float> normals;    // x,y,z interleaved, one per vertex, unit length
    std::vector<int32_t> indices;  // triangle list, 3 per face
};

// Naive Surface Nets (Gibson 1998): for every 2x2x2 block of occupancy
// samples that isn't uniform (a mix of inside/outside), place one vertex
// at the average of that block's inside/outside edge-crossing midpoints.
// Faces are then stitched between the four cells sharing each occupancy
// boundary. This produces one quad per silhouette-boundary crossing instead
// of the blocky "exposed cube face" look a naive voxel-to-quad conversion
// would give, without needing marching cubes' 256-entry case table.
Mesh ExtractSurfaceNets(const VoxelGrid& grid);

}  // namespace photo2model
