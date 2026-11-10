package com.photo2model.studio.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.photo2model.studio.R
import com.photo2model.studio.core.NativeBridge
import com.photo2model.studio.core.ProjectManager
import com.photo2model.studio.databinding.ActivityProjectListBinding

class ProjectListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectListBinding
    private lateinit var manager: ProjectManager
    private lateinit var adapter: ProjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = ProjectManager(applicationContext)

        adapter = ProjectAdapter(
            onClick = { summary -> openImport(summary.dir) },
            onLongClick = { summary -> confirmDelete(summary) }
        )
        binding.recyclerProjects.layoutManager = LinearLayoutManager(this)
        binding.recyclerProjects.adapter = adapter

        binding.fabNewProject.setOnClickListener { showCreateDialog() }

        binding.textCoreVersion.text = getString(R.string.core_version_format, NativeBridge.coreVersion())

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val projects = manager.listProjects()
        adapter.submit(projects)
        binding.textEmptyState.visibility = if (projects.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_project_name)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_new_project_title)
            .setView(input)
            .setPositiveButton(R.string.action_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val handle = manager.createProject(name)
                    openImport(handle.dir)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(summary: com.photo2model.studio.core.ProjectSummary) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, summary.projectName))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                manager.deleteProject(summary.dir)
                refreshList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openImport(projectDir: java.io.File) {
        val intent = Intent(this, ImportActivity::class.java)
        intent.putExtra(ImportActivity.EXTRA_PROJECT_DIR, projectDir.absolutePath)
        startActivity(intent)
    }
}
