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

    /** Which two world axes a view constrains - mirrors ViewAxisMode in voxel_carve.h exactly. */
    enum class ViewAxisMode(val code: Int) {
        FRONT(0), BACK(1), LEFT(2), RIGHT(3), TOP(4)
    }

    data class VisualHullMesh(val vertices: FloatArray, val normals: FloatArray, val indices: IntArray) {
        val vertexCount: Int get() = vertices.size / 3
        val triangleCount: Int get() = indices.size / 3
    }

    class ViewConstraintInput(
        val mask: ByteArray,
        val width: Int,
        val height: Int,
        val axisMode: ViewAxisMode,
        val minU: Float,
        val minV: Float,
        val maxU: Float,
        val maxV: Float
    )

    /**
     * Carves a voxel visual hull from whichever views are present and
     * extracts a mesh via Surface Nets, all in one native call. resolution
     * is voxels per axis - cost scales as resolution^3 * viewCount, so this
     * runs off the UI thread.
     */
    fun buildVisualHullMesh(views: List<ViewConstraintInput>, resolution: Int): VisualHullMesh {
        val masks = Array(views.size) { views[it].mask }
        val widths = IntArray(views.size) { views[it].width }
        val heights = IntArray(views.size) { views[it].height }
        val axisModes = IntArray(views.size) { views[it].axisMode.code }
        val minU = FloatArray(views.size) { views[it].minU }
        val minV = FloatArray(views.size) { views[it].minV }
        val maxU = FloatArray(views.size) { views[it].maxU }
        val maxV = FloatArray(views.size) { views[it].maxV }

        val packed = nativeBuildVisualHullMesh(masks, widths, heights, axisModes, minU, minV, maxU, maxV, resolution)
        val vertexCount = packed[0].toInt()
        val indexCount = packed[1].toInt()

        var offset = 2
        val vertices = packed.copyOfRange(offset, offset + vertexCount * 3)
        offset += vertexCount * 3
        val normals = packed.copyOfRange(offset, offset + vertexCount * 3)
        offset += vertexCount * 3
        val indicesFloat = packed.copyOfRange(offset, offset + indexCount)
        val indices = IntArray(indexCount) { indicesFloat[it].toInt() }

        return VisualHullMesh(vertices, normals, indices)
    }

    private external fun nativeGetCoreVersion(): String
    private external fun nativeProbeImage(path: String): IntArray
    private external fun nativeFloodFillInPlace(mask: ByteArray, width: Int, height: Int, seedX: Int, seedY: Int, newValue: Int)
    private external fun nativeFillHolesInPlace(mask: ByteArray, width: Int, height: Int)
    private external fun nativeKeepLargestComponentInPlace(mask: ByteArray, width: Int, height: Int)
    private external fun nativeTraceContour(mask: ByteArray, width: Int, height: Int): IntArray
    private external fun nativeBuildVisualHullMesh(
        maskArrays: Array<ByteArray>, widths: IntArray, heights: IntArray, axisModes: IntArray,
        minU: FloatArray, minV: FloatArray, maxU: FloatArray, maxV: FloatArray, resolution: Int
    ): FloatArray
}
