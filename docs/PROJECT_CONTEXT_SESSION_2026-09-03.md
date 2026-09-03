# Project Context Append — 2026-09-03

This file is an append-only supplement to `docs/PROJECT_CONTEXT.md` for the integrated radar/live-analysis work completed after the main context snapshot update. It is intentionally separate so the authoritative cumulative document is not destructively rewritten through a contents API update.

## User goal for this session
After confirming the previous Android build was green, the user requested continued implementation of the integrated traffic-monitoring system: stronger/native C++ algorithms, richer radar and tracking, a full-screen Local Analysis video with a clear exit action, removal of the phone-video slow-motion symptom, and use of `vietanhlee/Smart-Traffic-Monitoring-System` only as an engineering/design reference.

## Reference project findings
The reference repository was inspected through its README/tree/frontend. It demonstrates a video-first traffic-monitoring UI and a YOLO/ByteTrack-oriented monitoring architecture. Its linked demo video asset could not be fetched directly from this execution environment, so there is no claim of frame-by-frame demo inspection. No source code from the reference repository was copied into Smart-monitoring-system and no WebRTC/backend architecture was introduced solely because the reference project uses one.

## Local video performance
`LocalVideoFrameSource.kt` was evolved from fixed 100 ms timestamp sampling to API-28+ indexed decoding, then to small consecutive batches using `getFramesAtIndex()` where frame-count metadata is available. Android's current API documentation explicitly recommends `getFramesAtIndex()` for multiple consecutive frames. This reduces repeated decoder/index lookup overhead and removes the known artificial 100 ms sampling cadence. The source remains `REQUESTED_SAMPLE_TIME`; indexed frame position is not asserted to be exact decoded PTS.

Commit sequence included:
- `2a336c9bcfbe9e8c6a73225748bd28fe9d00a7e`: indexed sequential frame decoding.
- `736bd038b7eb98f9f872626f933aa411e747031c`: codec-safe fallback to timestamp sampling without resetting fallback timeline.
- `c5a82ec96fd569a5f2f79efa40aedb9881609848`: consecutive batch decoding with batch size 4 using `getFramesAtIndex()` and safe timestamp fallback.

Physical-speed measurement remains blocked for local video while timestamp precision is not proven exact PTS.

## Local Analysis presentation
`AnalysisRadarPreview.kt` now provides:
- a true full-window `Dialog` analysis mode;
- an explicit top-right close button;
- `ContentScale.Fit` image presentation with overlay scale/offset calculated from the same fitted content rectangle, preventing bounding-box drift when the view is enlarged or aspect ratio differs;
- a radar visualization with the last 12 track observations rendered as motion trails;
- explicit visual-radar vs metric-radar state.

A follow-up cleanup removed an initial size-dependent radar label overlay because its hard-coded dimensions could misplace labels on different screen sizes.

## Main Radar integration
`IntelligentRadarScreen.kt` was changed from a mostly static shell (zero tracks/blank speed/placeholder video) to a real local-video radar session using `LocalAnalysisViewModel`, which means the same `AnalysisEngine`, LiteRT detector, ByteTrack, geometry and native speed path are reused rather than introducing a second radar algorithm.

The main Radar screen now:
- selects a local traffic video;
- starts the real analysis session;
- reuses the same `AnalysisRadarPreview` renderer;
- filters the actual track results by vehicle class, minimum speed, confidence and a simple forward-direction gate;
- displays actual track count and maximum measured speed when available.

This is local-video radar functionality. ESP32 live radar is implemented separately below.

## Native/C++ architecture
A replaceable `GroundProjector` was introduced and the production local analysis path now uses `NativeGroundProjector.kt`, calling the already-compiled C++ homography projection through JNI while keeping the Kotlin implementation as the domain reference.

A replaceable `SpeedEstimatorBackend` was introduced. `NativeFirstSpeedEstimator.kt` calls the C++ robust speed implementation first and falls back to the Kotlin reference estimator when the native call, linker, output shape or numerical validation fails. The speed backend name is recorded in `AnalysisMetrics.speedEstimatorBackend` so provenance is visible in results.

The current native JNI speed contract returns:
`[metersPerSecond, confidence, errorKmh, inlierSamples]`.
Velocity components and detailed residuals are not currently returned by JNI; direction is therefore derived from the first/last valid metric points by the Kotlin adapter. Formal Kotlin/native numerical parity tests remain required before claiming equivalence.

