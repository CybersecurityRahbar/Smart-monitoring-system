# Smart Monitoring System — Project Context

Last updated: 2026-09-02

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
The user explicitly identified that file selection alone was insufficient. The Lab is a repeatable experiment workspace.

Current UI includes:
- video/image source;
- Full Pipeline / Detect+Track / Speed+Calibration / Plate+OCR test modes;
- known reference distance;
- analysis sample window;
- minimum confidence;
- Start Analysis Test;
- radar/trajectory overlay switch;
- measurement explanations.

Advanced experimental switches now exposed:
- calibrated road homography;
- Vehicle Keypoints;
- **Vehicle Keypoints + Dynamic Homography research mode**;
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
This is explicitly included as an **optional research mode** in Local Analysis. The current architecture is compatible with the recent 2026 monocular direction using a 36-keypoint vehicle template, dynamic homography updates and keypoint/warped optical-flow alternatives. Results must be benchmarked and reported with actual error; no enforcement-grade accuracy is assumed.

### Supporting methods
- Optical Flow: camera-motion compensation, propagation and motion refinement.
- SAM2/SAM2.1-style segmentation: selected-target refinement, annotation and difficult-scene research, not default heavy mobile per-frame processing.
- Grounded-SAM2-style grounding/segmentation/tracking: offline research and dataset creation.
- Re-ID embeddings: long-occlusion/look-alike vehicle identity recovery.
- Monocular depth: relative-depth consistency/research only, not metric speed without physical scale.

## Current AI/math implementation
Files under `android/app/src/main/java/com/smarttraffic/app/domain/analysis/`:
- `AnalysisModels.kt`: MediaSource, Detection, VehicleKeypoint, TrackObservation, Track, GroundPoint, SpeedEstimate, PlateReading, CalibrationProfile, AnalysisConfig, AnalysisMetrics, AnalysisResult.
- `AnalysisEngine.kt`: ObjectDetector, MultiObjectTracker, VehicleKeypointEstimator, PlateRecognizer, AnalysisEngine and ModularAnalysisEngine connected to the frame pipeline.
- `FrameSource.kt`: timestamped transport-independent frame contract.
- `AnalysisPipelineRunner.kt`: actual frame loop that calls detector, tracker, optional keypoint/plate stages, contact-point logic, homography projection and robust speed estimation.
- `GeometryAndSpeed.kt`: HomographyProjector + robust multi-sample/MAD outlier speed estimator.
- `HomographyEstimator.kt`: dependency-free homography solver for deterministic tests; native/OpenCV robust/RANSAC path remains planned.
- `ValidationMetrics.kt`: speed MAE/RMSE/median/P90/P95 and ±5/10/20% tolerance metrics.

## LiteRT detector integration — current
Added under `android/app/src/main/java/com/smarttraffic/app/data/vision/`:
- `LiteRtObjectDetector.kt`: first real Android detector adapter using LiteRT `CompiledModel` and tensor buffers.
- `DetectorBenchmark.kt`: on-device benchmark with warmup, mean, median, P95, min/max latency and estimated FPS.

Android dependency:
- `com.google.ai.edge.litert:litert:2.2.0`.
- 2.1.6 was removed after AGP 9.3.2 reported duplicate `com.google.ai.edge.litert` namespace between `litert` and `litert-api`.
- Google currently lists LiteRT 2.2.0 as the latest Android release and 2.1.6 as legacy.

The detector is designed to support current Ultralytics detection output layouts including end-to-end `[1,300,6]` and traditional `[1,84,8400]` style parsing, with class-aware NMS where required. The preprocessing path is CPU Kotlin today and is intentionally isolated so it can be replaced by the native C++ path without changing the detector contract.

Model weights are **not bundled**. `android/app/src/main/assets/models/README.md` defines the expected model asset workflow. A validated model file is required before claiming real detector inference on a phone.

Important runtime rule: benchmark CPU/GPU/NPU on the actual target phone where supported; do not copy vendor/website latency numbers into project results. LiteRT's CompiledModel API supports accelerator selection directly, and accelerator behavior must be captured per device/model rather than assumed.

## C++ / Android NDK native core — current
Added under `android/app/src/main/cpp/`:
- `CMakeLists.txt`: C++20 shared-library build.
- `traffic_core.h/.cpp`: native homography projection and robust MAD-based metric speed primitive.
- `traffic_core_jni.cpp`: JNI bridge for those primitives.

Added Kotlin facade:
- `data/nativecore/NativeTrafficCore.kt`.

Android native build:
- NDK `27.2.12479018`.
- CMake `3.22.1`.
- CI installs those SDK components explicitly.

