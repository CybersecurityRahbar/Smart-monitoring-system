# Project Context Append — 2026-09-06

This document records the Smart Traffic repository state, architecture audit, engineering work, validation, decisions, and stopping points for the 2026-09-06 session. It is read together with `docs/PROJECT_CONTEXT.md`, `docs/PROJECT_CONTEXT_MASTER_PLAN_2026-09-04.md`, and prior session context files.

## Project baseline
Repository: `CybersecurityRahbar/Smart-monitoring-system`
Primary working branch: `main`.
Project: Android-first Smart Traffic Monitoring System using a phone as the command/analysis center and ESP32 camera endpoints. The system is an engineering/university prototype and is not yet traffic-enforcement certified.

## Architecture actually present
`Compose UI -> ViewModel/StateFlow -> application AnalysisHost -> UnifiedAnalysisSession -> ModularAnalysisEngine/AnalysisPipelineRunner -> FrameSource + LiteRT detector + ByteTrack + geometry + speed + rules/evidence`

`AnalysisHost` owns an application-scoped `UnifiedAnalysisSession`; Local and Live ViewModels use the shared session.

## Model/runtime
- `android/app/src/main/assets/models/yolo26n.tflite`
- SHA-256: `d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73`
- 2,875,553 bytes
- Input: 640 NCHW RGB FP32
- Output: `[1,84,8400]`, 80 COCO classes
- Traffic classes: car, motorcycle, bus, truck
- Build verifies model SHA-256
- LiteRT `2.1.5`
- Android runtime intentionally defaults to CPU-only; optional GPU/native acceleration is not probed implicitly because native delegate failure can terminate the process outside Kotlin exception containment.

## Local video / replay
Recorded local video uses analyze-then-replay. The analysis completes first; Media3/ExoPlayer then presents the source at natural speed while historical track observations are interpolated for the overlay. Vehicle boxes are fluorescent/neon green. Preview throttling must not slow analysis.

Current `LocalAnalysisViewModel` routes ordinary local analysis through `ExactPtsVideoFrameSource` when `useGroundPlane=true`, and `AnalysisConfig.useGroundPlane` defaults true. `ExactPtsVideoFrameSource` uses MediaExtractor + MediaCodec + ImageReader and preserves source presentation timestamps. `LocalVideoFrameSource` remains an API-28 fallback whose timestamp provenance is `REQUESTED_SAMPLE_TIME`.

Known local-video validation still required: non-zero-start PTS alignment, H.264/H.265 device compatibility, frame loss, rotation, RGBA conversion, memory pressure, sustained throughput, and fixing the `LocalVideoFrameSource.close()` EOF/resource-release lifecycle bug.

## Tracking
The tracker is ByteTrack-inspired, not claimed as a byte-for-byte official implementation. It includes high/low confidence association, class gating, Hungarian matching, Kalman prediction, bounded empirical motion, acceleration/reversal gates, adaptive center gates, optional deterministic appearance association, bounded history and session-local monotonic IDs. HOTA/IDF1/MOTA/ID-switch/fragmentation metrics have not yet been established against labelled traffic ground truth.

## Speed architecture
Two distinct modes exist:
1. `CALIBRATED_GROUND_PLANE`: validated homography + authoritative timestamps + metric ground-plane coordinates + robust speed estimation + automatic two-line timing gate when usable. This is the intended physical/metric path but is not enforcement-certified.
2. `CALIBRATION_FREE_ESTIMATE`: bottom-center image motion + vehicle-class width priors + local per-interval scale + dominant-motion projection + robust temporal aggregation and explicit uncertainty. This remains an approximate engineering estimate, not ground truth or legal enforcement evidence.

Vehicle-width priors currently used: motorcycle 0.85 m, car 1.80 m, van 2.00 m, truck 2.50 m, bus 2.55 m, unknown 1.90 m.

