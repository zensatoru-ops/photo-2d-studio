package com.photo2model.studio.core

/**
 * A freshly carved mesh can be a few hundred KB of floats - too close to
 * the ~1MB Binder transaction limit to pass safely through Intent extras
 * at higher resolutions. Since both Activities run in the same process,
 * a static handoff avoids that limit entirely. MeshViewerActivity falls
 * back to ObjLoader reading the saved file if this is null (e.g. the
 * process was killed and recreated between the two Activities).
 */
object MeshHandoff {
    var mesh: NativeBridge.VisualHullMesh? = null
}
