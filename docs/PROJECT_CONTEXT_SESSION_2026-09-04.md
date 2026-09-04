# Project Context Append — 2026-09-04

This is the cumulative project handoff supplement for work performed on 2026-09-04 after the 2026-09-03 context snapshot. It must be read together with `docs/PROJECT_CONTEXT.md` and prior session append files.

## Verified CI baseline at start of this session
GitHub Actions Run #286 (`33786018566`) ran on exact commit `85e0073e7dd7a2b327f7e531c84bec294393f435` and completed successfully for all three jobs:
- Build & Test Android: assembleDebug, unit tests, Android lint, APK artifact upload — PASS.
- Native C++ Parity Vectors — PASS.
- Validate Offline Research Math — PASS.
This is the verified post-Run-#284 lint/API-compatibility baseline.

## Run #284 root cause and fix
Run #284 was not a parity failure. Android Lint failed because `LocalVideoFrameSource.kt` called `MediaMetadataRetriever.getFramesAtIndex()` (API 28) while the application minimum SDK is 26. The code had only a runtime boolean (`indexDecodeEnabled`) and Lint could not prove that the API call was guarded.

The fix was a direct runtime SDK guard immediately before the API-28 call. Android 26/27 now skip indexed decoding and remain on the timestamp-based fallback; Android 28+ may use indexed decoding. `minSdk=26` was retained. No lint baseline and no suppressing annotation were used. Run #286 verified this correction.

## Unified Analysis Session lifecycle hardening — 2026-09-04
`UnifiedAnalysisSession.kt` originally had overlapping resource-close ownership: `stop()` closed the source/runtime and the coroutine `finally` also closed them. The session now tracks `activeSourceClosed` and `activeCloseableClosed` so the session itself claims each resource once.

`stop()` claims resources under the mutex, closes the source before cancellation, cancels and joins the Job outside the mutex, then closes the runtime. The coroutine `finally` closes only resources that were not already claimed. The mutex is never held while waiting for `join()`, preserving the earlier deadlock fix.

A lifecycle regression test file `UnifiedAnalysisSessionTest.kt` was added with coverage for exactly-once closure, single active session admission, and reset-to-IDLE after stop. The test's cleanup initially used an invalid `SupervisorJob` context key; this was caught before a successful verification run and corrected to the standard `Job` key.

## FrameSource ownership boundary — 2026-09-04
A second lifecycle ambiguity was found outside the session itself: `AnalysisPipelineRunner` was also closing any `FrameSource` passed to it, while `UnifiedAnalysisSession` owned the same source. This created a real double-close path in the production Local/Live route even after the session-level flags were added.

The ownership contract is now explicit:
- `AnalysisPipelineRunner.run(FrameSource, ...)` does not close the supplied source; its caller owns that source.
- `ModularAnalysisEngine.analyze(MediaSource, ...)` creates a source through `FrameSourceFactory`, runs the pipeline, and closes that factory-created source in its own `finally`.
- `ModularAnalysisEngine.analyze(FrameSource, ...)` passes ownership through unchanged to the caller/session.

`ModularAnalysisEngineLifecycleTest.kt` verifies both sides of this boundary: a directly supplied source is not closed by the engine, while a factory-created source is closed exactly once by the `MediaSource` overload.

## Run #297 failure and correction — 2026-09-04
Run #297 (`docs: finalize 2026-09-04 cumulative context`) exposed two stale unit-test assertions after the ownership contract change:
1. `TrafficReliabilityTest.pipelineReportsFrameGapsAndProducesMeasuredSpeed` still expected `AnalysisPipelineRunner` to close a directly supplied `FrameSource`. That expectation was changed so the test verifies the runner leaves the source open, then explicitly closes it as the caller.
2. `ModularAnalysisEngineLifecycleTest.factoryCreatedFrameSourceIsClosedByMediaSourceOverload` compared the result source ID to the logical `MediaSource` ID. The production runner correctly reports `source.source` from the concrete factory-created `FrameSource`; therefore the assertion was corrected to the fixture's concrete source ID (`counting-source`) while retaining the important exactly-once close assertion.

These are test-contract corrections, not production-behavior changes. The production ownership boundary remains unchanged and is now tested consistently.

## Post-Run-297 lifecycle hardening — 2026-09-04
Added `factoryCreatedFrameSourceIsClosedWhenPipelineFails` to `ModularAnalysisEngineLifecycleTest.kt`. This exercises the `ModularAnalysisEngine.analyze(MediaSource, ...)` failure path with a detector that throws and verifies the factory-created source is still closed exactly once. This protects the cleanup contract against regression when analysis aborts with an exception.

## Important architectural observation
The current Local and Live ViewModels each instantiate their own `UnifiedAnalysisSession`. They share the same lifecycle abstraction and pipeline contract, but this is not yet a single app-level session instance. A future app-level host/coordinator is still required if Dashboard/Radar/Live/Local must observe exactly one process-wide analysis execution instead of one session per ViewModel.

Do not describe the current implementation as a process-wide singleton session.

## Exact PTS decoder hardening — 2026-09-04
`ExactPtsVideoFrameSource.kt` uses `MediaExtractor + MediaCodec + ImageReader` and timestamps returned frames with `MediaCodec.BufferInfo.presentationTimeUs`.

