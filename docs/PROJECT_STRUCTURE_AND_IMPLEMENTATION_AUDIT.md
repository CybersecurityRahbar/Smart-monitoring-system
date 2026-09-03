# Smart Traffic — Project Structure & Implementation Audit

**Audit date:** 2026-09-03
**Repository:** `CybersecurityRahbar/Smart-monitoring-system`
**Audited branch:** `main`
**Audited HEAD:** `fcb40deca327c8de135a5a76e21a63299001d775`

## 1. Purpose of this document

This document is an implementation-oriented inventory of the repository as it exists at the audited HEAD. It is intentionally different from the large Arabic conversation-context document: the conversation document preserves historical decisions and discussion, while this file describes the repository itself, its modules, the files that implement them, and the difference between implemented functionality and planned functionality.

The audit compares documentation with actual source code and follows the dependency direction of the application rather than assuming that a screen or class name means that the underlying feature is fully implemented.

## 2. Audit scope and evidence

Reviewed in `docs/`:

1. `ADVANCED_VISION_STACK.md`
2. `AI_RADAR_ARCHITECTURE.md`
3. `AI_VISION_ALGORITHM_PLAN.md`
4. `ANDROID_CI.md`
5. `ARCHITECTURE.md`
6. `LOCAL_ANALYSIS_LAB.md`
7. `PROJECT_CONTEXT.md`
8. `RELIABILITY_DESIGN.md`
9. `UI_SPEC.md`
10. `UI_VISION.md`
11. `وثيقة سياق المحادثة لمشروع مراقبة وتتبع المرور.md`

Reviewed repository structure, Android build files, Android runtime/domain/data/features, native C++/JNI, Python research code, model metadata/artifact presence, and JVM/offline tests.

The large Arabic conversation-context file is a historical transcript-style artifact of approximately 798 KB and contains substantial repetition. It remains the historical continuity source and was not replaced during this audit.

Binary model data such as `yolo26n.tflite` was verified through repository metadata, model registry metadata, checksum information and runtime contract code; binary bytes are not treated as human-readable source.

The audit does **not** claim that an opaque binary has been read as text, nor that every generated/cache artifact can be meaningfully inspected line by line. The source, configuration, documentation and test files relevant to application behavior were reviewed directly.

## 3. Repository-level architecture

The repository currently has these major areas:

```text
Smart-monitoring-system/
├── .github/
│   └── workflows/
│       └── android-ci.yml
│
├── README.md
├── PROJECT_CONTEXT.md                     # root legacy/context copy; not authoritative log
│
├── docs/
│   ├── ADVANCED_VISION_STACK.md
│   ├── AI_RADAR_ARCHITECTURE.md
│   ├── AI_VISION_ALGORITHM_PLAN.md
│   ├── ANDROID_CI.md
│   ├── ARCHITECTURE.md
│   ├── LOCAL_ANALYSIS_LAB.md
│   ├── PROJECT_CONTEXT.md                 # authoritative cumulative context
│   ├── RELIABILITY_DESIGN.md
│   ├── UI_SPEC.md
│   ├── UI_VISION.md
│   ├── وثيقة سياق المحادثة لمشروع مراقبة وتتبع المرور.md
│   └── PROJECT_STRUCTURE_AND_IMPLEMENTATION_AUDIT.md  # this document
│
├── ai/
│   ├── README.md
│   ├── requirements.txt
│   ├── robust_speed.py
│   ├── run_traffic_analysis.py
│   └── tests/
│       └── test_robust_speed.py
│
├── android/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   ├── README.md
│   ├── PROJECT_STRUCTURE.md
│   └── app/
│       ├── build.gradle.kts
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── assets/models/
│           │   │   ├── README.md
│           │   │   └── yolo26n.tflite
│           │   ├── cpp/
│           │   │   ├── CMakeLists.txt
│           │   │   ├── traffic_core.cpp
│           │   │   ├── traffic_core.h
│           │   │   └── traffic_core_jni.cpp
│           │   └── java/com/smarttraffic/app/
│           │       ├── MainActivity.kt
│           │       ├── SmartTrafficApp.kt
│           │       ├── core/
│           │       ├── data/
│           │       ├── domain/
│           │       ├── features/
│           │       ├── navigation/
│           │       └── ui/theme/
│           └── test/java/...               # JVM reliability/unit tests
│
├── esp32/
│   └── README.md
│
└── backend/                                # referenced in planning, currently absent
```

The real implementation is therefore concentrated in Android + AI/Python + native C++. The ESP32 and backend portions are currently incomplete or planned.

## 4. Product architecture

The intended application direction is:

