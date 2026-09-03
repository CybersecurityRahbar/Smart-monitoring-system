# Smart Traffic — Project Context

Last updated: 2026-09-03

## Project identity
Repository: `CybersecurityRahbar/Smart-monitoring-system`.
Product: Smart Traffic Monitoring System / Android Smart Traffic app.
University prototype; not certified traffic-enforcement equipment.
Android is the command/visual center; ESP32-CAM is the camera/Wi-Fi endpoint; AI/CV is intended to run on Android/native acceleration.
No Map Widget unless explicitly requested.

## Context continuity rule
`docs/PROJECT_CONTEXT.md` is the cumulative project handoff record. At every meaningful user request and every assistant response/work step, append the new project state, decisions, changes, failures, fixes, verification status and stopping point instead of relying on implicit chat memory.
The root-level `PROJECT_CONTEXT.md` is intentionally preserved for now and is not the authoritative cumulative log.
Do not invent historical messages that are no longer available; record only verified historical facts plus new conversation entries where available.

## Architecture
`Compose UI → ViewModel/StateFlow → Use Cases/Domain → Repositories → Data Sources`

Shared media path:
`ESP32 live / local video / image → FrameSource → AnalysisEngine → detector → tracker → geometry → speed → plates → rules → evidence`

Runtime split:
- Kotlin/Compose: UI, state, configuration, orchestration and persistence.
- LiteRT: mobile ML inference and accelerator selection.
- C++/NDK: performance-sensitive geometry/CV and future OpenCV/optical-flow/tracker hot paths.
- Python: offline research, dataset preparation and benchmark tooling.

## Hardware milestone
Initial target: ESP32-CAM + OV2640 → local Wi-Fi → Android MJPEG.
Endpoints: `/status`, `/capture`, `/stream`.
Exact board/pin mapping still requires physical board verification before firmware is finalized.
User also found an ESP32-S3-CAM N16R8 with OV5640CSP; physical board/pin mapping is not yet finalized.

## Reliability principle
A computed number is not automatically a trusted measurement. The pipeline distinguishes perception output, track association, physical geometry, robust estimation, quality gates and independent validation.
Unavailable capabilities must fail explicitly; no silent fake/no-op result is acceptable.

## Current Android runtime
Gradle/Android:
- AGP 9.3.2
- Kotlin Compose plugin 2.2.10
- Gradle 9.5.0
- compile/target SDK 37, min SDK 26
- Java 17
- NDK 27.2.12479018
- CMake 3.22.1
- Compose BOM 2026.08.00
- LiteRT `com.google.ai.edge.litert:litert:2.2.0`
- temporary `android.uniquePackageNames=false` compatibility workaround for LiteRT 2.2.0 duplicate namespace under AGP 9

## CI
GitHub Actions workflow `.github/workflows/android-ci.yml` builds debug APK, runs unit tests and uploads artifacts. It explicitly installs NDK/CMake and resolves sdkmanager from Android SDK command-line tools.

Previously verified successful baseline: Run #128 on `bbbc2307...` built the APK, compiled native C++, ran tests and uploaded artifacts.
Run #213 (`33764646784`) on `c34f1cf8f05024d758341a5abf0158bf9d801e2f` was verified successful for Android build/tests/lint and offline Python tests.
Run #225 (`33778931309`) on `28591789cf9bfaf4da656e06ec303c6d227e5c4f` verified the JUnit test dependency fix: Android build/tests/lint and offline Python tests completed successfully. This is the latest fully verified green baseline before the subsequent feature commits in this work log.
Recent failures fixed:
- MediaMetadataRetriever wrong overload;
- sdkmanager missing from PATH;
- LiteRT 2.1.6 duplicate namespace;
- LiteRT 2.2.0 duplicate namespace under AGP 9, mitigated with compatibility property;
- preview renderer Compose compile failures fixed in commit `eaaee7fa...`;
- Run #211 (`33763868986`) failed at `compileDebugKotlin` because the newly added bounded-motion parameters were referenced from `InternalTrack` without being in that class's scope; fixed in `2835ec1b0449d56413a288576bb08d830b51ae76`.
- Run #224 (`33778165424`) failed only in `CalibrationBuilderTest.kt` because it imported `kotlin.test.*` while the module only declared JUnit 4; fixed by changing that test to JUnit 4 assertions in commit `28591789cf9bfaf4da656e06ec303c6d227e5c4f`.

