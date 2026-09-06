# Project Context Append — 2026-09-06

This append-only document records the full repository/context audit requested on 2026-09-06. It is to be read with `docs/PROJECT_CONTEXT.md`, `docs/PROJECT_CONTEXT_MASTER_PLAN_2026-09-04.md`, and prior session append files.

## Audit scope
Reviewed the available prior conversation context and the repository `CybersecurityRahbar/Smart-monitoring-system` at main HEAD `ef5ff48451203b24977bc130863a121fa0759452`. Reviewed the complete current `docs/` inventory and the contents of the major architecture, reliability, CI, local-lab, radar, master-context and session-context documents; inspected the current source tree and traced the active Android analysis path through model registry, LiteRT detector, frame sources, unified session, tracker, geometry/speed backends, evidence, live/local ViewModels, playback, JNI/C++, ESP32 firmware, and CI workflow. This audit is a state assessment, not a claim that every byte of every historical transcript attachment was manually retyped.

## What the preceding conversation was doing
The latest project work had moved from basic ESP32-CAM planning into an Android-first Smart Traffic analysis system. The immediate focus became stabilizing and validating the Local Analysis Lab after the user experienced an app process death when pressing Run Real Analysis on a local car video. The investigation first isolated GPU/native acceleration and then LiteRT/runtime compatibility, pinned LiteRT to 2.1.5, moved the Local Lab to the Kotlin detector/geometry/speed path, and disabled optional appearance association for the first field validation. Subsequent work continued with automatic speed gates, calibration-free speed estimation, synchronized analyze-then-replay video presentation, tracker hardening, native/Kotlin parity and evidence artifact persistence.

## Current architecture actually present
`Compose UI -> ViewModel/StateFlow -> application AnalysisHost -> UnifiedAnalysisSession -> ModularAnalysisEngine/AnalysisPipelineRunner -> FrameSource + LiteRT detector + ByteTrack + geometry + speed + rules/evidence`

The application currently has an `AnalysisHost` which owns an application-scoped `UnifiedAnalysisSession`. Local and Live ViewModels use this shared session in the current main branch, eliminating the older ViewModel-scoped session design documented in the 2026-09-04 historical file.

## Current model/runtime
- Model asset: `android/app/src/main/assets/models/yolo26n.tflite`
- SHA-256: `d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73`
- Size: 2,875,553 bytes
- Input: 640 NCHW RGB FP32 preparation
- Current registered output: classic `[1,84,8400]`, 80 COCO classes
- Traffic classes parsed: car, motorcycle, bus, truck
- Build verifies model SHA-256 before preBuild
- Current LiteRT dependency in `android/app/build.gradle.kts`: `2.1.5`
- `AnalysisRuntimeFactory` is intentionally CPU-only at present; GPU/native delegates are not probed implicitly because native delegate failure can terminate the process outside Kotlin exception containment.

## Local-video path: important current discrepancy
Historical 2026-09-04 context says ordinary Local Lab was isolated from `ExactPtsVideoFrameSource` and used `LocalVideoFrameSource`. The current main branch has since changed again: `LocalAnalysisViewModel` chooses `ExactPtsVideoFrameSource` whenever `effectiveConfig.useGroundPlane` is true, and `AnalysisConfig.useGroundPlane` defaults to true. Therefore current ordinary local video runs go through the exact-PTS MediaCodec/ImageReader source by default. There is no current try/catch fallback to `LocalVideoFrameSource` in that selection block.

`ExactPtsVideoFrameSource` uses MediaExtractor + MediaCodec + ImageReader, propagates `MediaCodec.BufferInfo.presentationTimeUs`, checks monotonic timestamps and checks rendered-image timestamp consistency. It remains a major physical-device compatibility gate and is not equivalent to field validation merely because it compiles.

`LocalVideoFrameSource` remains the API-28 indexed-decoding/fallback source, but its timestamp provenance is deliberately `REQUESTED_SAMPLE_TIME`, not exact PTS.

