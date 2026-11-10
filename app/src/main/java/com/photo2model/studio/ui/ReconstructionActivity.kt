package com.photo2model.studio.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.photo2model.studio.R
import com.photo2model.studio.core.MeshHandoff
import com.photo2model.studio.core.ProjectManager
import com.photo2model.studio.core.ReconstructionManager
import com.photo2model.studio.core.ReconstructionResult
import com.photo2model.studio.core.ViewType
import com.photo2model.studio.databinding.ActivityReconstructionBinding
import java.io.File

/**
 * Section 4's "job system" in miniature: a plain background Thread posting
 * back to the main Looper, with a cancel flag the Activity checks before
 * acting on a finished result. No WorkManager/coroutines dependency added
 * for one background task. Cancel here is soft - it stops the UI from
 * acting on the result and re-enables the controls immediately, but the
 * native carve already in flight runs to completion in the background
 * (no cancellation token threaded into the C++ loops yet). On a slow
 * device at resolution 96 that means "Cancel" frees the screen, not the CPU.
 */
class ReconstructionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReconstructionBinding
    private lateinit var manager: ProjectManager
    private lateinit var handle: com.photo2model.studio.core.ProjectHandle

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var buildThread: Thread? = null

    private val resolutions = intArrayOf(24, 32, 48, 64, 96)
    private var selectedResolutionIndex = 2  // 48 - balance of mobile perf and quality

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReconstructionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = ProjectManager(applicationContext)
        val dirPath = intent.getStringExtra(EXTRA_PROJECT_DIR) ?: run { finish(); return }
        handle = manager.openProject(File(dirPath))
        title = getString(R.string.reconstruction_title)

        binding.seekResolution.max = resolutions.size - 1
        binding.seekResolution.progress = selectedResolutionIndex
        updateResolutionLabel()
        binding.seekResolution.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                selectedResolutionIndex = progress
                updateResolutionLabel()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        updateAvailableViewsSummary()

        binding.buttonBuildMesh.setOnClickListener { startBuild() }
        binding.buttonCancel.setOnClickListener { cancelBuild() }
        binding.buttonCancel.isEnabled = false
    }

    private fun updateResolutionLabel() {
        val res = resolutions[selectedResolutionIndex]
        binding.textResolutionValue.text = getString(R.string.resolution_format, res, res, res)
    }

    private fun updateAvailableViewsSummary() {
        val withMasks = ViewType.values().filter {
            com.photo2model.studio.core.MaskManager.maskFileFor(handle, it).exists()
        }
        binding.textAvailableViews.text = if (withMasks.isEmpty()) {
            getString(R.string.no_masks_available)
        } else {
            getString(R.string.masks_available_format, withMasks.joinToString { it.slug })
        }
    }

    private fun startBuild() {
        cancelled = false
        binding.buttonBuildMesh.isEnabled = false
        binding.buttonCancel.isEnabled = true
        binding.seekResolution.isEnabled = false
        binding.progressBuild.visibility = android.view.View.VISIBLE
        binding.textStatus.text = getString(R.string.status_carving)
        binding.textStatus.visibility = android.view.View.VISIBLE

        val resolution = resolutions[selectedResolutionIndex]
        buildThread = Thread {
            val result = ReconstructionManager.build(handle, resolution)
            if (!cancelled) {
                mainHandler.post { onBuildFinished(result) }
            }
        }.also { it.start() }
    }

    private fun cancelBuild() {
        cancelled = true
        binding.buttonBuildMesh.isEnabled = true
        binding.buttonCancel.isEnabled = false
        binding.seekResolution.isEnabled = true
        binding.progressBuild.visibility = android.view.View.GONE
        binding.textStatus.text = getString(R.string.status_cancelled)
    }

    private fun onBuildFinished(result: ReconstructionResult) {
        binding.buttonBuildMesh.isEnabled = true
        binding.buttonCancel.isEnabled = false
        binding.seekResolution.isEnabled = true
        binding.progressBuild.visibility = android.view.View.GONE

        when (result) {
            is ReconstructionResult.Success -> {
                binding.textStatus.text = getString(
                    R.string.status_done_format,
                    result.mesh.vertexCount,
                    result.mesh.triangleCount,
                    result.usedViews.joinToString { it.slug }
                )
                MeshHandoff.mesh = result.mesh
                val intent = Intent(this, MeshViewerActivity::class.java)
                intent.putExtra(MeshViewerActivity.EXTRA_OBJ_PATH, result.objFile.absolutePath)
                startActivity(intent)
            }
            is ReconstructionResult.Failure -> {
                binding.textStatus.text = result.reason
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelled = true
    }

    companion object {
        const val EXTRA_PROJECT_DIR = "extra_project_dir"
    }
}
