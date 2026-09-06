# Project Context Append — 2026-09-06

This append-only document records the full repository/context audit requested on 2026-09-06. It is to be read with `docs/PROJECT_CONTEXT.md`, `docs/PROJECT_CONTEXT_MASTER_PLAN_2026-09-04.md`, and prior session append files.

## Audit scope
Reviewed the available prior conversation context and the repository `CybersecurityRahbar/Smart-monitoring-system` at main HEAD `ef5ff48451203b24977bc130863a121fa0759452`. Reviewed the complete current `docs/` inventory and the contents of the major architecture, reliability, CI, local-lab, radar, master-context and session-context documents; inspected the current source tree and traced the active Android analysis path through model registry, LiteRT detector, frame sources, unified session, tracker, geometry/speed backends, evidence, live/local ViewModels, playback, JNI/C++, ESP32 firmware, and CI workflow. This audit is a state assessment, not a claim that every byte of every historical transcript attachment was manually retyped.

## What the preceding conversation was doing
The latest project work had moved from basic ESP32-CAM planning into an Android-first Smart Traffic analysis system. The immediate focus became stabilizing and validating the Local Analysis Lab after the user experienced an app process death when pressing Run Real Analysis on a local car video. The investigation first isolated GPU/native acceleration and then LiteRT/runtime compatibility, pinned LiteRT to 2.1.5, moved the Local Lab to the Kotlin detector/geometry/speed path, and disabled optional appearance association for the first field validation. Subsequent work continued with automatic speed gates, calibration-free speed estimation, synchronized analyze-then-replay video presentation, tracker hardening, native/Kotlin parity and evidence artifact persistence.

## Current architecture actually present
`Compose UI -> ViewModel/StateFlow -> application AnalysisHost -> UnifiedAnalysisSession -> ModularAnalysisEngine/AnalysisPipelineRunner -> FrameSource + LiteRT detector + ByteTrack + geometry + speed + rules/evidence`

The application currently has an `AnalysisHost` which owns an application-scoped `UnifiedAnalysisSession`. Local and Live ViewModels use this shared session in the current main branch, eliminating the older ViewModel-scoped session design documented in the 2026-09-04 historical file.

## Current model/runtime
- Model asset: `android/app/src/main/assets/models/yolo26n.tflite`
- SHA-256: `d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73`
- Size: 2,875,553 bytes
- Input: 640 NCHW RGB FP32 preparation
- Current registered output: classic `[1,84,8400]`, 80 COCO classes
- Traffic classes parsed: car, motorcycle, bus, truck
- Build verifies model SHA-256 before preBuild
- Current LiteRT dependency in `android/app/build.gradle.kts`: `2.1.5`
- `AnalysisRuntimeFactory` is intentionally CPU-only at present; GPU/native delegates are not probed implicitly because native delegate failure can terminate the process outside Kotlin exception containment.

## Local-video path: important current discrepancy
Historical 2026-09-04 context says ordinary Local Lab was isolated from `ExactPtsVideoFrameSource` and used `LocalVideoFrameSource`. The current main branch has since changed again: `LocalAnalysisViewModel` chooses `ExactPtsVideoFrameSource` whenever `effectiveConfig.useGroundPlane` is true, and `AnalysisConfig.useGroundPlane` defaults to true. Therefore current ordinary local video runs go through the exact-PTS MediaCodec/ImageReader source by default. There is no current try/catch fallback to `LocalVideoFrameSource` in that selection block.

`ExactPtsVideoFrameSource` uses MediaExtractor + MediaCodec + ImageReader, propagates `MediaCodec.BufferInfo.presentationTimeUs`, checks monotonic timestamps and checks rendered-image timestamp consistency. It remains a major physical-device compatibility gate and is not equivalent to field validation merely because it compiles.

`LocalVideoFrameSource` remains the API-28 indexed-decoding/fallback source, but its timestamp provenance is deliberately `REQUESTED_SAMPLE_TIME`, not exact PTS.

