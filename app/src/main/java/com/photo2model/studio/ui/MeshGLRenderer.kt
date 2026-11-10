package com.photo2model.studio.ui

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.photo2model.studio.core.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Flat clay-gray shading with one directional light - texture projection is
 * Phase 6, this view exists to let the person judge the carved *shape*,
 * not how it'll eventually look skinned.
 */
class MeshGLRenderer(mesh: NativeBridge.VisualHullMesh) : GLSurfaceView.Renderer {

    var rotationX = -20f
    var rotationY = 0f
    var zoom = 2.2f

    private val vertexBuffer: FloatBuffer
    private val normalBuffer: FloatBuffer
    private val indexBuffer: IntBuffer
    private val indexCount: Int

    private var program = 0
    private var positionHandle = 0
    private var normalHandle = 0
    private var mvpMatrixHandle = 0
    private var modelMatrixHandle = 0
    private var lightDirHandle = 0

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    init {
        // Recenter around the mesh's own bounding box center rather than
        // assuming the [0,1]^3 carve volume is fully occupied - a thin
        // character leaves most of that cube empty.
        val (centered, _) = recenter(mesh.vertices)

        vertexBuffer = ByteBuffer.allocateDirect(centered.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(centered); position(0) }
        normalBuffer = ByteBuffer.allocateDirect(mesh.normals.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(mesh.normals); position(0) }
        indexBuffer = ByteBuffer.allocateDirect(mesh.indices.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(mesh.indices); position(0) }
        indexCount = mesh.indices.size
    }

    private fun recenter(vertices: FloatArray): Pair<FloatArray, Float> {
        if (vertices.isEmpty()) return vertices to 1f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < vertices.size) {
            minX = minOf(minX, vertices[i]); maxX = maxOf(maxX, vertices[i])
            minY = minOf(minY, vertices[i + 1]); maxY = maxOf(maxY, vertices[i + 1])
            minZ = minOf(minZ, vertices[i + 2]); maxZ = maxOf(maxZ, vertices[i + 2])
            i += 3
        }
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f; val cz = (minZ + maxZ) / 2f
        val out = FloatArray(vertices.size)
        i = 0
        while (i < vertices.size) {
            out[i] = vertices[i] - cx
            out[i + 1] = vertices[i + 1] - cy
            out[i + 2] = vertices[i + 2] - cz
            i += 3
        }
        return out to 1f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.07f, 0.08f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        modelMatrixHandle = GLES20.glGetUniformLocation(program, "uModelMatrix")
        lightDirHandle = GLES20.glGetUniformLocation(program, "uLightDir")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        Matrix.perspectiveM(projectionMatrix, 0, 45f, aspect, 0.1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, zoom, 0f, 0f, 0f, 0f, 1f, 0f)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(modelMatrixHandle, 1, false, modelMatrix, 0)
        GLES20.glUniform3f(lightDirHandle, 0.4f, 0.8f, 0.6f)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        normalBuffer.position(0)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)
        GLES20.glEnableVertexAttribArray(normalHandle)

        indexBuffer.position(0)
        // GL_UNSIGNED_INT indices need OES_element_index_uint - universal on
        // API 29+ hardware in practice, which is this app's floor anyway.
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, indexBuffer)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER_SRC = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vNormal = mat3(uModelMatrix) * aNormal;
            }
        """

        private const val FRAGMENT_SHADER_SRC = """
            precision mediump float;
            varying vec3 vNormal;
            uniform vec3 uLightDir;
            void main() {
                vec3 n = normalize(vNormal);
                float diffuse = max(dot(n, normalize(uLightDir)), 0.0);
                float ambient = 0.35;
                vec3 clayColor = vec3(0.78, 0.74, 0.68);
                vec3 color = clayColor * (ambient + diffuse * 0.65);
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