```text
ESP32-CAM / local media
        ↓
     FrameSource
        ↓
 Analysis Pipeline
        ↓
 Detector
        ↓
 Multi-object Tracker
        ↓
 Ground/contact geometry
        ↓
 Camera/road calibration
        ↓
 Homography
        ↓
 Metric trajectory
        ↓
 Robust velocity
        ↓
 Plate/OCR (planned backend)
        ↓
 Traffic rules
        ↓
 Evidence
        ↓
 Operator UI
```

The Android UI follows the intended dependency direction:

```text
Compose UI
  ↓
State / ViewModel
  ↓
Domain interfaces and pipeline
  ↓
Data / persistence / device adapters
  ↓
External stream or local source
```

The repository also preserves explicit separation between Android production paths, C++ performance primitives, and Python research/evaluation.

## 5. Documentation inventory and actual status

### `ADVANCED_VISION_STACK.md`

Defines the intended high-quality traffic vision stack. It specifies modern detector/tracker candidates, calibration, homography, contact-point geometry, robust speed estimation, optical flow, dynamic keypoint research, segmentation, Re-ID, OCR, confidence fusion, scheduling and benchmarking.

Actual status: this is a design/reference document. A subset is implemented in Android (LiteRT detector, ByteTrack-inspired tracker, calibration, homography, robust speed). Many advanced backends remain research/planned and are not silently substituted.

### `AI_RADAR_ARCHITECTURE.md`

Defines Smart Radar as a **software computer-vision visualization/analytics layer**, not a physical radar sensor. It specifies interchangeable media sources, the shared pipeline, track state and visual overlay requirements.

Actual status: the Local Analysis preview implements an actual analysis-driven radar visualization; the top-level Intelligent Radar screen is still largely a UI shell and is not yet connected to the live engine.

### `AI_VISION_ALGORITHM_PLAN.md`

Defines the complete algorithm plan from frames through detection, tracking, geometry, speed, OCR, rules and evidence, plus the laboratory methodology and acceptance metrics.

Actual status: the deterministic geometry and local detection/tracking path exist; OCR, production rules/event persistence and evidence media capture are incomplete.

### `ANDROID_CI.md`

Documents the CI/APK workflow.

Actual status: the document is partially stale. It still states versions corresponding to an earlier build state, while the actual current Gradle configuration uses newer AGP/Gradle values. The workflow itself is the authoritative CI implementation.

### `ARCHITECTURE.md`

High-level architecture and repository abstraction principles.

Actual status: broadly consistent with the domain/data split, but some conceptual repository/use-case layers described there are not yet fully materialized as separate modules/classes.

### `LOCAL_ANALYSIS_LAB.md`

Specifies the intended hardware-independent laboratory for repeatable video/image experiments.

Actual status: real local analysis exists and runs the detector/tracker/geometry pipeline. Some requested operator controls such as exact pause/seek, complete session persistence and reference-speed comparison UI are still incomplete.

### `PROJECT_CONTEXT.md`

Current cumulative engineering handoff document. It contains the authoritative current context, known blockers, test philosophy, development order and recent work history.

Actual status: current and useful, but the context is a historical work log rather than a literal file-by-file source inventory; this audit is the companion implementation inventory.

### `RELIABILITY_DESIGN.md`

Defines the project's measurement integrity policy: perception, tracking, geometry, robust estimation, explicit quality gates and independent validation are separate concerns.

Actual status: the current implementation follows this policy in several important areas, especially calibration and physical-speed gating.

### `UI_SPEC.md`

Defines visual and interaction expectations for Dashboard, Live Camera, Radar, Local Analysis, Devices, Rules, Watchlist, Alerts, Evidence and Settings.

Actual status: some screens are real functional screens; several remain presentation shells.

### `UI_VISION.md`

Defines the product role, visual language and core interaction concepts.

Actual status: mostly a product/design specification rather than executable behavior.

### `وثيقة سياق المحادثة لمشروع مراقبة وتتبع المرور.md`

The large historical conversation archive. It contains the complete accumulated project discussion, decisions, hardware reasoning, UI changes, algorithm choices, bug fixes and stopping points.

Actual status: authoritative historical continuity source, but not an ideal technical index because of extensive transcript repetition.

## 6. Android application entry points and configuration

### `android/build.gradle.kts`

Current top-level Android plugin setup. It uses Android Gradle Plugin `9.3.2` and Kotlin Compose plugin `2.2.10`.

### `android/settings.gradle.kts`

Declares the `SmartTraffic` project and the single `:app` module, with Google/Maven Central/Gradle Plugin Portal repository configuration.

### `android/gradle.properties`

Sets a 2 GB Gradle JVM heap, AndroidX, official Kotlin style and the temporary `android.uniquePackageNames=false` compatibility workaround used for the current LiteRT dependency namespace issue under AGP 9.

