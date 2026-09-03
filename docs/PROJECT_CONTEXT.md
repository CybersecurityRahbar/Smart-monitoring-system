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
Recent failures fixed:
- MediaMetadataRetriever wrong overload;
- sdkmanager missing from PATH;
- LiteRT 2.1.6 duplicate namespace;
- LiteRT 2.2.0 duplicate namespace under AGP 9, mitigated with compatibility property;
- preview renderer Compose compile failures fixed in commit `eaaee7fa...` (that run must not be confused with later reliability/build failures);
- Run #211 (`33763868986`) failed at `compileDebugKotlin` because the newly added bounded-motion parameters were referenced from `InternalTrack` without being in that class's scope.

CI verification rule: only the run for the exact commit under review counts as verification. Cancelled/older runs do not count.

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

For production calibration, native/OpenCV SVD/RANSAC is still planned. Calibration records support reprojection error plus inlier count/ratio.

## Speed
Physical speed is never computed from raw pixel displacement.
The current estimator works in metric road coordinates and uses pairwise vector slopes, MAD outlier rejection, robust median velocity components, trajectory residuals and temporal coverage.
It reports m/s, km/h, velocity components, direction, trajectory residual and an uncertainty/dispersion estimate.
The quality/confidence score is not a calibrated probability; `errorKmh` is not ground-truth error.
Independent reference-speed measurements are required for accuracy claims.

A current data-quality limitation remains: `LocalVideoFrameSource` samples requested timestamps with `MediaMetadataRetriever`; these requested sample times are not guaranteed to be exact decoded presentation timestamps. Exact timestamp provenance is therefore explicitly tracked, and production physical-speed mode must require exact timestamps until a sequential decoder/source with trustworthy PTS is integrated.

## Pipeline
`AnalysisPipelineRunner.kt`:
- keeps tracker input threshold separate from report threshold;
- refuses unrelated detection fallback when a track has no current-frame observation;
- records inference median/P95, processing FPS, total time, frame gaps, tracking input count, association misses and peak active tracks;
- refuses physical speed unless calibration validation gates pass;
- rejects unsupported keypoints/OCR/Re-ID/segmentation/optical-flow/rules/evidence requests instead of silently doing nothing.

`AnalysisEngine.kt` no longer returns an empty fake result for `MediaSource`: a `FrameSourceFactory` is required to create a real source; direct `FrameSource` execution is real.

`LocalAnalysisViewModel.kt` and `LocalAnalysisScreen.kt` execute the actual local image/video detector+tracker pipeline. The Lab shows executed metrics and explicit runtime failures. Missing model assets disable real execution.

## Live analysis preview
The Lab now has an `AnalysisPreviewFrame` observer/state path that exposes the actual decoded bitmap, detections, current tracks and live speed estimates to `AnalysisRadarPreview`.
The preview renderer compile defects were fixed in commit `eaaee7fa...`; the preview must still be validated on a green CI run and a real device. Display throttling may be added later without changing analysis metrics.

## Plate/OCR
Current `PlateConsensus` groups OCR readings by track and normalized text and uses exponential recency weighting based on actual timestamps.
The original failing test on 2026-09-03 used `ABC123@0ms` at confidence 0.90 and `XYZ999@100ms` at confidence 0.60 with half-life 100ms, but expected `ABC123`. That expectation was mathematically wrong: weighted support is 0.45 versus 0.60, so `XYZ999@100ms` is correct. The test was corrected and an additional test checks that changing frame numbers without changing presentation timestamps does not change the winner.
Temporal consensus is not yet a full production plate detector/OCR backend; those remain pending.

## Benchmarks
`DetectorBenchmark.kt` measures model-run latency with warmup, median and P95. It is not yet a complete device benchmark. Final benchmark must compare CPU/GPU/NPU on the same physical phone, same model, same input frames, and record preprocessing, inference, postprocessing, end-to-end throughput, memory and thermal state.

## C++/JNI
`android/app/src/main/cpp/` contains C++20 native homography projection and speed primitives plus JNI facade. Native speed is not yet routed into production pipeline because parity with the improved Kotlin estimator has not been proven. Native/OpenCV work comes after parity tests.

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
- committed model behavior must be tested on the actual phone;
- CPU/GPU/NPU real-device benchmark pending;
- labeled traffic clips and TrackEval report pending;
- independent physical-speed validation pending;
- exact decoded PTS source for physical-speed video pending;
- native/OpenCV production calibration pending;
- trained vehicle-keypoint backend and dynamic homography pending;
- optical flow/segmentation/Re-ID backends pending;
- plate detector/OCR/rules/evidence persistence pending;
- final ESP32 hardware/firmware validation pending.

## Immediate execution order
1. Verify CI for the newest reliability/execution commit `2835ec1b...`.
2. Fix every compile/test/lint failure before further feature growth.
3. Validate the committed LiteRT artifact and tensor contract on the actual Android device.
4. Build CPU/GPU/NPU benchmark UI and collect physical-device measurements.
5. Evaluate ByteTrack baseline with TrackEval metrics on labeled traffic sequences.
6. Replace video requested-sample timestamps with trustworthy decoded PTS for physical-speed mode.
7. Add proper calibration UI and native/OpenCV RANSAC/SVD path.
8. Add Kotlin/native speed parity tests, then route the hot path to native after parity.
9. Implement real vehicle-keypoint/dynamic-homography research backend and compare to fixed calibration.
10. Add OCR, traffic rules, events and evidence persistence with the same reliability gates.

## Conversation / work log — 2026-09-03

### User request
"حسنا اكمل العمل بتفكيرا عميق وتركيزا شديد لا اريد اخطاء او نسيان هل فهمت"

### Assistant execution record
- Confirmed that future work must continue from the repository state and the cumulative context, not implicit memory.
- Reviewed the existing reliability failures and source implementations.
- Preserved root `PROJECT_CONTEXT.md` and used `docs/PROJECT_CONTEXT.md` as the authoritative cumulative handoff file.
- Identified that the timestamp-weighted plate test expectation was mathematically wrong and corrected it to `XYZ999` at 100 ms.
- Added a frame-rate/frame-index independence regression test for plate consensus.
- Inspected `ByteTrack`, `KalmanBoxPredictor` and `LinearAssignment` rather than widening the motion gate blindly.
- Added bounded empirical motion prediction from the last two timestamped detections and a regression that rejects an implausible 400 px jump.
- CI Run #211 (`33763868986`) then exposed a new compile-scope error: `InternalTrack` could not see `ByteTrack` constructor parameters `empiricalVelocityHorizonSeconds` and `empiricalVelocityBlend`.
- Corrected the production code in commit `2835ec1b0449d56413a288576bb08d830b51ae76` by passing both parameters explicitly into each `InternalTrack`.

### Current verification gate
The exact `2835ec1b...` commit is now the only commit whose CI result matters. The previous Run #211 failure was a compile error on its predecessor, not a valid verification of `2835ec1b`.

### Current stopping point
Production code and regression tests contain the intended fixes; the next concrete state to record is the CI result for commit `2835ec1b...`. No green build is claimed until GitHub Actions proves it.