The C++ layer is intentionally dependency-light in this first stage. OpenCV will be introduced when native/RANSAC calibration, optical flow or other optimized CV operations are actually integrated and benchmarked. Future tracker association and hot-path preprocessing may also move to C++ after profiling.

## Local video/frame transport
Added:
- `data/analysis/LocalVideoFrameSource.kt` for real local video frame extraction using `MediaMetadataRetriever` and media timestamps;
- `LocalImageFrameSource` for one-frame image experiments.

## Live video transport
Added:
- `core/network/MjpegStreamClient.kt` dependency-free multipart MJPEG decoder;
- `LiveCameraScreen` uses configured `DeviceSettings.streamUrl()` and displays decoded frames;
- `VideoViewport` accepts a real `Bitmap` frame and live status text;
- Live Camera has connect/reconnect/disconnect states.

This live transport is code-complete enough for hardware testing, but final verification still requires a real ESP32-CAM stream.

## Offline research engine
Added under `ai/`:
- `README.md` describing research/benchmark workflow;
- `requirements.txt` for Ultralytics/OpenCV/NumPy/Pandas/SciPy;
- `run_traffic_analysis.py` runnable video research pipeline using an explicit YOLO model and selectable tracker;
- optional fixed homography speed projection;
- optional Vehicle Keypoint + Dynamic Homography research path when a compatible keypoint model and template are supplied;
- JSONL/JSON outputs and optional annotated video.

Model weights are intentionally not bundled or invented in the repository; explicit validated weights must be supplied.

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

## CI/CD
GitHub Actions is mandatory. Previously verified Run #69 succeeded for Debug APK + tests + artifact.

Run #104/105 exposed a `MediaMetadataRetriever` API misuse; fixed by switching to the `Context + Uri` overload.
Run #123 exposed `sdkmanager` missing from PATH after NDK/CMake was added; fixed by resolving the SDK manager path in the workflow.
Run #124 reached Gradle successfully but failed in Android manifest processing because LiteRT 2.1.6 and `litert-api` shared a namespace under AGP 9.3.2. This triggered the upgrade to LiteRT 2.2.0.
A fresh CI run after the 2.2.0 change is required before calling the combined LiteRT+NDK build verified.

## Current status
The project has moved from UI-only scaffolding into a real transport + deterministic measurement foundation and now has the first mobile inference/native runtime layer:
- real local frame source;
- real MJPEG decoder path;
- real frame-pipeline coordinator;
- robust homography projection and speed estimation;
- LiteRT detector adapter;
- device-side detector benchmark primitive;
- C++/JNI native geometry/speed core;
- ByteTrack Android baseline source;
- advanced experiment switches;
- Python research/benchmark runner.

Still pending for a truly operational end-to-end AI system:
- a successful CI run with LiteRT 2.2.0 + NDK/CMake;
- validated `.tflite` detector weights installed and tested on the user's actual phone;
- UI wiring for model/backend benchmark and visible benchmark reports;
- full tracker correctness implementation/validation and actual integration with the pipeline runner;
- actual trained vehicle-keypoint model;
- native/OpenCV calibration + robust/RANSAC implementation;
- native optical-flow refinement;
- real ESP32 firmware and hardware stream validation;
- plate detector/OCR runtime;
- persistence for events/evidence/watchlist;
- complete rules/event integration with the engine.

## Next execution order
1. Verify the fresh Android CI run after upgrading to LiteRT 2.2.0.
2. If CI succeeds, add focused unit tests for detector preprocessing/output parsing, ByteTrack association, native-vs-Kotlin geometry parity and speed estimator parity.
3. Add a model/backend registry and wire DetectorBenchmark into Analysis Lab.
4. Put validated YOLO26 LiteRT assets on the test device and run CPU/GPU/NPU measurements.
5. Compare detector candidates on the same traffic clips and phone, recording accuracy + latency + thermal behavior.
6. Finish ByteTrack correctness and integrate detector + tracker outputs into `AnalysisPipelineRunner` so Local Analysis produces real tracks.
7. Add camera calibration profile UI/storage and native/OpenCV RANSAC homography.
8. Route metric trajectory estimation through validated native speed primitives where parity tests pass.
9. Implement and benchmark camera-only speed with explicit quality gates and validation error.
10. Implement actual Vehicle Keypoint + Dynamic Homography research backend and compare with fixed calibrated homography.
11. Add plate detector/OCR + temporal fusion + rules/watchlist/evidence persistence.
12. Optimize frame scheduling, quantization and accelerator selection only from measured device results.
