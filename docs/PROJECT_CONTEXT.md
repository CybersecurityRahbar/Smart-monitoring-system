# Smart Traffic — Project Context

Last updated: 2026-09-03

## Project identity
Repository: `CybersecurityRahbar/Smart-monitoring-system`.
Product: Smart Traffic Monitoring System / Android Smart Traffic app.
University prototype; not certified traffic-enforcement equipment.
Android is the command/visual center; ESP32-CAM is the camera/Wi-Fi endpoint; AI/CV is intended to run on Android/native acceleration.
No Map Widget unless explicitly requested.

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
- LiteRT 2.2.0 duplicate namespace under AGP 9, mitigated with compatibility property.

Every new source change must be judged only from the CI run for that exact commit. Cancelled/older runs do not count as verification.

## Detection
`data/vision/LiteRtObjectDetector.kt` is the real LiteRT adapter.
It validates runtime tensor count, input shape/type, output shape and registered model contract before inference. Current accepted detection contracts are YOLO26 `[1,300,6]` end-to-end and classic `[1,84,8400]` with NMS.

`DetectorModelRegistry.kt` currently registers:
- `yolo26n` → `models/yolo26n.tflite`, input 640, expected end-to-end `[1,300,6]`.

No `.tflite` weights are committed. The Android app refuses to run real inference when the registered model asset is absent.
Ultralytics current documentation says YOLO26 uses end-to-end `(N,300,6)` by default, while the classic one-to-many head is `(N,nc+4,8400)` and requires NMS. LiteRT exports use 640-pixel detection inputs. See `docs/RELIABILITY_DESIGN.md` for source links.

## Tracking
Android baseline is a ByteTrack-inspired implementation with:
- Kalman box prediction;
- global Hungarian linear assignment;
- high-confidence association;
- second low-confidence recovery association;
- class-aware gating;
- confirmation of identities;
- explicit lost/removed handling.

This is not claimed to be byte-for-byte equivalent to the official reference implementation until evaluated on labeled MOT/traffic sequences.
Future comparison candidates: official/reference ByteTrack behavior, BoT-SORT, OC-SORT and Deep OC-SORT.
Tracking evaluation target: HOTA plus IDF1/MOTA, ID switches and fragmentation using TrackEval rather than a home-made imitation metric.

## Geometry/calibration
`GeometryAndSpeed.kt` provides validated homography projection and robust metric trajectory speed estimation.
`HomographyEstimator.kt` now has normalized coordinate handling and deterministic RANSAC outlier rejection. The denormalization follows `H = T_target^-1 * H_normalized * T_source`.

For production calibration, native/OpenCV SVD/RANSAC is still planned. Calibration records now support reprojection error plus inlier count/ratio.

## Speed
Physical speed is never computed from raw pixel displacement.
The current estimator works in metric road coordinates and uses pairwise vector slopes, MAD outlier rejection, robust median velocity components, trajectory residuals and temporal coverage.
It reports m/s, km/h, velocity components, direction, trajectory residual and an uncertainty/dispersion estimate.
The quality/confidence score is not a calibrated probability; `errorKmh` is not ground-truth error.
Independent reference-speed measurements are required for accuracy claims.

## Pipeline
`AnalysisPipelineRunner.kt` now:
- keeps tracker input threshold separate from report threshold;
- refuses unrelated detection fallback when a track has no current-frame observation;
- records inference median/P95, processing FPS, total time, frame gaps, tracking input count, association misses and peak active tracks;
- refuses physical speed unless calibration validation gates pass;
- rejects unsupported keypoints/OCR/Re-ID/segmentation/optical-flow/rules/evidence requests instead of silently doing nothing.

`AnalysisEngine.kt` no longer returns an empty fake result for `MediaSource`: a `FrameSourceFactory` is required to create a real source; direct `FrameSource` execution is real.

`LocalAnalysisViewModel.kt` and `LocalAnalysisScreen.kt` now execute the actual local image/video detector+tracker pipeline. The Lab shows executed metrics and explicit runtime failures. Missing model assets disable real execution.

## Benchmarks
`DetectorBenchmark.kt` measures model-run latency with warmup, median and P95. It is not yet a complete device benchmark. Final benchmark must compare CPU/GPU/NPU on the same physical phone, same model, same input frames, and record preprocessing, inference, postprocessing, end-to-end throughput, memory and thermal state.

## C++/JNI
`android/app/src/main/cpp/` contains C++20 native homography projection and speed primitives plus JNI facade. Native speed is not yet routed into production pipeline because parity with the improved Kotlin estimator has not been proven. Native/OpenCV work comes after parity tests.

## Offline Python
`ai/robust_speed.py` mirrors the Android robust speed method and has unit tests.
`ai/run_traffic_analysis.py` now reports speed confidence, uncertainty, trajectory residuals and dynamic-homography RMSE/inlier ratio. Dynamic keypoints remain research-only and must be per-track and benchmarked before production use.

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
- validated `.tflite` model must be installed/tested on the actual phone;
- CPU/GPU/NPU real-device benchmark pending;
- labeled traffic clips and TrackEval report pending;
- independent physical-speed validation pending;
- native/OpenCV production calibration pending;
- trained vehicle-keypoint backend and dynamic homography pending;
- optical flow/segmentation/Re-ID backends pending;
- plate detector/OCR/rules/evidence persistence pending;
- final ESP32 hardware/firmware validation pending.

## Immediate execution order
1. Pass CI for the newest reliability/execution commit.
2. Fix any compile/test failures before further feature growth.
3. Validate the registered LiteRT tensor contract with a real `.tflite` artifact.
4. Build CPU/GPU/NPU benchmark UI and collect physical-device measurements.
5. Evaluate ByteTrack baseline with TrackEval metrics on labeled traffic sequences.
6. Add proper calibration UI and native/OpenCV RANSAC/SVD path.
7. Add Kotlin/native speed parity tests, then route hot path to native.
8. Implement real vehicle-keypoint/dynamic-homography research backend and compare to fixed calibration.
9. Add OCR, traffic rules, events and evidence persistence with the same reliability gates.