## Current Local Analysis presentation
Recorded local video uses analyze-then-replay. The pipeline finishes analysis first, then `AnalysisVideoPlayback` uses Media3/ExoPlayer at natural playback speed and interpolates bounding boxes from complete track history, with bounded 350 ms extrapolation. Boxes use a neon-green rendering; speed labels distinguish calibrated and calibration-free estimates. Preview updates are throttled by `maximumPreviewFps` rather than slowing analysis.

Potential synchronization point requiring validation: playback position is ExoPlayer timeline position, while track observations carry source presentation timestamps. The implementation assumes their time origins are compatible; a video whose first PTS is materially non-zero should be tested explicitly.

## Tracker
`ByteTrack` is ByteTrack-inspired rather than a byte-for-byte reference implementation. It uses high/low confidence association, class-aware gating, Hungarian assignment, Kalman prediction, short-horizon empirical velocity, acceleration bounding, reversal rejection, adaptive center-distance gating, optional deterministic appearance and bounded history. Track IDs are session-local monotonic IDs. HOTA/IDF1/MOTA/ID switches/fragmentation are not yet measured against labelled traffic ground truth.

## Speed system
Two distinct modes are implemented:
1. `CALIBRATED_GROUND_PLANE`: validated homography + authoritative timestamps + ground-plane coordinates + robust speed estimation and an automatic two-line timing gate when usable. This is the intended physical/metric path but is not yet enforcement-certified.
2. `CALIBRATION_FREE_ESTIMATE`: bottom-center image motion + dominant motion axis + class-dependent vehicle-width prior + per-interval local scale + robust median/MAD rejection. Priors are assumptions, not measured geometry. Current widths: motorcycle 0.85 m, car 1.80 m, van 2.00 m, truck 2.50 m, bus 2.55 m, unknown 1.90 m. Results are explicitly labelled calibration-free and carry broad uncertainty.

The current automatic gate is visual in uncalibrated mode and metric only in calibrated mode. Traffic-rule enforcement remains stricter than display of an approximate calibration-free speed.

## Native/C++ path
C++20 currently implements homography projection and robust metric speed math. JNI exposes seven result values: speed, confidence, error/uncertainty km/h, inlier sample count, velocity X, velocity Y and median position residual. `NativeFirstSpeedEstimator` uses native first and Kotlin fallback after validating all returned values. Live analysis currently uses `NativeGroundProjector` and `NativeFirstSpeedEstimator`; Local Lab deliberately uses Kotlin geometry/speed in the current code path.

## Evidence and reports
`FileEvidenceStore` is now a real bounded evidence vault: metadata + SHA-256-named JPEG frame/vehicle/plate artifacts, temporary-file writes, fsync, atomic replacement where supported, integrity checking, retention and orphan cleanup. Current Local Analysis code actually captures a bounded full frame and vehicle crop for requested events. A real plate crop still depends on an installed ANPR backend, which is not present. Exact live MJPEG event-frame evidence remains incomplete.

Persistent incident-report storage exists and is tested.

## ESP32 firmware
PlatformIO builds both AI-Thinker ESP32-CAM and ESP32-S3-N16R8 targets. Firmware provides `/stream`, `/capture`, `/status`, `/control`, STA then AP fallback, ~15 FPS VGA streaming, AI-Thinker flash control and quality control. The S3 pin map is explicitly marked board-revision dependent and still requires physical verification. Firmware compile success is not hardware validation.

## CI truth
The workflow currently runs Android debug build, instrumentation APK compilation, unit tests, lint, native C++ parity, offline Python research tests, and both ESP32 PlatformIO targets. The current `main` HEAD is a docs-only commit added on 2026-09-06 after the last recorded speed-cycle code commit. The available connector did not provide a current exact-HEAD successful workflow status; therefore this audit does not declare current HEAD green. The master context recorded Run #450 on speed SHA `8936c54` as pending. A new docs-only HEAD does not inherit certification from an earlier code SHA.