### `android/app/build.gradle.kts`

Defines the Android application build. The current documented/runtime targets include compile/target SDK 37, min SDK 26, Java 17, NDK `27.2.12479018`, CMake `3.22.1`, Compose BOM `2026.08.00` and LiteRT `2.2.0`.

The build also contains the model verification task for `yolo26n.tflite`, including checksum/size protection.

### `AndroidManifest.xml`

Currently requests:
- `INTERNET`
- `ACCESS_WIFI_STATE`
- `ACCESS_NETWORK_STATE`

The app permits cleartext HTTP because the first ESP32 milestone is local HTTP/MJPEG, and the main activity is PiP-capable.

### `MainActivity.kt`

Application entry point. It loads application/device settings, chooses language/direction/theme behavior and installs `SmartTrafficApp`.

### `SmartTrafficApp.kt`

Thin application composable that enters `AppNavHost`.

## 7. Navigation and UI shell

### `navigation/AppNavHost.kt`

Defines destinations:

`Dashboard, Radar, Live, Alerts, More, Reports, Analysis, Calibration, Devices, Rules, Watchlist, Evidence, Settings`

The main phone navigation uses five primary destinations and nested pages are entered from the More/Operations area.

The architecture is intentionally simple and is not yet a full navigation framework/route state machine.

Known minor implementation gap: the calibration nested title is hard-coded in English (`Camera Calibration`) instead of using the translation system.

### `ui/theme/Theme.kt`

Defines Material 3 light/dark color schemes and the visual baseline for the app.

## 8. Core settings and transport

### `core/AppSettings.kt`

Persists application-level preferences such as language and dark mode and provides the translation-key lookup used throughout the UI.

### `core/DeviceSettings.kt`

Persists a single editable ESP32 endpoint profile using `SharedPreferences`:

- host
- HTTP port
- stream path
- capture path
- status path

Default prototype values are `192.168.4.1:80`, `/stream`, `/capture`, `/status`.

It normalizes endpoint paths and builds URLs for the device layer.

### `core/TrafficRulePreferences.kt`

Provides the shared persistence bridge between traffic-rule configuration and the analysis runtime. It reads/writes speed limit, minimum speed confidence, enable flag and violation actions.

### `core/network/MjpegStreamClient.kt`

Real dependency-free MJPEG HTTP client. It parses multipart boundaries, optional `Content-Length`, JPEG start/end markers, maximum frame size and basic HTTP failures. This is not a fake preview: it can consume an actual MJPEG stream.

### `core/ui/VideoViewport.kt`

Shared video display component supporting image frames and multiple display modes plus PiP-related behavior.

## 9. Domain analysis layer

### `domain/analysis/AnalysisModels.kt`

Contains the core data contracts:

- `FrameTimestampPrecision`
- `MediaSource`
- `Detection`
- `GroundPoint`
- `VehicleKeypoint`
- `TrackObservation`
- `Track`
- `SpeedEstimate`
- `PlateReading`
- `CalibrationProfile`
- `AnalysisConfig`
- `AnalysisMetrics`
- `AnalysisResult`

This file is important because it makes detector/tracker/geometry components replaceable without changing the rest of the UI.

### `domain/analysis/AnalysisEngine.kt`

Defines replaceable interfaces for:
- object detection;
- multi-object tracking;
- learned vehicle keypoints;
- plate recognition;
- frame-source creation;
- analysis execution.

`ModularAnalysisEngine` delegates real work to `AnalysisPipelineRunner` and refuses to invent a source for an abstract `MediaSource` when no source factory exists.

### `domain/analysis/AnalysisPipelineRunner.kt`

The central online analysis coordinator. It currently performs the real sequence of:

1. source frame acquisition;
2. timestamp/size checks;
3. detector invocation;
4. tracker update;
5. contact-point estimation;
6. homography projection when valid;
7. optional keypoint/plate hooks;
8. robust speed estimation;
9. traffic-rule evaluation;
10. preview publication;
11. metrics aggregation;
12. result construction.

It also records frame gaps, inference timing, active tracks and other diagnostic metrics.

Critical reliability behavior: physical speed is blocked unless calibration and timestamp requirements pass. Unsupported requested optional components are rejected instead of silently acting as if they were available.

### `domain/analysis/GeometryAndSpeed.kt`

Contains:

- `HomographyProjector`: maps image coordinates to metric ground coordinates using a 3×3 homography.
- `RobustSpeedEstimator`: uses pairwise metric velocity slopes, MAD-based outlier rejection, robust median velocity components, trajectory residuals, temporal coverage and uncertainty/quality scoring.

The estimator explicitly avoids naive adjacent-frame Euclidean step speed because localization jitter can bias a non-negative distance sum upward.

