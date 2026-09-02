# Smart Monitoring System — Project Context

Last updated: 2026-09-03

## Project identity
- Repository: `CybersecurityRahbar/Smart-monitoring-system`
- Product: Smart Traffic Monitoring System / Android app Smart Traffic.
- University prototype; not legally certified traffic enforcement equipment.
- Android is the command center; ESP32-CAM is the camera/Wi-Fi endpoint; heavy AI/CV is intended for Android/native acceleration.
- No Map Widget unless explicitly requested.

## Architecture
`Compose UI → ViewModel/StateFlow → Use Cases/Domain → Repositories → Data Sources`

Shared media architecture:
`ESP32 live / local video / image → FrameSource → AnalysisEngine → detector → tracker → geometry → speed → plates → rules → evidence`

Runtime split:
- Kotlin/Compose: UI, state, configuration, orchestration, persistence.
- LiteRT: on-device ML inference and hardware accelerator selection.
- C++/NDK: performance-sensitive numerical/CV primitives and future trackers/optical-flow/OpenCV integration.
- Python: offline research, dataset preparation, model comparison and reproducible experiments.

Primary nav:
Dashboard / Intelligent Radar / Live Camera / Alerts / More.
More contains Reports, Analysis Lab, Devices, Traffic Rules, Watchlist, Evidence, Settings.

## Hardware and first milestone
Initial board: ESP32-CAM + OV2640; exact board must be verified from the physical device/photo before final firmware/wiring.
First hardware milestone:
`ESP32-CAM + OV2640 → local Wi-Fi → Android → live MJPEG stream`
Endpoints: `/status`, `/capture`, `/stream`.
No sensors are mandatory for v0.1.

## UI fixes already implemented
- dashboard responsive/scrolling fixes;
- Arabic RTL + English;
- persistent Night mode;
- explicit top Back for nested screens and controlled Android Back behavior;
- editable/persistent Traffic Rules;
- Radar control cleanup;
- shared video viewport Full/Standard/Compact + PiP preparation;
- Incident Reports;
- common operator localization;
- Devices endpoint fields now use larger LTR technical text for `/status`, `/stream`, etc.

## Devices
`core/DeviceSettings.kt` persists host/IP, port and endpoint paths. Devices screen supports editing, Save + Test, Reset and asynchronous `/status` health checks. Runtime IP/port are not hard-coded.

## Local Analysis Lab — required behavior
The Lab is a repeatable experiment workspace, not just a picker.

Current UI includes:
- video/image source;
- Full Pipeline / Detect+Track / Speed+Calibration / Plate+OCR test modes;
- known reference distance;
- analysis sample window;
- minimum confidence;
- Start Analysis Test;
- radar/trajectory overlay switch;
- measurement explanations.

Advanced experimental switches:
- calibrated road homography;
- Vehicle Keypoints;
- Vehicle Keypoints + Dynamic Homography research mode;
- Optical Flow refinement;
- Segmentation refinement;
- appearance Re-ID;
- radar/trajectory overlay.

Radar is a visualization/operations layer over the shared AnalysisEngine, not a separate speed algorithm.

## Vision algorithm stack
The goal is the strongest practical modular camera-first stack, selected by measured validation rather than model marketing.

### Detection candidates
- YOLO26: first mobile/edge baseline candidate.
- RT-DETRv2: accuracy/research comparison.
- Future traffic-domain fine-tuned detector.

### Tracking candidates
- BoT-SORT: general candidate with Kalman prediction, optional Re-ID and global-motion compensation.
- ByteTrack: fast baseline.
- OC-SORT.
- Deep OC-SORT.
- FastTracker.
- TrackTrack.

### Geometry
Preferred physical-measurement route:
1. intrinsic camera calibration;
2. lens distortion correction;
3. metric road-plane reference;
4. robust/RANSAC homography;
5. vehicle ground-contact point or learned keypoint;
6. image → metric road coordinates;
7. metric trajectory → velocity.

Never use raw pixel displacement as physical speed.

### Vehicle Keypoints + Dynamic Homography
This is an optional research mode. The architecture is compatible with the recent 2026 monocular direction using a multi-keypoint vehicle template, dynamic homography updates and keypoint/warped optical-flow alternatives. Results must be benchmarked and reported with actual error; no enforcement-grade accuracy is assumed.

### Supporting methods
- Optical Flow: camera-motion compensation, propagation and motion refinement.
- SAM2/SAM2.1-style segmentation: selected-target refinement, annotation and difficult-scene research, not default heavy mobile per-frame processing.
- Grounded-SAM2-style grounding/segmentation/tracking: offline research and dataset creation.
- Re-ID embeddings: long-occlusion/look-alike vehicle identity recovery.
- Monocular depth: relative-depth consistency/research only, not metric speed without physical scale.