CI verification rule: only the run for the exact commit under review counts. Cancelled/older runs do not count.

## Detection / model contract
`data/vision/LiteRtObjectDetector.kt` is the real LiteRT adapter.
It validates runtime input/output tensor contracts against the registered model before inference.

`DetectorModelRegistry.kt` currently registers:
- `yolo26n` → `models/yolo26n.tflite`
- input 640
- NCHW RGB FP32 input preparation
- classic `[1,84,8400]` output contract with class-aware NMS
- 80 COCO classes, traffic classes currently car/motorcycle/bus/truck
- SHA-256 `d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73`
- source: Ultralytics `yolo-flutter-app` release `v0.6.6` w8a32 asset.

The `.tflite` asset is committed at `android/app/src/main/assets/models/yolo26n.tflite` and the build task `verifyYolo26nModel` verifies its SHA-256 and byte size.
The context must not claim that no model asset is committed.
The classic output contract is intentional for this exact asset; a future end-to-end `[1,300,6]` export must be registered as a distinct validated contract rather than inferred at runtime.

## Tracking
Android baseline is a ByteTrack-inspired implementation with:
- Kalman box prediction;
- global Hungarian linear assignment;
- high-confidence association;
- second low-confidence recovery association;
- class-aware gating;
- confirmation of identities;
- explicit lost/removed handling;
- optional deterministic appearance cue.

This is not claimed to be byte-for-byte equivalent to the official reference implementation until evaluated on labeled MOT/traffic sequences.
Future comparison candidates: official/reference ByteTrack behavior, BoT-SORT, OC-SORT and Deep OC-SORT.
Tracking evaluation target: HOTA plus IDF1/MOTA, ID switches and fragmentation using TrackEval rather than a home-made imitation metric.

### 2026-09-03 bounded motion recovery
`ByteTrack.kt` now retains the previous timestamped observation and, only for a short bounded horizon, derives an empirical center velocity from the last two detections. The empirical prediction is blended with Kalman prediction before geometric association. The recovery is bounded in time and still subject to the existing center-distance/IoU gate; it is not an unlimited distance allowance.
Regression coverage includes recovery of the 0 → 8 → 55 px sequence at 100 ms intervals and rejection of an implausible 0 → 8 → 408 px jump.
The first implementation compiled incorrectly because its new parameters lived on `ByteTrack` while `InternalTrack` could not access outer constructor properties; commit `2835ec1b0449d56413a288576bb08d830b51ae76` fixes this by passing the bounded-motion parameters explicitly into each `InternalTrack`.

## Geometry/calibration
`GeometryAndSpeed.kt` provides validated homography projection and robust metric trajectory speed estimation.
`HomographyEstimator.kt` has normalized coordinate handling and deterministic RANSAC outlier rejection. Denormalization follows `H = T_target^-1 * H_normalized * T_source`.
`CalibrationBuilder.kt` builds versioned calibration profiles from measured image/ground correspondences, runs deterministic RANSAC, then applies `CalibrationValidator` quality gates.
`CalibrationValidator.kt` requires valid dimensions, a finite non-singular 3×3 homography, measured reprojection error, a finite inlier ratio and at least four inliers when an inlier count is provided.
`FileCalibrationStore.kt` persists validated profiles locally in `camera_calibrations.json` and increments the version when replacing the same calibration id.

