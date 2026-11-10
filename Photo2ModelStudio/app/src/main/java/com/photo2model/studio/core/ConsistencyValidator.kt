package com.photo2model.studio.core

import kotlin.math.abs

data class ConsistencyWarning(val message: String)

/**
 * Section 5.5, full version. The import-time check in ImportActivity only
 * had raw image aspect ratio to work with; once masks and landmarks exist
 * this runs the actual checks the master plan lists as examples:
 * cross-view height drift, missing landmarks, empty masks, landmarks that
 * land outside the silhouette, front/back silhouette divergence, and a
 * simple tilt heuristic.
 */
object ConsistencyValidator {

    private const val HEIGHT_DRIFT_WARN_FRACTION = 0.06f
    private const val ASPECT_DIVERGENCE_WARN_FRACTION = 0.20f
    private const val TILT_WARN_FRACTION = 0.05f
    private const val LANDMARK_OFF_MASK_SEARCH_RADIUS_FRAC = 0.03f

    fun validate(handle: ProjectHandle): List<ConsistencyWarning> {
        val warnings = mutableListOf<ConsistencyWarning>()
        val views = handle.model.views

        val missingRequiredViews = ViewType.values()
            .filter { it.requirement == Requirement.REQUIRED }
            .filter { views[it] == null }
        if (missingRequiredViews.isNotEmpty()) {
            warnings += ConsistencyWarning(
                "Missing required view(s): ${missingRequiredViews.joinToString { it.slug }}"
            )
        }

        val landmarkSets = views.keys.associateWith { LandmarkSet.load(handle, it) }
        val maskData = views.keys.associateWith { type ->
            MaskManager.load(MaskManager.maskFileFor(handle, type))
        }

        checkHeightDrift(views, landmarkSets, warnings)
        checkMissingLandmarks(views, landmarkSets, warnings)
        checkEmptyMasks(views, maskData, warnings)
        checkLandmarksOffMask(views, landmarkSets, maskData, warnings)
        checkFrontBackSilhouetteDivergence(views, maskData, warnings)
        checkTilt(views, landmarkSets, warnings)

        return warnings
    }

    private fun characterHeightPx(view: ViewSlot, landmarks: LandmarkSet): Float? {
        val top = landmarks.points["top_of_head"] ?: return null
        val bottom = landmarks.points["heel"] ?: landmarks.points["ankle_left"]
            ?: landmarks.points["ankle_right"] ?: landmarks.points["foot_back"] ?: return null
        return abs(bottom.y - top.y) * view.sourceHeight
    }

    private fun checkHeightDrift(
        views: Map<ViewType, ViewSlot>,
        landmarkSets: Map<ViewType, LandmarkSet>,
        warnings: MutableList<ConsistencyWarning>
    ) {
        val heights = views.mapNotNull { (type, slot) ->
            landmarkSets[type]?.let { characterHeightPx(slot, it) }?.let { type to it }
        }.toMap()
        if (heights.size < 2) return

        val reference = heights[ViewType.FRONT] ?: heights.values.first()
        for ((type, height) in heights) {
            if (reference == 0f) continue
            val drift = abs(height - reference) / reference
            if (drift > HEIGHT_DRIFT_WARN_FRACTION && height != reference) {
                val pct = (drift * 100).toInt()
                val direction = if (height > reference) "taller" else "shorter"
                warnings += ConsistencyWarning(
                    "${labelFor(type)} view is $pct% $direction than the reference height — check the character is drawn at the same scale."
                )
            }
        }
    }

    private fun checkMissingLandmarks(
        views: Map<ViewType, ViewSlot>,
        landmarkSets: Map<ViewType, LandmarkSet>,
        warnings: MutableList<ConsistencyWarning>
    ) {
        for (type in views.keys) {
            val missing = landmarkSets[type]?.missingRequired() ?: continue
            if (missing.isNotEmpty()) {
                warnings += ConsistencyWarning(
                    "${labelFor(type)} view is missing required landmark(s): ${missing.joinToString { it.displayName }}"
                )
            }
        }
    }