## Current Local Analysis presentation
Recorded local video uses analyze-then-replay. The pipeline finishes analysis first, then `AnalysisVideoPlayback` uses Media3/ExoPlayer at natural playback speed and interpolates bounding boxes from complete track history, with bounded 350 ms extrapolation. Boxes use a neon-green rendering; speed labels distinguish calibrated and calibration-free estimates. Preview updates are throttled by `maximumPreviewFps` rather than slowing analysis.

Potential synchronization point requiring validation: playback position is ExoPlayer timeline position, while track observations carry source presentation timestamps. The implementation assumes their time origins are compatible; a video whose first PTS is materially non-zero should be tested explicitly.

## Tracker
`ByteTrack` is ByteTrack-inspired rather than a byte-for-byte reference implementation. It uses high/low confidence association, class-aware gating, Hungarian assignment, Kalman prediction, short-horizon empirical velocity, acceleration bounding, reversal rejection, adaptive center-distance gating, optional deterministic appearance and bounded history. Track IDs are session-local monotonic IDs. HOTA/IDF1/MOTA/ID switches/fragmentation are not yet measured against labelled traffic ground truth.

## Speed system before this session
Two distinct modes were implemented:
1. `CALIBRATED_GROUND_PLANE`: validated homography + authoritative timestamps + ground-plane coordinates + robust speed estimation and an automatic two-line timing gate when usable. This is the intended physical/metric path but is not yet enforcement-certified.
2. `CALIBRATION_FREE_ESTIMATE`: bottom-center image motion + dominant motion axis + class-dependent vehicle-width prior + per-interval local scale + robust median/MAD rejection. Priors are assumptions, not measured geometry. Previous widths: motorcycle 0.85 m, car 1.80 m, van 2.00 m, truck 2.50 m, bus 2.55 m, unknown 1.90 m. Results were explicitly labelled calibration-free and carried broad uncertainty.

The previous automatic gate was visual in uncalibrated mode and metric only in calibrated mode. Traffic-rule enforcement remained stricter than display of an approximate calibration-free speed.

## Research review performed 2026-09-06
The current web review focused on calibration-free monocular vehicle speed estimation and recent 2025-2026 methods.

### Primary current research reference
The August 17, 2026 arXiv preprint `Calibration-Free Vehicle Speed Estimation: A Monocular Keypoint-Template Approach` by Gaofeng Su, Keya Li, Raja Sengupta and Kara M. Kockelman proposes a genuinely calibration-free monocular framework that does not rely on roadway features or prior camera calibration. It uses a 36-keypoint vehicle template, estimates a homography dynamically at each frame from vehicle semantic geometry, and evaluates both keypoint-only tracking and warped optical-flow aggregation on more than 400 clips from roadside and overhead datasets. Reported warped-optical-flow MAE was 15.0% on VS13 and 9.7% on BrnoCompSpeed; after trimming 10% of edge-of-track observations, MAE improved to 11.7% and 7.6% respectively, with 85.3% and 95.4% of estimates within ±20% error. The paper also identifies weak pixel support at very far and very close/partially occluded vehicle positions as important error sources.

### Important comparison result
The repository implementation before this session did NOT implement the 36-keypoint dynamic-homography method. It implemented a reasonable fallback based on a class-dependent vehicle-width prior and local bbox scaling. Therefore it was genuinely executable in the app but was not equivalent to the latest research method.

### Additional supporting research
A 2025 Journal of Real-Time Image Processing paper on efficient vision-based vehicle speed estimation reports a strong accuracy/speed tradeoff using optimized 3D bounding-box and vanishing-point geometry, showing that real-time performance depends strongly on model/runtime architecture rather than a display-only trick.

A 2024 image-motion paper on 3D tracking of rigid objects reports that optical-flow-based motion can significantly improve vehicle speed estimation versus naive bounding-box tracking on scenes with rotation, while using some camera calibration. This supports the general direction of combining robust tracking with image motion rather than relying only on raw bbox-center displacement.

A July 2026 Electronics paper on automatic roadside monocular calibration reports mean relative camera calibration error of 5.31% and mean speed error of 1.79 km/h on BrnoCompSpeed when explicit self-calibration and scene-scale recovery are performed. This is a separate calibrated/self-calibrated strategy, not calibration-free, but it provides a strong benchmark for future automatic calibration work.