### 2026-09-03 calibration lab integration
A real `CalibrationLabScreen` was added at `android/app/src/main/java/com/smarttraffic/app/features/calibration/CalibrationLabScreen.kt`.
It lets the operator choose a reference image, tap image coordinates, enter measured ground X/Y coordinates in metres, build a homography with the existing RANSAC estimator, and save only profiles that pass the existing quality gates. No synthetic measurements are generated.
Navigation and the More/Operations hub now expose the calibration lab through `AppDestination.Calibration`.
`LocalAnalysisScreen` now loads saved profiles from `FileCalibrationStore`, lets the operator select one, shows its dimensions/inlier ratio/reprojection error, and injects the selected profile into the real `AnalysisConfig` used by the pipeline.
The physical-speed gate remains intact: selecting a profile does not bypass source-dimension checks, calibration gates or timestamp-precision requirements.
The calibration UI now correctly labels the stored estimator statistic as mean target reprojection error rather than RMSE; the current builder stores `estimate.meanError`, not an RMS statistic.

For production calibration, native/OpenCV SVD/RANSAC is still planned. Native parity should be established before replacing the current testable fallback.

## Speed
Physical speed is never computed from raw pixel displacement.
The current estimator works in metric road coordinates and uses pairwise vector slopes, MAD outlier rejection, robust median velocity components, trajectory residuals and temporal coverage.
It reports m/s, km/h, velocity components, direction, trajectory residual and an uncertainty/dispersion estimate.
The quality/confidence score is not a calibrated probability; `errorKmh` is not ground-truth error.
Independent reference-speed measurements are required for accuracy claims.

`LocalVideoFrameSource` still marks video timestamps as `REQUESTED_SAMPLE_TIME`, because indexed frame position does not itself prove exact decoded PTS. Physical-speed mode remains blocked by the exact-timestamp gate until a trustworthy PTS-preserving source is integrated.

## Pipeline
`AnalysisPipelineRunner.kt`:
- keeps tracker input threshold separate from report threshold;
- refuses unrelated detection fallback when a track has no current-frame observation;
- records inference median/P95, processing FPS, total time, frame gaps, tracking input count, association misses and peak active tracks;
- refuses physical speed unless calibration validation gates pass;
- rejects unsupported keypoints/OCR/Re-ID/segmentation/optical-flow requests instead of silently doing nothing;
- accepts injectable geometry and speed backends. The default remains the Kotlin reference implementation for tests.

`AnalysisEngine.kt` exposes injectable `GroundProjector` and `SpeedEstimatorBackend` implementations while preserving the same shared frame pipeline.

## Local video laboratory playback — 2026-09-03
The original Local Analysis video source used repeated timestamp seeks at a fixed 100 ms interval. That could make a phone video appear to move unusually slowly because every processed frame was obtained through repeated timestamp lookup rather than sequential decoding.

`LocalVideoFrameSource.kt` was changed to use `MediaMetadataRetriever.getFrameAtIndex()` on API 28+ when frame-count metadata is available. The effective source FPS is taken from capture metadata or derived from frame count/duration. The source no longer inserts an artificial 100 ms sampling delay. API 26/27 and codecs that reject indexed decoding retain a timestamp-based fallback, with the fallback timestamp anchored to the current frame index/FPS rather than restarting from zero.

This change deliberately does not label the resulting timestamps as exact PTS. It improves decoding/processing throughput without weakening measurement provenance.

The Local Analysis UI now explicitly reports source FPS separately from processing FPS and tells the operator that visualization is driven by the real processed frames without an intentional playback sleep. A green CI result for the latest commit is still required before treating the implementation as build-verified, and real-device timing must still be measured because detector inference can remain slower than source playback on some phones.

## Live analysis preview and radar — 2026-09-03
`AnalysisRadarPreview.kt` was upgraded from a basic bounded preview into a video-first operator surface:
- true full-window analysis dialog with a clear close button;
- `ContentScale.Fit` video presentation with overlay coordinates computed from the same fit rectangle, preventing bbox drift when the video is enlarged or aspect ratio differs;
- current track boxes and speed labels at the video layer;
- tracking-radar visualization with a short history trail per track so motion is visually continuous rather than a set of isolated dots;
- explicit visual-radar versus metric-radar labeling;
- full-screen mode reuses the exact current processed frame and track state rather than starting a second analysis process.

This was inspired by the reference project's video-first dashboard philosophy, but no reference code was imported and the project remains Android-only at this stage. The reference repository's demo video URL could not be directly fetched in this environment, so no claim is made that its demo was visually inspected frame-by-frame.

