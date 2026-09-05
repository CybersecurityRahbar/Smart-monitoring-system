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

Measurement-grade speed requires validated calibration, authoritative timestamps, sufficient observations and duration, temporal continuity, bounded gaps, reliable ground-plane projection, acceptable trajectory residual, plausibility, and adequate track quality. Rejection semantics distinguish at least:
`NOT_ELIGIBLE`, `CALIBRATION_INVALID`, `TIMESTAMP_INVALID`, `INSUFFICIENT_DATA`, `DISCONTINUOUS_TRACK`, `GEOMETRY_QUALITY_FAILURE`, `ROBUST_ESTIMATOR_REJECTION`, `PLAUSIBILITY_REJECTION`, `INTERNAL_ERROR`.

`ExactPtsVideoFrameSource` is implemented using MediaExtractor/MediaCodec/ImageReader with decoder PTS propagation, monotonic timestamp checks and bounded image backpressure. It is not physically certified yet. Ordinary `LocalVideoFrameSource` uses requested sample time and is not measurement-grade.

Live MJPEG uses `LOCAL_MONOTONIC_ARRIVAL` timestamps, so physical live speed remains blocked until authoritative source timing and calibration are available.

## Frame sources
`FrameSource` exposes `droppedFrameCount` as a property.

`MjpegFrameSource` owns its producer lifecycle independently of a ViewModel scope, retains one latest pending frame, recycles replaced bitmaps, counts source-level dropped frames, propagates producer failures, and closes idempotently.

`LocalImageFrameSource` and local video sources have bounded ownership/cleanup. Preview rendering must never determine analysis throughput.

## Local laboratory playback architecture — 2026-09-05
The previous local-lab video symptom was traced to an architectural coupling: `LocalVideoFrameSource.nextFrame()` feeds the detector/tracker pipeline sequentially, and the UI preview was receiving decoded bitmaps from that same analysis-driven stream. Therefore, when CPU inference was slower than the video's nominal FPS, the displayed video advanced at analysis speed instead of the video's natural playback clock. This was confirmed by repository inspection: the pipeline calls `source.nextFrame()`, runs detector/tracker work, then emits the preview; there was no independent playback clock for the displayed video.

This is not fixed by reducing detector quality or skipping analysis frames. The required architecture is to separate media presentation from AI processing. Web research corroborates this design: Android Media3/ExoPlayer provides `PlayerView` for media playback and supports explicit `surface_type` selection including `texture_view`, while real-time YOLO implementations commonly separate inference and display so slow inference does not control playback. Official Android Media3 documentation was checked for the current 1.11.0 line and PlayerView/surface behavior; the realtime-yolov5-detection repository was used as a concrete corroborating architecture example.

Implementation added in the current pass:
- `androidx.media3:media3-exoplayer:1.11.0` and `androidx.media3:media3-ui:1.11.0` dependencies.
- `AnalysisVideoPlayback.kt` with an independent ExoPlayer presentation clock. The AI pipeline does not control video playback speed.
- `view_analysis_player.xml` using Media3 `PlayerView` with `surface_type="texture_view"` so the Compose/Canvas tracking overlay can render reliably above the video surface.
- `AnalysisPreviewFrame.videoUri` carries the exact selected local video source for playback.
- `LocalAnalysisViewModel` attaches the selected URI to video previews without changing detector/tracker configuration or lowering inference quality.
- The video overlay reads ExoPlayer `currentPosition` and interpolates bounding boxes between timestamped track observations; it uses short-horizon extrapolation when playback is slightly ahead, keeping the visual tracking motion smooth without altering the underlying tracking data.
- Vehicle tracking boxes in the visual preview use fluorescent green `#39FF14` with a 4px stroke. Labels remain white with a black shadow for readability.
- Image analysis remains on the existing bitmap preview path; only recorded-video previews use the independent player.

Important limitation of the architecture: an independent playback clock guarantees that video playback itself is not slowed by inference. Exact frame-by-frame radar synchronization on every device still depends on the analysis throughput being able to keep sufficiently close to the playback clock. No arbitrary detector quality reduction or inference frame skipping was introduced to manufacture synchronization. A production-grade physical device benchmark is still required.

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

Process correction recorded: material engineering changes must not be called ready after compilation alone. The affected files must be reviewed together, and the CI gate must include build, instrumentation compilation, unit tests, lint, native parity and ESP32 builds. The context document must be updated in the same material cycle with exact failure, root cause, fix and verification state.

## Current verification target
Current repository HEAD is `b72a512cc9db17600affecd25b48966ebc5ad9e3e`. A new CI run for that exact SHA is required because #413 failed only at lint and the correction was committed afterward. Do not issue a new APK-testing instruction until the current SHA has a verified green Android job.

Once green, physical testing should use a normal recorded traffic video in the local lab and verify independently: original video playback rate, fluorescent-green stable tracking boxes/IDs, analysis FPS/frame gaps, lifecycle stability, and absence of regression in image analysis. Do not treat the independent playback architecture as proof of exact synchronization until observed on a real device.

## Non-negotiable honesty rules
No map integration unless explicitly requested; previous map integration caused UI/display problems and is intentionally excluded.

Never hide runtime/native failures behind broad catches. Never present fake OCR, fake speed, fake tracking IDs, metadata-only evidence, or unverified hardware support as finished capability.

The project owner requires this cumulative context document under `docs` to be updated during every material engineering cycle, recording changes, failures, fixes, CI truth, unresolved risks, and exact repository state required to resume work without reconstructing the prior conversation.
