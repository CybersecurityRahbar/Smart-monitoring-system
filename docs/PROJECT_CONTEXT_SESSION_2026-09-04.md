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

## First real-device crash during Local Analysis — root-cause investigation
The first practical Android test exposed a process-level crash when the user selected a local car video and pressed `Run Real Analysis`. The earlier CI state therefore did not constitute device readiness.

The startup path was inspected in execution order. `LocalAnalysisViewModel.run()` first calls `AnalysisRuntimeFactory.createDetector()`, which constructs `LiteRtObjectDetector`; only after successful detector construction does it construct the video `FrameSource`. Therefore an immediate process death on button press is most consistent with a native runtime failure during detector construction, or less likely a native media-construction failure after it.

The original runtime factory attempted `Accelerator.GPU` first and relied on Kotlin `catch(Throwable)` for CPU fallback. That is not a safe containment boundary for a native delegate/driver abort. This was removed.

A second risk was the custom `ExactPtsVideoFrameSource`: it creates a full-resolution `ImageReader` with three RGBA buffers and uses `MediaCodec`/Surface output. That path is not yet device-validated and can create substantial gralloc/native memory pressure depending on video dimensions and codec.

## Stabilization revision after the crash
Two changes were applied first:
1. `AnalysisRuntimeFactory.kt` now uses CPU as the default accelerator and no longer implicitly probes GPU.
2. `LocalAnalysisViewModel.kt` now uses `KotlinGroundProjector` and `KotlinSpeedEstimatorBackend` for the Local Lab path so the first field test is independent of JNI/native loading.

The user retested and the application still died immediately. This means the GPU delegate theory alone was insufficient; the investigation must continue into LiteRT model initialization and the media path rather than assuming the first mitigation fixed the root cause.

## Definitive model/runtime findings
The bundled model is the official Ultralytics `yolo26n` Android w8a32 asset lineage. Current Ultralytics documentation states that Android LiteRT assets use `nms=False` and `end2end=False`, producing the traditional `(1,84,8400)` output for detection; the repository registry expects exactly that output shape and the parser implements the corresponding `xywh + class-score` decoding. citeturn207917search1turn458690search0

The repository dependency was `com.google.ai.edge.litert:litert:2.2.0`. The current Ultralytics Android project source uses `com.google.ai.edge.litert:litert:2.1.5`, despite LiteRT 2.2.0 now existing as a public release. This makes 2.2.0 an unnecessary runtime delta from the known Ultralytics Android baseline for this exact model family and was treated as a compatibility risk rather than assumed safe. citeturn207917search2turn233446search5

## Second stabilization revision — 2026-09-04
Applied commit `4a22f6e6fb19347b0094668b7e5b5f9cbd457a20`:
- pin Android LiteRT dependency from `2.2.0` to `2.1.5`, matching the published Ultralytics Android runtime baseline.

Applied commit `ef0172235a88337d15bbc1935f5f57a304b7bb0b`:
- Local Analysis Lab no longer uses `ExactPtsVideoFrameSource` for ordinary local video tests.
- Local Lab now uses the existing `LocalVideoFrameSource` for the first on-device detector/tracker validation.
- The first field path disables the optional deterministic appearance association cue to isolate the core detector/tracker path.
- Local Lab explicitly reports a RUNNING state before runtime initialization instead of remaining visually idle while expensive/native setup occurs.

This intentionally separates three device-validation layers:
1. basic detector/tracker stability;
2. exact-PTS media compatibility;
3. JNI/native and GPU acceleration.
They must not all fail at once during the first field test.

## Other full-review findings to address before calling the system production-ready
1. CI is build/unit/lint/parity validation, not physical-device validation. The Android job does not prove LiteRT model load, codec compatibility, native library loading, real preview rendering, thermal behavior, or long-run memory stability on a phone.
2. `LocalAnalysisScreen` still contains a stale readiness label describing inference as `Auto: GPU → CPU` even though the current Local Lab runtime is intentionally CPU-only; this is a diagnostics/UI-contract defect and should be corrected.
3. `AnalysisPipelineRunner.metrics.decodeFps` currently reports `source.source.frameRate`, which is source metadata/nominal FPS, not measured decoder throughput. Actual processing FPS is reported separately. The metric name is therefore semantically misleading and should be changed or accompanied by an explicit nominal-vs-measured distinction.
4. `AnalysisPipelineRunner` can report every track as a rejected speed estimate when speed is globally blocked by calibration/timestamp policy. That conflates “not eligible to attempt speed” with “attempted but failed quality estimation” and needs separate counters/reasons.
5. Physical speed currently gates on calibration acceptance and exact timestamps but does not yet require explicit track continuity/observation quality. Occlusion gaps are represented indirectly (`wasOccluded`) rather than as a first-class temporal continuity metric, and the robust estimator can bridge some missing observations inside its pair-gap limit. A track-quality gate is still required before measurement-grade speed claims.
6. `ExactPtsVideoFrameSource` remains unvalidated on representative physical devices for H.264/H.265, frame loss, crop, rotation, image timestamps, memory, and sustained throughput. It must stay out of the ordinary Local Lab until that validation exists.
7. `NativeTrafficCore` uses an unguarded `System.loadLibrary("smarttraffic_native")`; JNI should remain behind an explicitly validated runtime boundary until device smoke tests pass for each supported ABI/device class.
8. Plate recognition and OCR are not actually installed. The UI can expose a Plate/OCR test mode, but the current runtime explicitly disables plate recognition; this must not be presented as implemented recognition.
9. Evidence storage currently stores event metadata only; it does not yet store actual frame/crop image bytes. Event evidence is therefore incomplete for a forensic use case.
10. The current Local and Live analysis sessions are ViewModel-scoped, not process-wide. Cross-screen observation of exactly one persistent analysis execution still needs an app-level coordinator/host.

## Current verification state after crash stabilization
Run #302 (`33876824474`) is the last fully verified green CI run on exact commit `e77a0493616b1faaa17ee46bc8b7b9aa1a87a5ef`. That run predates the current crash-stabilization revision.

The new commits are:
- `9a10d728e54ba87dda9d06f31cea6d2d8c880267` — CPU-safe runtime default.
- `a1d297824f14448b95ab796722c476d86f70e13d` — Kotlin geometry/speed for Local Lab.
- `bda0e06ee02a7f5c6c6785e863b994c3f4259a86` — crash context synchronization.
- `4a22f6e6fb19347b0094668b7e5b5f9cbd457a20` — LiteRT 2.1.5 pin.
- `ef0172235a88337d15bbc1935f5f57a304b7bb0b` — isolate Local Lab from Exact PTS and appearance path.

The latest field-test revision requires a new CI verification and then installation of that exact APK before another device run.

## Current stopping point
The next device experiment should use the smallest possible path: select a short, ordinary H.264 local video at standard resolution, press `Run Real Analysis`, and verify that the app survives and reports an explicit state/error instead of process death. If that revision still terminates the process, the next decisive artifact is the phone's Android crash/Logcat stack trace because a native SIGABRT/SIGSEGV cannot be inferred reliably from Kotlin control flow alone.

Only after basic detector/tracker stability is established should exact PTS decoding, JNI/native projection/speed, GPU acceleration, and then quantitative tracking/speed benchmarks be reintroduced one at a time.
