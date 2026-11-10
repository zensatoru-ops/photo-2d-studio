package com.photo2model.studio.core

import java.io.File

/**
 * Bare-bones Wavefront OBJ: positions, normals, triangle faces. No UVs, no
 * materials - texture projection is section 5.8's job (Phase 6), this is
 * only the preview_mesh.obj the project layout already names for
 * reconstruction/.
 */
object ObjExporter {

    fun write(file: File, mesh: NativeBridge.VisualHullMesh) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.write("# Photo2Model Studio - visual hull preview\n")
            out.write("# ${mesh.vertexCount} vertices, ${mesh.triangleCount} triangles\n")

            var i = 0
            while (i < mesh.vertices.size) {
                out.write("v ${mesh.vertices[i]} ${mesh.vertices[i + 1]} ${mesh.vertices[i + 2]}\n")
                i += 3
            }
            i = 0
            while (i < mesh.normals.size) {
                out.write("vn ${mesh.normals[i]} ${mesh.normals[i + 1]} ${mesh.normals[i + 2]}\n")
                i += 3
            }
            i = 0
            while (i < mesh.indices.size) {
                // OBJ indices are 1-based.
                val a = mesh.indices[i] + 1
                val b = mesh.indices[i + 1] + 1
                val c = mesh.indices[i + 2] + 1
                out.write("f $a//$a $b//$b $c//$c\n")
                i += 3
            }
        }
    }
}
