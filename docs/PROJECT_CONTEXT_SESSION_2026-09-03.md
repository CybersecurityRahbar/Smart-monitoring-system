# Project Context Append — 2026-09-03

This file is an append-only supplement to `docs/PROJECT_CONTEXT.md` for the integrated radar/live-analysis work completed after the main context snapshot update. It is intentionally separate so the authoritative cumulative document is not destructively rewritten through a contents API update.

## User goal for this session
After confirming the previous Android build was green, the user requested continued implementation of the integrated traffic-monitoring system: stronger/native C++ algorithms, richer radar and tracking, a full-screen Local Analysis video with a clear exit action, removal of the phone-video slow-motion symptom, and use of `vietanhlee/Smart-Traffic-Monitoring-System` only as an engineering/design reference.

## Reference project findings
The reference repository was inspected through its README/tree/frontend. It demonstrates a video-first traffic-monitoring UI and a YOLO/ByteTrack-oriented monitoring architecture. Its linked demo video asset could not be fetched directly from this execution environment, so there is no claim of frame-by-frame demo inspection. No source code from the reference repository was copied into Smart-monitoring-system and no WebRTC/backend architecture was introduced solely because the reference project uses one.

## Local video performance
`LocalVideoFrameSource.kt` was evolved from fixed 100 ms timestamp sampling to API-28+ indexed decoding. The source now avoids the artificial 100 ms cadence and retains an explicit `REQUESTED_SAMPLE_TIME` provenance label until exact decoded PTS is available.

## Local Analysis presentation
`AnalysisRadarPreview.kt` now provides:
- a true full-window `Dialog` analysis mode;
- an explicit top-right close button;
- `ContentScale.Fit` image presentation with overlay scale/offset calculated from the same fitted content rectangle, preventing bounding-box drift when the view is enlarged or aspect ratio differs;
- a radar visualization with the last 12 track observations rendered as motion trails;
- explicit visual-radar vs metric-radar state.

A follow-up cleanup removed an initial size-dependent radar label overlay because its hard-coded dimensions could misplace labels on different screen sizes.

## Main Radar integration
`IntelligentRadarScreen.kt` was changed from a mostly static shell to reuse the real Local Analysis engine path rather than introducing a second radar algorithm. The radar consumes real track results and displays the same preview/tracking information.

## Native/C++ architecture
A replaceable `GroundProjector` was introduced and the production local/live analysis path uses `NativeGroundProjector.kt` for homography projection through JNI/C++.

A replaceable `SpeedEstimatorBackend` was introduced. `NativeFirstSpeedEstimator.kt` calls the C++ robust speed implementation first and falls back to the Kotlin reference estimator when native loading or result validation fails. `AnalysisMetrics.speedEstimatorBackend` records provenance.

### Native result contract extension — 2026-09-03
The native C++ `SpeedResult` and JNI contract were extended from four values to seven:
`[metersPerSecond, confidence, uncertainty/errorKmh, inlierSamples, velocityXMps, velocityYMps, medianPositionResidualMeters]`.
`NativeFirstSpeedEstimator` now maps the complete result into `SpeedEstimate`, including velocity components, direction derived from those components, and trajectory residual.
This makes Kotlin/C++ parity testable on the same observable mathematical outputs rather than speed alone.

## LiteRT accelerator path
`AnalysisRuntimeFactory.kt` centralizes detector construction and tries LiteRT `Accelerator.GPU` first, with an explicit CPU fallback if GPU initialization is unsupported. Local and live sessions use the same factory. This is a performance policy, not an accuracy claim.

## Live ESP32/MJPEG analysis
`MjpegFrameSource.kt` is a real `FrameSource` around the existing `MjpegStreamClient`.
Its queue is deliberately conflated so stale frames are dropped rather than allowing latency to grow when inference is slower than the camera. Producer sequence numbers are used to count skipped live frames explicitly. Arrival timestamps use `SystemClock.elapsedRealtime()` only for ordering, so live MJPEG remains `UNKNOWN` timestamp precision and cannot unlock physical speed.

`LiveAnalysisViewModel.kt` and `LiveCameraScreen.kt` now use the same analysis engine path as Local Analysis. During live analysis the raw MJPEG viewer is stopped so there is not a second simultaneous stream connection; the analysis preview becomes the displayed video/radar surface. Capture uses the configured `/capture` endpoint. Camera control remains explicitly unavailable because the current ESP32 contract has no control endpoint.

## Unified Analysis Session — 2026-09-03
`UnifiedAnalysisSession.kt` is now the lifecycle boundary shared by Local and Live analysis.
It provides:
- `AnalysisSessionPhase`: IDLE, STARTING, RUNNING, COMPLETED, FAILED, STOPPED;
- `AnalysisSessionState`: phase, message, accelerator, latest preview and final result;
- one active analysis job at a time;
- explicit source/runtime ownership and cleanup;
- cancellation-safe stop behavior;
- execution on `Dispatchers.Default`, never on the Compose/UI dispatcher;
- preview publication into the same session state.

A deadlock risk was caught during review: an earlier `stop()` implementation held the session mutex while joining the analysis job, but the job's `finally` also needed the mutex. This was corrected so cancellation/close/join occur outside the mutex and state cleanup happens afterward.

`LocalAnalysisViewModel` now creates its decoder/runtime/engine and starts them through `UnifiedAnalysisSession` rather than owning a parallel analysis Job itself.
`LiveAnalysisViewModel` was likewise moved onto the same session abstraction.