## Current AI/math implementation
Files under `android/app/src/main/java/com/smarttraffic/app/domain/analysis/`:
- `AnalysisModels.kt`: MediaSource, Detection, VehicleKeypoint, TrackObservation, Track, GroundPoint, SpeedEstimate, PlateReading, CalibrationProfile, AnalysisConfig, AnalysisMetrics, AnalysisResult.
- `AnalysisEngine.kt`: ObjectDetector, MultiObjectTracker, VehicleKeypointEstimator, PlateRecognizer, AnalysisEngine and ModularAnalysisEngine.
- `FrameSource.kt`: timestamped transport-independent frame contract.
- `AnalysisPipelineRunner.kt`: actual frame loop that calls detector, tracker, optional keypoint/plate stages, contact-point logic, homography projection and robust speed estimation.
- `GeometryAndSpeed.kt`: HomographyProjector + robust multi-sample/MAD outlier speed estimator.
- `HomographyEstimator.kt`: dependency-free homography solver for deterministic tests; native/OpenCV robust/RANSAC path remains planned.
- `ValidationMetrics.kt`: speed MAE/RMSE/median/P90/P95 and ±5/10/20% tolerance metrics.

Known correctness gaps before production-grade use:
- `AnalysisPipelineRunner` currently uses a fallback highest-confidence detection when a track observation is unavailable; this needs explicit track/detection association.
- `droppedFrames` currently measures an empty-track condition, not decoder frame loss; it must be replaced by an explicit frame-drop counter.
- `inferenceLatencyMs`, dynamic keypoint homography, optical-flow refinement, segmentation and Re-ID are not yet fully implemented in the runner.
- `activeTracks` is currently reported as zero at result completion rather than a time-series/peak metric.
- `ModularAnalysisEngine.analyze(MediaSource, ...)` still needs a real injected source factory before that overload can execute end-to-end.

## LiteRT detector integration — current
Added under `android/app/src/main/java/com/smarttraffic/app/data/vision/`:
- `LiteRtObjectDetector.kt`: Android detector adapter using LiteRT `CompiledModel` and tensor buffers.
- `DetectorBenchmark.kt`: benchmark primitive with warmup, mean, median, P95, min/max latency and estimated FPS.

Android dependency:
- `com.google.ai.edge.litert:litert:2.2.0`.
- LiteRT 2.2.0 is current in this project; upgrading from 2.1.6 did **not** remove the AGP 9 namespace conflict.

Current detector adapter assumptions that still require a validated exported model:
- supported parser shapes: end-to-end `[1,300,6]` and traditional `[1,84,8400]` style;
- preprocessing is CPU Kotlin letterbox/RGB/NCHW;
- class-aware NMS is applied for the traditional output;
- actual tensor metadata/output semantics must be checked against the exact `.tflite` model before real inference is declared valid.

Model weights are **not bundled**. `android/app/src/main/assets/models/README.md` defines the expected model asset workflow. A validated model file is required before claiming real detector inference on a phone.

## C++ / Android NDK native core — current
Added under `android/app/src/main/cpp/`:
- `CMakeLists.txt`: C++20 shared-library build.
- `traffic_core.h/.cpp`: native homography projection and robust MAD-based metric speed primitive.
- `traffic_core_jni.cpp`: JNI bridge.

Added Kotlin facade:
- `data/nativecore/NativeTrafficCore.kt`.

Android native build configuration:
- NDK `27.2.12479018`.
- CMake `3.22.1`.
- CI installs those SDK components explicitly.

The C++ layer is dependency-light. OpenCV will be introduced when native/RANSAC calibration, optical flow or other optimized CV operations are actually integrated and benchmarked.

Native correctness items to validate later:
- add Kotlin-vs-C++ homography parity tests;
- add Kotlin-vs-C++ robust-speed parity tests;
- replace the JNI `reinterpret_cast<const long long*>` bridge with a representation-safe conversion;
- decide whether speed needs signed longitudinal velocity/direction in addition to scalar speed;
- document that the current native `error_kmh` field is a robust-scatter indicator, not a ground-truth validation error.

## Local/video transport
- `data/analysis/LocalVideoFrameSource.kt`: real local video extraction using `MediaMetadataRetriever` and media timestamps.
- `core/network/MjpegStreamClient.kt`: dependency-free multipart MJPEG decoder.
- `LiveCameraScreen`: configured `DeviceSettings.streamUrl()` with real decoded frames.
- `VideoViewport`: real `Bitmap` frame/status support with display modes and PiP preparation.

Final transport verification still requires the real ESP32-CAM stream.

