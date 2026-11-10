#include "mask_ops.h"

#include <queue>
#include <vector>

namespace photo2model {

namespace {
inline int32_t Idx(int32_t x, int32_t y, int32_t width) { return y * width + x; }
}  // namespace

void FloodFill(uint8_t* mask, int32_t width, int32_t height,
                int32_t seedX, int32_t seedY, uint8_t newValue) {
    if (seedX < 0 || seedX >= width || seedY < 0 || seedY >= height) return;
    const uint8_t target = mask[Idx(seedX, seedY, width)];
    if (target == newValue) return;

    std::vector<int32_t> stack;
    stack.push_back(Idx(seedX, seedY, width));
    mask[Idx(seedX, seedY, width)] = newValue;

    while (!stack.empty()) {
        int32_t i = stack.back();
        stack.pop_back();
        int32_t x = i % width;
        int32_t y = i / width;

        const int32_t dx[4] = {1, -1, 0, 0};
        const int32_t dy[4] = {0, 0, 1, -1};
        for (int32_t d = 0; d < 4; d++) {
            int32_t nx = x + dx[d];
            int32_t ny = y + dy[d];
            if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
            int32_t ni = Idx(nx, ny, width);
            if (mask[ni] == target) {
                mask[ni] = newValue;
                stack.push_back(ni);
            }
        }
    }
}

void FillHoles(uint8_t* mask, int32_t width, int32_t height) {
    std::vector<uint8_t> outside(static_cast<size_t>(width) * height, 0);
    std::queue<int32_t> q;

    auto tryMark = [&](int32_t x, int32_t y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        int32_t i = Idx(x, y, width);
        if (mask[i] == 0 && outside[i] == 0) {
            outside[i] = 1;
            q.push(i);
        }
    };

    for (int32_t x = 0; x < width; x++) {
        tryMark(x, 0);
        tryMark(x, height - 1);
    }
    for (int32_t y = 0; y < height; y++) {
        tryMark(0, y);
        tryMark(width - 1, y);
    }

    while (!q.empty()) {
        int32_t i = q.front();
        q.pop();
        int32_t x = i % width;
        int32_t y = i / width;
        tryMark(x + 1, y);
        tryMark(x - 1, y);
        tryMark(x, y + 1);
        tryMark(x, y - 1);
    }

    // Anything background that was never reached from the border is an
    // interior hole - promote it to foreground.
    for (size_t i = 0; i < outside.size(); i++) {
        if (mask[i] == 0 && outside[i] == 0) {
            mask[i] = 255;
        }
    }
}

void KeepLargestComponent(uint8_t* mask, int32_t width, int32_t height) {
    const size_t total = static_cast<size_t>(width) * height;
    std::vector<int32_t> label(total, 0);
    int32_t nextLabel = 1;
    std::vector<int32_t> componentSize;
    componentSize.push_back(0);  // label 0 unused

    for (int32_t y = 0; y < height; y++) {
        for (int32_t x = 0; x < width; x++) {
            int32_t start = Idx(x, y, width);
            if (mask[start] != 255 || label[start] != 0) continue;

            int32_t thisLabel = nextLabel++;
            componentSize.push_back(0);
            std::vector<int32_t> stack;
            stack.push_back(start);
            label[start] = thisLabel;

            while (!stack.empty()) {
                int32_t i = stack.back();
                stack.pop_back();
                componentSize[thisLabel]++;
                int32_t cx = i % width;
                int32_t cy = i / width;

                const int32_t dx[4] = {1, -1, 0, 0};
                const int32_t dy[4] = {0, 0, 1, -1};
                for (int32_t d = 0; d < 4; d++) {
                    int32_t nx = cx + dx[d];
                    int32_t ny = cy + dy[d];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int32_t ni = Idx(nx, ny, width);
                    if (mask[ni] == 255 && label[ni] == 0) {
                        label[ni] = thisLabel;
                        stack.push_back(ni);
                    }
                }
            }
        }
    }

    if (nextLabel <= 1) return;  // no foreground at all

    int32_t largestLabel = 1;
    for (int32_t l = 2; l < nextLabel; l++) {
        if (componentSize[l] > componentSize[largestLabel]) largestLabel = l;
    }

    for (size_t i = 0; i < total; i++) {
        if (mask[i] == 255 && label[i] != largestLabel) {
            mask[i] = 0;
        }
    }
}

std::vector<int32_t> TraceContour(const uint8_t* mask, int32_t width, int32_t height) {
    std::vector<int32_t> result;

    int32_t startX = -1, startY = -1;
    for (int32_t y = 0; y < height && startX < 0; y++) {
        for (int32_t x = 0; x < width; x++) {
            if (mask[Idx(x, y, width)] == 255) {
                startX = x;
                startY = y;
                break;
            }
        }
    }
    if (startX < 0) return result;  // empty mask

    auto isForeground = [&](int32_t x, int32_t y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return false;
        return mask[Idx(x, y, width)] == 255;
    };

    // Moore-neighbor tracing, clockwise, 8-connected.
    const int32_t nx8[8] = {1, 1, 0, -1, -1, -1, 0, 1};
    const int32_t ny8[8] = {0, 1, 1, 1, 0, -1, -1, -1};

    int32_t curX = startX, curY = startY;
    int32_t backtrackDir = 6;  // arrived from "above" since we scanned top-down
    const size_t maxSteps = static_cast<size_t>(width) * height * 2 + 16;
    size_t steps = 0;

    do {
        result.push_back(curX);
        result.push_back(curY);

        int32_t dir = (backtrackDir + 1) % 8;
        int32_t foundX = curX, foundY = curY;
        bool found = false;
        for (int32_t k = 0; k < 8; k++) {
            int32_t d = (dir + k) % 8;
            int32_t tx = curX + nx8[d];
            int32_t ty = curY + ny8[d];
            if (isForeground(tx, ty)) {
                foundX = tx;
                foundY = ty;
                backtrackDir = (d + 4) % 8;  // direction back to curX,curY from the new pixel
                found = true;
                break;
            }
        }
        if (!found) break;  // isolated single pixel
        curX = foundX;
        curY = foundY;
        steps++;
    } while ((curX != startX || curY != startY) && steps < maxSteps);

    return result;
}

}  // namespace photo2model
