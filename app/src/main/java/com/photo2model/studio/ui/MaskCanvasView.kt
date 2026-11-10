package com.photo2model.studio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import kotlin.math.max

/**
 * Sits on top of an ImageView showing the source photo at FIT_CENTER and
 * edits a mask held at the image's own pixel resolution - not the view's.
 * All touch coordinates get mapped through the ImageView's image matrix so
 * brush strokes land on the right pixel regardless of how the preview is
 * scaled or letterboxed.
 */
class MaskCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { LASSO, BRUSH, ERASER }

    var tool: Tool = Tool.LASSO
    var brushRadiusViewPx: Float = 36f

    lateinit var mask: ByteArray
        private set
    var maskWidth = 0
        private set
    var maskHeight = 0
        private set

    private var targetImageView: ImageView? = null
    private var overlayBitmap: Bitmap? = null
    private val lassoPointsView = mutableListOf<PointF>()
    private var onMaskChanged: (() -> Unit)? = null

    private val lassoPaint = Paint().apply {
        color = Color.parseColor("#4CC2FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val overlayPaint = Paint().apply { isAntiAlias = false }
    private val contourPaint = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private var contourMaskSpace: IntArray? = null

    fun bind(imageView: ImageView, mask: ByteArray, width: Int, height: Int, onChanged: () -> Unit) {
        targetImageView = imageView
        this.mask = mask
        maskWidth = width
        maskHeight = height
        onMaskChanged = onChanged
        rebuildOverlay()
        invalidate()
    }

    private fun rebuildOverlay() {
        if (maskWidth == 0 || maskHeight == 0) return
        val bmp = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(maskWidth * maskHeight)
        for (i in pixels.indices) {
            pixels[i] = if (mask[i] != 0.toByte()) Color.argb(110, 0x4C, 0xC2, 0xFF) else Color.TRANSPARENT
        }
        bmp.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        overlayBitmap?.recycle()
        overlayBitmap = bmp
    }

    /** The image's drawn rect inside this view, accounting for FIT_CENTER letterboxing. */
    private fun imageRect(): RectF? {
        val iv = targetImageView ?: return null
        val drawable = iv.drawable ?: return null
        val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        val matrix: Matrix = iv.imageMatrix
        matrix.mapRect(rect)
        // imageMatrix is relative to the ImageView's own bounds; this overlay
        // is laid out to exactly match the ImageView, so no further offset needed.
        return rect
    }

    private fun viewToMask(viewX: Float, viewY: Float): PointF? {
        val rect = imageRect() ?: return null
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        val fx = (viewX - rect.left) / rect.width()
        val fy = (viewY - rect.top) / rect.height()
        return PointF(fx * maskWidth, fy * maskHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = imageRect() ?: return
        overlayBitmap?.let { canvas.drawBitmap(it, null, rect, overlayPaint) }

        if (tool == Tool.LASSO && lassoPointsView.size > 1) {
            val path = Path()
            path.moveTo(lassoPointsView[0].x, lassoPointsView[0].y)
            for (i in 1 until lassoPointsView.size) path.lineTo(lassoPointsView[i].x, lassoPointsView[i].y)
            canvas.drawPath(path, lassoPaint)
        }

        contourMaskSpace?.let { flat ->
            if (flat.size < 4) return@let
            val fx = rect.width() / maskWidth
            val fy = rect.height() / maskHeight
            val path = Path()
            path.moveTo(rect.left + flat[0] * fx, rect.top + flat[1] * fy)
            var i = 2
            while (i + 1 < flat.size) {
                path.lineTo(rect.left + flat[i] * fx, rect.top + flat[i + 1] * fy)
                i += 2
            }
            path.close()
            canvas.drawPath(path, contourPaint)
        }
    }

    /** Runs the native Moore-neighbor tracer on the current mask and overlays the result. */
    fun traceAndShowContour() {
        contourMaskSpace = com.photo2model.studio.core.NativeBridge.traceContour(mask, maskWidth, maskHeight)
        invalidate()
    }

    fun clearContourOverlay() {
        contourMaskSpace = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (tool) {
            Tool.LASSO -> handleLasso(event)
            Tool.BRUSH -> handleBrush(event, paint = true)
            Tool.ERASER -> handleBrush(event, paint = false)
        }
        return true
    }

    private fun handleLasso(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lassoPointsView.clear()
                lassoPointsView.add(PointF(event.x, event.y))
            }
            MotionEvent.ACTION_MOVE -> lassoPointsView.add(PointF(event.x, event.y))
        }
        invalidate()
    }

    /** Closes the lasso path and fills it into the mask. Call from the editor's "commit" button. */
    fun commitLasso(fillValue: Int) {
        if (lassoPointsView.size < 3) { lassoPointsView.clear(); invalidate(); return }

        val maskPoints = lassoPointsView.mapNotNull { viewToMask(it.x, it.y) }
        if (maskPoints.size < 3) { lassoPointsView.clear(); invalidate(); return }

        val path = Path()
        path.moveTo(maskPoints[0].x, maskPoints[0].y)
        for (i in 1 until maskPoints.size) path.lineTo(maskPoints[i].x, maskPoints[i].y)
        path.close()

        val temp = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ALPHA_8)
        val tempCanvas = Canvas(temp)
        val fillPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true; style = Paint.Style.FILL }
        tempCanvas.drawPath(path, fillPaint)

        val buffer = java.nio.ByteBuffer.allocate(maskWidth * maskHeight)
        temp.copyPixelsToBuffer(buffer)
        val filled = buffer.array()
        temp.recycle()

        for (i in mask.indices) {
            if (filled[i] != 0.toByte()) mask[i] = fillValue.toByte()
        }

        lassoPointsView.clear()
        rebuildOverlay()
        invalidate()
        onMaskChanged?.invoke()
    }

    fun cancelLasso() {
        lassoPointsView.clear()
        invalidate()
    }

    private fun handleBrush(event: MotionEvent, paint: Boolean) {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) return
        val maskPoint = viewToMask(event.x, event.y) ?: return
        val rect = imageRect() ?: return
        val scale = maskWidth / max(rect.width(), 1f)
        val radiusMask = (brushRadiusViewPx * scale).toInt().coerceAtLeast(1)

        val cx = maskPoint.x.toInt()
        val cy = maskPoint.y.toInt()
        val minX = (cx - radiusMask).coerceAtLeast(0)
        val maxX = (cx + radiusMask).coerceAtMost(maskWidth - 1)
        val minY = (cy - radiusMask).coerceAtLeast(0)
        val maxY = (cy + radiusMask).coerceAtMost(maskHeight - 1)
        val r2 = radiusMask * radiusMask
        val value = if (paint) 255.toByte() else 0.toByte()

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= r2) mask[y * maskWidth + x] = value
            }
        }
        rebuildOverlay()
        invalidate()
        onMaskChanged?.invoke()
    }

    fun invert() {
        for (i in mask.indices) mask[i] = if (mask[i] != 0.toByte()) 0 else 255.toByte()
        rebuildOverlay()
        invalidate()
        onMaskChanged?.invoke()
    }

    fun refreshFromMask() {
        rebuildOverlay()
        invalidate()
    }
}
