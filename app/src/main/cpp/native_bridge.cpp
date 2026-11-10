#include <jni.h>
#include <string>
#include <vector>

#include "image_probe.h"
#include "mask_ops.h"

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
