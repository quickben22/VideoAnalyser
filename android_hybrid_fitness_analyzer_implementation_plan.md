# Implementation Plan: Real-Time Zero-Storage Hybrid Fitness Analyzer (Android)

## 1. Objective & Architectural Constraints
Build a native Android application in **Kotlin** and **Jetpack Compose** that analyzes live exercise sessions (such as kickboxing aerobics) in real time **without ever recording or saving video files to disk**.

* **Zero-Storage Pipeline:** Live camera frames are processed strictly in RAM via Jetpack CameraX `ImageAnalysis` (`ImageProxy`) and immediately closed/discarded after inference. Do **not** include or bind `VideoCapture`.
* **Local Vision Engine:** Google **ML Kit Pose Detection** (`STREAM_MODE`) extracts 33 skeletal 3D landmarks per frame at 30+ FPS completely offline.
* **Local Exercise & Telemetry Engine:** Deterministic Kotlin state machines smooth raw coordinates, compute joint angles/velocities, classify movements (punches, high knees, side kicks), count reps, and aggregate a compact JSON session log in memory.
* **Cloud Factual Summary:** When the user ends a workout, the app sends only compact detected counts, duration and recorded first/last times to Gemini Flash via Firebase AI Logic for a factual recap, without coaching or advice.
* **Offline Resilience:** If offline at workout completion, persist only the tiny JSON telemetry payload locally (DataStore/Room) and render immediate local stats, syncing the AI summary when network connectivity returns.

---

## 2. Tech Stack & Gradle Dependencies

* **Language:** Kotlin (2.0+)
* **UI Framework:** Jetpack Compose (Material 3)
* **Architecture:** MVVM + Unidirectional Data Flow (`StateFlow`), Clean Architecture separation (`ui`, `camera`, `vision`, `domain`, `data`)
* **Min SDK:** 24 | **Target SDK:** 35+

### Required Dependencies (`gradle/libs.versions.toml` & `app/build.gradle.kts`)
1. **CameraX:**
   * `androidx.camera:camera-core:1.4.2`
   * `androidx.camera:camera-camera2:1.4.2`
   * `androidx.camera:camera-lifecycle:1.4.2`
   * `androidx.camera:camera-view:1.4.2`
2. **ML Kit Pose Detection (Base model for 30+ FPS speed):**
   * `com.google.mlkit:pose-detection:18.0.0-beta5`
3. **Firebase AI Logic (Secure Gemini API access without hardcoded API keys):**
   * `com.google.firebase:firebase-bom:35.1.0`
   * `com.google.firebase:firebase-ai`
   * `com.google.firebase:firebase-appcheck-playintegrity`
4. **Serialization & Persistence:**
   * `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`
   * `androidx.datastore:datastore-preferences:1.1.3`
5. **Permissions & Coroutines:**
   * `com.google.accompanist:accompanist-permissions:0.37.0`
   * `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0`

### Manifest Permissions (`AndroidManifest.xml`)
```xml
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## 3. Project Package Structure

```text
com.example.fitpulseai/
├── MainActivity.kt
├── di/
│   └── AppModule.kt                    # Dependency injection (or manual ServiceLocator)
├── camera/
│   ├── CameraAnalyzer.kt               # ImageAnalysis.Analyzer bridging CameraX -> ML Kit
│   └── CameraManager.kt                # Lifecycle binding, resolution & backpressure config
├── vision/
│   ├── PoseDetectorWrapper.kt          # ML Kit STREAM_MODE setup & coordinate mapping
│   ├── LandmarkSmoother.kt             # Exponential Moving Average (EMA) filter for joint jitter
│   └── PoseGeometry.kt                 # 2D/3D angle (atan2), torso normalization & velocity math
├── domain/
│   ├── model/
│   │   ├── ExerciseType.kt             # Enum: PUNCH_JAB_CROSS, HIGH_KNEE, SIDE_KICK, IDLE
│   │   ├── PoseFrameData.kt            # Smoothed landmarks + frame timestamp
│   │   └── WorkoutTelemetry.kt         # Serializable session summary & timeline events
│   ├── classifier/
│   │   ├── ExerciseClassifier.kt       # Interface for movement state machines
│   │   ├── PunchClassifier.kt          # Elbow extension + wrist velocity detector
│   │   ├── HighKneeClassifier.kt       # Knee-to-hip Y elevation detector
│   │   └── SideKickClassifier.kt       # Hip abduction + knee extension detector
│   └── SessionAggregator.kt            # Tracks rep counts, timestamps, left/right angle averages
├── data/
│   ├── FactualSummary.kt               # Firebase AI Logic, local storage and offline retry
│   └── LocalSessionStorage.kt          # DataStore cache for offline workout JSON logs
└── ui/
    ├── navigation/AppNavGraph.kt
    ├── workout/
    │   ├── WorkoutScreen.kt            # Full-screen CameraX preview + Compose skeleton overlay + HUD
    │   ├── SkeletonOverlayCanvas.kt    # Renders 33 joints & bones scaled to screen aspect ratio
    │   └── WorkoutViewModel.kt         # Manages live rep counters, timer, and session state
    └── summary/
        ├── SummaryScreen.kt            # Displays factual recap and detected counts
        └── SummaryViewModel.kt         # Triggers Gemini API call & handles offline retry
