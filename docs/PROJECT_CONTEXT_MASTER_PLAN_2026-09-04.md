# Smart Traffic Monitoring System — Master Engineering Plan

## Purpose
Master context for the Smart Traffic Monitoring System in `CybersecurityRahbar/Smart-monitoring-system`. The project is a real traffic-monitoring system centered on Android analysis/control with ESP32 camera ingestion. The engineering rule is to expose only functionality that is actually implemented and verified; computed values are not automatically trusted measurements.

## Target architecture
`Compose UI -> ViewModel/StateFlow -> Application AnalysisHost/Supervisor -> Domain Pipeline -> Source/Runtime/Detector/Tracker/Geometry/Speed/ANPR/Rules/Evidence`

One application-scoped `AnalysisHost`/`UnifiedAnalysisSession` owns the active execution. Lifecycle ownership and resource closing are centralized. Runtime phases remain explicit and must not be confused with product readiness.

## Release/verification gate
`Build -> Static/Lint -> Unit -> Instrumented -> Physical Device -> Media/Model Compatibility -> Stress/Memory -> Crash/ANR -> Accuracy/Tracking Benchmark -> Release Gate`

A green CI run proves only the test layers that actually completed at that exact commit SHA. Never claim zero bugs, production readiness, or physical-device stability from CI alone.

## Detector/runtime
LiteRT detector asset: `yolo26n.tflite`.
SHA-256: `d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73`.
Size: 2,875,553 bytes. Input: 640 NCHW. Output contract: `[1,84,8400]`, 80 classes.

`DetectorModelRegistry` validates the model specification and tensor contract. Current isolated local path uses CPU; GPU/native acceleration is not enabled automatically because native process-abort risk must be validated explicitly. A real one-frame instrumentation smoke path exists.

## Tracker
ByteTrack-inspired multi-object tracking with Hungarian association, Kalman prediction, confidence tiers, class gating, optional appearance cue, short-horizon velocity, actual dt, acceleration/reversal checks, adaptive distance gates, bounded history, and explicit states:
`TENTATIVE`, `CONFIRMED`, `LOST`, `REMOVED`.

Track IDs are stable monotonic session-local IDs and must follow the same vehicle across frames. Detector misses do not immediately create a new vehicle. Recovery/occlusion behavior is explicit. `AnalysisPipelineRunner` preserves terminal track state rather than forcing all completed tracks to `CONFIRMED`.

Quantitative ID-switch/fragmentation metrics remain unbenchmarked (`null`) until ground-truth data exists.

## Speed/geometry
Perception and measurement are separate contracts. `RobustSpeedEstimator` uses robust pairwise slopes with bounded pair gaps, plausible-speed rejection, MAD/outlier rejection, and residual/quality scoring. Kotlin/native parity fixtures have historically passed for linear, diagonal, and variable-timestamp trajectories.

The project has **one physical-speed output concept: a calibrated speed estimate, not an exact/ground-truth speed**. The value is derived from metric road coordinates and authoritative timestamps and carries an uncertainty-like `errorKmh`. There is currently no independent "exact speed" instrument in software. An exact or enforcement-grade value requires external ground truth such as a calibrated speed gun/radar (or equivalent reference) and empirical validation against it. The UI must therefore use wording such as `≈ speed ± error`, never `exact speed`.

Measurement-grade speed requires validated calibration, authoritative timestamps, sufficient observations and duration, temporal continuity, bounded gaps, reliable ground-plane projection, acceptable trajectory residual, plausibility, and adequate track quality. Rejection semantics distinguish at least:
`CALIBRATION_INVALID`, `TIMESTAMP_INVALID`, `INSUFFICIENT_OBSERVATIONS`, `INSUFFICIENT_DURATION`, `TRACK_QUALITY_LOW`, `DISCONTINUOUS_TRACK`, `GROUND_GEOMETRY_INCOMPLETE`, `PLAUSIBILITY_REJECTION`, `ROBUST_ESTIMATOR_REJECTION`.

`ExactPtsVideoFrameSource` is implemented using MediaExtractor/MediaCodec/ImageReader with decoder PTS propagation, monotonic timestamp checks and bounded image backpressure. It is not physically certified yet. Ordinary `LocalVideoFrameSource` uses requested sample time and is not measurement-grade.