## Exact PTS video source — 2026-09-03
`ExactPtsVideoFrameSource.kt` was added using `MediaExtractor + MediaCodec + ImageReader`.
It decodes the video sequentially and uses `MediaCodec.BufferInfo.presentationTimeUs` as the frame timestamp. The source is marked `FrameTimestampPrecision.EXACT_SOURCE_CLOCK`, and frame dimension/rotation metadata are retained.

`LocalAnalysisViewModel` now attempts `ExactPtsVideoFrameSource` first for local video. If codec initialization fails, it explicitly falls back to `LocalVideoFrameSource`; the fallback remains `REQUESTED_SAMPLE_TIME`, so the physical-speed gate still blocks measurement-grade speed. No exact-timestamp claim is made for the fallback.

Android's current Media3/MediaCodec documentation confirms that decoded output buffers carry an explicit presentation timestamp, which is the temporal field used by this implementation. citeturn554474search0turn554474search1

The exact PTS source has not yet been validated on the user's physical phone across all codecs; real-device media compatibility remains a hard gate. If the Android CI build reports codec/API compilation problems, the source must be corrected before enabling it broadly.

## Tracker prediction/recovery hardening — 2026-09-03
`ByteTrack.kt` was strengthened while preserving the existing ByteTrack-inspired high/low association design.
New behavior:
- empirical velocity prediction remains short-horizon and bounded;
- actual timestamp `dt` determines the prediction interval;
- observed velocity changes are constrained by a pixel-acceleration prior;
- a strong sudden direction reversal is treated as an unreliable empirical cue so the Kalman prediction remains authoritative;
- center-distance gating adapts modestly with the prediction time gap and target scale, but remains finite and conservative;
- existing class-aware gating, IoU association, Hungarian assignment and low-confidence recovery remain intact.

New regression coverage exercises variable timestamp intervals, rejects a large direction reversal, and preserves the existing short bounded 0→8→55 px recovery while rejecting unbounded jumps.

## Native parity verification — 2026-09-03
A standalone host-side test harness was added under `tools/native-parity/` with a CMake/CTest build. The CI workflow now runs this target independently of Android.
Golden cases include:
- linear metric motion;
- diagonal metric motion;
- variable timestamp spacing;
- homography projection.

The first parity run exposed an error in the test expectation rather than native code: the correct confidence for the 0–700 ms linear fixture is `0.9025`, not `0.6525`; the variable-duration fixture is `0.90625`. The goldens were corrected rather than weakening the tolerance.
A mirrored Android JUnit fixture `RobustSpeedParityTest.kt` checks the Kotlin estimator against the same mathematical goldens.

The native parity CI harness successfully compiles `traffic_core.cpp`; the first execution failed only because the initially written confidence goldens were mathematically wrong. The corrected vectors are now committed.

## Build failure and fixes — 2026-09-03
The user's provided Run #258 failed at `compileDebugKotlin` with four errors:
1. `MjpegFrameSource.kt` used `throw _` inside `catch`, which Kotlin rejects because `_` cannot be referenced as an exception variable.
2–4. `AppNavHost.kt` passed `onOpenRadar`, `onOpenLive`, and `onOpenAnalysis` to `DashboardScreen`, but the screen signature did not accept them.

These were fixed by naming the caught `CancellationException` and extending `DashboardScreen` with the missing callbacks/quick actions.

The subsequent native parity workflow then exposed only the incorrect golden confidence values described above.

## Current CI interpretation
Only a CI run whose `head_sha` exactly equals the current `main` commit counts as verification for that state. Earlier green runs such as #225 (`28591789...`) are historical baselines only. The workflow is now split into Android build/tests/lint, native parity, and Python research tests.

At the latest inspected point, Run #279 (`33783420003`) was pending for commit `7a277c329bf5511d5a51278d7cb6e86c2af8fea5`; later feature commits were still being written, so no final green claim is made here.

## Reliability decisions retained
- No mock/fake radar animation is used.
- Physical speed requires validated calibration and exact timestamps when configured.
- Native failures may fall back to the Kotlin reference estimator, and backend provenance is retained.
- Live MJPEG is optimized for low latency by dropping stale frames; this does not make its timestamps suitable for measurement-grade speed.
- Video playback traversal and AI processing throughput are separate metrics; no artificial sleep is used to make them look synchronized.
- The reference repository is an inspiration source, not a source of copied code.

## Next hard gates
1. Get a green Android build/tests/lint on the exact current HEAD after the unified session, exact PTS, tracker and native-result-contract changes.
2. Validate `ExactPtsVideoFrameSource` on the user's actual phone with representative H.264/H.265 phone videos, checking timestamps, rotation, frame count and memory.
3. Run Local Analysis with a real traffic clip and compare source FPS, processing FPS, inference latency, dropped frames and visual smoothness.
4. Compare native and Kotlin speed outputs over randomized deterministic fixtures with a defined numerical tolerance; then treat native as the preferred production backend.
5. Add native homography parity vectors and, after parity, move calibration SVD/RANSAC into native/OpenCV.
6. Build a labeled tracking benchmark with HOTA/IDF1/MOTA, ID switches and fragmentation, then tune tracker parameters from measured data.
7. Extend the unified session to selected calibration profile/runtime settings and eventually a single app-level analysis session observable by Dashboard/Radar/Live without duplicate ViewModel jobs.
8. Add real vehicle keypoints/dynamic homography, optical flow, segmentation and learned Re-ID only after benchmark evidence justifies their cost.
9. Complete plate detector/OCR, traffic-rule thresholds, evidence image/crop persistence, alert feed, reports persistence, and final ESP32 firmware/hardware validation.