    private fun checkEmptyMasks(
        views: Map<ViewType, ViewSlot>,
        maskData: Map<ViewType, Triple<ByteArray, Int, Int>?>,
        warnings: MutableList<ConsistencyWarning>
    ) {
        for (type in views.keys) {
            val mask = maskData[type] ?: continue
            if (MaskManager.foregroundPixelCount(mask.first) == 0) {
                warnings += ConsistencyWarning("${labelFor(type)} view has a mask file with no foreground pixels — redraw or re-threshold it.")
            }
        }
    }

    private fun checkLandmarksOffMask(
        views: Map<ViewType, ViewSlot>,
        landmarkSets: Map<ViewType, LandmarkSet>,
        maskData: Map<ViewType, Triple<ByteArray, Int, Int>?>,
        warnings: MutableList<ConsistencyWarning>
    ) {
        for (type in views.keys) {
            val mask = maskData[type] ?: continue
            val (bytes, w, h) = mask
            val landmarks = landmarkSets[type] ?: continue
            val searchRadius = (LANDMARK_OFF_MASK_SEARCH_RADIUS_FRAC * maxOf(w, h)).toInt().coerceAtLeast(2)

            val offMask = landmarks.points.values.filter { point ->
                val px = (point.x * w).toInt()
                val py = (point.y * h).toInt()
                !hasForegroundNearby(bytes, w, h, px, py, searchRadius)
            }
            if (offMask.isNotEmpty()) {
                warnings += ConsistencyWarning(
                    "${labelFor(type)} view: landmark(s) fall outside the mask: ${offMask.joinToString { it.id }}"
                )
            }
        }
    }

    private fun hasForegroundNearby(mask: ByteArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int): Boolean {
        val minX = (cx - radius).coerceAtLeast(0)
        val maxX = (cx + radius).coerceAtMost(w - 1)
        val minY = (cy - radius).coerceAtLeast(0)
        val maxY = (cy + radius).coerceAtMost(h - 1)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (mask[y * w + x] != 0.toByte()) return true
            }
        }
        return false
    }

    private fun checkFrontBackSilhouetteDivergence(
        views: Map<ViewType, ViewSlot>,
        maskData: Map<ViewType, Triple<ByteArray, Int, Int>?>,
        warnings: MutableList<ConsistencyWarning>
    ) {
        val front = maskData[ViewType.FRONT] ?: return
        val back = maskData[ViewType.BACK] ?: return
        val frontBox = MaskManager.boundingBox(front.first, front.second, front.third) ?: return
        val backBox = MaskManager.boundingBox(back.first, back.second, back.third) ?: return

        val frontAspect = (frontBox[2] - frontBox[0]).toFloat() / (frontBox[3] - frontBox[1]).toFloat()
        val backAspect = (backBox[2] - backBox[0]).toFloat() / (backBox[3] - backBox[1]).toFloat()
        val divergence = abs(frontAspect - backAspect) / frontAspect
        if (divergence > ASPECT_DIVERGENCE_WARN_FRACTION) {
            warnings += ConsistencyWarning(
                "Back silhouette shape differs significantly from front (${(divergence * 100).toInt()}% aspect divergence) — check the pose matches."
            )
        }
    }

    private fun checkTilt(
        views: Map<ViewType, ViewSlot>,
        landmarkSets: Map<ViewType, LandmarkSet>,
        warnings: MutableList<ConsistencyWarning>
    ) {
        for (type in listOf(ViewType.SIDE_LEFT, ViewType.SIDE_RIGHT, ViewType.FRONT, ViewType.BACK)) {
            val landmarks = landmarkSets[type] ?: continue
            val top = landmarks.points["top_of_head"] ?: continue
            val bottom = landmarks.points["heel"] ?: landmarks.points["ankle_left"] ?: landmarks.points["ankle_right"] ?: continue
            val offset = abs(top.x - bottom.x)
            if (offset > TILT_WARN_FRACTION) {
                warnings += ConsistencyWarning(
                    "${labelFor(type)} view: head-to-foot horizontal offset is ${(offset * 100).toInt()}% of image width — character may be drawn tilted."
                )
            }
        }
    }

    private fun labelFor(type: ViewType): String = when (type) {
        ViewType.FRONT -> "Front"
        ViewType.BACK -> "Back"
        ViewType.SIDE_LEFT -> "Left side"
        ViewType.SIDE_RIGHT -> "Right side"
        ViewType.THREE_QUARTER -> "3/4"
        ViewType.TOP -> "Top"
    }
}