The decoder path was hardened in two steps:
1. Replace bounded polling of `ImageReader.acquireNextImage()` with an `OnImageAvailableListener` feeding a bounded image queue. A decoded output that does not produce a rendered image within the bounded wait now causes an explicit error (except EOS), rather than silently dropping the decoded frame.
2. Validate the `ImageReader.Image.timestamp` against the decoder presentation timestamp when both are non-zero. The decoder PTS remains the authoritative `AnalysisFrame.timestampMs`; the image timestamp is used only as a consistency check. A mismatch beyond 2 ms now fails the source explicitly.

The implementation still retains rotation metadata and converts RGBA output to Bitmap. It must still be tested on the user's physical phone with representative H.264/H.265 material, especially frame count, frame loss, crop/output format, rotation, memory pressure, and sustained throughput. Build success does not constitute device validation.

Current Android documentation confirms that rendered codec output is associated with a presentation time when released to a Surface, while image timestamps expose a nanosecond timestamp for the resulting image. This is why the implementation treats decoder PTS as authoritative and image timestamp as a consistency check. citeturn122537search3turn122537search5turn122537search4

## Native parity state
Run #286 confirmed the existing native speed/parity vectors and Python research tests are green. The host native parity harness already includes:
- linear metric motion;
- diagonal metric motion;
- variable timestamp spacing;
- homography projection.

The next parity improvement is to move from duplicated hand-written goldens toward a shared/generated deterministic fixture source so Kotlin and native tests consume exactly the same inputs and expected outputs.

## Real-device crash found during first Local Analysis test — 2026-09-04
The first practical Android test exposed a process-level crash when the user selected a local car video and pressed `Run Real Analysis`. This is a real field defect; the earlier CI state must not be described as device-ready.

Repository inspection identified a high-risk startup path: `AnalysisRuntimeFactory.createDetector()` previously constructed `LiteRtObjectDetector` with `Accelerator.GPU` first and attempted CPU fallback only via Kotlin `catch (Throwable)`. GPU delegate initialization is a native operation; a vendor/driver/native abort is not necessarily catchable by Kotlin, so the app can terminate instead of reaching the fallback.

The Local Analysis ViewModel also constructed `NativeGroundProjector` and `NativeFirstSpeedEstimator` for the first real-analysis path. Those JNI-backed components were not needed to validate basic detection/tracking/radar behavior and had no physical-device smoke coverage yet.

### Immediate stabilization changes
1. `AnalysisRuntimeFactory.kt` now uses `Accelerator.CPU` as the production-safe default for analysis. It no longer probes GPU implicitly. GPU acceleration is deferred until a real-device instrumented smoke test proves it safe on the target devices.
2. `LocalAnalysisViewModel.kt` now uses `KotlinGroundProjector` and `KotlinSpeedEstimatorBackend` for the Local Analysis Lab path, keeping the first device validation independent of JNI/native loading. The C++ implementation remains protected by the existing native parity tests and can be reintroduced behind a separately validated runtime path later.

These changes intentionally prioritize process survival and deterministic validation over acceleration for the first field test. They do not remove the native code from the repository.

The exact cause of the observed crash cannot be declared 100% proven without the phone's logcat/stack trace; however, the GPU delegate startup path is the strongest repository-grounded suspect because it executes immediately when `Run Real Analysis` is pressed and can fail outside Kotlin exception handling. The stabilization changes remove that risk from the Local Lab path rather than masking a failure after it occurs.

## Reliability policy reinforced
- Build-green does not imply device-green.
- CI must always be interpreted against the exact commit SHA under review.
- Do not weaken tolerances or add baselines/suppressions merely to make CI green.
- Physical speed remains gated by validated calibration plus exact timestamp provenance when required.
- Native speed remains preferred through the native-first adapter only after result validation; Kotlin remains the reference fallback.
- The exact PTS path must not be promoted to a measurement-grade claim until real-device media compatibility is validated.
- Resource ownership must be explicit: callers own supplied `FrameSource` instances; creators own sources they create.
- JNI/native and GPU delegate paths require explicit device smoke testing before being treated as production-safe startup dependencies.

## Current verification state
The last fully verified green run before the crash fix was Run #302 (`33876824474`) on exact commit `e77a0493616b1faaa17ee46bc8b7b9aa1a87a5ef`, with Android build/test/lint, native parity, and Python research validation all green. That run did not contain the subsequent crash-stabilization commits and did not include physical-device execution.

Crash-stabilization commits currently on `main`:
- `9a10d728e54ba87dda9d06f31cea6d2d8c880267` — CPU-safe analysis runtime default.
- `a1d297824f14448b95ab796722c476d86f70e13d` — Local Analysis Lab uses Kotlin geometry/speed path instead of JNI for device validation.

A new exact-HEAD CI run is required for these changes before another APK is treated as test-ready.

## Current stopping point
The immediate goal after the field crash is:
1. verify CI on the exact new `main` HEAD;
2. install that new APK on the same physical phone;
3. repeat `Run Real Analysis` with the same car video;
4. record whether the app survives, whether detections/tracks appear, and whether any explicit analysis error is shown instead of process termination;
5. only after this stability gate continue with GPU/JNI device validation and deeper tracking/speed benchmarks.
