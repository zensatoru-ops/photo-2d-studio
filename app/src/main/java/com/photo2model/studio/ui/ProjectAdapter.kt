package com.photo2model.studio.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.photo2model.studio.R
import com.photo2model.studio.core.ProjectSummary
import java.text.DateFormat
import java.util.Date

class ProjectAdapter(
    private val onClick: (ProjectSummary) -> Unit,
    private val onLongClick: (ProjectSummary) -> Unit
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    private val items = mutableListOf<ProjectSummary>()

    fun submit(newItems: List<ProjectSummary>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.textProjectName)
        val meta: TextView = view.findViewById(R.id.textProjectMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_project, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.projectName
        val dateStr = DateFormat.getDateTimeInstance().format(Date(item.modifiedAtEpochMs))
        holder.meta.text = holder.itemView.context.getString(R.string.project_meta_format, item.viewCount, dateStr)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    override fun getItemCount(): Int = items.size
}
