# Photo2Model Studio — Phase 1 + 2 + 3

Project Manager + Multi-View Import (Phase 1), Mask Editor + Landmark
Editor + consistency validator (Phase 2), Voxel Visual Hull + Surface Nets
mesh + OpenGL viewer (Phase 3 - "First 3D Reconstruction", the roadmap's
MVP core). Native core wired via JNI from turn one — every per-pixel and
per-voxel operation runs in C++, everything else in Kotlin.

## Phase 3 additions — First 3D Reconstruction

- **Voxel carving** (`voxel_carve.h/.cpp`) — classic shape-from-silhouette
  (master plan Method B): a voxel survives only if its projection lands
  inside every available view's mask. With just Front + one Side (the
  minimum this requires), that's Method A's 2D silhouette intersection
  done in 3D - it won't recover concavities a silhouette can't show, which
  is Method B's known limitation, not a bug.
- **No camera calibration step exists yet.** Each view calibrates off its
  *own* mask bounding box - documented, fixed axis-mapping assumptions
  (Front/Back project x,y; Left/Right project z,y; Top projects x,z; Back
  and Right are horizontally mirrored since they're seen from the opposite
  side). If a character's drawn facing a different way than these
  assumptions expect, the hull comes out backward along one axis - cosmetic,
  fixable by Phase 8's Advanced Editor, not a correctness failure of the
  carve itself.
- **Surface Nets** (`surface_nets.h/.cpp`) — converts the occupancy grid to
  a triangle mesh with smooth per-vertex normals. Deliberately not Marching
  Cubes: MC's 256-case edge table is ~4,000 hardcoded integers with no way
  to usefully shorten it, and Surface Nets (Gibson 1998) gets a comparable
  non-blocky result from an algorithm that's maybe 150 lines, correctly
  windable from first principles (each face's winding is resolved against
  the known inside→outside direction at that occupancy boundary, not
  memorized per-axis).
- **One JNI call** (`nativeBuildVisualHullMesh`) does carve + mesh together
  and hands back a single packed `FloatArray` - avoids crossing the JNI
  boundary with an entire voxel grid, consistent with how `mask_ops` already
  mutates arrays in place rather than round-tripping.
- **`ReconstructionActivity`** — resolution slider (24³ to 96³, default
  48³), runs the native call on a plain background `Thread` (Section 4's
  "job system," minimal version - no WorkManager/coroutines dependency
  added for one task). Cancel is soft: it frees the UI immediately but
  doesn't interrupt an in-flight native carve, which is stated plainly in
  the code rather than implied to be a hard stop.
- **`MeshViewerActivity`** — OpenGL ES 2.0, one directional light, flat
  clay-gray shading (texture projection is Phase 6 — this view exists to
  judge *shape*, not final look). Drag to orbit, pinch to zoom.
- **`ObjExporter`** / **`ObjLoader`** — bare vertices/normals/faces, no
  UVs or materials, written to `reconstruction/preview_mesh.obj` exactly
  where Phase 1's directory layout already named it. `ObjLoader` is a
  narrow reader that only handles exactly what `ObjExporter` writes - not
  a general OBJ importer - used as a fallback if `MeshViewerActivity` is
  recreated after process death and the in-memory mesh handoff is gone.

## Phase 2 additions

- **Mask editor** (`MaskEditorActivity` + `MaskCanvasView`) — per section
  5.3's tool list: manual lasso (fills a drawn polygon to foreground),
  brush/eraser touch-up, color threshold (tap a background pixel, mark
  everything within an RGB distance tolerance as background), flood fill
  (tap a region to clear it to background). Starts from the source PNG's
  alpha channel when one exists, per section 5.3-E.
