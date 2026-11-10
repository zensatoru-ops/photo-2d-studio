package com.photo2model.studio.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.photo2model.studio.R
import com.photo2model.studio.core.MaskManager
import com.photo2model.studio.core.NativeBridge
import com.photo2model.studio.core.ProjectManager
import com.photo2model.studio.core.ViewType
import com.photo2model.studio.databinding.ActivityMaskEditorBinding
import java.io.File

/**
 * Section 5.3's mask tools: manual polygon (lasso), threshold, flood fill,
 * brush/eraser touch-up, plus the cleanup pair (fill holes, keep largest
 * component) that section 5.3's "smooth small holes" / "remove noise"
 * items reduce to for a binary mask. Threshold and flood fill both run
 * against the full-resolution decoded source bitmap, not the downsampled
 * preview, so the mask stays pixel-accurate.
 */
class MaskEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMaskEditorBinding
    private lateinit var manager: ProjectManager
    private lateinit var handle: com.photo2model.studio.core.ProjectHandle
    private lateinit var viewType: ViewType

    private var mask = ByteArray(0)
    private var maskWidth = 0
    private var maskHeight = 0
    private var sourceBitmap: android.graphics.Bitmap? = null

    private var pendingThresholdSample = false
    private var pendingFloodFill = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMaskEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = ProjectManager(applicationContext)
        val dirPath = intent.getStringExtra(EXTRA_PROJECT_DIR) ?: run { finish(); return }
        val typeSlug = intent.getStringExtra(EXTRA_VIEW_TYPE) ?: run { finish(); return }
        viewType = ViewType.fromSlug(typeSlug) ?: run { finish(); return }
        handle = manager.openProject(File(dirPath))
        title = getString(R.string.mask_editor_title, labelFor(viewType))

        val slot = handle.model.views[viewType] ?: run { finish(); return }
        val sourceFile = manager.resolveImageFile(handle, slot)
        val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
        sourceBitmap = bitmap
        binding.imageSource.setImageBitmap(bitmap)

        loadOrInitMask(bitmap)

        binding.maskCanvas.bind(binding.imageSource, mask, maskWidth, maskHeight) {
            binding.textForegroundCount.text = getString(
                R.string.foreground_pixel_format,
                MaskManager.foregroundPixelCount(mask)
            )
        }

        wireToolButtons()
        wireSourceTapListener()
        binding.textForegroundCount.text = getString(R.string.foreground_pixel_format, MaskManager.foregroundPixelCount(mask))
    }

    private fun loadOrInitMask(bitmap: android.graphics.Bitmap) {
        val file = MaskManager.maskFileFor(handle, viewType)
        val loaded = MaskManager.load(file)
        if (loaded != null) {
            mask = loaded.first
            maskWidth = loaded.second
            maskHeight = loaded.third
        } else {
            maskWidth = bitmap.width
            maskHeight = bitmap.height
            mask = MaskManager.maskFromAlphaChannel(bitmap)
        }
    }

    private fun wireToolButtons() {
        binding.buttonToolLasso.setOnClickListener { selectTool(MaskCanvasView.Tool.LASSO) }
        binding.buttonToolBrush.setOnClickListener { selectTool(MaskCanvasView.Tool.BRUSH) }
        binding.buttonToolEraser.setOnClickListener { selectTool(MaskCanvasView.Tool.ERASER) }

        binding.buttonCommitLasso.setOnClickListener { binding.maskCanvas.commitLasso(255) }
        binding.buttonCancelLasso.setOnClickListener { binding.maskCanvas.cancelLasso() }

        binding.buttonThresholdSample.setOnClickListener {
            pendingThresholdSample = true
            pendingFloodFill = false
            Toast.makeText(this, R.string.hint_tap_background, Toast.LENGTH_SHORT).show()
        }
        binding.buttonFloodFill.setOnClickListener {
            pendingFloodFill = true
            pendingThresholdSample = false
            Toast.makeText(this, R.string.hint_tap_to_flood, Toast.LENGTH_SHORT).show()
        }

        binding.buttonFillHoles.setOnClickListener {
            NativeBridge.fillHoles(mask, maskWidth, maskHeight)
            binding.maskCanvas.refreshFromMask()
            updateCount()
        }
        binding.buttonLargestComponent.setOnClickListener {
            NativeBridge.keepLargestComponent(mask, maskWidth, maskHeight)
            binding.maskCanvas.refreshFromMask()
            updateCount()
        }
        binding.buttonInvert.setOnClickListener {
            binding.maskCanvas.invert()
            updateCount()
        }
        binding.buttonTraceContour.setOnClickListener {
            binding.maskCanvas.traceAndShowContour()
        }

        binding.buttonSaveMask.setOnClickListener { saveAndFinish() }
    }

    private fun wireSourceTapListener() {
        // The mask canvas already consumes touches for lasso/brush/eraser.
        // Threshold-sample and flood-fill are one-shot taps layered on top,
        // read directly off the ImageView so they work regardless of which
        // drawing tool is currently active.
        binding.imageSource.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            if (!pendingThresholdSample && !pendingFloodFill) return@setOnTouchListener false

            val maskPoint = viewTouchToMaskPixel(event.x, event.y) ?: return@setOnTouchListener true
            val bitmap = sourceBitmap ?: return@setOnTouchListener true

            if (pendingThresholdSample) {
                mask = MaskManager.maskFromColorThreshold(bitmap, maskPoint.first, maskPoint.second, THRESHOLD_TOLERANCE)
                pendingThresholdSample = false
                binding.maskCanvas.bind(binding.imageSource, mask, maskWidth, maskHeight) { updateCount() }
                updateCount()
            } else if (pendingFloodFill) {
                NativeBridge.floodFill(mask, maskWidth, maskHeight, maskPoint.first, maskPoint.second, 0)
                pendingFloodFill = false
                binding.maskCanvas.refreshFromMask()
                updateCount()
            }
            true
        }
    }

    private fun viewTouchToMaskPixel(viewX: Float, viewY: Float): Pair<Int, Int>? {
        val drawable = binding.imageSource.drawable ?: return null
        val rect = android.graphics.RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        binding.imageSource.imageMatrix.mapRect(rect)
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        val fx = ((viewX - rect.left) / rect.width()).coerceIn(0f, 1f)
        val fy = ((viewY - rect.top) / rect.height()).coerceIn(0f, 1f)
        return Pair((fx * maskWidth).toInt().coerceIn(0, maskWidth - 1), (fy * maskHeight).toInt().coerceIn(0, maskHeight - 1))
    }

    private fun selectTool(tool: MaskCanvasView.Tool) {
        pendingThresholdSample = false
        pendingFloodFill = false
        binding.maskCanvas.tool = tool
    }

    private fun updateCount() {
        binding.textForegroundCount.text = getString(R.string.foreground_pixel_format, MaskManager.foregroundPixelCount(mask))
    }

    private fun saveAndFinish() {
        MaskManager.save(MaskManager.maskFileFor(handle, viewType), mask, maskWidth, maskHeight)
        Toast.makeText(this, R.string.mask_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun labelFor(type: ViewType): String = when (type) {
        ViewType.FRONT -> getString(R.string.view_front)
        ViewType.BACK -> getString(R.string.view_back)
        ViewType.SIDE_LEFT -> getString(R.string.view_left)
        ViewType.SIDE_RIGHT -> getString(R.string.view_right)
        ViewType.THREE_QUARTER -> getString(R.string.view_three_quarter)
        ViewType.TOP -> getString(R.string.view_top)
    }

    companion object {
        const val EXTRA_PROJECT_DIR = "extra_project_dir"
        const val EXTRA_VIEW_TYPE = "extra_view_type"
        private const val THRESHOLD_TOLERANCE = 45f
    }
}
