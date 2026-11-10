package com.photo2model.studio.core

import org.json.JSONObject
import java.io.File

sealed class ReconstructionResult {
    data class Success(val mesh: NativeBridge.VisualHullMesh, val objFile: File, val usedViews: List<ViewType>) : ReconstructionResult()
    data class Failure(val reason: String) : ReconstructionResult()
}

/**
 * Phase 3's "First 3D Reconstruction": gathers whatever view masks exist,
 * calibrates each one off its own silhouette bounding box (section 5.6 -
 * there's no camera calibration step in this phase, so each view trusts its
 * own mask extent), carves a voxel visual hull, and extracts a mesh. This
 * is Method B from the master plan, run as the MVP baseline; Method C
 * (manual cross-section correction) is Phase 4, not here.
 */
object ReconstructionManager {

    private val ORTHOGRAPHIC_VIEWS = listOf(ViewType.FRONT, ViewType.BACK, ViewType.SIDE_LEFT, ViewType.SIDE_RIGHT, ViewType.TOP)

    fun build(handle: ProjectHandle, resolution: Int): ReconstructionResult {
        val constraints = mutableListOf<NativeBridge.ViewConstraintInput>()
        val usedViews = mutableListOf<ViewType>()

        for (type in ORTHOGRAPHIC_VIEWS) {
            val axisMode = axisModeFor(type) ?: continue
            val loaded = MaskManager.load(MaskManager.maskFileFor(handle, type)) ?: continue
            val (bytes, w, h) = loaded
            val bbox = MaskManager.boundingBox(bytes, w, h) ?: continue  // empty mask - skip rather than carve against nothing

            constraints += NativeBridge.ViewConstraintInput(
                mask = bytes, width = w, height = h, axisMode = axisMode,
                minU = bbox[0].toFloat(), minV = bbox[1].toFloat(),
                maxU = bbox[2].toFloat(), maxV = bbox[3].toFloat()
            )
            usedViews += type
        }

        val hasFront = ViewType.FRONT in usedViews
        val hasSide = ViewType.SIDE_LEFT in usedViews || ViewType.SIDE_RIGHT in usedViews
        if (!hasFront || !hasSide) {
            val have = if (usedViews.isEmpty()) "none" else usedViews.joinToString { it.slug }
            return ReconstructionResult.Failure(
                "Need a Front mask and at least one Side mask to carve a hull from two orthogonal directions - have: $have"
            )
        }

        val mesh = NativeBridge.buildVisualHullMesh(constraints, resolution)
        if (mesh.vertexCount == 0) {
            return ReconstructionResult.Failure(
                "Carve produced an empty volume at resolution $resolution - the masks may not overlap once projected into 3D, or the resolution is too coarse to catch the overlap"
            )
        }

        val objFile = File(handle.dir, "reconstruction/preview_mesh.obj")
        ObjExporter.write(objFile, mesh)
        saveSettings(handle, resolution, usedViews)

        return ReconstructionResult.Success(mesh, objFile, usedViews)
    }

    private fun axisModeFor(type: ViewType): NativeBridge.ViewAxisMode? = when (type) {
        ViewType.FRONT -> NativeBridge.ViewAxisMode.FRONT
        ViewType.BACK -> NativeBridge.ViewAxisMode.BACK
        ViewType.SIDE_LEFT -> NativeBridge.ViewAxisMode.LEFT
        ViewType.SIDE_RIGHT -> NativeBridge.ViewAxisMode.RIGHT
        ViewType.TOP -> NativeBridge.ViewAxisMode.TOP
        ViewType.THREE_QUARTER -> null  // doesn't fit a single orthographic axis pair
    }

    private fun saveSettings(handle: ProjectHandle, resolution: Int, usedViews: List<ViewType>) {
        val settings = JSONObject().apply {
            put("resolution", resolution)
            put("method", "visual_hull_surface_nets")
            put("usedViews", usedViews.map { it.slug })
            put("builtAtEpochMs", System.currentTimeMillis())
        }
        File(handle.dir, "reconstruction/settings.json").apply {
            parentFile?.mkdirs()
            writeText(settings.toString(2))
        }
    }
}
