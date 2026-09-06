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