Important semantic rule: its confidence is a quality score, not a calibrated probability; `errorKmh` is uncertainty, not an independently measured ground-truth error.

### `domain/analysis/HomographyEstimator.kt`

Contains normalized coordinate handling and deterministic RANSAC-based homography estimation. The documented denormalization order is:

`H = T_target^-1 * H_normalized * T_source`

It reports inlier counts/ratios and reprojection error.

### `domain/analysis/CalibrationBuilder.kt`

Builds a versioned calibration profile from measured image-to-ground correspondences. Requires at least four point pairs, computes a RANSAC homography and then validates the resulting profile before returning it.

### `domain/analysis/CalibrationValidator.kt`

Central calibration quality gate. Checks:

- non-empty id;
- positive image dimensions;
- source-size matching when supplied;
- positive version;
- finite 3×3 homography;
- non-singular homography;
- finite measured reprojection error;
- sufficient inlier ratio;
- sufficient inlier count when present.

### `domain/analysis/CalibrationStore.kt`

Contract for durable calibration profile storage.

### `domain/analysis/PlateConsensus.kt`

Implements temporal OCR consensus by normalizing plate text and weighting readings using exponential recency based on actual timestamps. This is a consensus layer only; it is not a real plate detector/OCR implementation.

### `domain/analysis/TrafficRules.kt`

Defines `TrafficRuleConfig` and `TrafficRuleEngine`. Current rule behavior is intentionally narrow: it emits a `SPEEDING` event only when rules are enabled, a calibration is supplied, speed is finite, speed confidence is above threshold, speed exceeds the limit, and the corresponding track exists.

### `domain/analysis/ValidationMetrics.kt`

Calculates independent-reference speed evaluation statistics: MAE, RMSE, median absolute error, P90/P95 absolute error and ±5/10/20% coverage.

### `domain/analysis/FrameSource.kt` and `AnalysisPreview.kt`

Define the timestamped frame abstraction and preview observer/state path used to expose actual processed frames and tracks to the Local Analysis UI.

## 10. Data layer

### `data/analysis/LocalVideoFrameSource.kt`

Uses `MediaMetadataRetriever` to sample local video at requested times (currently 100 ms intervals by default). The source explicitly labels its timestamp precision as `REQUESTED_SAMPLE_TIME` because the requested time is not guaranteed to be the exact decoded presentation timestamp.

This is a deliberate safety limitation: physical-speed mode is therefore prevented from claiming exact timing on this source.

The same file currently also defines `LocalImageFrameSource`, which emits one exact source image frame for single-image experiments.

### `data/analysis/FileCalibrationStore.kt`

Stores validated calibration profiles in app-private `camera_calibrations.json`. Profiles are replaceable by calibration id and preserve version numbers.

### `data/evidence/FileEvidenceStore.kt`

Stores evidence records in app-private `traffic_evidence.json` and validates the record schema. It writes through a temporary file before replacing the published file to reduce partial-write risk.

Important limitation: current evidence records contain metadata and frame/source references; the actual captured image bytes/crops are not yet persisted by this store.

### `data/nativecore/NativeTrafficCore.kt`

JNI facade exposing native homography projection and robust speed functions to Android.

### `data/tracking/AppearanceSignature.kt`

Creates a lightweight deterministic 2×2 spatial RGB histogram. It is explicitly **not** learned Re-ID.

### `data/tracking/AppearanceAugmentingDetector.kt`

Wraps another detector and adds the deterministic appearance signature to each detection so the tracker can optionally use it as a secondary association cue.

### `data/tracking/KalmanBoxPredictor.kt`

Provides a constant-velocity scalar Kalman model for bounding-box center/size prediction.

### `data/tracking/LinearAssignment.kt`

Implements deterministic rectangular Hungarian minimum-cost assignment.

### `data/tracking/ByteTrack.kt`

Current Android tracker. It is ByteTrack-inspired rather than reference-equivalent and currently includes:

- Kalman prediction;
- Hungarian/global assignment;
- class-aware gating;
- high-confidence matching;
- lower-confidence second-stage matching;
- IoU/center-distance gating;
- optional deterministic appearance similarity;
- bounded empirical-motion recovery;
- lost/removed track handling;
- identity confirmation by repeated hits.

The code itself explicitly avoids claiming byte-for-byte equivalence with the official ByteTrack implementation.

## 11. Vision model layer

### `data/vision/DetectorModelRegistry.kt`

The model registry is intentionally explicit. Current registered model:

`yolo26n` → `models/yolo26n.tflite`

with:
- input size 640;
- NCHW RGB FP32 preprocessing;
- 80-class COCO metadata;
- current traffic classes: car/motorcycle/bus/truck;
- exact SHA-256 metadata;
- source metadata;
- classic `[1,84,8400]` output contract.

