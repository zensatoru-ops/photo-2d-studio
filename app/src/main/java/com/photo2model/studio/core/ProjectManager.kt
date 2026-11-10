package com.photo2model.studio.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Owns the on-disk project layout from section 5.1 of the master plan:
 *
 *   MyCharacter.project/
 *     project.json
 *     source_images/
 *     masks/
 *     landmarks/
 *     reconstruction/
 *     textures/
 *     models/
 *     logs/
 *
 * Phase 1 only touches project.json and source_images/. The other
 * directories are created up front so later phases never have to check
 * whether they exist.
 */
class ProjectManager(context: Context) {

    private val rootDir: File = File(context.getExternalFilesDir(null), "Projects").apply { mkdirs() }

    private val subDirs = listOf("source_images", "masks", "landmarks", "reconstruction", "textures", "models", "logs")

    fun listProjects(): List<ProjectSummary> {
        val dirs = rootDir.listFiles { f -> f.isDirectory && f.name.endsWith(".project") } ?: emptyArray()
        return dirs.mapNotNull { dir ->
            val jsonFile = File(dir, "project.json")
            if (!jsonFile.exists()) return@mapNotNull null
            runCatching {
                val model = ProjectModel.fromJson(JSONObject(jsonFile.readText()))
                ProjectSummary(dir, model.projectName, model.modifiedAtEpochMs, model.views.size)
            }.getOrNull()
        }.sortedByDescending { it.modifiedAtEpochMs }
    }

    fun createProject(name: String): ProjectHandle {
        val sanitized = sanitizeName(name)
        val dir = uniqueProjectDir(sanitized)
        dir.mkdirs()
        subDirs.forEach { File(dir, it).mkdirs() }

        val now = System.currentTimeMillis()
        val model = ProjectModel(projectName = name, createdAtEpochMs = now, modifiedAtEpochMs = now)
        val handle = ProjectHandle(dir, model)
        save(handle)
        appendLog(handle, "Project created: $name")
        return handle
    }

    fun openProject(dir: File): ProjectHandle {
        val jsonFile = File(dir, "project.json")
        val model = ProjectModel.fromJson(JSONObject(jsonFile.readText()))
        return ProjectHandle(dir, model)
    }

    /** Re-reads project.json into the same handle instance, so UI holding onto this handle (and anything bound to it, like a RecyclerView adapter) sees fresh data without needing a new object. */
    fun reload(handle: ProjectHandle) {
        val jsonFile = File(handle.dir, "project.json")
        handle.model = ProjectModel.fromJson(JSONObject(jsonFile.readText()))
    }

    fun save(handle: ProjectHandle) {
        handle.model.modifiedAtEpochMs = System.currentTimeMillis()
        val jsonFile = File(handle.dir, "project.json")
        jsonFile.writeText(handle.model.toJson().toString(2))
    }

    /**
     * Copies a picked image into source_images/ under the view's canonical
     * name, probes its real dimensions via the native core, and registers a
     * ViewSlot. Returns null if the native probe can't make sense of the
     * file - the caller should surface that as an import error rather than
     * silently storing a zero-dimension slot.
     */
    fun importViewImage(handle: ProjectHandle, viewType: ViewType, sourceStream: java.io.InputStream, extension: String): ViewSlot? {
        val sourceImagesDir = File(handle.dir, "source_images")
        val destFile = File(sourceImagesDir, "${viewType.slug}.$extension")

        sourceStream.use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        }

        val probe = NativeBridge.probeImage(destFile.absolutePath)
        if (!probe.valid) {
            destFile.delete()
            appendLog(handle, "Import rejected for ${viewType.slug}: native probe found no valid header")
            return null
        }

        val slot = ViewSlot(
            viewType = viewType,
            relativeImagePath = "source_images/${destFile.name}",
            sourceWidth = probe.width,
            sourceHeight = probe.height
        )
        handle.model.views[viewType] = slot
        save(handle)
        appendLog(handle, "Imported ${viewType.slug}: ${probe.width}x${probe.height}, ${probe.channels}ch, ${probe.format}")
        return slot
    }

    fun removeView(handle: ProjectHandle, viewType: ViewType) {
        handle.model.views[viewType]?.let { slot ->
            File(handle.dir, slot.relativeImagePath).delete()
        }
        handle.model.views.remove(viewType)
        save(handle)
        appendLog(handle, "Removed view ${viewType.slug}")
    }

    fun deleteProject(dir: File) {
        dir.deleteRecursively()
    }

    fun resolveImageFile(handle: ProjectHandle, slot: ViewSlot): File = File(handle.dir, slot.relativeImagePath)

    private fun appendLog(handle: ProjectHandle, message: String) {
        val logFile = File(handle.dir, "logs/project.log")
        runCatching {
            FileOutputStream(logFile, true).use { out ->
                out.write("${System.currentTimeMillis()} $message\n".toByteArray())
            }
        }.onFailure { Log.w(TAG, "Failed to write project log", it) }
    }

    private fun sanitizeName(name: String): String {
        val trimmed = name.trim().ifEmpty { "Untitled" }
        return trimmed.replace(Regex("[^A-Za-z0-9 _-]"), "_")
    }

    private fun uniqueProjectDir(sanitizedName: String): File {
        var candidate = File(rootDir, "$sanitizedName.project")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(rootDir, "$sanitizedName-$suffix.project")
            suffix++
        }
        return candidate
    }

    companion object {
        private const val TAG = "ProjectManager"
    }
}

data class ProjectSummary(val dir: File, val projectName: String, val modifiedAtEpochMs: Long, val viewCount: Int)

class ProjectHandle(val dir: File, var model: ProjectModel)
