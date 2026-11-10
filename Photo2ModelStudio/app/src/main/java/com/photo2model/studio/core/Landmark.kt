package com.photo2model.studio.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A single named landmark definition - section 5.4's "required/recommended/optional" grouping. */
data class LandmarkSpec(val id: String, val displayName: String, val requirement: Requirement)

/** A placed point for one landmark, in fractions (0..1) of the source image. */
data class LandmarkPoint(val id: String, val x: Float, val y: Float) {
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("x", x); put("y", y) }

    companion object {
        fun fromJson(json: JSONObject): LandmarkPoint =
            LandmarkPoint(json.getString("id"), json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
    }
}

object LandmarkSchema {

    /** Section 5.4's general landmark list - used for front/back/3-4/top views. */
    val FRONTAL: List<LandmarkSpec> = listOf(
        LandmarkSpec("top_of_head", "Top of Head", Requirement.REQUIRED),
        LandmarkSpec("chin", "Chin", Requirement.REQUIRED),
        LandmarkSpec("eye_left", "Left Eye Center", Requirement.RECOMMENDED),
        LandmarkSpec("eye_right", "Right Eye Center", Requirement.RECOMMENDED),
        LandmarkSpec("neck_center", "Neck Center", Requirement.RECOMMENDED),
        LandmarkSpec("shoulder_left", "Left Shoulder", Requirement.REQUIRED),
        LandmarkSpec("shoulder_right", "Right Shoulder", Requirement.REQUIRED),
        LandmarkSpec("elbow_left", "Left Elbow", Requirement.OPTIONAL),
        LandmarkSpec("elbow_right", "Right Elbow", Requirement.OPTIONAL),
        LandmarkSpec("wrist_left", "Left Wrist", Requirement.OPTIONAL),
        LandmarkSpec("wrist_right", "Right Wrist", Requirement.OPTIONAL),
        LandmarkSpec("chest_center", "Chest Center", Requirement.OPTIONAL),
        LandmarkSpec("waist_center", "Waist Center", Requirement.RECOMMENDED),
        LandmarkSpec("hip_left", "Left Hip", Requirement.REQUIRED),
        LandmarkSpec("hip_right", "Right Hip", Requirement.REQUIRED),
        LandmarkSpec("knee_left", "Left Knee", Requirement.OPTIONAL),
        LandmarkSpec("knee_right", "Right Knee", Requirement.OPTIONAL),
        LandmarkSpec("ankle_left", "Left Ankle", Requirement.REQUIRED),
        LandmarkSpec("ankle_right", "Right Ankle", Requirement.REQUIRED),
        LandmarkSpec("foot_front", "Foot Front", Requirement.RECOMMENDED),
        LandmarkSpec("foot_back", "Foot Back", Requirement.RECOMMENDED),
        LandmarkSpec("hair_extreme", "Hair Extreme Point", Requirement.OPTIONAL),
        LandmarkSpec("accessory_anchor", "Accessory Anchor", Requirement.OPTIONAL)
    )

    /** Section 5.4's profile list - used for left/right side views. */
    val PROFILE: List<LandmarkSpec> = listOf(
        LandmarkSpec("top_of_head", "Top of Head", Requirement.REQUIRED),
        LandmarkSpec("forehead", "Forehead", Requirement.RECOMMENDED),
        LandmarkSpec("nose_tip", "Nose Tip", Requirement.OPTIONAL),
        LandmarkSpec("chin", "Chin", Requirement.REQUIRED),
        LandmarkSpec("ear", "Ear", Requirement.RECOMMENDED),
        LandmarkSpec("shoulder", "Shoulder", Requirement.REQUIRED),
        LandmarkSpec("chest_depth", "Chest Depth", Requirement.OPTIONAL),
        LandmarkSpec("back_contour", "Back Contour", Requirement.OPTIONAL),
        LandmarkSpec("hip_depth", "Hip Depth", Requirement.REQUIRED),
        LandmarkSpec("knee_profile", "Knee Profile", Requirement.OPTIONAL),
        LandmarkSpec("heel", "Heel", Requirement.REQUIRED),
        LandmarkSpec("toe", "Toe", Requirement.RECOMMENDED)
    )

    fun specsFor(viewType: ViewType): List<LandmarkSpec> = when (viewType) {
        ViewType.SIDE_LEFT, ViewType.SIDE_RIGHT -> PROFILE
        ViewType.FRONT, ViewType.BACK, ViewType.THREE_QUARTER, ViewType.TOP -> FRONTAL
    }
}

class LandmarkSet(val viewType: ViewType, val points: MutableMap<String, LandmarkPoint> = mutableMapOf()) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("viewType", viewType.slug)
        val array = JSONArray()
        points.values.forEach { array.put(it.toJson()) }
        put("points", array)
    }

    fun missingRequired(): List<LandmarkSpec> =
        LandmarkSchema.specsFor(viewType).filter { it.requirement == Requirement.REQUIRED && it.id !in points }

    companion object {
        fun fromJson(json: JSONObject): LandmarkSet {
            val type = ViewType.fromSlug(json.optString("viewType")) ?: ViewType.FRONT
            val set = LandmarkSet(type)
            val array = json.optJSONArray("points") ?: JSONArray()
            for (i in 0 until array.length()) {
                val p = LandmarkPoint.fromJson(array.getJSONObject(i))
                set.points[p.id] = p
            }
            return set
        }

        fun fileFor(handle: ProjectHandle, viewType: ViewType): File =
            File(handle.dir, "landmarks/${viewType.slug}.json")

        fun load(handle: ProjectHandle, viewType: ViewType): LandmarkSet {
            val file = fileFor(handle, viewType)
            if (!file.exists()) return LandmarkSet(viewType)
            return runCatching { fromJson(JSONObject(file.readText())) }.getOrDefault(LandmarkSet(viewType))
        }

        fun save(handle: ProjectHandle, set: LandmarkSet) {
            val file = fileFor(handle, set.viewType)
            file.parentFile?.mkdirs()
            file.writeText(set.toJson().toString(2))
        }
    }
}
