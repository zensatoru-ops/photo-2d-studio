package com.photo2model.studio.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Masks are stored as single-channel 8-bit PNGs under masks/<slug>_mask.png
 * (section 5.1). Encoded through an ALPHA_8 bitmap - PNG compression of an
 * ALPHA_8 buffer produces a standard 8-bit grayscale PNG with no color
 * data, and BitmapFactory.decodeFile with inPreferredConfig = ALPHA_8 reads
 * it straight back into the same byte layout. 255 = foreground (character),
 * 0 = background, matching the convention the native mask ops expect.
 */
object MaskManager {

    fun maskFileFor(handle: ProjectHandle, viewType: ViewType): File =
        File(handle.dir, "masks/${viewType.slug}_mask.png")

    fun createEmptyMask(width: Int, height: Int): ByteArray = ByteArray(width * height)

    /**
     * Seeds a mask from the source image's alpha channel, if it has one.
     * Section 5.3-E: "Jika PNG sudah transparan, gunakan alpha sebagai mask
     * awal." Pixels with alpha above the midpoint are foreground.
     */
    fun maskFromAlphaChannel(source: Bitmap): ByteArray {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = ByteArray(width * height)
        for (i in pixels.indices) {
            val alpha = (pixels[i] ushr 24) and 0xFF
            mask[i] = if (alpha > 127) 255.toByte() else 0
        }
        return mask
    }

    /**
     * Section 5.3-B: color threshold. Samples the color at (sampleX,
     * sampleY) as the background key, then marks every pixel within
     * [tolerance] Euclidean RGB distance of that key as background and
     * everything else as foreground. Works well for the flat, uniform
     * backgrounds the master plan recommends for turnaround sheets;
     * explicitly not meant for complex backgrounds (section 5.3 says as much).
     */
    fun maskFromColorThreshold(source: Bitmap, sampleX: Int, sampleY: Int, tolerance: Float): ByteArray {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val keyPixel = pixels[sampleY.coerceIn(0, height - 1) * width + sampleX.coerceIn(0, width - 1)]
        val keyR = (keyPixel ushr 16) and 0xFF
        val keyG = (keyPixel ushr 8) and 0xFF
        val keyB = keyPixel and 0xFF

        val mask = ByteArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val dr = (r - keyR).toDouble()
            val dg = (g - keyG).toDouble()
            val db = (b - keyB).toDouble()
            val distance = sqrt(dr * dr + dg * dg + db * db)
            mask[i] = if (distance > tolerance) 255.toByte() else 0
        }
        return mask
    }

    fun save(file: File, mask: ByteArray, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(mask))
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
    }

    /** Returns null if no mask file exists yet for this view. */
    fun load(file: File): Triple<ByteArray, Int, Int>? {
        if (!file.exists()) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ALPHA_8 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val buffer = ByteBuffer.allocate(bitmap.width * bitmap.height)
        bitmap.copyPixelsToBuffer(buffer)
        val mask = buffer.array()
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return Triple(mask, width, height)
    }

    /** Bounding box of foreground pixels, in mask-pixel coordinates. Null if the mask is empty. */
    fun boundingBox(mask: ByteArray, width: Int, height: Int): IntArray? {
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x] != 0.toByte()) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }

    fun foregroundPixelCount(mask: ByteArray): Int = mask.count { it != 0.toByte() }
}