## Native C++/JNI — 2026-09-03
The native layer remains C++20 and contains robust homography projection and robust speed math exposed through JNI.

New production wiring:
- `GroundProjector` is injectable in the domain pipeline.
- `NativeGroundProjector.kt` routes calibrated image-to-road projection through `NativeTrafficCore.projectHomography()` in the Android production Lab path.
- `SpeedEstimatorBackend` is injectable in the domain pipeline.
- `NativeFirstSpeedEstimator.kt` routes the calibrated speed hot path through `NativeTrafficCore.estimateRobustSpeed()` and falls back to the Kotlin reference estimator if the native call/linker/result validation fails.
- Native speed results are range-checked against configured maximum speed and retain explicit duration/sample/confidence/error information; direction is derived from the first/last valid metric points because the existing JNI result contract does not return velocity components.
- `AnalysisMetrics.speedEstimatorBackend` records the chosen backend name so native use is visible in executed results rather than silent.

The current C++ robust speed algorithm and Kotlin reference algorithm intentionally remain structurally aligned: timestamped metric points, bounded pair gaps, pairwise vector velocities, MAD rejection, robust median velocity and residual/stability confidence. Formal Kotlin/native numerical parity tests are still required before claiming byte-for-byte numerical equivalence.

## Inference acceleration — 2026-09-03
`LocalAnalysisViewModel.kt` no longer forces CPU for every Lab run. It attempts LiteRT `Accelerator.GPU` first and explicitly constructs a CPU detector if GPU model construction is unsupported. The selected accelerator is shown in the runtime and executed-metrics panels.

This is a performance optimization, not an accuracy claim. GPU success on one device does not imply it will be available or faster on every Android device; a final benchmark must compare CPU/GPU/NPU on the same frames and report median/P95 and end-to-end throughput.

## UI/runtime cleanup — 2026-09-03
Removed build warnings for direction-sensitive/deprecated Compose APIs:
- `Icons.Filled.Assignment` → `Icons.AutoMirrored.Filled.Assignment`;
- `Icons.Filled.Rule` → `Icons.AutoMirrored.Filled.Rule`;
- `Icons.Filled.ArrowBack` → `Icons.AutoMirrored.Filled.ArrowBack`;
- old `menuAnchor()` call → current `menuAnchor(ExposedDropdownMenuAnchorType..., enabled = true)` overload.

These changes are non-functional cleanup and should remain separate conceptually from the vision algorithm changes.

## Plate/OCR
Current `PlateConsensus` groups OCR readings by track and normalized text and uses exponential recency weighting based on actual timestamps.
The original failing test on 2026-09-03 used `ABC123@0ms` at confidence 0.90 and `XYZ999@100ms` at confidence 0.60 with half-life 100ms, but expected `ABC123`. That expectation was mathematically wrong: weighted support is 0.45 versus 0.60, so `XYZ999@100ms` is correct. The test was corrected and an additional test checks that changing frame numbers without changing presentation timestamps does not change the winner.
Temporal consensus is not yet a full production plate detector/OCR backend; those remain pending.

## Benchmarks
`DetectorBenchmark.kt` measures model-run latency with warmup, median and P95. It is not yet a complete device benchmark. Final benchmark must compare CPU/GPU/NPU on the same physical phone, same model, same input frames, and record preprocessing, inference, postprocessing, end-to-end throughput, memory and thermal state.

## Offline Python
`ai/robust_speed.py` mirrors the Android robust speed method and has unit tests.
`ai/run_traffic_analysis.py` reports speed confidence, uncertainty, trajectory residuals and dynamic-homography RMSE/inlier ratio. Dynamic keypoints remain research-only and must be per-track and benchmarked before production use.

## Research-backed direction
- ByteTrack reference: Kalman prediction + global assignment + low-score second association.
- BoT-SORT: motion + appearance/Re-ID + camera-motion compensation candidate.
- OC-SORT: observation-centric motion robustness candidate.
- TrackEval: HOTA/IDF1/MOTA reference evaluator.
- Theil-Sen principle: robust median pairwise slopes; used as inspiration for outlier-resistant speed estimation, not as a claim of identical implementation.
- Recent monocular vehicle-speed research with keypoint templates/dynamic homography is treated as an experimental comparison, not a guarantee.