The registry distinguishes model id from model tensor contract so future model exports cannot be silently misinterpreted.

### `data/vision/LiteRtObjectDetector.kt`

Real LiteRT detector adapter. It:

1. prepares a letterboxed RGB tensor;
2. checks input tensor assumptions;
3. runs a compiled LiteRT model;
4. checks output tensor element count;
5. parses the currently active classic YOLO contract;
6. optionally supports an explicitly registered `[1,300,6]` end-to-end contract;
7. converts coordinates back to source image coordinates;
8. performs class-aware NMS for the classic contract;
9. records inference latency.

The output parser is not allowed to guess a tensor shape outside the declared contracts.

### `data/vision/DetectorBenchmark.kt`

Provides model inference benchmarking with warmup, measured runs, median/P95/min/max/mean latency and estimated FPS. It can accept a LiteRT accelerator selection.

Current limitation: the Local Analysis runtime hardcodes CPU and the UI does not yet expose a complete CPU/GPU/NPU device benchmark workflow.

## 12. Feature screens and actual implementation state

### Dashboard
`features/dashboard/DashboardScreen.kt`

Current role: command-center presentation.

Status: primarily UI shell. Several high-level operational metrics are static placeholders rather than connected live aggregates.

### Intelligent Radar
`features/radar/IntelligentRadarScreen.kt`

Current role: top-level radar workspace.

Status: **not yet connected to the actual analysis engine**. It currently owns local UI state for filters and a start/stop toggle, renders a shared `VideoViewport`, and shows placeholder `0` tracks / `—` speed. The Analyze Media button does not yet launch the engine.

This is one of the most important remaining integration gaps.

### Live Camera
`features/live/LiveCameraScreen.kt`

Current role: real ESP32/local-network MJPEG viewer.

Status: actual stream connection/disconnection works through `MjpegStreamClient` and the configurable `DeviceSettings` URL. Capture and camera-control buttons are currently UI placeholders with empty handlers. Live analysis is not yet attached to this screen.

### Local Analysis Lab
`features/analysis/LocalAnalysisScreen.kt` + `LocalAnalysisViewModel.kt`

Current role: actual offline experiment runner.

Status: this is currently the strongest end-to-end functional UI path. It can choose video/image media, select calibration profiles, instantiate the registered LiteRT detector, use the ByteTrack-inspired tracker, execute the analysis engine, expose actual processed frames and metrics, and persist evidence metadata when enabled.

The current tracker/model selection is still mostly fixed in the ViewModel rather than being a fully user-selectable benchmark matrix.

### Analysis Radar Preview
`features/analysis/AnalysisRadarPreview.kt`

Displays the actual decoded bitmap and tracked detections. When calibrated, it can visualize ground-plane coordinates; otherwise it clearly labels the visualization as image-space rather than physical metric radar.

### Camera Calibration Lab
`features/calibration/CalibrationLabScreen.kt`

Real calibration workflow at the **road-plane homography** level:

- choose a reference image;
- tap image points;
- enter measured ground X/Y in metres;
- build RANSAC homography;
- calculate quality statistics;
- save only profiles passing validation.

Important distinction: this is not yet a full intrinsic camera-calibration/lens-distortion workflow using a chessboard or equivalent camera model. Intrinsics/distortion fields exist in the domain profile but are not populated by this UI.

### Devices
`features/devices/DevicesScreen.kt`

Real operator-configured device endpoint screen. It uses editable host/port/path values and a Save + Test operation against `/status`. Technical fields are explicitly LTR even when the application is Arabic.

### Traffic Rules
`features/rules/TrafficRulesScreen.kt`

Current UI exposes speed limit, warning threshold, capture threshold, confidence threshold and action toggles.

Persistence uses the same `traffic_rules` preferences namespace as `TrafficRulePreferences`.

Important implementation gap: warning speed and capture speed fields are saved by the screen, but the runtime `TrafficRuleConfig` currently consumes only the speed limit, minimum speed confidence and action flags. Therefore warning/capture numeric thresholds are currently UI configuration values without runtime effect.

### Evidence
`features/evidence/EvidenceScreen.kt`

Reads the durable evidence record store and displays event metadata such as speed, limit, confidence, timestamp, frame, plate, calibration and model.

Important limitation: because the backing store currently contains metadata/frame references rather than actual image files/crops, this is not yet a complete visual evidence vault.

### Incident Reports
`features/reports/IncidentReportsScreen.kt`

UI-only local state form for manual reports. Submission currently changes local screen state rather than writing to a persistent incident repository. Recent reports are static examples.

### Watchlist
`features/watchlist/WatchlistScreen.kt`

