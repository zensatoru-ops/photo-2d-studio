package com.photo2model.studio.core

import java.io.File

/**
 * Reads back exactly what ObjExporter writes: "v x y z", "vn x y z",
 * "f a//a b//b c//c" (1-based, position index == normal index since
 * ObjExporter never diverges them). Not a general-purpose OBJ reader -
 * doesn't handle separate vt/vn indexing, negative indices, or polygons
 * wider than a triangle, because nothing in this app ever writes those.
 */
object ObjLoader {

    fun load(file: File): NativeBridge.VisualHullMesh {
        val vertices = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()

        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("v ") -> {
                    val parts = line.substring(2).trim().split(Regex("\\s+"))
                    vertices.add(parts[0].toFloat())
                    vertices.add(parts[1].toFloat())
                    vertices.add(parts[2].toFloat())
                }
                line.startsWith("vn ") -> {
                    val parts = line.substring(3).trim().split(Regex("\\s+"))
                    normals.add(parts[0].toFloat())
                    normals.add(parts[1].toFloat())
                    normals.add(parts[2].toFloat())
                }
                line.startsWith("f ") -> {
                    val parts = line.substring(2).trim().split(Regex("\\s+"))
                    for (p in parts) {
                        val vIndex = p.substringBefore("//").toInt() - 1
                        indices.add(vIndex)
                    }
                }
            }
        }

        return NativeBridge.VisualHullMesh(vertices.toFloatArray(), normals.toFloatArray(), indices.toIntArray())
    }
}