Live MJPEG uses `LOCAL_MONOTONIC_ARRIVAL` timestamps, so physical live speed remains blocked until authoritative source timing and calibration are available.

## Reference implementations reviewed — 2026-09-05
### `swhan0329/vehicle_speed_estimation`
The repository is a lightweight fixed-camera OpenCV baseline. It uses Lucas–Kanade optical-flow point tracking with forward/backward consistency, configurable lane polygons, lane-specific `px_to_meter` calibration, and lane speed derived from point displacement multiplied by scale and source FPS. The important lesson is that simple smooth tracking comes from avoiding a full detector on every point, while the important measurement limitation is that the pixel-to-metre scale is scene/camera-specific and must be calibrated rather than guessed.

### `vietanhlee/Smart-Traffic-Monitoring-System`
The repository is a closer architecture reference for this project: YOLO + ByteTrack, multiprocessing for multi-camera processing, low-latency WebRTC, CPU/GPU execution, and multiple inference/export backends including TensorRT, OpenVINO and INT8 variants. Its design explains why a CPU-only mobile detector can visibly lag behind a natural-speed presentation clock even when the overall algorithm is logically correct. The project should borrow the separation of media transport, inference, tracking and acceleration, not blindly copy backend-specific infrastructure into Android.

### Algorithmic research direction
Recent YOLO26 tooling supports tracking with ByteTrack/BoT-SORT/other trackers and exports such as LiteRT, ONNX, TensorRT and OpenVINO. BoT-SORT adds appearance and camera-motion compensation on top of motion association. These are candidate improvements for a later real-time/mobile acceleration pass. No claim is made that the current Android tracker is reference-equivalent to ByteTrack or BoT-SORT.

Automatic camera/road self-calibration is an active research problem. Camera/road geometry can be inferred from visible road features or vehicle keypoints, but calibration-free monocular speed papers still report nonzero error and remain benchmarked estimates, not exact speed. Therefore the project must not invent metres from arbitrary phone video without a valid physical scale assumption.

## Frame sources
`FrameSource` exposes `droppedFrameCount` as a property.

`MjpegFrameSource` owns its producer lifecycle independently of a ViewModel scope, retains one latest pending frame, recycles replaced bitmaps, counts source-level dropped frames, propagates producer failures, and closes idempotently.

`LocalImageFrameSource` and local video sources have bounded ownership/cleanup. Preview rendering must never determine analysis throughput.

## Local laboratory playback — empirical finding from APK #420
The user tested APK #420 with several downloaded phone videos rather than one fixed video. The recorded video playback rate was correct and natural. However, tracking boxes appeared only after a visible delay because the analysis pipeline processed frames serially on CPU while ExoPlayer played independently. This is a real latency/architecture defect, not a reason to weaken detector quality.

The lab design is therefore changed to **analyze first, then replay deterministically** for recorded-video validation. The analyzer processes the source in timestamp order; playback stays paused while analysis runs; after the complete track history is available, ExoPlayer starts at time zero and the overlay interpolates track observations against the video's playback position. This guarantees that the lab replay cannot outrun the known analysis result. This mode is intentionally different from future live ESP32 operation, where a bounded real-time pipeline and acceleration are required.

`AnalysisPreviewFrame.playbackReady` now explicitly controls whether recorded-video replay may start.

## Tracking-radar correction and automatic speed gate — 2026-09-05
The previous radar visualization recalculated min/max plotting bounds from the currently visible tracks on each frame, causing viewport drift when vehicles entered/left the scene. That code has been replaced by fixed `RadarBounds` supplied by the analysis pipeline. Metric mode uses the calibrated ground projection of the source-image corners; image-space mode uses the stable pixel coordinate system. Track labels are now `car ID: N` rather than `#N`.

A new `AutoSpeedGate.kt` implements a deterministic scene-adaptive timing gate:
- gather actual vehicle contact-point observations from each individual track;
- infer the dominant traffic-flow direction from adjacent samples **within each track**, then combine those vectors robustly;
- place two cross-flow lines at robust 35th/65th longitudinal scene quantiles;
- in calibrated mode, construct the lines in the metric ground plane, inverse-project them to image coordinates, and clip them to the actual video frame;
- in uncalibrated mode, draw the same type of virtual lines in image-space only and deliberately do **not** claim metric speed.