### Research implementation boundary
The new implementation in this session intentionally does NOT claim to reproduce the August 2026 paper's learned 36-keypoint network because this repository does not yet contain a trained Android-deployable 36-keypoint model or backend. The existing `VehicleKeypointEstimator` interface remains the explicit insertion point for that future research-grade module. No fake keypoints or unverified weights were introduced.

## Calibration-free speed v2 implemented in this session
A new branch `feat/calibration-free-speed-v2` was created from current `main` to upgrade the existing calibration-free estimator without weakening the validated metric path.

### Code path
The estimator remains `android/app/src/main/java/com/smarttraffic/app/domain/analysis/CalibrationFreeSpeedEstimator.kt` and is already wired into the real `AnalysisPipelineRunner`. `AnalysisConfig.enableCalibrationFreeSpeedEstimate` remains enabled by default. The pipeline still selects validated physical speed only when the calibration/timestamp policy is satisfied; otherwise the calibration-free result is independently produced when enabled.

### Improvements added
1. Symmetric 10% edge trimming is applied when enough samples remain, following the latest paper's finding that edge-of-track samples are disproportionately noisy. The sample budget is protected so the configured minimum is never violated.
2. Metric scale is refreshed locally for every valid interval from the apparent vehicle width instead of applying one global trajectory scale.
3. Dominant motion direction is estimated from the full interval displacement covariance and every motion sample is projected onto that axis. Low directional consistency is rejected rather than silently converted into a large speed.
4. The system now creates a cumulative pseudo-metric trajectory by integrating projected local metric displacement using the real end timestamp of each interval.
5. A Theil-Sen robust temporal slope is computed over the cumulative trajectory. This adds an independent trajectory-level speed estimate that is less sensitive to individual noisy frames than a median of frame-to-frame speeds.
6. The instantaneous local-scale speed and the trajectory-level robust slope are independently compared and robustly aggregated. Their disagreement directly reduces confidence and increases the uncertainty estimate.
7. Speed stability, vehicle-width stability, detector confidence, track confidence, temporal duration and motion-axis consistency all contribute separately to the reported confidence score.
8. The returned `SpeedEstimate.errorKmh` remains explicitly an uncertainty proxy, not a measured ground-truth error. It incorporates prior uncertainty, motion instability, apparent-width instability and disagreement between the two estimators.
9. Regression work is bounded to at most 180 trajectory samples before Theil-Sen pair generation to avoid uncontrolled O(n²) cost on long tracks.
10. The implementation keeps exact interval timestamps attached to each motion sample. This prevents any filtered-index/timestamp coupling when an intermediate motion interval is discarded.

### Important correctness fix found during the upgrade
The first draft of the v2 implementation still used an inferred point index when constructing the cumulative trajectory. That would have been incorrect if a middle interval were discarded. It was immediately replaced with `endTimeMs` stored in every `MotionSample`; cumulative trajectory time now comes from actual observation timestamps.

### Tests added/updated
`CalibrationFreeSpeedEstimatorTest.kt` now covers:
- calibration-free estimate generation and mode labelling,
- large timestamp-gap rejection,
- edge trimming while preserving the configured minimum sample count,
- correct handling of an invalid middle interval without timestamp-index corruption.

The historical Python robust-speed tests remain unchanged; they cover the offline research/reference path separately.

## Current status after code change
Latest branch code commits:
- `840f14e74e1b6565d7e9223120dabbd0739bc234` — initial robust temporal calibration-free implementation.
- `e677acaa59d5355dcda0ca5fabd0e247bbe02e79` — interval timestamp provenance correction.
- `5a1dd62a78e0c5ba33065d71ec1f37897086becf` — regression tests for the v2 estimator.
- This context-document update is the next commit on the same branch.

The exact `main` branch before this work was `2b36e3275b710c2b7c979a131a08224c3026a9b4`; it was a docs-only audit commit after the previous speed-cycle SHA. The latest previous speed implementation commit was `8936c54ce3ec2fea5bfea962b7b491aceeb1c405`.