## Ground truth and acceptance
Detection: precision/recall/mAP50/mAP50-95/per-class recall/localization error.
Tracking: HOTA/IDF1/MOTA, ID switches, fragmentation and lost duration.
Geometry: homography reprojection error and ground-position error.
Speed: MAE, RMSE, median/P90/P95 absolute error and ±5/10/20% coverage against independent speed ground truth.
OCR: character accuracy and exact plate match with temporal consensus.
Runtime: decode FPS, inference latency, E2E latency, dropped frames, memory and thermal observations.

Test conditions should include daylight, dusk/night, glare/shadows, dense traffic, occlusion, multiple lanes, approaching/receding vehicles and varied camera geometry.

## Current blockers / not yet claimed
- current post-upgrade commit still needs exact CI verification;
- LiteRT model behavior and GPU acceleration must be tested on the actual phone;
- GPU fallback currently protects model-construction failures, but runtime GPU failure during inference is surfaced as an analysis error rather than silently replaying with another backend;
- local video timestamps are still requested/sample time, not proven exact decoded PTS;
- indexed video decoding improves throughput but does not by itself guarantee realtime analysis on a low-end device;
- labeled traffic clips and TrackEval report pending;
- independent physical-speed validation pending;
- native/OpenCV calibration pending;
- formal Kotlin/native speed parity tests pending;
- trained vehicle-keypoint backend and dynamic homography pending;
- optical flow/segmentation/Re-ID backends pending;
- plate detector/OCR/rules/evidence persistence pending;
- final ESP32 hardware/firmware validation pending.

## Immediate execution order
1. Verify the exact latest feature commit with Android build/tests/lint and Python tests; fix any compiler/API regressions before adding further scope.
2. Run the Local Analysis Lab on a real phone with a representative phone-recorded traffic clip and record source FPS, processing FPS, inference median/P95 and dropped-frame gaps. Confirm the old slow-motion symptom is removed.
3. Compare CPU versus GPU on the same phone and source frames; keep whichever is actually faster/stabler, not whichever sounds faster.
4. Add native/Kotlin numerical parity tests for homography and speed, then tighten the native result contract to include velocity components/residuals if parity supports it.
5. Improve tracker evaluation on labeled traffic sequences with HOTA/IDF1/MOTA, ID switches and fragmentation. Use the reference repository only for presentation/architecture lessons, not as an algorithmic authority.
6. Replace requested-sample video timing with a trustworthy decoded PTS source before enabling physical speed on video.
7. Add native/OpenCV calibration path and parity tests.
8. Implement real vehicle-keypoint/dynamic-homography research backend and compare it empirically to fixed calibration.
9. Add plate detector/OCR, rule thresholds, alert feed and evidence image/crop persistence with the existing quality gates.
10. Implement and validate the ESP32 camera firmware/endpoints and wire the live MJPEG source into the same `FrameSource → AnalysisEngine` path.

## Conversation / work log — 2026-09-03

### User request — integrated build continuation
User confirmed the Android build now succeeds and requested continued development of the integrated traffic system, especially stronger C++ algorithms, richer radar/tracking, a full-screen Local Analysis video view with an exit button, and a fix for a phone-recorded car video appearing to play extremely slowly in the lab. User also provided `https://github.com/vietanhlee/Smart-Traffic-Monitoring-System.git` as a design/engineering reference, explicitly asking to learn from it without replacing our project architecture.

### Reference repository review
- Inspected the reference repository README/tree and its dashboard/frontend structure.
- The reference project emphasizes YOLO + ByteTrack, realtime/multi-camera monitoring, video-first dashboards and low-latency streaming architecture.
- Its linked demo video could not be directly fetched in this environment, so it is not represented as a frame-by-frame visual audit.
- Adopted only the useful presentation/system lessons: video should dominate the operator surface, tracks and telemetry should remain visually coupled to the processed frame, and realtime metrics should distinguish source cadence from actual processing throughput.
- No source code was copied into Smart-monitoring-system and no backend/WebRTC stack was added merely because the reference repo has one.

