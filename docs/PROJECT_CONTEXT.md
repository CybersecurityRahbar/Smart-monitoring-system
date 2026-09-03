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
Recent failures fixed:
- MediaMetadataRetriever wrong overload;
- sdkmanager missing from PATH;
- LiteRT 2.1.6 duplicate namespace;
- LiteRT 2.2.0 duplicate namespace under AGP 9, mitigated with compatibility property;
- preview renderer Compose compile failures fixed in commit `eaaee7fa...`;
- Run #211 (`33763868986`) failed at `compileDebugKotlin` because the newly added bounded-motion parameters were referenced from `InternalTrack` without being in that class's scope; fixed in `2835ec1b0449d56413a288576bb08d830b51ae76`.

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
1. Finish verification of the current calibration/integration commit and fix every compile/test/lint failure before further feature growth.
2. Validate the committed LiteRT artifact and tensor contract on the actual Android device.
3. Build CPU/GPU/NPU benchmark UI and collect physical-device measurements.
4. Evaluate ByteTrack baseline with TrackEval metrics on labeled traffic sequences.
5. Replace video requested-sample timestamps with trustworthy decoded PTS for physical-speed mode.
6. Add native/OpenCV calibration path and parity tests against the current Kotlin fallback.
7. Add Kotlin/native speed parity tests, then route the hot path to native after parity.
8. Implement real vehicle-keypoint/dynamic-homography research backend and compare to fixed calibration.
9. Add OCR, traffic rules, events and evidence persistence with the same reliability gates.

## Conversation / work log — 2026-09-03

### User request
"حسنا البناء الان نجح الان اكمل العمل هيا اعط اكبر سياق تمتلك واكبر جهد مع التركيز على عدم كتابه اي اخطاء هل فهمت هيا انطلق"

### Assistant execution record
- Verified that the previously reported green build corresponded to exact commit `c34f1cf8...`: Run #213 (`33764646784`) had successful Android build/tests/lint and successful offline Python tests.
- Reviewed the repository tree and confirmed the calibration backend already existed but was not exposed as a usable operator workflow.
- Added `CalibrationLabScreen.kt` in commit `879aa2b8b2bf46328f1caf70f19e3e488283c31a` with real reference-image point selection, measured ground coordinates, RANSAC calibration building, validation and durable profile storage.
- Exposed the calibration lab in navigation in commit `1303258645d2ab47eb64f6d4df5da4f9de14de3b`.
- Added a Camera Calibration entry to the More/Operations hub in commit `bbaf9f9d271496833be91caa682a2c452b312180`.
- Connected saved calibration profiles into `LocalAnalysisScreen` and injects the selected profile into `AnalysisConfig` in commit `9d86cedf50e8c3a41e9072cae547f15eea3b3b2b`.
- Added `CalibrationBuilderTest.kt` with an outlier-rejection/validated-profile case and singular/missing-error quality-gate case. The assertions were then corrected for Kotlin operator precedence in commit `7e9dd8f4648d1388c28f15b1ca1181824adb0c6e`.
- During review, noticed that the calibration UI was calling the stored `meanError` statistic “RMSE”; corrected that mathematical label and wording in commit `91d6d3bc969a1d5d70bb672c32f47c51958588aa`.
- Local container build could not be run because the environment could not resolve `github.com`; this is an infrastructure limitation, not a project failure.

### Current exact verification gate
- Run #219 (`33766357537`) was for commit `7e9dd8f...`; its offline Python job succeeded, while the Android job was still running when the next documentation/label commit superseded it.
- Current HEAD is `91d6d3bc969a1d5d70bb672c32f47c51958588aa`.
- Run #220 (`33766595466`) is the exact CI run for current HEAD and must be the only current verification result used for this state. At the last check it was pending; no green result is claimed until its Android build/tests/lint complete.

### Current stopping point
The calibration workflow is implemented end-to-end at the app/UI/storage/domain level, with explicit quality gates and regression tests. Physical speed remains protected by calibration and timestamp gates. The exact current CI gate is Run #220 on `91d6d3bc...`; after it is green, the next high-priority work is real-device LiteRT/tensor validation and benchmark instrumentation, followed by TrackEval and exact PTS work.