## LiteRT accelerator path
`LocalAnalysisViewModel.kt` no longer pins all local inference to CPU. It tries LiteRT `Accelerator.GPU` first and explicitly constructs a CPU detector when GPU construction fails. The selected accelerator is visible in the analysis state and executed metrics.

A new `AnalysisRuntimeFactory.kt` centralizes detector construction and GPU→CPU selection so local and future live sessions share the same runtime policy.

## Live ESP32/MJPEG analysis
`MjpegFrameSource.kt` was added as a real `FrameSource` around the existing `MjpegStreamClient`.
Its queue is deliberately conflated so stale frames are dropped rather than allowing latency to grow when inference is slower than the camera. A producer sequence number lets the consumer detect and count skipped live frames.
Arrival timestamps use `SystemClock.elapsedRealtime()` only for ordering; the source remains `FrameTimestampPrecision.UNKNOWN`, so live MJPEG time cannot unlock measurement-grade physical speed.

`LiveAnalysisViewModel.kt` now creates a real session:
`ESP32 MJPEG → MjpegFrameSource → LiteRT → ByteTrack → Native geometry/speed → AnalysisPreviewObserver`.
It uses the same runtime factory and the same native/Kotlin backends as Local Analysis.

`LiveCameraScreen.kt` now:
- can start/stop live analysis using the shared engine;
- shows the actual analysis preview/radar when active;
- keeps the raw MJPEG stream path for ordinary viewing when analysis is stopped;
- makes the Capture button call the configured `/capture` endpoint and decode the returned JPEG instead of doing nothing;
- leaves Camera Control as an explicit unavailable state because the current ESP32 endpoint contract does not define a control API.

The live analysis path deliberately stops the raw stream job before starting the analyzed stream, avoiding two simultaneous MJPEG connections to the ESP32 during analysis.

## Reliability decisions retained
- No mock or fake radar animation was introduced.
- A reported speed must still pass calibration and timestamp gates.
- Native failures do not silently produce zero or fabricated measurements; the speed adapter falls back to the tested Kotlin reference implementation.
- GPU selection is a performance optimization, not an accuracy claim.
- The user-visible playback speed and processing speed are separate concepts; removing the fixed sample interval improves source traversal, but real-device detector latency can still make processed preview slower than realtime.

## CI status relevant to this append
The last fully green baseline before the feature sequence was Run #225 on commit `28591789cf9bfaf4da656e06ec303c6d227e5c4f`. The feature sequence generated new push-triggered Android CI runs for each commit. At the time this append was written, the latest run for the current integrated work had not yet returned a completed result from the GitHub Actions API, so the feature branch state must not be called green until the exact HEAD is verified.

## Exact feature commits after the green baseline
- `2a336c9...` indexed local video.
- `736bd038...` safe video fallback.
- `9e8692c...` immersive preview + radar trails.
- `143f1475...` injectable ground projection.
- `ce73f998...` native ground projector.
- `53329d00...` pipeline injection.
- `7d542a19...` injectable speed backend.
- `48f996b3...` native-first speed adapter.
- `ec66de51...` GPU-first inference + native geometry/speed wiring.
- `81f088e7...` radar overlay cleanup.
- `dfb54ef...` main Radar uses real analysis engine.
- `f596286...` bounded MJPEG FrameSource.
- `c5a82ec...` batched local sequential frame decoding.
- `d1bdda6...` centralized detector runtime factory.
- `f872ad7...` live analysis ViewModel.
- `6062cbd...` Live Camera real analysis/capture controls.
- `dff7487...` explicit live MJPEG dropped-frame sequence accounting.

## Next hard gates
1. Verify the exact current HEAD through Android build, unit tests and lint, plus Python tests.
2. Run on the user's real phone with a representative phone-recorded traffic clip. Record source FPS, processed FPS, detector median/P95, frame gaps and memory/thermal observations.
3. Compare GPU vs CPU on the same physical device and same frames.
4. Add exact PTS-preserving video decoding before enabling physical-speed measurement on local video.
5. Establish Kotlin/native numerical parity for homography and robust speed.
6. Add TrackEval HOTA/IDF1/MOTA evaluation and improve tracking based on measured ID switches/fragmentation.
7. Wire ESP32 live analysis into a shared session/state layer so Dashboard/Radar/Live screens can observe one running analysis session rather than creating independent jobs.
8. Add native/OpenCV calibration and later research keypoint/dynamic-homography, optical flow, segmentation and learned Re-ID only after benchmarks justify them.
9. Implement real plate detection/OCR, rule thresholds, evidence image/crop persistence, alerts and reports.