## Calibration-free speed v2 work completed
The v2 estimator is integrated into the real `AnalysisPipelineRunner`. It is enabled by default through `AnalysisConfig.enableCalibrationFreeSpeedEstimate`. Valid calibrated physical speed remains preferred; calibration-free speed is independently produced when physical calibration/timestamp policy is unavailable and the track is otherwise eligible.

Implemented improvements:
- safe symmetric 10% edge trimming when enough samples remain;
- local per-interval apparent-width metric scaling;
- dominant motion-axis estimation and direction-consistency gating;
- cumulative pseudo-metric trajectory built from actual interval end timestamps;
- bounded Theil-Sen robust trajectory slope;
- agreement check between instantaneous and trajectory-level speed;
- explicit confidence/uncertainty decomposition;
- bounded trajectory sample count before pairwise Theil-Sen work;
- exact timestamp retention on motion samples so a rejected middle interval cannot corrupt temporal indexing.

The first v2 draft had a timestamp/index coupling bug in the cumulative trajectory. It was corrected by storing and using `endTimeMs` on each motion sample.

The implementation intentionally does not claim reproduction of the August 2026 36-keypoint dynamic-homography research method because no validated trained 36-keypoint Android backend/model is present in this repository.

## Test debugging: Run #454
Run #454 (`34039504425`) failed during Kotlin unit-test compilation. The APK build itself had already succeeded. `AutoSpeedGateTest.kt` compared nullable `SpeedEstimate.errorKmh` directly with `0.0`. The test was corrected to use `requireNotNull(speed.errorKmh)` before the numeric assertion. No production runtime behavior changed.

A non-fatal instrumentation warning remains in `LiteRtStartupSmokeTest.kt` (`Condition is always 'true'`) and is separate cleanup work.

## Test debugging: Run #456
Run #456 built the application and instrumentation APKs successfully but six unit tests failed. All six were analyzed from the actual test report.

Cause 1: the synthetic `AutoSpeedGateTest` observations were spaced 1000 ms apart while production crossing logic rejects intervals above 600 ms. The test fixtures were corrected to 100 ms spacing.

Cause 2: regular `CalibrationFreeSpeedEstimatorTest` fixtures also used 1000 ms spacing while the estimator configuration used a 600 ms maximum gap. The regular fixtures were corrected to 100 ms spacing; the explicit large-gap test still creates a gap beyond the threshold, and the middle-invalid-interval test intentionally allows 2000 ms.

Cause 3: two `TrafficReliabilityTest` assertions still expected the old pre-fallback behavior of an empty speed-result map when validated physical speed was unavailable. The current production design intentionally produces a calibration-free estimate in that situation when the fallback is enabled. Those assertions were updated to require a calibration-free estimate and zero rejected speed estimates.

An accidental incomplete overwrite of `TrafficReliabilityTest.kt` during repair was detected and corrected by restoring the complete original suite from `main` and changing only the two stale expectations.

## Final automated validation: Run #460
Run #460 (`34041108609`) is green.

Successful stages:
- Android debug APK build
- Android instrumentation APK compilation
- Android unit tests
- Android Lint
- Offline Research Math tests
- Native C++ parity vectors
- AI-Thinker ESP32-CAM firmware build
- ESP32-S3-N16R8 firmware build

The successful run produced debug APK artifacts. This proves the checked repository build and automated tests for the supplied targets; it does not prove physical-device stability, camera/media compatibility, thermal behavior, or traffic-speed ground truth.

## Branch-management decision and correction
During the v2 work, a branch named `feat/calibration-free-speed-v2` was created. This was an assistant-side workflow decision and was not requested by the user. The user explicitly prefers `main` as the central project branch and does not want unnecessary project fragmentation.

PR #1 (`Upgrade calibration-free speed estimation`) was merged into `main` only after Run #460 passed.

Merge record:
- PR: `#1`
- head branch before merge: `feat/calibration-free-speed-v2`
- head SHA: `07c2c520c0c51cb218080103ab71fe19d2bd144c`
- merge commit now on `main`: `b855534ea652a5e27d0eb47c5896b81f6c875793`
- PR state: merged and closed