## Concrete code findings from this audit
1. `LocalVideoFrameSource.close()` returns immediately when `finished == true`. If EOF is reached naturally and sets `finished=true`, the subsequent close call may skip `MediaMetadataRetriever.release()`. This is a lifecycle/resource bug to fix with a separate `closed` flag or unconditional decoder release.
2. `LocalAnalysisViewModel` currently routes default `useGroundPlane=true` videos into `ExactPtsVideoFrameSource`, contradicting the historical 2026-09-04 “basic field validation uses LocalVideoFrameSource” safety isolation. This should be treated as an intentional-or-accidental regression decision and resolved before the next physical field test.
3. `AnalysisVideoPlayback` uses player-relative `currentPosition` against source observation timestamps. Test non-zero-start PTS material to guarantee overlay temporal alignment.
4. `AnalysisMetrics.decodeFps` in the current pipeline is computed from source-read elapsed time, while the older context described it as source nominal FPS. The metric semantics should be made unambiguous: nominal source FPS vs measured decode/read throughput.
5. Speed rejection bookkeeping still needs a clean distinction between “not eligible because physical speed policy is unavailable” and “eligible/attempted but estimator rejected the track.”
6. Exact PTS source still needs physical-device validation for H.264/H.265, frame loss, rotation, RGBA conversion, memory pressure and sustained throughput.
7. Native library loading is lazy, but device smoke validation is still required for every supported ABI/device class before native acceleration is treated as a release dependency.
8. `PlateRecognizer` is an interface/slot, not a production installed ANPR implementation. The UI must not invent plate text.
9. Tracking and speed accuracy are not yet backed by independent labelled traffic ground truth; current confidence is quality scoring, not probability and current error is an estimator uncertainty proxy, not measured ground-truth error.

## Development status
Current system maturity is best described as a substantial engineering prototype with a functioning local/live analysis architecture, real detector/tracker pipeline, real calibration subsystem, two speed modes, automatic radar/gate visualization, replay presentation, native hot paths, evidence persistence and CI coverage. It is not yet a validated traffic-enforcement system and is not yet field-certified on the target phone/camera combination.

## Remaining major work, ordered by dependency
1. Restore a trustworthy CI certification boundary for the latest code state and keep the exact commit SHA explicit.
2. Decide and fix the Local Lab source policy: basic field stability via `LocalVideoFrameSource` versus default exact-PTS source; if Exact PTS remains default, finish device validation before calling it stable.
3. Fix `LocalVideoFrameSource` resource-release behavior and add regression coverage.
4. Validate analyze-then-replay timestamp alignment using videos with non-zero/nontrivial PTS.
5. Measure real target-device throughput: detector median/P95, processing FPS, source lag, dropped frames, memory and thermal behavior.
6. Build labelled tracking ground truth and evaluate HOTA/IDF1/MOTA, ID switches and fragmentation; compare ByteTrack baseline with BoT-SORT/OC-SORT where justified.
7. Build independent speed ground truth and report MAE/MAPE, ±10/±20 km/h hit rates, failure rate and confidence calibration across perspective, lighting, class and occlusion.
8. Complete native/OpenCV homography/calibration and optical-flow work only after parity and benchmarks justify it.
9. Add an Android-deployable plate detector/OCR pipeline with temporal consensus and real evidence crops.
10. Complete traffic rules/alerts/report persistence and policy separation between approximate display values and physical enforcement events.
11. Validate ESP32 hardware/firmware with the exact purchased board revision, power source, camera, Wi-Fi conditions and long-run streaming.
12. Add advanced keypoints/dynamic homography/ReID/segmentation only after benchmark evidence shows a material benefit.

## Current stopping point for the next engineering conversation
The project should resume from code-state verification, not from architecture design. First reconcile the current LocalVideo vs ExactPts source-policy discrepancy, fix the discovered `LocalVideoFrameSource` release bug, then obtain a green exact-HEAD CI result and perform the next physical-device run with full crash/log evidence. Do not declare the system production-ready until device, media compatibility, stress and independent accuracy gates are passed.

## User request recorded
The user explicitly requested a comprehensive review of prior conversation context, all `docs`, project structure and code, and asked that this work be recorded cumulatively in the repository context. This file is the append-only record of that audit.