UI-only state. Plate input and action toggles exist, but Add Watchlist has no persistence and no matching integration.

### Alerts
`features/alerts/AlertsScreen.kt`

Currently a static alert-status presentation. No durable alert feed or pipeline subscription exists yet.

### Settings
`features/settings/SettingsScreen.kt`

Language and dark-mode settings are real and persisted. System-setting entries below them are currently presentation cards rather than complete navigation/configuration workflows.

### More / Operations Hub
`features/more/OperationsHubScreen.kt`

Acts as the secondary feature navigation hub and exposes Reports, Analysis, Calibration, Devices, Rules, Watchlist, Evidence and Settings.

Minor gap: the calibration title is partly hard-coded instead of fully localized.

## 13. Native C++ / JNI layer

### `android/app/src/main/cpp/CMakeLists.txt`

Builds the shared library `smarttraffic_native` with C++20, optimization and Android/log linkage.

### `traffic_core.cpp`

Implements:
- safe homography point projection;
- robust speed estimation in metric coordinates;
- pairwise velocity slopes;
- MAD filtering;
- robust median velocity components;
- trajectory residual/quality measures;
- uncertainty estimate.

It intentionally mirrors the Android Kotlin mathematical approach closely enough to support future parity testing.

### `traffic_core.h` and `traffic_core_jni.cpp`

Declare native APIs and bridge them to Java/Kotlin through JNI.

### `data/nativecore/NativeTrafficCore.kt`

Android-side JNI facade exposing homography and robust-speed calls.

### Native production status

The C++ code is compiled, but the production analysis path still uses Kotlin geometry/speed because native/Kotlin parity has not yet been established with dedicated tests. Production routing must wait for parity verification.

## 14. Python research/evaluation layer

### `ai/README.md`

Describes offline research/evaluation intent.

### `ai/requirements.txt`

Provides the research stack, including Ultralytics, OpenCV, NumPy, pandas and SciPy.

### `ai/robust_speed.py`

Offline Python counterpart to the Android robust-speed methodology.

### `ai/tests/test_robust_speed.py`

Tests outlier-resistant speed behavior and stationary/noise cases.

### `ai/run_traffic_analysis.py`

Offline video-analysis/research runner. It can use OpenCV/Ultralytics tracking and produce structured outputs such as track records, summaries and optional annotated video. It also contains research hooks for dynamic homography/keypoint experiments.

Important limitations recorded in the project context:
- video timestamps are approximate when derived from frame index/FPS;
- dynamic keypoint/homography work is research-only;
- some setup is oriented toward experimentation rather than the exact Android production pipeline;
- it is not yet the project's final labeled-dataset evaluation harness.

## 15. Model artifact

Current committed model:

`android/app/src/main/assets/models/yolo26n.tflite`

Model metadata is registered in `DetectorModelRegistry.kt`, and the build verifies its expected SHA-256. The current active contract is the classic `[1,84,8400]` style output, not the newer end-to-end `[1,300,6]` contract.

The model file is therefore a real committed inference artifact, not a placeholder filename.

## 16. Calibration storage and physical-measurement safety

The project deliberately treats calibration as camera/profile specific.

Current calibration profile data model supports:

- resolution;
- homography;
- optional intrinsics/distortion;
- reprojection error;
- version;
- inlier count/ratio.

The current practical workflow constructs the road-plane homography from measured correspondences and then applies quality gates.

Physical speed is intentionally blocked in the local-video path when exact decoded timestamps are unavailable. This is one of the most important reliability decisions in the repository because a mathematically correct speed formula can still produce an invalid physical measurement if the time base is not trustworthy.

## 17. Traffic rule and evidence path actually present

Current real logical chain:

```text
SpeedEstimate
   ↓
TrafficRuleEngine
   ↓
TrafficEvent
   ↓
(optional)
FileEvidenceStore
```

The `TrafficRuleEngine` is deterministic and validates that a physical calibration is supplied before a speeding event can be emitted.

Current evidence persistence is **metadata-oriented**, not image-oriented. A future complete implementation must save reproducible image/crop/frame evidence rather than merely reference the source and frame index.

## 18. Test suite and reliability coverage

The Android test suite includes coverage for:

- calibration acceptance/rejection;
- singular/invalid homography;
- calibration quality/inlier gates;
- PlateConsensus temporal behavior;
- speeding-rule acceptance/rejection by confidence;
- missing-calibration safety;
- robust speed outlier behavior;
- stationary jitter behavior;
- homography estimation behavior;
- ByteTrack identity ordering;
- low-confidence tracking recovery;
- bounded motion recovery;
- rejection of implausible motion jumps;
- timestamp and frame-gap safety;
- detector model registry invariants;
- Hungarian assignment.