From this point onward, ordinary work must continue directly on `main`. Do not create feature branches unless the user explicitly asks for one.

## Context-maintenance rule
The user explicitly requires meaningful project stages to be recorded cumulatively in the repository context so future conversations can resume without losing decisions or implementation history. Missing updates during the v2/CI repair were an error.

Every meaningful future engineering cycle must record: the problem, diagnosis, exact changes, test/validation result, relevant commit/branch state, remaining risks, and the next stopping point.

## Current maturity
The project now has a real Android detector/tracker pipeline, calibration subsystem, calibrated and calibration-free speed modes, automatic speed gates, synchronized analyze-then-replay presentation, native/Kotlin parity paths, evidence persistence and CI coverage. It remains a prototype.

## Remaining high-priority work
1. Fix and regression-test `LocalVideoFrameSource` resource release at EOF.
2. Validate exact-PTS local playback on the target phone with non-zero and irregular source timestamps.
3. Measure real target-device detector median/P95 latency, processing FPS, source lag, memory, thermal behavior and long-run stability.
4. Build labelled tracking ground truth and measure HOTA/IDF1/MOTA, ID switches and fragmentation.
5. Build independent speed ground truth and measure MAE/MAPE, failure rate and ±10/±20 error bands. Confidence/error fields are not ground-truth accuracy.
6. Install and validate a real ANPR detector/OCR pipeline with temporal consensus and real plate crops.
7. Validate the exact ESP32 hardware revision, camera, power, Wi-Fi and long-duration streaming.
8. Keep calibration-free speed clearly separated from enforcement-grade physical measurements.

## Next stopping point
Resume all future work from `main`. The calibration-free speed v2 cycle is complete and its corrected code has been merged. The next engineering focus is physical-device/media validation and the remaining lifecycle/accuracy gates, not reopening the branch workflow.

## Comprehensive repository audit — 2026-09-06 (current main)

This section records the results of the comprehensive review performed after the repository advanced beyond the previous context commit. The review started from the live `main` tree and compared current source behavior against the documented architecture, historical context files, tests and CI definitions.

### Current repository head and history
- Current `main` HEAD is `6000d903a86997bbabb068530b866b0fbb849122`.
- That commit is a documentation-only upload: it adds `docs/سياق المحادثه الجزء الثالث.md`; no production source file changed in that commit.
- The previous context-maintenance commit is `3ae4c15eae9ec291c9e46019b9ca258c0068e588`.
- The calibration-free v2 merge commit remains `b855534ea652a5e27d0eb47c5896b81f6c875793`.
- Live branch listing currently contains both `main` and the old `feat/calibration-free-speed-v2` ref. The old feature ref still points to `07c2c520...`; it is not the active project branch. No new branch was created during this audit.
- No workflow run is currently associated with the latest documentation-only HEAD `6000d903...`; the previously green Run #460 is the validation evidence for the merged application code, not for this later docs-only commit.

### Documentation state
The `docs/` directory contains the current architecture/specification/reliability/local-lab/CI/context documents plus the historical Arabic conversation context files. The comprehensive audit found that several design documents are intentionally historical or higher-level and are not always synchronized with the newest implementation details.

Important documentation/current-code discrepancies to keep explicit:
- Older reliability/radar documentation still describes calibrated physical speed as the only trustworthy speed path or treats radar as a largely conceptual layer, while current `AnalysisPipelineRunner` and `IntelligentRadarScreen` actually support/display the explicitly labelled calibration-free fallback.
- Older documentation describes GPU attempts and earlier local-video routing states; the current runtime factory is CPU-only by construction and the current local-video path chooses `ExactPtsVideoFrameSource` when `useGroundPlane=true`.
- The historical context files (`سياق المحادثة الجزء الثاني .txt`, `سياق المحادثه الجزء الثالث.md`, and the original large Arabic context file) contain chronological development records, not necessarily current specifications. The current code must remain the behavioral source of truth.

