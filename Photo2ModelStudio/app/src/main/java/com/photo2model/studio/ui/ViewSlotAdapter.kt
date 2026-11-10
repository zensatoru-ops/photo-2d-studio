package com.photo2model.studio.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.photo2model.studio.R
import com.photo2model.studio.core.ProjectHandle
import com.photo2model.studio.core.ProjectManager
import com.photo2model.studio.core.Requirement
import com.photo2model.studio.core.ViewType

class ViewSlotAdapter(
    private val handle: ProjectHandle,
    private val manager: ProjectManager,
    private val onPickRequested: (ViewType) -> Unit,
    private val onRemoveRequested: (ViewType) -> Unit,
    private val onEditRequested: (ViewType) -> Unit,
    private val onMaskEditRequested: (ViewType) -> Unit,
    private val onLandmarkEditRequested: (ViewType) -> Unit
) : RecyclerView.Adapter<ViewSlotAdapter.VH>() {

    private val viewTypes = ViewType.values().toList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.imageThumbnail)
        val label: TextView = view.findViewById(R.id.textViewLabel)
        val badge: TextView = view.findViewById(R.id.textRequirementBadge)
        val dimensions: TextView = view.findViewById(R.id.textDimensions)
        val removeButton: View = view.findViewById(R.id.buttonRemove)
        val editButtons: View = view.findViewById(R.id.layoutEditButtons)
        val maskButton: View = view.findViewById(R.id.buttonEditMask)
        val landmarkButton: View = view.findViewById(R.id.buttonEditLandmarks)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_view_slot, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val type = viewTypes[position]
        val slot = handle.model.views[type]
        val ctx = holder.itemView.context

        holder.label.text = labelFor(type)
        holder.badge.text = when (type.requirement) {
            Requirement.REQUIRED -> ctx.getString(R.string.badge_required)
            Requirement.RECOMMENDED -> ctx.getString(R.string.badge_recommended)
            Requirement.OPTIONAL -> ctx.getString(R.string.badge_optional)
        }
        holder.badge.setBackgroundColor(
            ContextCompat.getColor(
                ctx,
                when (type.requirement) {
                    Requirement.REQUIRED -> R.color.badge_required
                    Requirement.RECOMMENDED -> R.color.badge_recommended
                    Requirement.OPTIONAL -> R.color.badge_optional
                }
            )
        )

        if (slot != null) {
            val file = manager.resolveImageFile(handle, slot)
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            holder.thumbnail.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, opts))
            holder.dimensions.text = ctx.getString(R.string.dimensions_format, slot.sourceWidth, slot.sourceHeight)
            holder.dimensions.visibility = View.VISIBLE
            holder.removeButton.visibility = View.VISIBLE
            holder.editButtons.visibility = View.VISIBLE
        } else {
            holder.thumbnail.setImageResource(R.drawable.ic_photo_placeholder)
            holder.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER
            holder.dimensions.visibility = View.GONE
            holder.removeButton.visibility = View.GONE
            holder.editButtons.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            if (slot != null) onEditRequested(type) else onPickRequested(type)
        }
        holder.itemView.setOnLongClickListener { onPickRequested(type); true }
        holder.removeButton.setOnClickListener { onRemoveRequested(type) }
        holder.maskButton.setOnClickListener { onMaskEditRequested(type) }
        holder.landmarkButton.setOnClickListener { onLandmarkEditRequested(type) }
    }

    override fun getItemCount(): Int = viewTypes.size

    private fun labelFor(type: ViewType): String = when (type) {
        ViewType.FRONT -> "Front"
        ViewType.BACK -> "Back"
        ViewType.SIDE_LEFT -> "Left Side"
        ViewType.SIDE_RIGHT -> "Right Side"
        ViewType.THREE_QUARTER -> "3/4 View"
        ViewType.TOP -> "Top"
    }

    fun refresh() = notifyDataSetChanged()
}
