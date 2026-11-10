package com.photo2model.studio.ui

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.app.AppCompatActivity
import com.photo2model.studio.R
import com.photo2model.studio.core.MeshHandoff
import com.photo2model.studio.core.NativeBridge
import com.photo2model.studio.core.ObjLoader
import com.photo2model.studio.databinding.ActivityMeshViewerBinding
import java.io.File

class MeshViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMeshViewerBinding
    private lateinit var renderer: MeshGLRenderer
    private lateinit var scaleDetector: ScaleGestureDetector

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeshViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mesh = resolveMesh() ?: run {
            finish()
            return
        }

        title = getString(R.string.mesh_viewer_title, mesh.vertexCount, mesh.triangleCount)
        binding.textMeshStats.text = getString(R.string.mesh_stats_format, mesh.vertexCount, mesh.triangleCount)

        binding.glSurfaceView.setEGLContextClientVersion(2)
        renderer = MeshGLRenderer(mesh)
        binding.glSurfaceView.setRenderer(renderer)
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoom = (renderer.zoom / detector.scaleFactor).coerceIn(0.8f, 8f)
                return true
            }
        })

        wireTouch()
    }

    private fun resolveMesh(): NativeBridge.VisualHullMesh? {
        MeshHandoff.mesh?.let { return it }

        val objPath = intent.getStringExtra(EXTRA_OBJ_PATH) ?: return null
        val file = File(objPath)
        if (!file.exists()) return null
        return runCatching { ObjLoader.load(file) }.getOrNull()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireTouch() {
        binding.glSurfaceView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        renderer.rotationY += dx * 0.4f
                        renderer.rotationX = (renderer.rotationX + dy * 0.4f).coerceIn(-85f, 85f)
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) MeshHandoff.mesh = null
    }

    companion object {
        const val EXTRA_OBJ_PATH = "extra_obj_path"
    }
}
