# Smart Traffic Monitoring System — Master Engineering Context

Last verified context update: 2026-09-05
Repository: `CybersecurityRahbar/Smart-monitoring-system`

## 1. Engineering rule
The project must expose only functionality that is actually implemented and verified. A computed number is not automatically a trusted measurement. Speed outputs are therefore typed by measurement mode and carry uncertainty/confidence where available.

Release gate:
`Build -> Static/Lint -> Unit -> Instrumented -> Physical Device -> Media/Model Compatibility -> Stress/Memory -> Crash/ANR -> Accuracy/Tracking Benchmark -> Release Gate`

A green CI run certifies only the checks that completed at that exact commit SHA. It does not prove zero bugs, production readiness, physical-device stability, or enforcement-grade accuracy.

## 2. Target architecture
`Compose UI -> ViewModel/StateFlow -> Application AnalysisHost/Supervisor -> Domain Pipeline -> Source/Runtime/Detector/Tracker/Geometry/Speed/ANPR/Rules/Evidence`

`AnalysisHost` is application-scoped and owns `UnifiedAnalysisSession`. Lifecycle ownership is centralized so screen recomposition cannot silently create duplicate analysis sessions or close a newer session with an old resource owner.

## 3. Detector/model
LiteRT model: `android/app/src/main/assets/models/yolo26n.tflite`

SHA-256: `d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73`
Size: 2,875,553 bytes
Input: 640 NCHW
Output: `[1,84,8400]`, 80 classes

`DetectorModelRegistry` validates the model contract. The current mobile path uses CPU by default. GPU/native delegates are not enabled merely because they exist; they require explicit physical-device validation because native crashes/aborts are a release risk. Instrumentation has a real LiteRT startup smoke path.

## 4. Tracking
The Android tracker is ByteTrack-inspired, with Hungarian association, Kalman prediction, confidence tiers, class gating, optional appearance association, actual timestamp delta handling, motion/acceleration/reversal checks, adaptive gates, bounded history, and explicit states:
`TENTATIVE`, `CONFIRMED`, `LOST`, `REMOVED`.

Track IDs are session-local monotonic IDs and are displayed as `car ID: N`. A detector miss does not immediately create a new vehicle. Recovery/occlusion is represented in track state and metrics.

ID-switch and fragmentation metrics remain `null` until a labelled ground-truth benchmark is available.

## 5. Speed system — two legitimate modes
Speed is deliberately split into two modes:

### A. `CALIBRATED_GROUND_PLANE`
Preferred physical/metric estimate. Uses validated road/camera calibration, authoritative source timestamps, ground-plane projection, continuity checks, robust trajectory estimation, automatic two-line timing gate, plausibility checks, and uncertainty/error reporting.

Current automatic gate:
- collects contact-point observations within each individual track;
- infers dominant traffic direction from within-track motion only;
- places two robust cross-flow timing lines using scene quantiles;
- in calibrated mode the lines are constructed in metric ground coordinates and inverse-projected to the image;
- `SpeedGateEstimator` accepts either line order, interpolates crossing timestamps, computes separation / crossing-time speed, and compares it with robust trajectory slope;
- confidence combines timing quality, observations, agreement and gate geometry quality.

`RobustSpeedEstimator` is the general backend: bounded pair gaps, robust pairwise slopes, MAD outlier rejection, plausible-speed filtering, residual and quality scoring. Historical Kotlin/native parity fixtures have passed for linear, diagonal and variable-timestamp trajectories.

This is still not an exact ground-truth measurement. Exact/enforcement-grade accuracy requires independent reference instrumentation and empirical calibration/validation.

### B. `CALIBRATION_FREE_ESTIMATE`
This mode is now implemented and is enabled by default through `AnalysisConfig.enableCalibrationFreeSpeedEstimate = true`.

It is intentionally an estimate for ordinary phone videos where validated camera calibration is unavailable. Current implementation:
- extracts bottom-center vehicle image motion from the tracked bounding box;
- estimates a dominant motion axis from within-track image motion;
- projects each interval onto that axis;
- estimates local metric scale from a class-dependent robust vehicle-width prior;
- refreshes scale per interval rather than using one global width, reducing perspective bias as a vehicle approaches/recedes;
- applies robust median/MAD rejection to interval metric speeds;
- reports `confidence` and `errorKmh`;
- returns `SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE` so the result cannot be mistaken for calibrated metric speed.

Current vehicle-width priors are assumptions, not measured scene geometry: motorcycle 0.85 m, car 1.80 m, van 2.00 m, truck 2.50 m, bus 2.55 m, unknown 1.90 m. Consequently the error bound is deliberately broad and this output must never be described as exact or enforcement-grade.