```

---

## 4. Step-by-Step Implementation Specifications

### Step 1: CameraX & Zero-Storage ML Kit Pipeline (`camera/` & `vision/`)
1. Configure `PoseDetectorOptions` with `PoseDetectorOptions.STREAM_MODE` and `PoseDetectorOptions.CPU_GPU` hardware preference.
2. Configure `ImageAnalysis` in `CameraManager.kt`:
   * Set backpressure strategy: `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`.
   * Set target resolution: `Size(640, 480)` (sufficient for ML Kit's minimum `256x256` subject requirement while preventing thermal throttling).
   * Execute analysis on a dedicated single-thread `Executors.newSingleThreadExecutor()`.
3. In `CameraAnalyzer.analyze(imageProxy: ImageProxy)`:
   * Guard against null `imageProxy.image`.
   * Construct `InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)`.
   * Pass to `poseDetector.process(inputImage)`.
   * **CRITICAL:** Attach `.addOnCompleteListener { imageProxy.close() }` so every frame is guaranteed to be released from RAM immediately after processing, regardless of success or failure.
4. Coordinate Transformation:
   * Account for sensor rotation (`90°`/`270°`) and front-camera horizontal mirroring when mapping ML Kit's image-space coordinates `(x, y)` to the Jetpack Compose `Canvas` viewport.

### Step 2: Landmark Smoothing & Geometry Math (`vision/`)
1. **Confidence Filtering:** Discard or pause rep detection for any joint where `landmark.inFrameLikelihood < 0.65f`.
2. **Smoothing (`LandmarkSmoother.kt`):** Apply an Exponential Moving Average filter ($\alpha = 0.45$) across consecutive frames for key joints (`LEFT/RIGHT_SHOULDER`, `ELBOW`, `WRIST`, `HIP`, `KNEE`, `ANKLE`) to eliminate high-frequency jitter.
3. **Geometry (`PoseGeometry.kt`):**
   * `calculateAngle(a: PointF, b: PointF, c: PointF): Float` using `atan2` to return degrees in `[0f, 180f]`.
   * `torsoScale(pose): Float` computing Euclidean distance between mid-shoulder and mid-hip to normalize limb speeds and distances independent of how far the user stands from the camera.

### Step 3: Exercise State Machines (`domain/classifier/`)
Implement a 3-state finite state machine (`READY` -> `ACTIVE_PEAK` -> `COOLDOWN`) per exercise with a minimum 350ms debounce between reps to prevent double-counting:

1. **`PunchClassifier` (Shadow Boxing / Jab-Cross):**
   * **Trigger:** Elbow angle (`shoulder-elbow-wrist`) extends from `< 100°` to `> 155°` AND normalized wrist forward/lateral velocity exceeds threshold while wrist `Y` stays near shoulder height.
   * **Metrics Recorded:** Left vs. right count, peak elbow extension angle.
2. **`HighKneeClassifier`:**
   * **Trigger:** Active knee `Y` rises above active hip `Y` (`knee.y < hip.y` in screen coordinates) while the standing ankle remains stationary near baseline `Y`.
   * **Metrics Recorded:** Left vs. right count, peak knee elevation ratio relative to torso height.
3. **`SideKickClassifier`:**
   * **Trigger:** Active hip abduction angle (`oppositeHip - activeHip - activeKnee`) exceeds `110°` AND active knee angle (`hip - knee - ankle`) extends `> 150°`.
   * **Metrics Recorded:** Left vs. right count, peak leg extension angle (`degrees`).

### Step 4: Session Telemetry Schema (`WorkoutTelemetry.kt`)
When the user taps **Finish Workout**, `SessionAggregator` serializes the session into the following compact JSON structure (kept under ~1,500 tokens):

```json
{
  "session_id": "uuid-string",
  "duration_seconds": 360,
  "timeline_blocks": [
    {
      "start_time": "00:03",
      "end_time": "01:15",
      "primary_exercise": "PUNCH_JAB_CROSS",
      "reps": 48,
      "left_reps": 24,
      "right_reps": 24,
      "avg_peak_angle_deg": 164.2
    },
    {
      "start_time": "01:18",
      "end_time": "01:39",
      "primary_exercise": "HIGH_KNEE",
      "reps": 26,
      "left_reps": 13,
      "right_reps": 13,
      "avg_peak_angle_deg": 88.5
    },
    {
      "start_time": "01:40",
      "end_time": "02:30",
      "primary_exercise": "SIDE_KICK",
      "reps": 18,
      "left_reps": 9,
      "right_reps": 9,
      "avg_peak_angle_deg": 134.0,
      "left_avg_angle_deg": 126.5,
      "right_avg_angle_deg": 141.5
    }
  ],
  "totals": {
    "total_reps": 92,
    "detected_asymmetries": [
      "Left side kick extension averaged 15.0 degrees lower than right side"
    ]
  }
}
```

### Step 5: Gemini Flash Factual Summary Integration (`FactualSummary.kt`)
1. Initialize the Gemini generative model using Firebase AI Logic (`Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-3.8-flash")`).
2. Configure structured JSON output with a response schema for `headline`, `overview`, and `chronological_entries` (`timestamp`, `title`, `description`).
3. Instruct the model to describe only supplied detected estimates, with no advice, form evaluation, or inferred activities. First/last detection bounds do not imply continuous activity.