The critical correction in the gate algorithm is that unrelated vehicles are never treated as consecutive samples when estimating velocity. This prevents arbitrary direction vectors caused by concatenating different tracks.

`SpeedGateEstimator` detects each vehicle's crossing of line 1 and line 2, linearly interpolates the crossing timestamp between source observations, and computes speed from known metric separation divided by the measured crossing-time interval. It also derives a timing-interpolation error bound. Calibration/detection/model uncertainty is not falsely hidden inside that number.

The pipeline now carries `speedGate` and `uniqueVehiclesDetected` through `AnalysisPreviewFrame`. The cumulative count is based on unique confirmed Track IDs and is independent of the current active-track count, so a vehicle leaving the frame no longer decreases the total detected count.

## Current UI semantics after lab feedback
Recorded-video lab replay now:
- uses the original video at natural speed after analysis completes;
- shows fluorescent-green vehicle boxes;
- labels vehicles as `car ID: 1`, `car ID: 2`, etc.;
- separates `Cars detected` (cumulative unique tracks) from `Active` (vehicles currently visible);
- draws fixed red `SPEED LINE 1` and `SPEED LINE 2` when a gate can be inferred;
- displays a speed beside a vehicle ID only after a calibrated line-crossing measurement is available;
- never presents the visual-only gate as a metric measurement when no validated calibration is available.

## Current code commits in this cycle
- `012686f72cbdae81e1c1f9921312bcb0091877dc`: initial automatic speed-gate implementation.
- `8693d947aa699f9b4c828e769988baac707df285`: corrected/strengthened speed-gate geometry and crossing uncertainty handling.
- `5dc090ce34f9b57d176d190cafa75db5db4fea53`: preview model now carries speed gate and cumulative unique vehicle count.
- `da0d3042ab6b879fe084719bcd823b20876edfcb`: analysis metrics now expose cumulative unique vehicle count.
- `c341baf95fa66d752abb2d7d134897df7d6f9697`: pipeline integrates frozen automatic speed gates and cumulative vehicle counting.
- `ac43e108663170de10655c47361069a0b7506e9b`: fixed automatic traffic-axis inference to use within-track motion only.
- `b224ea23ff4cacd42409c05ff144f26a1670b6e9`: video overlay adds fixed red speed lines, `car ID: N`, and calibrated speed text.
- `0ee068ee588f3015c6e0cd71045914d258a9f11f`: deterministic recorded-video replay waits for `playbackReady` and prevents boxes from appearing outside the actual observation interval.
- `4ffce2bc53618120eb7c8ea8cc86e0895c1addf4`: radar UI shows cumulative vehicle count and the new ID format.
- `6eca3d7e8bf715221f56c2e4196d8d34df803852`: Local Analysis publishes a completed full-track replay preview for recorded-video lab validation.
- `89542218b3ca1ca1e1938fadf33f75d3af256a4b`: lab UI text/result metrics updated to document analyze-then-replay behavior and unique counts.

## CI truth after APK #420 and current cycle
Run #420 (`33984686267`) at SHA `e786f30a0fa57029370d62bc3095240884aa3244` completed successfully for Android Build & Test, Native C++ Parity, ESP32 firmware, and Offline Research Math. APK #420 was therefore a valid build, but the user's physical test exposed functional latency that CI cannot detect.

The code commits listed in the current cycle supersede #420, so #420 does not certify the new automatic speed gate, cumulative counter, synchronized replay, or current UI changes. A fresh CI run is mandatory on the final current SHA before another APK is distributed.

## Current verification target
Current repository HEAD is the most recent code commit after the automatic speed-gate and deterministic replay changes. The next gate must verify Android Build & Test, instrumentation APK compilation, unit tests, lint, Native C++ parity, Research Math, and both ESP32 firmware targets at the exact current SHA.

After CI, recorded-video physical validation should use **multiple different phone videos and multiple camera/road arrangements**, not a single chosen video. The test matrix should include different traffic directions, vehicle densities, distances, camera heights/angles, daylight conditions, perspective strength, and partial occlusion.