The current implementation is a pragmatic fallback, not a reproduction of the strongest calibration-free research method. The 2026 paper **Calibration-Free Vehicle Speed Estimation: A Monocular Keypoint-Template Approach** uses a 36-keypoint vehicle template and dynamic homography and reports nonzero error on hundreds of clips; its results support treating calibration-free speed as a benchmarked estimate rather than an exact measurement. Future work can add a dedicated vehicle-keypoint backend and dynamic homography to strengthen this mode. Reference: arXiv 2608.16785 (2026-08-17). citeturn821121academia24

A separate GitHub calibration-free implementation (`damiadedayo/Vehicle-Speed-Estimation`) uses YOLOv8n + DeepSORT and a learned XGBoost speed regression trained from labelled speeds; it is useful as a reference for a future learned calibration-free backend, not as code copied into this Android implementation. citeturn821121search0

A simpler fixed-camera OpenCV reference (`swhan0329/vehicle_speed_estimation`) uses Lucas–Kanade optical flow, forward/backward consistency and scene-specific `px_to_meter` calibration. Its main lesson is that pixel-to-metre scale is camera/scene-specific unless a physical prior or calibration model is supplied. citeturn647289search11

## 6. Speed gating and UI semantics
The automatic gate is visual in both modes, but only calibrated mode has a physical separation in metres.

Recorded-video playback is deterministic analyze-then-replay: analysis runs over the source first, then ExoPlayer starts at natural playback speed with complete track histories. This avoids the visible box latency observed in APK #420 without weakening detector quality.

Current speed labels distinguish modes:
- calibrated: `speed: X km/h ± E`
- calibration-free: `speed: X km/h ± E (calibration-free estimate)`

This explicit label is required because the calibration-free result is useful but materially weaker than a validated metric result.

## 7. Timestamp rules
`ExactPtsVideoFrameSource` propagates decoder PTS and enforces monotonic source timing, but it is not yet physically certified.

`LocalVideoFrameSource` uses requested sample times and is not measurement-grade by itself.

Live MJPEG currently uses `LOCAL_MONOTONIC_ARRIVAL`; therefore calibrated physical live speed remains blocked until authoritative source timing is available. Calibration-free estimation can technically run on the live track stream because it does not require calibrated metres, but it must remain labelled as an approximate estimate.

## 8. Pipeline behavior
`AnalysisPipelineRunner` now chooses speed as follows:
1. If validated calibration + allowed timestamps are present, compute calibrated speed.
2. Otherwise, when enabled, compute `CALIBRATION_FREE_ESTIMATE` from tracked image motion and vehicle-size prior.
3. If neither path produces a robust result, retain a typed rejection reason instead of displaying a fabricated number.

Rejection reasons include:
`CALIBRATION_INVALID`, `TIMESTAMP_INVALID`, `INSUFFICIENT_OBSERVATIONS`, `INSUFFICIENT_DURATION`, `TRACK_QUALITY_LOW`, `DISCONTINUOUS_TRACK`, `GROUND_GEOMETRY_INCOMPLETE`, `PLAUSIBILITY_REJECTION`, `ROBUST_ESTIMATOR_REJECTION`.

Traffic-rule enforcement remains intentionally stricter: `TrafficRuleEngine` requires a validated physical calibration and therefore does not turn a calibration-free estimate into an enforcement event. fileciteturn388file0L1-L2

## 9. Tests added in this cycle
`CalibrationFreeSpeedEstimatorTest` covers:
- successful calibration-free estimate from a synthetic vehicle track;
- explicit `CALIBRATION_FREE_ESTIMATE` mode;
- confidence and nonzero uncertainty;
- rejection of a large observation gap.

`AutoSpeedGateTest` covers stable image-space gate creation, calibrated forward crossing speed, and reverse-direction crossing order. A nullable velocity assertion that previously blocked Android unit compilation was fixed before the next CI cycle.

## 10. Evidence / incident integrity
`FileEvidenceStore` is real and bounded: SHA-256 content-addressed artifact names, serialized operations, flushed writes, atomic move where supported, safe fallback, integrity verification, orphan pruning, and bounded count/bytes.

Current evidence limitation: exact live MJPEG event-frame capture and a real ANPR plate crop are not fully wired.

Persistent incident-report storage exists and is tested.

## 11. ANPR
Required pipeline remains:
`vehicle -> plate detector -> bounded crop -> perspective correction -> plate-quality gate -> OCR -> normalization -> plate-format validation -> temporal consensus -> evidence`.

A production Android-deployable plate detector/OCR backend has not yet been installed. The UI must not invent plate text.

## 12. ESP32
PlatformIO targets:
- `esp32cam_ai_thinker`, board `esp32cam`
- `esp32s3_n16r8`, board `esp32-s3-devkitc-1`, 16 MB flash, OPI PSRAM

Endpoints: `/stream`, `/capture`, `/status`, `/control`.
Wi-Fi: STA then AP fallback.
AI-Thinker flash control exists; S3 returns unsupported for that control.
Configured stream is approximately 15 FPS at VGA 640x480.
Credentials are isolated in ignored `src/secrets.h`; `secrets.example.h` is committed.

