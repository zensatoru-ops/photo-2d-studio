package com.photo2model.studio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.photo2model.studio.core.LandmarkPoint
import com.photo2model.studio.core.LandmarkSet
import com.photo2model.studio.core.Requirement

class LandmarkOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private lateinit var landmarkSet: LandmarkSet
    private var targetImageView: ImageView? = null
    var activeLandmarkId: String? = null
    private var onPointPlaced: ((String) -> Unit)? = null

    private var draggingId: String? = null
    private val touchSlopPx = 48f

    private val dotPaintRequired = Paint().apply { color = Color.parseColor("#DC2626"); isAntiAlias = true }
    private val dotPaintRecommended = Paint().apply { color = Color.parseColor("#D97706"); isAntiAlias = true }
    private val dotPaintOptional = Paint().apply { color = Color.parseColor("#6B7280"); isAntiAlias = true }
    private val dotPaintActive = Paint().apply { color = Color.parseColor("#4CC2FF"); isAntiAlias = true }
    private val ringPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }

    fun bind(imageView: ImageView, set: LandmarkSet, onPlaced: (String) -> Unit) {
        targetImageView = imageView
        landmarkSet = set
        onPointPlaced = onPlaced
        invalidate()
    }

    private fun imageRect(): RectF? {
        val iv = targetImageView ?: return null
        val drawable = iv.drawable ?: return null
        val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        val matrix: Matrix = iv.imageMatrix
        matrix.mapRect(rect)
        return rect
    }

    private fun fractionToView(point: LandmarkPoint): PointF? {
        val rect = imageRect() ?: return null
        return PointF(rect.left + point.x * rect.width(), rect.top + point.y * rect.height())
    }

    private fun viewToFraction(x: Float, y: Float): PointF? {
        val rect = imageRect() ?: return null
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        return PointF(((x - rect.left) / rect.width()).coerceIn(0f, 1f), ((y - rect.top) / rect.height()).coerceIn(0f, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::landmarkSet.isInitialized) return

        for ((id, point) in landmarkSet.points) {
            val viewPoint = fractionToView(point) ?: continue
            val spec = com.photo2model.studio.core.LandmarkSchema.specsFor(landmarkSet.viewType).firstOrNull { it.id == id }
            val paint = when {
                id == activeLandmarkId -> dotPaintActive
                spec?.requirement == Requirement.REQUIRED -> dotPaintRequired
                spec?.requirement == Requirement.RECOMMENDED -> dotPaintRecommended
                else -> dotPaintOptional
            }
            canvas.drawCircle(viewPoint.x, viewPoint.y, 14f, paint)
            canvas.drawCircle(viewPoint.x, viewPoint.y, 14f, ringPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::landmarkSet.isInitialized) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingId = hitTest(event.x, event.y)
                if (draggingId == null) {
                    placeActiveLandmark(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val id = draggingId ?: return true
                val frac = viewToFraction(event.x, event.y) ?: return true
                landmarkSet.points[id] = LandmarkPoint(id, frac.x, frac.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingId = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): String? {
        for ((id, point) in landmarkSet.points) {
            val viewPoint = fractionToView(point) ?: continue
            if (kotlin.math.abs(x - viewPoint.x) < touchSlopPx && kotlin.math.abs(y - viewPoint.y) < touchSlopPx) return id
        }
        return null
    }

    private fun placeActiveLandmark(x: Float, y: Float) {
        val id = activeLandmarkId ?: return
        val frac = viewToFraction(x, y) ?: return
        landmarkSet.points[id] = LandmarkPoint(id, frac.x, frac.y)
        invalidate()
        onPointPlaced?.invoke(id)
    }

    fun refresh() = invalidate()
}
