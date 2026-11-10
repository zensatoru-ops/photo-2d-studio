package com.photo2model.studio.core

/**
 * Thin wrapper over libphoto2model_core.so. Phase 1 only exposes header
 * probing - everything in the reconstruction pipeline (visual hull, mesh,
 * texture projection) lands on this same bridge in later phases without
 * changing this class's public shape.
 */
object NativeBridge {

    init {
        System.loadLibrary("photo2model_core")
    }

    enum class ImageFormat(val code: Int) {
        UNKNOWN(0), PNG(1), JPEG(2), WEBP(3);

        companion object {
            fun fromCode(code: Int): ImageFormat = values().firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class ImageProbeResult(
        val valid: Boolean,
        val width: Int,
        val height: Int,
        val channels: Int,
        val format: ImageFormat
    )

    fun coreVersion(): String = nativeGetCoreVersion()

    fun probeImage(path: String): ImageProbeResult {
        val raw = nativeProbeImage(path)
        return ImageProbeResult(
            valid = raw[0] == 1,
            width = raw[1],
            height = raw[2],
            channels = raw[3],
            format = ImageFormat.fromCode(raw[4])
        )
    }

    /** mask is mutated in place. 0 = background, 255 = foreground. */
    fun floodFill(mask: ByteArray, width: Int, height: Int, seedX: Int, seedY: Int, newValue: Int) {
        nativeFloodFillInPlace(mask, width, height, seedX, seedY, newValue)
    }

    fun fillHoles(mask: ByteArray, width: Int, height: Int) {
        nativeFillHolesInPlace(mask, width, height)
    }

    fun keepLargestComponent(mask: ByteArray, width: Int, height: Int) {
        nativeKeepLargestComponentInPlace(mask, width, height)
    }

    /** Returns a flat [x0,y0,x1,y1,...] boundary polyline, empty if the mask has no foreground. */
    fun traceContour(mask: ByteArray, width: Int, height: Int): IntArray =
        nativeTraceContour(mask, width, height)

    private external fun nativeGetCoreVersion(): String
    private external fun nativeProbeImage(path: String): IntArray
    private external fun nativeFloodFillInPlace(mask: ByteArray, width: Int, height: Int, seedX: Int, seedY: Int, newValue: Int)
    private external fun nativeFillHolesInPlace(mask: ByteArray, width: Int, height: Int)
    private external fun nativeKeepLargestComponentInPlace(mask: ByteArray, width: Int, height: Int)
    private external fun nativeTraceContour(mask: ByteArray, width: Int, height: Int): IntArray
}