The tests are intentionally defensive against false confidence in measurements.

Missing validation categories still include:

- real-device LiteRT verification on the target phone;
- CPU/GPU/NPU comparative device benchmark;
- labeled traffic sequences evaluated with TrackEval;
- independently measured physical-speed ground truth;
- exact decoded presentation timestamps for the local video production path;
- native/Kotlin numerical parity tests;
- production OpenCV intrinsic calibration and distortion correction;
- learned vehicle-keypoint backend;
- dynamic homography production/research comparison;
- production optical-flow backend;
- learned Re-ID backend;
- real plate detector + OCR backend;
- persistent watchlist/alerts/reports backend;
- image/crop evidence capture;
- complete ESP32 firmware/field validation.

## 19. Important implementation gaps discovered by this audit

### Gap A — Main Radar is not yet the engine

The project has a real analysis-driven radar preview in Local Analysis, but the top-level `IntelligentRadarScreen` is still a UI shell with placeholder metrics and an empty Analyze Media action.

This should be treated as an integration task, not as a need to rebuild the perception engine.

### Gap B — Live Camera is transport-only

The actual ESP32 MJPEG transport works, but capture and device-control buttons are still unimplemented, and the live stream is not yet passed through the production analysis engine.

### Gap C — Local video timestamps are approximate

`MediaMetadataRetriever` requested sample times are explicitly tracked as approximate. The system correctly refuses to treat them as exact physical-speed timestamps.

### Gap D — Calibration is road-plane calibration, not full intrinsic calibration

The calibration UI currently solves image-to-ground homography from measured points. Intrinsics/distortion are available in the profile schema but are not yet generated by the UI.

### Gap E — Tracker is a project-specific approximation

The current tracker is strong enough to be a practical prototype baseline, but it is not officially equivalent to reference ByteTrack. It must win on actual annotated traffic data before being declared production-grade.

### Gap F — Model selection is not yet truly configurable

Interfaces allow replacement, but the Local Analysis ViewModel currently instantiates the registered YOLO26n and ByteTrack directly, with CPU acceleration fixed.

### Gap G — OCR pipeline is only a consensus utility

`PlateConsensus` is implemented, but there is no production `PlateRecognizer` implementation feeding it real plate observations.

### Gap H — Evidence does not yet contain image evidence

The evidence model currently persists metadata and source/frame references. A real enforcement-style evidence package needs the actual relevant frame/crop(s), hashes/versions and reproducible linkage.

### Gap I — Rules UI exposes unused numeric thresholds

Warning speed and capture speed are stored by the UI, but the domain rule engine currently evaluates the configured speed limit and confidence gate only.

### Gap J — Several secondary feature screens are shells

Reports, Watchlist, Alerts and parts of Settings are mostly UI-level prototypes and do not yet participate in persistent domain workflows.

### Gap K — Documentation version mismatch

`docs/ANDROID_CI.md` describes an older toolchain than the current build files. The CI workflow/current build scripts must be treated as the source of truth.

### Gap L — Planned structure document is not the exact tree

`android/PROJECT_STRUCTURE.md` is a design proposal and contains directories that do not exactly correspond to the current implementation. It should not be used as a literal file inventory.

### Gap M — Backend is planned but absent

The root README references a future `backend/` area, but no such repository directory is currently present.

## 20. Current real execution paths

### Local image

```text
Selected image
 → LocalImageFrameSource
 → LiteRtObjectDetector
 → AppearanceAugmentingDetector (optional)
 → ByteTrack
 → contact point
 → optional validated homography
 → robust speed only when gates permit
 → rules/evidence if enabled
 → AnalysisPreviewFrame
 → AnalysisResult
```

### Local video

```text
Selected video
 → LocalVideoFrameSource
 → requested-time samples
 → LiteRtObjectDetector
 → ByteTrack
 → geometry
 → robust speed blocked by exact-timestamp requirement
 → preview/metrics/result
```

### Live ESP32

```text
DeviceSettings
 → MjpegStreamClient
 → Bitmap frames
 → LiveCameraScreen display
```

The missing piece is the handoff from live transport into the same production analysis engine used by Local Analysis.

### Calibration

```text
Reference image
 → point selection
 → measured ground coordinates
 → CalibrationBuilder
 → HomographyEstimator + RANSAC
 → CalibrationValidator
 → FileCalibrationStore
 → Local Analysis selection
 → AnalysisConfig
```

## 21. Current implementation maturity by subsystem