### Local video performance work
- Commit `2a336c9bcfbe9e8c6a73225748bd28fe9d00a7e2`: changed `LocalVideoFrameSource` from fixed 100 ms timestamp sampling to indexed sequential frame decoding on API 28+ and derived source FPS where possible.
- Commit `736bd038b7eb98f9f872626f933aa411e747031c`: added robust codec fallback from indexed decoding to timestamp sampling and preserved the fallback timeline relative to the current frame index rather than resetting timestamps.
- The key design decision is to remove the artificial sample interval and avoid repeated random timestamp lookup in the common Android 12+ path, while preserving the existing `REQUESTED_SAMPLE_TIME` provenance gate for physical speed.

### Lab/UI work
- Commit `9e8692c1a31bb259a50bbaa206b2f59674d11e88`: upgraded `AnalysisRadarPreview` with an immersive full-window dialog, explicit close action, fit-aware bbox overlay mapping and radar history trails.
- Commit `2bb20706fffec27a660a1f096cf27e1d109f23ef`: expanded `LocalAnalysisScreen` with explicit runtime readiness, source-vs-processing FPS explanation and executed metric panels.
- Commit `96c2d4027d5714fa278a40fe17744e2bbe825490`: exposed the speed estimator backend and timestamp precision in executed results.

### C++/native production wiring
- Commit `143f1475cd47af2577bafe01d98fc0d0949b9d7e`: added injectable domain `GroundProjector` with Kotlin reference implementation.
- Commit `ce73f998c6d5f75d4c8b9e1d0b1ea942e12a1147`: added `NativeGroundProjector` using existing JNI homography math.
- Commit `53329d00c00ed26c89373b9f17dc4bd103575e1d`: injected `GroundProjector` through `ModularAnalysisEngine` into `AnalysisPipelineRunner`.
- Commit `7d542a191bf8a2ad74c58cc247068f18cd38c625`: added injectable `SpeedEstimatorBackend` with Kotlin reference implementation.
- Commit `48f996b33c4acac6ecc064b319fc8ad32359ab22`: added native-first robust speed adapter with Kotlin fallback.
- Commit `52c6d068913c2dafcce4b41ff3848e4dda049602`: added backend identity/provenance to the speed estimator contract.
- Commit `264ff06d635a9bcfd62fd48724a8bb62a66cea80`: added `speedEstimatorBackend` to `AnalysisMetrics`.
- Commit `96eb8a4c03e0154f3b66f35fdd7d2b69139a272f`: routed the selected speed backend through the shared pipeline and recorded its identity in the result.
- Commit `ec66de51bb62a6c0e26c210ed50914701d6f0f53`: Local Analysis now uses the native ground projector and native-first speed estimator; LiteRT GPU is attempted before CPU.
- Commit `70f9223c02a9a23745ac6d4d6900c488f010ca9f`: exposed the native-first speed backend name explicitly.

### Runtime acceleration and warning cleanup
- Local Analysis now attempts `Accelerator.GPU`, falling back to CPU only when GPU detector construction fails. The selected accelerator is visible in the lab state/results.
- Deprecated Compose APIs were updated in commits `efad674ed41fb6838e942cb3b049b2440953c15e`, `332185946699f687415ac7b4f30f5f83a245401c`, and `360c6629efff8ef9675047f5d0799ba242646ddf`.
- The codebase should still be validated on the exact feature HEAD because these changes occurred after the last confirmed green baseline.

### Current stopping point
The integrated architecture is now stronger than the pre-request state: local video no longer has the known fixed sampling delay in its common path, the Lab has true immersive viewing and smoother radar visualization, LiteRT can use GPU automatically, calibrated geometry is routed through native C++, and calibrated speed can use native C++ with a Kotlin safety fallback. The next hard gate is exact CI verification of the latest HEAD followed by real-device playback/performance validation. No claim is made yet that the phone video runs at realtime speed or that native/Kotlin numerical parity is formally proven.
