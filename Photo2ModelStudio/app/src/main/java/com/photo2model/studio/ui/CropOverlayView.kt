package com.photo2model.studio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a draggable, resizable crop rectangle on top of an image preview.
 * Coordinates are tracked in view-local pixels and exposed as 0..1
 * fractions of the view bounds so ProjectManager can store them independent
 * of whatever size the preview happens to be rendered at.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val rect = RectF()
    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val handleTouchSlop = 48f

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#4CC2FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000")
    }
    private val handlePaint = Paint().apply {
        color = Color.parseColor("#4CC2FF")
        style = Paint.Style.FILL
    }

    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = min(w, h) * 0.1f
        rect.set(margin, margin, w - margin, h - margin)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)
        canvas.drawRect(rect, boxPaint)

        val handleRadius = 14f
        canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                return dragMode != DragMode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                applyDrag(dx, dy)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): DragMode {
        fun near(px: Float, py: Float) = abs(x - px) < handleTouchSlop && abs(y - py) < handleTouchSlop
        return when {
            near(rect.left, rect.top) -> DragMode.TOP_LEFT
            near(rect.right, rect.top) -> DragMode.TOP_RIGHT
            near(rect.left, rect.bottom) -> DragMode.BOTTOM_LEFT
            near(rect.right, rect.bottom) -> DragMode.BOTTOM_RIGHT
            rect.contains(x, y) -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val minSize = 64f
        when (dragMode) {
            DragMode.MOVE -> {
                val newLeft = (rect.left + dx).coerceIn(0f, width - rect.width())
                val newTop = (rect.top + dy).coerceIn(0f, height - rect.height())
                rect.offsetTo(newLeft, newTop)
            }
            DragMode.TOP_LEFT -> {
                rect.left = min(rect.left + dx, rect.right - minSize).coerceAtLeast(0f)
                rect.top = min(rect.top + dy, rect.bottom - minSize).coerceAtLeast(0f)
            }
            DragMode.TOP_RIGHT -> {
                rect.right = max(rect.right + dx, rect.left + minSize).coerceAtMost(width.toFloat())
                rect.top = min(rect.top + dy, rect.bottom - minSize).coerceAtLeast(0f)
            }
            DragMode.BOTTOM_LEFT -> {
                rect.left = min(rect.left + dx, rect.right - minSize).coerceAtLeast(0f)
                rect.bottom = max(rect.bottom + dy, rect.top + minSize).coerceAtMost(height.toFloat())
            }
            DragMode.BOTTOM_RIGHT -> {
                rect.right = max(rect.right + dx, rect.left + minSize).coerceAtMost(width.toFloat())
                rect.bottom = max(rect.bottom + dy, rect.top + minSize).coerceAtMost(height.toFloat())
            }
            DragMode.NONE -> {}
        }
    }

    /** Crop rectangle as fractions (0..1) of the view's bounds. */
    fun getCropFraction(): RectF {
        if (width == 0 || height == 0) return RectF(0f, 0f, 1f, 1f)
        return RectF(rect.left / width, rect.top / height, rect.right / width, rect.bottom / height)
    }

    fun reset() {
        onSizeChanged(width, height, width, height)
    }

    /** Restores a previously saved crop (as 0..1 fractions) once the view has its real size. */
    fun setCropFraction(fraction: RectF) {
        if (width == 0 || height == 0) return
        rect.set(fraction.left * width, fraction.top * height, fraction.right * width, fraction.bottom * height)
        invalidate()
    }
}