| Subsystem | Current state | Assessment |
|---|---|---|
| Android shell/navigation | Implemented | Functional prototype |
| Theme/language/settings core | Implemented | Functional |
| ESP32 endpoint configuration | Implemented | Functional prototype |
| MJPEG transport/display | Implemented | Real transport; field validation pending |
| Local image analysis | Implemented | Real pipeline |
| Local video decoding | Implemented | Real pipeline; timing limitation |
| YOLO26 LiteRT adapter | Implemented | Real artifact; target-device validation pending |
| Tracker | Implemented | ByteTrack-inspired prototype; benchmark pending |
| Homography | Implemented | Kotlin fallback; native parity pending |
| Calibration workflow | Implemented | Road-plane homography only |
| Robust speed | Implemented | Strong prototype; independent GT pending |
| Analysis preview/radar visualization | Implemented in Lab | Real preview; top-level Radar not integrated |
| Traffic-speed rule evaluation | Implemented | Narrow deterministic rule set |
| Evidence metadata | Implemented | Image/crop evidence pending |
| Plate consensus | Implemented | OCR backend pending |
| Plate detector/OCR | Not implemented | Pending |
| Re-ID | Not implemented | Pending |
| Optical flow | Not implemented | Pending |
| Vehicle keypoint model | Not implemented | Research task |
| Dynamic homography | Research-only | Not production |
| SAM2/segmentation | Research/planned | Not live mobile path |
| Intrinsic camera calibration UI | Not implemented | Pending |
| Native production geometry path | Compiled but not selected | Pending parity |
| Device CPU/GPU/NPU benchmark | Partial | Benchmark utility exists; complete UI/workflow pending |
| TrackEval integration | Not implemented | Pending labeled data/evaluator |
| Watchlist persistence | Not implemented | UI shell |
| Alerts persistence/subscription | Not implemented | UI shell |
| Incident reports persistence | Not implemented | UI shell |
| Complete evidence image vault | Not implemented | Metadata store only |
| ESP32 firmware | Not implemented | README/planning only |
| Cloud backend | Not implemented | Directory absent |

## 22. Recommended next development order after this audit

1. Verify the current HEAD with the exact current GitHub Actions run and resolve every build/test/lint problem before adding more feature surface.
2. Validate the committed YOLO26 LiteRT artifact on the actual Android device and record real inference behavior.
3. Integrate the same `AnalysisEngine` into `IntelligentRadarScreen` instead of creating a second radar algorithm.
4. Add real live-stream-to-analysis handoff so ESP32 frames enter the same engine as local frames.
5. Build a complete on-device benchmark page for CPU/GPU/NPU and end-to-end latency.
6. Replace requested-time local video sampling with a sequential decoder/source carrying trustworthy decoded PTS for physical-speed mode.
7. Add native/Kotlin parity tests for homography and robust speed before switching production hot paths to native.
8. Add labeled traffic data and TrackEval-based tracking evaluation.
9. Add a real contact/keypoint or segmentation backend only where measured data shows that bounding-box lower-center geometry is insufficient.
10. Add plate detector + OCR + temporal fusion.
11. Connect warning/capture thresholds and complete the rule/event model.
12. Add persistent watchlist, alert and incident/report repositories.
13. Upgrade evidence from metadata records to actual reproducible image/crop evidence.
14. Implement ESP32 firmware and field validation against the same Android device configuration.

## 23. Architectural rules that must remain unchanged

- Do not calculate physical speed directly from pixel displacement.
- Do not present speed as physically valid when timing/calibration quality is insufficient.
- Do not silently substitute missing AI components with fake/no-op results.
- Do not create a second analysis algorithm for the Radar screen; it must consume the same engine state.
- Do not promote native C++ into the production path until numerical parity is demonstrated.
- Do not call deterministic color-histogram appearance matching "learned Re-ID".
- Do not call calibration reprojection error a speed ground-truth error.
- Do not claim reference-equivalent ByteTrack behavior without benchmark evidence.
- Do not select a detector/tracker because of a desktop benchmark alone; measure on the target phone and traffic clips.
- Treat independent physical reference measurements as the authority for speed-accuracy claims.

## 24. Final audit conclusion

The repository is no longer merely a UI mockup. It contains a real Android local-analysis pipeline, a real committed LiteRT detector artifact, a working ByteTrack-inspired tracking implementation, deterministic homography/calibration tooling, a robust metric-space speed estimator, explicit measurement safety gates, persistent calibration/evidence metadata, a native C++ acceleration foundation, and a Python research/evaluation layer.

At the same time, several visible screens are still prototypes around that core. The largest remaining work is **integration and empirical validation**, not inventing another architecture: connect the main Radar and live ESP32 transport to the same analysis engine, establish trustworthy video timestamps, benchmark the actual phone, validate tracking and speed against independent ground truth, then add OCR/events/evidence persistence and the remaining research components.

This audit should be updated when significant architectural files, runtime paths or validation gates change.