- **Native mask ops** (`mask_ops.h/.cpp`) — flood fill, hole filling
  (BFS from the border to find what's actually outside the silhouette),
  largest-connected-component extraction (removes stray flecks), and
  Moore-neighbor contour tracing, all iterative (no recursion, no stack
  overflow on large masks). Wired to a "Trace Contour" button that overlays
  the traced boundary in yellow.
- **Landmark editor** (`LandmarkEditorActivity` + `LandmarkOverlayView`) —
  section 5.4's two point groups (frontal: 23 points / profile: 12 points),
  each with required/recommended/optional status. Tap to place the active
  landmark, drag an existing one to adjust, chip list shows placement
  status and jumps to the next unplaced required point automatically.
- **Full consistency validator** (`ConsistencyValidator.kt`) — now that
  masks and landmarks exist, runs the checks section 5.5 lists as examples:
  cross-view height drift (via `top_of_head`/`heel` landmarks), missing
  required landmarks per view, empty masks, landmarks that land outside
  the mask's foreground, front/back silhouette aspect divergence, and a
  head-to-foot horizontal offset heuristic for tilt. Re-runs every time
  `ImportActivity` resumes, since mask/landmark edits happen in separate
  Activities.

**Known limitation, stated plainly rather than hidden:** the lasso tool
only adds to the mask (use the eraser for removal) — matches how section
5.3 describes manual polygon drawing as tracing the character outline, not
a general set-algebra tool.

## What's in this build

- **Project Manager** — create/open/list/delete projects under
  `getExternalFilesDir(null)/Projects/<name>.project/`, with the full
  directory layout from the master plan (`source_images/`, `masks/`,
  `landmarks/`, `reconstruction/`, `textures/`, `models/`, `logs/`) created
  up front so later phases never need to check for them.
- **Multi-view import** — 6 slots (Front/Back/Left/Right/3-4/Top) with
  required/recommended/optional badges matching section 3.1 of the plan.
  Pick via `ActivityResultContracts.GetContent()`, copied into
  `source_images/` under a canonical name.
- **Native image probe** (`libphoto2model_core.so`) — reads just the PNG
  IHDR chunk, JPEG SOF marker, or WebP VP8/VP8X header to recover real
  width/height/channel count without decoding a full bitmap. Rejects files
  that aren't actually valid images before they ever reach the project
  model. VP8L (lossless WebP) isn't parsed — falls through to `valid=false`.
- **Rotate + crop** — non-destructive: rotation degrees and crop fractions
  are stored in `project.json`, the source file on disk is never
  re-encoded.
- **View consistency validator (partial)** — front/side aspect-ratio drift
  check and missing-required-view check run at import time. Full
  baseline/proportion/landmark checks are Phase 2, once landmarks exist.
- **project.json** persistence via `org.json` (no extra serialization
  dependency).

## Not in this build (later phases)

Cross-section/profile-lofting correction (Method C, Phase 4), mesh cleanup
beyond what Surface Nets gives for free (decimation, retopology - Phase 5),
texture projection (Phase 6), GLB/FBX export (Phase 7 - only raw OBJ exists
now), the full Advanced Editor (Phase 8), rigging prep (Phase 9). OpenCV
still isn't integrated - every CV operation in this build (mask ops, voxel
carve, surface nets) is hand-rolled, matching the plan's "classic CV, no
AI" stance by construction rather than by having a library to avoid.

## Build

Open in Android Studio (Koala or newer, AGP 8.7.2, NDK 26.3.11579264 will
auto-install on first sync) and run on a device/emulator — **minSdk 29
(Android 10) through targetSdk 35 (Android 15)**.

Command line — the wrapper is committed (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`), no local Gradle install needed:

```bash
./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Build verification before you push

This sandbox has no Android SDK/NDK and no route to `dl.google.com`, so the
Gradle+CMake build has never actually been run end to end here — everything
above was verified statically (every `R.id`/`R.layout`/`R.drawable`/
`R.color` reference cross-checked against what's defined, every JNI
`external fun` name diffed against its C++ `Java_...` symbol, every custom
view's constructor checked against what the XML inflater needs, brace/paren
balance checked file by file). That's real verification, but it's not a
compile.

`.github/workflows/android-build.yml` is the actual compile check — it runs
`./gradlew lintDebug` and `./gradlew assembleDebug` on every push and PR to
`main`/`develop`, on a runner that does have the SDK and NDK. **Push this,
then check the Actions tab before trusting the build.** If the native layer
(CMake/JNI) has a real problem, that's where it'll show up — a debug APK is
uploaded as a build artifact on success, and the lint report is uploaded on
failure.

### Version pins that have to stay matched

- AGP `8.7.2` ↔ Gradle wrapper `8.9` — AGP 8.7.x requires Gradle 8.9+.
  Bumping one without the other is the most common reason a freshly cloned
  Android project fails to sync.
- `compileSdk`/`targetSdk 35` ↔ `com.google.android.material:1.12.0` and
  AGP `8.7.2` — both support API 35.
- `ndkVersion 26.3.11579264` and CMake `3.22.1` are pinned in
  `app/build.gradle.kts` / `externalNativeBuild` so CI and a local machine
  resolve the identical native toolchain.

## Package layout

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   ├── native_bridge.cpp      # JNI entry points
│   ├── image_probe.h/.cpp     # PNG/JPEG/WebP header parsing
│   ├── mask_ops.h/.cpp        # flood fill, hole fill, largest component, contour trace
│   ├── voxel_carve.h/.cpp     # shape-from-silhouette visual hull
│   └── surface_nets.h/.cpp    # voxel grid -> triangle mesh
├── java/com/photo2model/studio/
│   ├── core/
│   │   ├── NativeBridge.kt
│   │   ├── ProjectModel.kt           # ViewType, ViewSlot, ProjectModel (JSON)
│   │   ├── ProjectManager.kt         # filesystem + import logic
│   │   ├── MaskManager.kt            # mask PNG I/O, threshold, alpha-seed
│   │   ├── Landmark.kt               # LandmarkSchema, LandmarkSet (JSON)
│   │   ├── ConsistencyValidator.kt   # section 5.5 checks
│   │   ├── ReconstructionManager.kt  # mask -> constraint -> native carve+mesh
│   │   ├── ObjExporter.kt / ObjLoader.kt
│   │   └── MeshHandoff.kt            # in-process mesh handoff between Activities
│   └── ui/
│       ├── ProjectListActivity.kt
│       ├── ImportActivity.kt
│       ├── MaskEditorActivity.kt
│       ├── LandmarkEditorActivity.kt
│       ├── ReconstructionActivity.kt
│       ├── MeshViewerActivity.kt
│       ├── MeshGLRenderer.kt
│       ├── CropOverlayView.kt
│       ├── MaskCanvasView.kt
│       ├── LandmarkOverlayView.kt
│       ├── ProjectAdapter.kt
│       └── ViewSlotAdapter.kt
└── res/
```