## What this does and does not mean
The calibration-free estimator is now more robust and materially closer to the current research direction in its treatment of perspective-dependent scale, temporal robustness, edge trimming, and cross-estimator agreement. It is genuinely executed by the application rather than being a placeholder.

It is still NOT equivalent to the 2026 36-keypoint dynamic-homography method, because the repository does not contain that learned keypoint model/backend. It also is not a ground-truth speed measurement. Any displayed calibration-free km/h value must continue to be treated as an approximate engineering estimate with explicit uncertainty.

The calibrated ground-plane mode has not been weakened or replaced. The calibration-free mode remains a fallback/secondary measurement mode and must not be promoted to a legally enforceable event without independent validation.

## Validation still required
1. Run Android unit tests and lint/compile on the exact branch/commit.
2. Perform benchmark tests against labelled video with independent vehicle speeds and report MAE/MAPE, failure rate and ±10/±20 error bands. The current confidence/error fields must not be presented as benchmark truth.
3. Compare v2 against the previous width-prior method on near/far vehicles, diagonal traffic, perspective changes, partial occlusion and varying lighting.
4. Test non-zero and irregular source timestamps because speed accuracy depends directly on temporal provenance.
5. Evaluate real Android throughput and thermal behaviour before enabling heavier learned keypoints or optical-flow modules.
6. A future research-grade upgrade can implement the explicit `VehicleKeypointEstimator` backend with a validated 36-keypoint model, vehicle metric template and dynamic per-frame homography/RANSAC path, followed by optical-flow refinement where runtime budget permits.

## Native/C++ path
C++20 currently implements homography projection and robust metric speed math. JNI exposes seven result values: speed, confidence, error/uncertainty km/h, inlier sample count, velocity X, velocity Y and median position residual. `NativeFirstSpeedEstimator` uses native first and Kotlin fallback after validating all returned values. Live analysis currently uses `NativeGroundProjector` and `NativeFirstSpeedEstimator`; Local Lab deliberately uses Kotlin geometry/speed in the current code path.

## Evidence and reports
`FileEvidenceStore` is now a real bounded evidence vault: metadata + SHA-256-named JPEG frame/vehicle/plate artifacts, temporary-file writes, fsync, atomic replacement where supported, integrity checking, retention and orphan cleanup. Current Local Analysis code actually captures a bounded full frame and vehicle crop for requested events. A real plate crop still depends on an installed ANPR backend, which is not present. Exact live MJPEG event-frame evidence remains incomplete.

Persistent incident-report storage exists and is tested.

## ESP32 firmware
PlatformIO builds both AI-Thinker ESP32-CAM and ESP32-S3-N16R8 targets. Firmware provides `/stream`, `/capture`, `/status`, `/control`, STA then AP fallback, ~15 FPS VGA streaming, AI-Thinker flash control and quality control. The S3 pin map is explicitly marked board-revision dependent and still requires physical verification. Firmware compile success is not hardware validation.

## Known non-speed issues that remain
- `LocalVideoFrameSource.close()` still requires the separate closed-state/resource-release fix identified in the audit.
- Local Lab default exact-PTS source policy still requires an explicit decision and device validation.
- Analyze-then-replay timestamp origin still requires non-zero-start PTS testing.
- Decode throughput metric semantics should be clarified between measured source-read throughput and nominal source FPS.
- Speed rejection bookkeeping needs a final distinction between policy-ineligible and attempted-but-rejected tracks.
- Exact PTS source needs physical-device validation across codecs, rotation, frame loss, memory pressure and sustained throughput.
- Learned vehicle-keypoint/ReID/optical-flow modules are still not installed; the repository must not describe them as active capabilities.

## Next requested phase after calibration-free speed
The user explicitly wants to return next to the Local Analysis presentation and Radar: video must play at its real source speed, the tracking radar must follow vehicles at real-time/smooth motion, and no detection/tracking/speed quality may be reduced merely to make UI animation look smooth. The design rule remains: keep analysis quality independent from rendering rate, then synchronize/interpolate the already computed high-quality track state to the playback clock. Any optimization must be benchmarked so it removes latency or scheduling waste rather than reducing model/tracker precision.