ESP32 firmware compilation is not physical hardware validation. The S3 camera pin map is board-revision dependent and must be checked on the exact PCB.

## 13. Performance / real-time roadmap
The recorded-video lab latency issue was solved architecturally by analyze-then-replay. It does not solve live ESP32 real-time operation.

Future live benchmark must measure detector median/P95, tracker latency, queue depth, source-to-result timestamp lag, dropped frames, sustained FPS and thermal/memory behavior on the target Android device. Hardware acceleration can become default only after these measurements pass.

Ultralytics YOLO26 documentation lists ByteTrack, BoT-SORT and other tracking backends; BoT-SORT adds camera-motion compensation and optional ReID and is the primary future candidate when moving-camera identity becomes a requirement. citeturn647289search0turn647289search3

## 14. Key historical fixes
Important verified fixes include:
- Run #331: duplicate local image source, stale `sourceUri`, missing `nativeCanvas`, malformed live VM initialization.
- `cedc14d789e05962b87d7e993ed88702883ae69d`: stale `droppedFrameCount()` call after property conversion.
- `a3d83a715eadcc11dbc556b482eda7119314b105`: `FileEvidenceStore.clear(): Unit` fix.
- `02fc5e83e1c909b36ac64140297cc905cdba991b` and `64e38afc33130571892d068af42023d42029715d`: nullable evidence hash and metadata-only regression.
- `dbef0c69d18edb2e64b53c8fdf730792625a6799`: stale ViewModel scope in MJPEG source construction and invalid PlatformIO dependency removal.
- `a4a4666cbc70fc6a0295317a4c71e180d71806f5`: ESP32 `camera_status_t.width/height` compile fix.
- `1165b2dc64f41f1764f18a1dc50f1e29a452b009`: `UnifiedAnalysisSessionTest` unresolved-result compile fix.

## 15. Current speed-cycle commits
- `40c05cd55b806a67cae4e5a2598df47323bd857f`: corrected bidirectional automatic-gate velocity sign.
- `ce12902f9044897a69b7b78b03745ea893eec9a2`: speed-gate test cycle before the nullable assertion correction.
- `b50bddb9e5bc001bca6a1faee725f12aeedd87d1`: fixed nullable reverse-speed test assertion.
- `4528ff29878591d7e7eae7a1933903a48db5f677`: added explicit `SpeedEstimateMode` and calibration-free configuration switch.
- `3a072084039f2fa5592cb08e9a72fe4b58b9e4f5`: initial calibration-free vehicle-width-prior estimator.
- `a386d8a30d20f8f879bef8f9f64c585504ebbc7b`: integrated calibration-free estimates into the analysis pipeline.
- `2e47a94d579c08b699b0825860f97623bb40a968`: added calibration-free speed unit tests.
- `7762e4ec9a5ea8c4aac27e7ef13112a72fd57102`: UI explicitly labels calibration-free speed.
- `8936c54ce3ec2fea5bfea962b7b491aceeb1c405`: corrected calibration-free motion projection to avoid filtered-index coupling and refreshed local scale per interval.

## 16. CI status / truth
Run #442 (`33989677478`) on the earlier speed-gate SHA reached Android compilation and failed at unit-test compilation because a nullable velocity value was asserted as non-null; ESP32, native parity and research math succeeded.

Run #447 (`33990283152`) was started after the nullable test fix.

After subsequent pushes, the newest workflow visible on `main` is Run #450 (`33990470253`) at SHA `8936c54ce3ec2fea5bfea962b7b491aceeb1c405`. At the latest repository check it was still `pending`; therefore the current speed implementation is **not yet CI-certified at this final SHA**.

Do not distribute a new APK as verified until the final SHA passes Android Build/Test, instrumentation compilation, unit tests, lint, Native C++ parity, Offline Research Math, and both ESP32 targets.

## 17. Immediate next engineering targets
1. Finish CI verification for the current SHA and fix every compile/test/lint failure rather than weakening tests.
2. Physically validate calibration-free speed on multiple different phone videos and camera/road configurations, comparing estimates against an independent reference speed wherever possible.
3. Build an accuracy report by vehicle class, perspective, lighting, distance, occlusion and camera angle. Record MAE/MAPE, median absolute error, percentage within ±10/±20 km/h or ±20%, failure rate, and confidence calibration.
4. Replace the current vehicle-width prior fallback with an optional real vehicle-keypoint backend plus dynamic homography inspired by the 2026 calibration-free research, once an Android-deployable model is selected and benchmarked. citeturn821121academia24
5. Keep calibrated and calibration-free outputs separate in analytics, evidence and enforcement policies.

## 18. Non-negotiable honesty constraints
Never call calibration-free speed “exact”.
Never invent a metre-per-pixel constant for an arbitrary camera.
Never convert a calibration-free estimate into an enforcement-grade violation by lowering a threshold.
Never treat a green CI run as physical-device proof.
Never weaken detector/model quality solely to hide performance problems; performance improvements must be benchmarked separately from correctness.
