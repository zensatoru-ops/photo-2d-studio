#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>

#include "image_probe.h"
#include "mask_ops.h"
#include "surface_nets.h"
#include "voxel_carve.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_photo2model_studio_core_NativeBridge_nativeGetCoreVersion(
        JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF("photo2model_core/0.1.0-phase1");
}

// Returns {valid(0/1), width, height, channels, format} or an all-zero
// array if the file couldn't be read at all.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_photo2model_studio_core_NativeBridge_nativeProbeImage(
        JNIEnv* env, jobject /* thiz */, jstring jPath) {
    const char* pathChars = env->GetStringUTFChars(jPath, nullptr);
    std::string path(pathChars);
    env->ReleaseStringUTFChars(jPath, pathChars);

    photo2model::ImageInfo info = photo2model::ProbeImage(path);

    jintArray result = env->NewIntArray(5);
    jint values[5] = {
            info.valid ? 1 : 0,
            info.width,
            info.height,
            info.channels,
            static_cast<jint>(info.format)
    };
    env->SetIntArrayRegion(result, 0, 5, values);
    return result;
}

// Mask ops below all mutate the passed-in ByteArray in place (0/255 mask
// bytes reinterpret cleanly as uint8_t regardless of jbyte's signedness -
// only the Kotlin side needs to mask with 0xFF when reading values back).