A separate future performance pass is still required for truly real-time live operation. It should benchmark hardware-accelerated LiteRT delegates on target devices, detector latency/P95, tracker latency, queue depth, end-to-end timestamp lag, and dropped frames before enabling acceleration as default.

## ANPR
Required real pipeline:
`vehicle -> plate detector -> bounded crop -> perspective correction -> plate-quality gate -> OCR -> normalization -> plate-format validation -> temporal consensus -> evidence`.

A real Android-deployable licensed plate detector/OCR backend has not yet been installed. The UI therefore does not present ANPR/OCR as executable functionality. Unsupported, blurred, oblique, occluded, or low-quality plates must not produce guessed text.

## Evidence and incidents
`FileEvidenceStore` is real and bounded. Artifacts use immutable SHA-256 content-addressed names, serialized store operations, flushed writes, atomic move where supported with safe fallback, integrity verification on read, and orphan pruning. Frame/vehicle/plate artifacts are stored when supplied; metadata-only records remain incomplete forensic evidence.

Persistent incident-report storage and tests exist. Dashboard/alerts state is sourced from the shared analysis session and real `TrafficRulePreferences`, rather than fake counters/status.

Current evidence limitation: exact live MJPEG event-frame capture and a real ANPR plate crop are not fully wired.

## Diagnostics/lifecycle
`AnalysisDiagnostics` records durable run IDs and stage markers before high-risk model/media/runtime initialization, including device/runtime/model/media metadata, so process-level failures can be correlated on the next launch.

`AnalysisHost` owns a `SupervisorJob + Dispatchers.Default` scope. `UnifiedAnalysisSession` has tests covering completed-run cleanup, restart, duplicate-start rejection, stop-time exact-once cleanup, and resource identity protection so a stale session cannot close a newer session's resources.

## ESP32 firmware
PlatformIO project exists in `esp32/` with two required targets:
- `esp32cam_ai_thinker` (`board = esp32cam`)
- `esp32s3_n16r8` (`board = esp32-s3-devkitc-1`, 16MB flash, OPI PSRAM)

Firmware endpoints: `/stream`, `/capture`, `/status`, `/control`. Wi-Fi uses station mode with AP fallback. AI-Thinker flash control is implemented; S3 returns unsupported for that control. Stream is bounded around 15 FPS at configured VGA 640x480. Credentials use ignored `src/secrets.h` with `secrets.example.h`.

The S3 pin map is board-revision dependent and must be checked against the exact PCB before wiring. Do not infer hardware correctness from firmware compilation.

## Important historical CI fixes
- Run #331: duplicate `LocalImageFrameSource`, stale `sourceUri`, missing `nativeCanvas`, malformed Live VM initialization. Corrected.
- Run #356: stale calls to `droppedFrameCount()` after conversion to a property. Fixed in `cedc14d789e05962b87d7e993ed88702883ae69d`.
- Run #357 (`33971826268`) passed Android build/test, instrumentation APK compilation, unit tests, lint, native parity and research math at its exact SHA.
- Evidence hardening: `55426379e908213a9bb22d763ed30f2a7ac354c2`, instrumentation expansion `39ad0c62e46a828461f01026f93652b176974570`.
- Tracker terminal-state preservation: `4dd4f597a401f68d909c6f493a0e9a7986482fe7d`.
- Run #362: `FileEvidenceStore.clear()` return-type bug; fixed by explicit `clear(): Unit` in `a3d83a715eadcc11dbc556b482eda7119314b105`.
- Nullable evidence SHA bug fixed in `02fc5e83e1c909b36ac64140297cc905cdba991b`, with regression coverage in `64e38afc33130571892d068af42023d42029715d`.
- Run #396 (`33974167681`): removed stale `scope = viewModelScope` from `MjpegFrameSource` construction and removed invalid PlatformIO registry dependency `espressif/esp32-camera @ ^2.0.5` in `dbef0c69d18edb2e64b53c8fdf730792625a6799`.
- Run #397 (`33974604758`): ESP32 compile error because `camera_status_t` has no `width`/`height`; fixed by reporting the configured VGA 640x480 dimensions in `a4a4666cbc70fc6a0295317a4c71e180d71806f5`.

