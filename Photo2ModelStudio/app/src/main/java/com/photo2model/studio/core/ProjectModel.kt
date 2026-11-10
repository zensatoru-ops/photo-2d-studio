package com.photo2model.studio.core

import org.json.JSONArray
import org.json.JSONObject

/** Matches the view table in section 3.1 of the master plan. */
enum class ViewType(val slug: String, val requirement: Requirement) {
    FRONT("front", Requirement.REQUIRED),
    SIDE_LEFT("left", Requirement.REQUIRED),
    BACK("back", Requirement.RECOMMENDED),
    SIDE_RIGHT("right", Requirement.OPTIONAL),
    THREE_QUARTER("three_quarter", Requirement.OPTIONAL),
    TOP("top", Requirement.OPTIONAL);

    companion object {
        fun fromSlug(slug: String): ViewType? = values().firstOrNull { it.slug == slug }
    }
}

enum class Requirement { REQUIRED, RECOMMENDED, OPTIONAL }

data class ViewSlot(
    val viewType: ViewType,
    val relativeImagePath: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val rotationDegrees: Int = 0,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("viewType", viewType.slug)
        put("relativeImagePath", relativeImagePath)
        put("sourceWidth", sourceWidth)
        put("sourceHeight", sourceHeight)
        put("rotationDegrees", rotationDegrees)
        put("cropLeft", cropLeft)
        put("cropTop", cropTop)
        put("cropRight", cropRight)
        put("cropBottom", cropBottom)
        put("scale", scale)
        put("offsetX", offsetX)
        put("offsetY", offsetY)
    }

    companion object {
        fun fromJson(json: JSONObject): ViewSlot? {
            val type = ViewType.fromSlug(json.optString("viewType")) ?: return null
            return ViewSlot(
                viewType = type,
                relativeImagePath = json.optString("relativeImagePath"),
                sourceWidth = json.optInt("sourceWidth"),
                sourceHeight = json.optInt("sourceHeight"),
                rotationDegrees = json.optInt("rotationDegrees", 0),
                cropLeft = json.optDouble("cropLeft", 0.0).toFloat(),
                cropTop = json.optDouble("cropTop", 0.0).toFloat(),
                cropRight = json.optDouble("cropRight", 1.0).toFloat(),
                cropBottom = json.optDouble("cropBottom", 1.0).toFloat(),
                scale = json.optDouble("scale", 1.0).toFloat(),
                offsetX = json.optDouble("offsetX", 0.0).toFloat(),
                offsetY = json.optDouble("offsetY", 0.0).toFloat()
            )
        }
    }
}

data class ProjectModel(
    val formatVersion: Int = 1,
    val projectName: String,
    val createdAtEpochMs: Long,
    var modifiedAtEpochMs: Long,
    val views: MutableMap<ViewType, ViewSlot> = mutableMapOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("formatVersion", formatVersion)
        put("projectName", projectName)
        put("createdAtEpochMs", createdAtEpochMs)
        put("modifiedAtEpochMs", modifiedAtEpochMs)
        val viewsArray = JSONArray()
        views.values.forEach { viewsArray.put(it.toJson()) }
        put("views", viewsArray)
    }

    companion object {
        fun fromJson(json: JSONObject): ProjectModel {
            val model = ProjectModel(
                formatVersion = json.optInt("formatVersion", 1),
                projectName = json.optString("projectName"),
                createdAtEpochMs = json.optLong("createdAtEpochMs"),
                modifiedAtEpochMs = json.optLong("modifiedAtEpochMs")
            )
            val viewsArray = json.optJSONArray("views") ?: JSONArray()
            for (i in 0 until viewsArray.length()) {
                ViewSlot.fromJson(viewsArray.getJSONObject(i))?.let {
                    model.views[it.viewType] = it
                }
            }
            return model
        }
    }
}