### Android runtime path actually present
The live production path is:
`UI -> ViewModel/StateFlow -> AnalysisHost -> UnifiedAnalysisSession -> ModularAnalysisEngine -> AnalysisPipelineRunner -> FrameSource -> LiteRT detector -> ByteTrack -> ground/keypoint enrichment -> speed -> traffic rules/evidence -> preview/result`.

`UnifiedAnalysisSession` centralizes job, source and runtime ownership, closes resources after completion, supports cancellation, and guards concurrent analysis starts. The previous mutex/join deadlock pattern is no longer present.

### Detector/model findings
The repository contains a real `yolo26n.tflite` asset and a strict detector contract. `LiteRtObjectDetector` supports both end-to-end `[1,300,6]` and classic `[1,84,8400]` output formats, but the registry-selected current model contract is the classic 80-class output. Runtime output length is checked before parsing; letterbox scaling/padding is explicit and inverse-mapped to source coordinates; traffic classes parsed are car/motorcycle/bus/truck. `AnalysisRuntimeFactory` always instantiates the CPU accelerator in the current production path.

### Tracker findings
`ByteTrack` is a custom ByteTrack-inspired tracker rather than an official reference implementation. It uses high/low confidence two-stage association, class gating, Hungarian assignment, Kalman prediction and empirical short-horizon motion. It also applies acceleration bounding, reversal protection, adaptive center-distance gating, optional deterministic appearance similarity, bounded observation history and explicit track states. The code returns only confirmed, currently matched tracks to the live frame preview. No labelled traffic benchmark currently proves HOTA/IDF1/MOTA/ID-switch/fragmentation performance.

### Calibration and physical-speed findings
The calibrated path validates homography quality, source dimensions, inlier ratio and reprojection error before using the ground projector. Physical-speed eligibility also depends on exact timestamp precision when configured. `AutoSpeedGate` is metric in calibrated mode and visual-only in uncalibrated mode. Traffic rules remain stricter than display/analysis and should not be promoted to enforcement based on calibration-free output.

### Calibration-free speed findings
`CalibrationFreeSpeedEstimator` is genuinely wired into the production `AnalysisPipelineRunner`; it is not dead code or a screen-only feature. It only runs for confirmed/high-confidence tracks and has explicit temporal, directional and plausibility gates. It uses class width priors, local per-interval scale, dominant-motion projection, robust weighted median, cumulative pseudo-metric trajectory, bounded Theil-Sen slope, agreement residual, width stability and explicit uncertainty. `CALIBRATION_FREE_ESTIMATE` is preserved in the result/UI semantics.

However, the core metric conversion remains heuristic:
`metricDistanceM = imageDisplacementPx * assumedVehicleWidthM / localWidthPx`.
This is a useful engineering prior, but it is not a learned 3-D vehicle geometry model, not a camera-intrinsic/extrinsic solution, and not the published 36-keypoint dynamic-homography method referenced in the project documentation. Therefore the implementation should still be treated as an approximate estimate until benchmarked against ground truth. The absence of a trained keypoint backend in the repository is an explicit boundary, not a missing claim.

### Local-video source findings
`ExactPtsVideoFrameSource` is robustly designed around MediaExtractor + MediaCodec + ImageReader, propagates source PTS and checks monotonic decoder timestamps and image timestamp consistency. It is suitable for device validation, not yet device-certified.

`LocalVideoFrameSource` remains a fallback/indexed-decoding implementation. Its source timestamp precision is deliberately `REQUESTED_SAMPLE_TIME`, and it still contains the previously identified lifecycle flaw: EOF can set `finished=true`, after which `close()` returns before calling `retriever.release()`. It is not the default path when `useGroundPlane=true`, but it remains a real resource-management defect in a supported code path.

### Recorded-video presentation findings
`AnalysisVideoPlayback` correctly separates analysis from natural-speed replay. It uses ExoPlayer, interpolates historical detections by timestamp and bounds post-track extrapolation to 350 ms. This is the correct direction for avoiding the old inference-coupled slow playback.

