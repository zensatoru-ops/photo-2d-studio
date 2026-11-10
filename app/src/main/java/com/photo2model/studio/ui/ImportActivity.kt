package com.photo2model.studio.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.photo2model.studio.R
import com.photo2model.studio.core.ProjectHandle
import com.photo2model.studio.core.ProjectManager
import com.photo2model.studio.core.ViewType
import com.photo2model.studio.databinding.ActivityImportBinding
import java.io.File

class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var manager: ProjectManager
    private lateinit var handle: ProjectHandle
    private lateinit var adapter: ViewSlotAdapter

    private var pendingViewType: ViewType? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val type = pendingViewType
        if (uri != null && type != null) {
            importImage(type, uri)
        }
        pendingViewType = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = ProjectManager(applicationContext)
        val dirPath = intent.getStringExtra(EXTRA_PROJECT_DIR)
            ?: run { finish(); return }
        handle = manager.openProject(File(dirPath))
        title = handle.model.projectName

        adapter = ViewSlotAdapter(
            handle = handle,
            manager = manager,
            onPickRequested = { type -> requestPick(type) },
            onRemoveRequested = { type -> removeView(type) },
            onEditRequested = { type -> openCropRotateDialog(type) },
            onMaskEditRequested = { type -> openMaskEditor(type) },
            onLandmarkEditRequested = { type -> openLandmarkEditor(type) }
        )
        binding.recyclerViewSlots.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerViewSlots.adapter = adapter

        binding.buttonBuildMeshEntry.setOnClickListener {
            val meshIntent = Intent(this, ReconstructionActivity::class.java)
            meshIntent.putExtra(ReconstructionActivity.EXTRA_PROJECT_DIR, handle.dir.absolutePath)
            startActivity(meshIntent)
        }

        updateConsistencyWarnings()
    }

    override fun onResume() {
        super.onResume()
        // Mask/landmark edits happen in separate Activities - re-check on return.
        manager.reload(handle)
        adapter.refresh()
        updateConsistencyWarnings()
    }

    private fun requestPick(type: ViewType) {
        pendingViewType = type
        pickImage.launch("image/*")
    }

    private fun importImage(type: ViewType, uri: Uri) {
        val extension = extensionFor(uri) ?: "png"
        val stream = contentResolver.openInputStream(uri)
        if (stream == null) {
            Toast.makeText(this, R.string.error_cannot_open_image, Toast.LENGTH_SHORT).show()
            return
        }
        val slot = manager.importViewImage(handle, type, stream, extension)
        if (slot == null) {
            Toast.makeText(this, R.string.error_invalid_image, Toast.LENGTH_SHORT).show()
        } else {
            adapter.refresh()
            updateConsistencyWarnings()
        }
    }

    /**
     * Crop and rotate edit for a slot that already has an image. Rotation
     * is baked into the stored degrees value (applied at render time, not
     * re-encoded into the source file) so the original import is never
     * destructively modified - section 5.2 calls for non-destructive
     * alignment, and re-encoding on every rotate would violate that.
     */
    private fun openCropRotateDialog(type: ViewType) {
        val slot = handle.model.views[type] ?: return
        val file = manager.resolveImageFile(handle, slot)

        val dialogBinding = com.photo2model.studio.databinding.DialogCropRotateBinding.inflate(layoutInflater)
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        dialogBinding.imagePreview.setImageBitmap(bitmap)
        dialogBinding.imagePreview.rotation = slot.rotationDegrees.toFloat()

        var currentRotation = slot.rotationDegrees

        dialogBinding.buttonRotateLeft.setOnClickListener {
            currentRotation = ((currentRotation - 90) % 360 + 360) % 360
            dialogBinding.imagePreview.rotation = currentRotation.toFloat()
        }
        dialogBinding.buttonRotateRight.setOnClickListener {
            currentRotation = (currentRotation + 90) % 360
            dialogBinding.imagePreview.rotation = currentRotation.toFloat()
        }
        dialogBinding.buttonResetCrop.setOnClickListener {
            dialogBinding.cropOverlay.reset()
        }
        dialogBinding.cropOverlay.post {
            dialogBinding.cropOverlay.setCropFraction(
                android.graphics.RectF(slot.cropLeft, slot.cropTop, slot.cropRight, slot.cropBottom)
            )
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_crop_title, labelFor(type)))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                val crop = dialogBinding.cropOverlay.getCropFraction()
                val updated = slot.copy(
                    rotationDegrees = currentRotation,
                    cropLeft = crop.left,
                    cropTop = crop.top,
                    cropRight = crop.right,
                    cropBottom = crop.bottom
                )
                handle.model.views[type] = updated
                manager.save(handle)
                adapter.refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun labelFor(type: ViewType): String = when (type) {
        ViewType.FRONT -> getString(R.string.view_front)
        ViewType.BACK -> getString(R.string.view_back)
        ViewType.SIDE_LEFT -> getString(R.string.view_left)
        ViewType.SIDE_RIGHT -> getString(R.string.view_right)
        ViewType.THREE_QUARTER -> getString(R.string.view_three_quarter)
        ViewType.TOP -> getString(R.string.view_top)
    }

    private fun removeView(type: ViewType) {
        manager.removeView(handle, type)
        adapter.refresh()
        updateConsistencyWarnings()
    }

    private fun openMaskEditor(type: ViewType) {
        val intent = Intent(this, MaskEditorActivity::class.java)
        intent.putExtra(MaskEditorActivity.EXTRA_PROJECT_DIR, handle.dir.absolutePath)
        intent.putExtra(MaskEditorActivity.EXTRA_VIEW_TYPE, type.slug)
        startActivity(intent)
    }

    private fun openLandmarkEditor(type: ViewType) {
        val intent = Intent(this, LandmarkEditorActivity::class.java)
        intent.putExtra(LandmarkEditorActivity.EXTRA_PROJECT_DIR, handle.dir.absolutePath)
        intent.putExtra(LandmarkEditorActivity.EXTRA_VIEW_TYPE, type.slug)
        startActivity(intent)
    }

    /**
     * Phase 2 of section 5.5: masks and landmarks exist now, so the full
     * validator runs instead of the raw-dimension aspect check Phase 1 used.
     * Re-opens the project handle to pick up whatever a mask/landmark editor
     * just wrote to disk in a different Activity instance.
     */
    private fun updateConsistencyWarnings() {
        manager.reload(handle)
        val warnings = com.photo2model.studio.core.ConsistencyValidator.validate(handle)

        if (warnings.isEmpty()) {
            binding.textWarnings.visibility = android.view.View.GONE
        } else {
            binding.textWarnings.visibility = android.view.View.VISIBLE
            binding.textWarnings.text = warnings.joinToString("\n") { "\u26A0 ${it.message}" }
        }
    }

    private fun extensionFor(uri: Uri): String? {
        val resolver: ContentResolver = contentResolver
        val mime = resolver.getType(uri)
        if (mime != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { return it }
        }
        var displayName: String? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) displayName = cursor.getString(idx)
        }
        return displayName?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val EXTRA_PROJECT_DIR = "extra_project_dir"
    }
}