extern "C" JNIEXPORT void JNICALL
Java_com_photo2model_studio_core_NativeBridge_nativeFloodFillInPlace(
        JNIEnv* env, jobject /* thiz */, jbyteArray jMask,
        jint width, jint height, jint seedX, jint seedY, jint newValue) {
    jbyte* mask = env->GetByteArrayElements(jMask, nullptr);
    photo2model::FloodFill(reinterpret_cast<uint8_t*>(mask), width, height,
                            seedX, seedY, static_cast<uint8_t>(newValue));
    env->ReleaseByteArrayElements(jMask, mask, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_photo2model_studio_core_NativeBridge_nativeFillHolesInPlace(
        JNIEnv* env, jobject /* thiz */, jbyteArray jMask, jint width, jint height) {
    jbyte* mask = env->GetByteArrayElements(jMask, nullptr);
    photo2model::FillHoles(reinterpret_cast<uint8_t*>(mask), width, height);
    env->ReleaseByteArrayElements(jMask, mask, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_photo2model_studio_core_NativeBridge_nativeKeepLargestComponentInPlace(
        JNIEnv* env, jobject /* thiz */, jbyteArray jMask, jint width, jint height) {
    jbyte* mask = env->GetByteArrayElements(jMask, nullptr);
    photo2model::KeepLargestComponent(reinterpret_cast<uint8_t*>(mask), width, height);
    env->ReleaseByteArrayElements(jMask, mask, 0);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_photo2model_studio_core_NativeBridge_nativeTraceContour(
        JNIEnv* env, jobject /* thiz */, jbyteArray jMask, jint width, jint height) {
    jbyte* mask = env->GetByteArrayElements(jMask, nullptr);
    std::vector<int32_t> contour = photo2model::TraceContour(
            reinterpret_cast<uint8_t*>(mask), width, height);
    env->ReleaseByteArrayElements(jMask, mask, JNI_ABORT);  // read-only here

    jintArray result = env->NewIntArray(static_cast<jsize>(contour.size()));
    if (!contour.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(contour.size()), contour.data());
    }
    return result;
}

// Carves a voxel visual hull from however many view masks are available and
// extracts a surface via Surface Nets, in one native call so the voxel grid
// (which can be a few hundred KB) never has to cross the JNI boundary.
//
// Result layout, a single flat FloatArray:
//   [0] = vertexCount, [1] = indexCount,
//   then vertexCount*3 floats of vertex positions,
//   then vertexCount*3 floats of normals,
//   then indexCount floats (each an exact integer index value).
// Counts are stored as float because every mesh this phase produces is far
// under 2^24 vertices, where float stops being able to represent every
// integer exactly - Kotlin casts them back with .toInt() on the way out.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_photo2model_studio_core_NativeBridge_nativeBuildVisualHullMesh(
        JNIEnv* env, jobject /* thiz */,
        jobjectArray jMaskArrays, jintArray jWidths, jintArray jHeights, jintArray jAxisModes,
        jfloatArray jMinU, jfloatArray jMinV, jfloatArray jMaxU, jfloatArray jMaxV,
        jint resolution) {

    jsize viewCount = env->GetArrayLength(jMaskArrays);
    jint* widths = env->GetIntArrayElements(jWidths, nullptr);
    jint* heights = env->GetIntArrayElements(jHeights, nullptr);
    jint* axisModes = env->GetIntArrayElements(jAxisModes, nullptr);
    jfloat* minU = env->GetFloatArrayElements(jMinU, nullptr);
    jfloat* minV = env->GetFloatArrayElements(jMinV, nullptr);
    jfloat* maxU = env->GetFloatArrayElements(jMaxU, nullptr);
    jfloat* maxV = env->GetFloatArrayElements(jMaxV, nullptr);

    std::vector<jbyteArray> maskRefs(viewCount);
    std::vector<jbyte*> maskPtrs(viewCount);
    std::vector<photo2model::ViewConstraint> views(viewCount);

    for (jsize i = 0; i < viewCount; i++) {
        auto maskArr = static_cast<jbyteArray>(env->GetObjectArrayElement(jMaskArrays, i));
        maskRefs[i] = maskArr;
        maskPtrs[i] = env->GetByteArrayElements(maskArr, nullptr);

        views[i].mask = reinterpret_cast<const uint8_t*>(maskPtrs[i]);
        views[i].width = widths[i];
        views[i].height = heights[i];
        views[i].axisMode = static_cast<photo2model::ViewAxisMode>(axisModes[i]);
        views[i].minU = minU[i];
        views[i].minV = minV[i];
        views[i].maxU = maxU[i];
        views[i].maxV = maxV[i];
    }

    photo2model::VoxelGrid grid = photo2model::CarveVisualHull(views, resolution);
    photo2model::Mesh mesh = photo2model::ExtractSurfaceNets(grid);

    for (jsize i = 0; i < viewCount; i++) {
        env->ReleaseByteArrayElements(maskRefs[i], maskPtrs[i], JNI_ABORT);
        env->DeleteLocalRef(maskRefs[i]);
    }
    env->ReleaseIntArrayElements(jWidths, widths, JNI_ABORT);
    env->ReleaseIntArrayElements(jHeights, heights, JNI_ABORT);
    env->ReleaseIntArrayElements(jAxisModes, axisModes, JNI_ABORT);
    env->ReleaseFloatArrayElements(jMinU, minU, JNI_ABORT);
    env->ReleaseFloatArrayElements(jMinV, minV, JNI_ABORT);
    env->ReleaseFloatArrayElements(jMaxU, maxU, JNI_ABORT);
    env->ReleaseFloatArrayElements(jMaxV, maxV, JNI_ABORT);

    jsize vertexCount = static_cast<jsize>(mesh.vertices.size() / 3);
    jsize indexCount = static_cast<jsize>(mesh.indices.size());
    jsize totalFloats = 2 + vertexCount * 3 + vertexCount * 3 + indexCount;

    std::vector<jfloat> packed(totalFloats);
    packed[0] = static_cast<jfloat>(vertexCount);
    packed[1] = static_cast<jfloat>(indexCount);
    size_t offset = 2;
    std::copy(mesh.vertices.begin(), mesh.vertices.end(), packed.begin() + offset);
    offset += mesh.vertices.size();
    std::copy(mesh.normals.begin(), mesh.normals.end(), packed.begin() + offset);
    offset += mesh.normals.size();
    for (jsize i = 0; i < indexCount; i++) {
        packed[offset + i] = static_cast<jfloat>(mesh.indices[i]);
    }

    jfloatArray result = env->NewFloatArray(totalFloats);
    env->SetFloatArrayRegion(result, 0, totalFloats, packed.data());
    return result;
}
