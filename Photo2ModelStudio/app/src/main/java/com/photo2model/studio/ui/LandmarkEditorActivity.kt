package com.photo2model.studio.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photo2model.studio.R
import com.photo2model.studio.core.LandmarkSchema
import com.photo2model.studio.core.LandmarkSet
import com.photo2model.studio.core.LandmarkSpec
import com.photo2model.studio.core.ProjectManager
import com.photo2model.studio.core.Requirement
import com.photo2model.studio.core.ViewType
import com.photo2model.studio.databinding.ActivityLandmarkEditorBinding
import java.io.File

class LandmarkEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLandmarkEditorBinding
    private lateinit var manager: ProjectManager
    private lateinit var handle: com.photo2model.studio.core.ProjectHandle
    private lateinit var viewType: ViewType
    private lateinit var landmarkSet: LandmarkSet
    private lateinit var specs: List<LandmarkSpec>
    private lateinit var specAdapter: SpecAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLandmarkEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = ProjectManager(applicationContext)
        val dirPath = intent.getStringExtra(EXTRA_PROJECT_DIR) ?: run { finish(); return }
        val typeSlug = intent.getStringExtra(EXTRA_VIEW_TYPE) ?: run { finish(); return }
        viewType = ViewType.fromSlug(typeSlug) ?: run { finish(); return }
        handle = manager.openProject(File(dirPath))
        title = getString(R.string.landmark_editor_title, labelFor(viewType))

        val slot = handle.model.views[viewType] ?: run { finish(); return }
        val sourceFile = manager.resolveImageFile(handle, slot)
        binding.imageSource.setImageBitmap(BitmapFactory.decodeFile(sourceFile.absolutePath))

        landmarkSet = LandmarkSet.load(handle, viewType)
        specs = LandmarkSchema.specsFor(viewType)

        binding.landmarkOverlay.bind(binding.imageSource, landmarkSet) { placedId ->
            specAdapter.notifyPlacement(placedId)
            advanceToNextUnplaced(placedId)
        }

        specAdapter = SpecAdapter(specs, landmarkSet) { spec ->
            binding.landmarkOverlay.activeLandmarkId = spec.id
            specAdapter.setActive(spec.id)
        }
        binding.recyclerLandmarkSpecs.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerLandmarkSpecs.adapter = specAdapter

        binding.landmarkOverlay.activeLandmarkId = specs.firstOrNull { it.id !in landmarkSet.points }?.id
        specAdapter.setActive(binding.landmarkOverlay.activeLandmarkId)

        binding.buttonSaveLandmarks.setOnClickListener {
            LandmarkSet.save(handle, landmarkSet)
            android.widget.Toast.makeText(this, R.string.landmarks_saved, android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun advanceToNextUnplaced(justPlacedId: String) {
        val currentIndex = specs.indexOfFirst { it.id == justPlacedId }
        val next = specs.drop(currentIndex + 1).firstOrNull { it.id !in landmarkSet.points }
            ?: specs.firstOrNull { it.id !in landmarkSet.points }
        binding.landmarkOverlay.activeLandmarkId = next?.id
        specAdapter.setActive(next?.id)
    }

    private fun labelFor(type: ViewType): String = when (type) {
        ViewType.FRONT -> getString(R.string.view_front)
        ViewType.BACK -> getString(R.string.view_back)
        ViewType.SIDE_LEFT -> getString(R.string.view_left)
        ViewType.SIDE_RIGHT -> getString(R.string.view_right)
        ViewType.THREE_QUARTER -> getString(R.string.view_three_quarter)
        ViewType.TOP -> getString(R.string.view_top)
    }

    /** Horizontal chip list of landmark names - tap to select which one the next tap places. */
    private class SpecAdapter(
        private val specs: List<LandmarkSpec>,
        private val landmarkSet: LandmarkSet,
        private val onSelect: (LandmarkSpec) -> Unit
    ) : RecyclerView.Adapter<SpecAdapter.VH>() {

        private var activeId: String? = null

        class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val text = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_landmark_chip, parent, false) as TextView
            return VH(text)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val spec = specs[position]
            val placed = spec.id in landmarkSet.points
            holder.text.text = spec.displayName + if (placed) " \u2713" else ""
            holder.text.setBackgroundColor(
                when {
                    spec.id == activeId -> Color.parseColor("#4CC2FF")
                    placed -> Color.parseColor("#DCFCE7")
                    spec.requirement == Requirement.REQUIRED -> Color.parseColor("#FEE2E2")
                    spec.requirement == Requirement.RECOMMENDED -> Color.parseColor("#FEF3C7")
                    else -> Color.parseColor("#F3F4F6")
                }
            )
            holder.text.setOnClickListener { onSelect(spec) }
        }

        override fun getItemCount(): Int = specs.size

        fun setActive(id: String?) {
            activeId = id
            notifyDataSetChanged()
        }

        fun notifyPlacement(id: String) {
            notifyDataSetChanged()
        }
    }

    companion object {
        const val EXTRA_PROJECT_DIR = "extra_project_dir"
        const val EXTRA_VIEW_TYPE = "extra_view_type"
    }
}