## Offline research engine
Added under `ai/`:
- `README.md`;
- `requirements.txt`;
- `run_traffic_analysis.py` with explicit YOLO model and selectable tracker;
- optional fixed homography speed projection;
- optional keypoint/dynamic homography research path;
- JSONL/JSON outputs and optional annotated video.

Weights are intentionally not bundled or invented.

## Validation methodology
Detection: precision/recall/mAP when ground truth exists.
Tracking: HOTA/IDF1/MOTA, ID switches, fragmentation, lost duration.
Geometry: reprojection error and ground-position error.
Speed: MAE, RMSE, median/P90/P95 error, ±5/10/20% coverage.
OCR: character error, exact full-plate match, temporal consensus.
Runtime: decode FPS, inference latency, end-to-end latency, dropped frames, memory/thermal observations.

Controlled test clips should cover daylight, dusk/night, glare/shadows, rain when possible, dense traffic, occlusion, multiple lanes and approaching/receding traffic.

## Plate/OCR
`vehicle track → plate detector → crop → rectification → enhancement → OCR → temporal consensus → normalization`
Do not trust one OCR frame when multiple observations of a track exist.

## Events/evidence
Store track ID, camera ID, timestamp, speed, rule threshold, confidence, metric position/zone, evidence refs, plate result and calibration/model/tracker versions.

## CI/CD and verified failures
GitHub Actions is mandatory. Previously verified Run #69 succeeded for Debug APK + tests + artifact, but that predates the current native/LiteRT changes.

- Run #104/105: `MediaMetadataRetriever` API misuse; fixed by switching to the `Context + Uri` overload.
- Run #123: `sdkmanager` was missing from PATH after NDK/CMake was added; workflow now resolves the Android SDK command-line tools path explicitly.
- Run #124: LiteRT 2.1.6 failed AGP 9 namespace validation.
- Run #126 on commit `c11006d32b650d122b86a25259a55ee522dd6381`: LiteRT 2.2.0 still failed the same namespace rule between `litert` and `litert-api`; NDK/CMake installation and Kotlin compilation reached successfully, so the blocking issue is specifically AGP namespace validation.

Current compatibility workaround:
- `android/gradle.properties` sets `android.uniquePackageNames=false`.
- This is an upstream/AGP migration workaround, not a permanent dependency fix. Android's AGP 9 documentation explicitly documents that AGP 9 changed the default from `false` to `true` and allows disabling the flag when unique namespaces cannot yet be achieved. LiteRT's upstream issue tracker documents the same duplicate-namespace family as a known problem.
- The flag must be removed when LiteRT publishes artifacts with distinct namespaces and the project has verified the new dependency graph.

The latest source change fixing the current blocker is commit `52bbf55e4c7a80d4b85d41f9857ee9d5719219f1`.

## Current status
The project has a real transport + measurement foundation and an initial mobile inference/native layer, but it is not yet a verified end-to-end AI system.

Implemented foundation:
- real local frame source;
- real MJPEG decoder path;
- real frame-pipeline coordinator;
- robust homography projection and speed estimation;
- LiteRT detector adapter;
- detector benchmark primitive;
- C++/JNI native geometry/speed core;
- ByteTrack Android baseline source;
- advanced experiment switches;
- Python research runner.

Still pending:
- fresh CI pass after `android.uniquePackageNames=false`;
- focused unit tests;
- validated `.tflite` asset on the actual phone;
- model/backend registry and benchmark UI;
- full tracker validation/integration;
- native-vs-Kotlin parity tests;
- calibration UI + OpenCV/RANSAC;
- real ESP32 firmware/hardware validation;
- plate detector/OCR runtime;
- persistence for events/evidence/watchlist;
- complete rules/event integration.

## Execution gate
No new major AI feature work should be merged until the current CI blocker is cleared and the next build is verified through APK assembly and unit tests. After that, proceed in small validated increments.

## Next execution order
1. Verify fresh CI after `android.uniquePackageNames=false`.
2. Review the complete dependency graph and build warnings after the first green build.
3. Add focused unit tests for preprocessing/output parsing, ByteTrack association, native-vs-Kotlin geometry parity and speed parity.
4. Add model/backend registry and benchmark UI.
5. Put a validated LiteRT model on the test device and benchmark CPU/GPU/NPU using real frames.
6. Compare detector candidates on identical traffic clips/device conditions.
7. Finish ByteTrack correctness and integrate detector + tracker into `AnalysisPipelineRunner`.
8. Add calibration profile UI/storage and native/OpenCV RANSAC homography.
9. Route metric trajectory estimation through native speed only after parity validation.
10. Implement and benchmark camera-only speed with explicit quality gates.
11. Implement actual Vehicle Keypoint + Dynamic Homography research backend and compare with fixed calibration.
12. Add plate detector/OCR + temporal fusion + rules/watchlist/evidence persistence.
13. Optimize only from measured device profiling.
