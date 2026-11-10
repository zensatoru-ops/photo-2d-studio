#pragma once
#include <cstdint>
#include <vector>

namespace photo2model {

// Which two world axes a view's mask constrains, and how they map onto the
// mask's u (horizontal) / v (vertical) pixel axes. World space is a unit
// cube: x = character left(0)->right(1), y = feet(0)->head(1),
// z = front(0)->back(1). There's no camera calibration step in this phase
// - these are the master plan's stated orthographic-turnaround assumptions,
// documented here rather than derived from anything in the project file.
enum class ViewAxisMode : int32_t {
    kFront = 0,  // u = x,   v = 1-y
    kBack = 1,   // u = 1-x, v = 1-y   (mirrored - seen from behind)
    kLeft = 2,   // u = z,   v = 1-y   (image-left = character-front)
    kRight = 3,  // u = 1-z, v = 1-y   (mirrored)
    kTop = 4     // u = x,   v = z     (image-top = character-front)
};

struct ViewConstraint {
    const uint8_t* mask;   // width*height bytes, 0 = background, 255 = foreground
    int32_t width;
    int32_t height;
    ViewAxisMode axisMode;
    // The character's silhouette bounding box within this mask, in pixel
    // coordinates. World (u,v) in [0,1] map linearly onto [min, max] here -
    // each view calibrates itself off its own silhouette extent rather than
    // a shared camera model.
    float minU, minV, maxU, maxV;
};

struct VoxelGrid {
    std::vector<uint8_t> occupancy;  // resolution^3, 1 = inside, 0 = outside
    int32_t resolution = 0;
};

// Classic shape-from-silhouette / visual hull carving (master plan
// section 5.6, Method B): a voxel survives only if its projection falls
// inside every available view's silhouette. A voxel whose projection in
// any single view lands outside that view's mask (or off it entirely) is
// carved away. With only 2 orthogonal views (front + one side) this is
// exactly Method A's silhouette intersection in 3D instead of 2D - it
// won't recover concavities a silhouette can't see, which section 5.6
// already notes as this method's known limitation.
VoxelGrid CarveVisualHull(const std::vector<ViewConstraint>& views, int32_t resolution);

}  // namespace photo2model