## CI Run #399 failure and fix
Run #399 (`33974752889`) reached Android unit-test compilation and failed in `UnifiedAnalysisSessionTest.kt`:
`Unresolved reference 'result'` at the `BlockingEngine` references.

Root cause: `BlockingEngine` is a nested test class and referenced `result` without declaring it as a constructor/property dependency. The production `UnifiedAnalysisSession` was reviewed and was not changed for this failure.

Fixed by adding `private val result: AnalysisResult` to `BlockingEngine` and passing the shared fixture from the test call. Commit:
`1165b2dc64f41f1764f18a1dc50f1e29a452b009`
Message: `test: fix UnifiedAnalysisSession blocking engine test fixture`.

## CI Run #400 and #401 verification
Run #400 was `33975048887` from commit `1165b2dc...`; Native parity, Research Math, and both ESP32 firmware targets succeeded, and its Android job was still in progress when it was superseded by the documentation commit.

Run #401 was `33975138919` from `3fe3b98...` and completed successfully across Android Build & Test, Android instrumentation APK compilation, unit tests, lint, Native C++ parity, Research Math, and both ESP32 firmware targets.

## Local playback decoupling work and CI failures
The local playback work introduced these commits:
- `399be09efacca68da09be3058db275bfc199fc90`: initial independent Media3 playback composable.
- `130ee297128ac240de35224ccfb47f57b8451b96`: Media3 ExoPlayer/UI dependencies.
- `c1128680b678728e14d56eb2d82557aa6843cca8`: local analysis preview wired to independent video player.
- `f416044fff771b0d57e93663de8739e21e2f3a97`: `videoUri` added to `AnalysisPreviewFrame`.
- `7b13f29c886dab0a3241b1070e6328cc68e86928`: local video preview attaches selected URI.
- `3aeaabc80d5a029138a13fa92c5c95e5f52cebba`: texture-view player layout added.
- `b0bd913661cc4f960008f1d57b569219d9602194`: final Media3 texture-view binding cleanup.

Run #412 (`33981996456`) failed at Android `compileDebugKotlin` because `AnalysisVideoPlayback.kt` omitted the Compose imports for `nativeCanvas` and `dp`. No runtime/media failure was observed; the failure was a source-import oversight.

Fixed in commit `459a499a11001b55d92308c2a9bbe6a61b7f6fe6` by adding:
`import androidx.compose.ui.graphics.nativeCanvas`
and
`import androidx.compose.ui.unit.dp`.

Run #413 (`33983045596`) then completed Debug APK build, instrumentation APK compilation, unit tests, Native C++ parity, Research Math, and both ESP32 firmware targets successfully, but Android lint failed with exactly one new `UnsafeOptInUsageError` for the unnecessary call `PlayerView.setShutterBackgroundColor(...)` in `AnalysisVideoPlayback.kt:85`.

Fixed in commit `b72a512cc9db17600affecd25b48966ebc5ad9e3e` by removing that unstable cosmetic API call. No lint baseline or project-wide suppression was added.

Run #415 (`33983483845`) from documentation commit `7aed48c4c22d426b75cf810574c97e7d0e6dbe4e` completed successfully across its listed verification jobs: Android Build & Test, instrumentation APK compilation, unit tests, Android lint, Native C++ parity, Research Math, and both ESP32 firmware targets. This result is valid for SHA `7aed48...` only and does not certify later code changes.

Run #420 (`33984686267`) from documentation commit `e786f30a0fa57029370d62bc3095240884aa3244` completed successfully across Android Build & Test, Native C++ parity, ESP32 firmware and Offline Research Math. The user then physically tested APK #420 and exposed a functional analysis-latency defect, which CI correctly did not detect.

## Non-negotiable honesty rules
No map integration unless explicitly requested; previous map integration caused UI/display problems and is intentionally excluded.

Never hide runtime/native failures behind broad catches. Never present fake OCR, fake speed, fake tracking IDs, metadata-only evidence, or unverified hardware support as finished capability.

The project owner requires this cumulative context document under `docs` to be updated during every material engineering cycle, recording changes, failures, fixes, CI truth, unresolved risks, and exact repository state required to resume work without reconstructing the prior conversation.