A concrete synchronization risk remains: ExoPlayer `currentPosition` is used as the replay clock while track observations preserve source PTS milliseconds. The code currently seeks the player to `0L`, and the completed preview seed frame also uses timestamp `0L`. If a source video begins with a materially non-zero PTS, overlay timing can be offset because no explicit normalization to the first source PTS is applied. This must be tested and should be fixed before claiming robust timestamp synchronization.

### Radar/live findings
`IntelligentRadarScreen` currently analyzes a user-selected local traffic video through the shared analysis ViewModel and displays the same real detector/tracker/speed output in `AnalysisRadarPreview`; it is not a simulation. The separate live path uses `LiveAnalysisViewModel` + `MjpegFrameSource` and the same detector/tracker engine, with `NativeGroundProjector`/`NativeFirstSpeedEstimator` on the live engine.

The live MJPEG source intentionally keeps only the newest pending frame and drops/recycles stale frames, preventing an unbounded latency queue. Its timestamps are local monotonic arrival times, so calibrated physical live speed is intentionally blocked. Calibration-free speed can run because it does not require a calibrated metre scale, but the live path is not yet physically benchmarked.

The major remaining presentation/performance problem is therefore not algorithmic existence; it is real-time synchronization and scheduling on the target phone: source arrival, detector latency, tracker updates, UI frame presentation, queue/drop behavior, and long-run thermal/memory limits have not been measured on real hardware.

### Evidence/reporting findings
`FileEvidenceStore` is a real bounded persistence layer with hashing, atomic replacement/fallback handling, integrity checks and cleanup. `LocalAnalysisViewModel` can generate bounded full-frame and vehicle-crop JPEG artifacts for requested events. Exact live-MJPEG event-frame capture and production ANPR are still incomplete.

### ANPR findings
The domain layer already has `PlateReading`, `PlateConsensus` and the correct architectural insertion point, but `enablePlateRecognition` defaults false and there is no production Android plate-detector/OCR backend installed. Therefore plate recognition is not a completed system capability yet.

### CI findings
The current workflow executes Android debug build, instrumentation compilation, unit tests, lint, native parity tests, offline Python research tests, and both ESP32 firmware builds. A green run proves only those automated checks at that commit; it does not prove target-phone behavior, decoder compatibility, memory/thermal stability, tracking accuracy or speed accuracy. The current latest main HEAD `6000d903...` is documentation-only and has no workflow run attached in the current GitHub Actions lookup.

### Remaining work ranked by engineering priority
1. Fix `LocalVideoFrameSource.close()` EOF ownership and add a regression test.
2. Fix/validate replay clock normalization so ExoPlayer position and source PTS share the same zero point, including non-zero and irregular PTS videos.
3. Install a target-device validation loop: detector startup, MediaCodec decode, exact-PTS integrity, frame-drop accounting, sustained FPS, memory, thermal and crash/ANR monitoring.
4. Benchmark live ESP32-to-phone scheduling so the radar can present smoothly without reducing detector/model correctness. The intended solution is timestamped state interpolation/presentation scheduling, not hiding lag by lowering analysis quality.
5. Establish labelled tracking ground truth and calculate HOTA/IDF1/MOTA, ID switches and fragmentation.
6. Establish independent speed ground truth and calculate MAE/MAPE, robust error bands and confidence calibration by class, distance, perspective, lighting and occlusion.
7. Add/validate a real vehicle-keypoint/dynamic-homography calibration-free backend only after selecting deployable weights and comparing it to the current width-prior fallback. Do not substitute fake keypoints.
8. Complete production ANPR and temporal plate consensus.
9. Validate the exact ESP32-S3 board/camera pin map, power, Wi-Fi and long-run streaming.

### Exact current stopping point
The calibration-free v2 work is already merged on `main`, and the current repository head is later than the merge because of a documentation-only upload. The project should now resume on `main` from physical-device/media validation and from the playback/live-radar synchronization problem. No new algorithm should be added merely to make the UI look smooth; correctness, timing provenance and quality metrics must remain intact.