### Step 6: UI & User Experience (`ui/`)
1. **Framing Calibration Banner (`WorkoutScreen`):** Before starting the timer, check if both shoulders and both ankles have `inFrameLikelihood >= 0.65f`. If not, display an overlay prompt: *"Step back until your full body fits inside the frame."*
2. **Live Skeleton HUD (`SkeletonOverlayCanvas`):** Draw green bones when joint confidence is high, yellow when partially occluded, alongside a top floating pill showing elapsed time, active detected exercise name, and live rep counter.
3. **Summary Screen (`SummaryScreen`):** Show local totals, duration and detected exercise breakdown, plus a factual AI recap and pending, generating, or failure states with retry.

---

## 5. Verification & Acceptance Criteria

* [ ] **Zero Disk Footprint:** Inspect app storage (`context.filesDir`, `cacheDir`, `MediaStore`) during and after a 5-minute session; confirm zero `.mp4` or raw image files are created.
* [ ] **No Memory Leaks:** Verify `imageProxy.close()` executes on 100% of frames using Android Studio Memory Profiler (heap memory stays flat during continuous 30 FPS camera streaming).
* [ ] **Framing & Orientation Handling:** Verify skeleton overlay aligns accurately with the user's body in portrait mode using both front (mirrored) and rear cameras.
* [ ] **Classifier Accuracy:** Confirm state machines accurately increment reps and record timestamps when performing shadow punches, high knees, and side kicks in front of the camera.
* [ ] **End-to-End Gemini Summary:** Confirm tapping **Finish Workout** transmits only compact JSON telemetry (< 3 KB) and renders a factual recap on `SummaryScreen`.
* [ ] **Offline Fallback:** Enable Airplane Mode before finishing a workout; confirm local telemetry is saved safely and the summary screen still renders raw rep/angle metrics with a pending AI sync state.