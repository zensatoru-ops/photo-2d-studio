#include "surface_nets.h"

#include <array>
#include <cmath>

namespace photo2model {

namespace {

struct Vec3 {
    float x = 0, y = 0, z = 0;
    Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec3 operator*(float s) const { return {x * s, y * s, z * s}; }
};

Vec3 Cross(const Vec3& a, const Vec3& b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

float Dot(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }

Vec3 Normalized(const Vec3& v) {
    float len = std::sqrt(Dot(v, v));
    if (len < 1e-8f) return {0, 0, 0};
    return {v.x / len, v.y / len, v.z / len};
}

inline int32_t Sample(const VoxelGrid& grid, int32_t x, int32_t y, int32_t z) {
    int32_t r = grid.resolution;
    size_t idx = (static_cast<size_t>(z) * r + y) * r + x;
    return grid.occupancy[idx];
}

Vec3 SampleWorldPos(int32_t x, int32_t y, int32_t z, int32_t resolution) {
    float step = 1.0f / static_cast<float>(resolution);
    return {(x + 0.5f) * step, (y + 0.5f) * step, (z + 0.5f) * step};
}

// Corner dx,dy,dz for corner indices 0..7, and the 12 edges as pairs of corner indices.
constexpr int32_t kCornerDx[8] = {0, 1, 0, 1, 0, 1, 0, 1};
constexpr int32_t kCornerDy[8] = {0, 0, 1, 1, 0, 0, 1, 1};
constexpr int32_t kCornerDz[8] = {0, 0, 0, 0, 1, 1, 1, 1};
constexpr int32_t kEdges[12][2] = {
    {0, 1}, {2, 3}, {4, 5}, {6, 7},
    {0, 2}, {1, 3}, {4, 6}, {5, 7},
    {0, 4}, {1, 5}, {2, 6}, {3, 7}
};

}  // namespace

Mesh ExtractSurfaceNets(const VoxelGrid& grid) {
    Mesh mesh;
    const int32_t res = grid.resolution;
    const int32_t cellsPerAxis = res - 1;
    if (cellsPerAxis < 1) return mesh;

    std::vector<int32_t> cellVertexIndex(
        static_cast<size_t>(cellsPerAxis) * cellsPerAxis * cellsPerAxis, -1);

    auto cellIndex = [&](int32_t cx, int32_t cy, int32_t cz) {
        return (static_cast<size_t>(cz) * cellsPerAxis + cy) * cellsPerAxis + cx;
    };

    // Pass 1: one vertex per non-uniform cell, at the average of its
    // inside/outside edge-crossing midpoints.
    for (int32_t cz = 0; cz < cellsPerAxis; cz++) {
        for (int32_t cy = 0; cy < cellsPerAxis; cy++) {
            for (int32_t cx = 0; cx < cellsPerAxis; cx++) {
                int32_t cornerVal[8];
                for (int32_t c = 0; c < 8; c++) {
                    cornerVal[c] = Sample(grid, cx + kCornerDx[c], cy + kCornerDy[c], cz + kCornerDz[c]);
                }
                int32_t sum = 0;
                for (int32_t c = 0; c < 8; c++) sum += cornerVal[c];
                if (sum == 0 || sum == 8) continue;  // uniform cell - no boundary here

                Vec3 accum{0, 0, 0};
                int32_t crossings = 0;
                for (auto& edge : kEdges) {
                    int32_t a = edge[0], b = edge[1];
                    if (cornerVal[a] == cornerVal[b]) continue;
                    Vec3 pa = SampleWorldPos(cx + kCornerDx[a], cy + kCornerDy[a], cz + kCornerDz[a], res);
                    Vec3 pb = SampleWorldPos(cx + kCornerDx[b], cy + kCornerDy[b], cz + kCornerDz[b], res);
                    accum = accum + (pa + pb) * 0.5f;
                    crossings++;
                }
                if (crossings == 0) continue;

                Vec3 vertex = accum * (1.0f / static_cast<float>(crossings));
                int32_t vIndex = static_cast<int32_t>(mesh.vertices.size() / 3);
                mesh.vertices.push_back(vertex.x);
                mesh.vertices.push_back(vertex.y);
                mesh.vertices.push_back(vertex.z);
                mesh.normals.push_back(0);
                mesh.normals.push_back(0);
                mesh.normals.push_back(0);
                cellVertexIndex[cellIndex(cx, cy, cz)] = vIndex;
            }
        }
    }

    auto accumulateFaceNormal = [&](int32_t a, int32_t b, int32_t c) {
        Vec3 pa{mesh.vertices[a * 3], mesh.vertices[a * 3 + 1], mesh.vertices[a * 3 + 2]};
        Vec3 pb{mesh.vertices[b * 3], mesh.vertices[b * 3 + 1], mesh.vertices[b * 3 + 2]};
        Vec3 pc{mesh.vertices[c * 3], mesh.vertices[c * 3 + 1], mesh.vertices[c * 3 + 2]};
        Vec3 n = Normalized(Cross(pb - pa, pc - pa));
        for (int32_t idx : {a, b, c}) {
            mesh.normals[idx * 3] += n.x;
            mesh.normals[idx * 3 + 1] += n.y;
            mesh.normals[idx * 3 + 2] += n.z;
        }
    };

    // addQuad orders two triangles a-b-c and a-c-d, flipping winding if the
    // resulting normal points the wrong way relative to the known
    // inside->outside direction at this occupancy boundary.
    auto addQuad = [&](int32_t a, int32_t b, int32_t c, int32_t d, const Vec3& outward) {
        Vec3 pa{mesh.vertices[a * 3], mesh.vertices[a * 3 + 1], mesh.vertices[a * 3 + 2]};
        Vec3 pb{mesh.vertices[b * 3], mesh.vertices[b * 3 + 1], mesh.vertices[b * 3 + 2]};
        Vec3 pc{mesh.vertices[c * 3], mesh.vertices[c * 3 + 1], mesh.vertices[c * 3 + 2]};
        Vec3 n = Cross(pb - pa, pc - pa);
        bool flip = Dot(n, outward) < 0.0f;

        if (!flip) {
            mesh.indices.insert(mesh.indices.end(), {a, b, c, a, c, d});
            accumulateFaceNormal(a, b, c);
            accumulateFaceNormal(a, c, d);
        } else {
            mesh.indices.insert(mesh.indices.end(), {a, c, b, a, d, c});
            accumulateFaceNormal(a, c, b);
            accumulateFaceNormal(a, d, c);
        }
    };

    // Pass 2: a face is stitched wherever two axis-adjacent SAMPLES differ
    // in occupancy - that boundary is shared by up to 4 cells, each of
    // which (by definition) is non-uniform and already has a vertex.
    for (int32_t z = 1; z < cellsPerAxis; z++) {
        for (int32_t y = 1; y < cellsPerAxis; y++) {
            for (int32_t x = 0; x < res - 1; x++) {
                if (Sample(grid, x, y, z) == Sample(grid, x + 1, y, z)) continue;
                int32_t v00 = cellVertexIndex[cellIndex(x, y - 1, z - 1)];
                int32_t v10 = cellVertexIndex[cellIndex(x, y, z - 1)];
                int32_t v11 = cellVertexIndex[cellIndex(x, y, z)];
                int32_t v01 = cellVertexIndex[cellIndex(x, y - 1, z)];
                if (v00 < 0 || v10 < 0 || v11 < 0 || v01 < 0) continue;

                Vec3 inner = SampleWorldPos(x, y, z, res);
                Vec3 outer = SampleWorldPos(x + 1, y, z, res);
                Vec3 outward = Normalized(outer - inner) * (Sample(grid, x, y, z) == 1 ? 1.0f : -1.0f);
                addQuad(v00, v10, v11, v01, outward);
            }
        }
    }
    for (int32_t z = 1; z < cellsPerAxis; z++) {
        for (int32_t x = 1; x < cellsPerAxis; x++) {
            for (int32_t y = 0; y < res - 1; y++) {
                if (Sample(grid, x, y, z) == Sample(grid, x, y + 1, z)) continue;
                int32_t v00 = cellVertexIndex[cellIndex(x - 1, y, z - 1)];
                int32_t v10 = cellVertexIndex[cellIndex(x, y, z - 1)];
                int32_t v11 = cellVertexIndex[cellIndex(x, y, z)];
                int32_t v01 = cellVertexIndex[cellIndex(x - 1, y, z)];
                if (v00 < 0 || v10 < 0 || v11 < 0 || v01 < 0) continue;

                Vec3 inner = SampleWorldPos(x, y, z, res);
                Vec3 outer = SampleWorldPos(x, y + 1, z, res);
                Vec3 outward = Normalized(outer - inner) * (Sample(grid, x, y, z) == 1 ? 1.0f : -1.0f);
                addQuad(v00, v10, v11, v01, outward);
            }
        }
    }
    for (int32_t y = 1; y < cellsPerAxis; y++) {
        for (int32_t x = 1; x < cellsPerAxis; x++) {
            for (int32_t z = 0; z < res - 1; z++) {
                if (Sample(grid, x, y, z) == Sample(grid, x, y, z + 1)) continue;
                int32_t v00 = cellVertexIndex[cellIndex(x - 1, y - 1, z)];
                int32_t v10 = cellVertexIndex[cellIndex(x, y - 1, z)];
                int32_t v11 = cellVertexIndex[cellIndex(x, y, z)];
                int32_t v01 = cellVertexIndex[cellIndex(x - 1, y, z)];
                if (v00 < 0 || v10 < 0 || v11 < 0 || v01 < 0) continue;

                Vec3 inner = SampleWorldPos(x, y, z, res);
                Vec3 outer = SampleWorldPos(x, y, z + 1, res);
                Vec3 outward = Normalized(outer - inner) * (Sample(grid, x, y, z) == 1 ? 1.0f : -1.0f);
                addQuad(v00, v10, v11, v01, outward);
            }
        }
    }

    // Normalize accumulated per-vertex normals (smooth shading).
    size_t vertexCount = mesh.vertices.size() / 3;
    for (size_t i = 0; i < vertexCount; i++) {
        Vec3 n{mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2]};
        n = Normalized(n);
        mesh.normals[i * 3] = n.x;
        mesh.normals[i * 3 + 1] = n.y;
        mesh.normals[i * 3 + 2] = n.z;
    }

    return mesh;
}

}  // namespace photo2model
