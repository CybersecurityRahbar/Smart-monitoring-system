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

## Reliability policy reinforced
- Build-green does not imply device-green.
- CI must always be interpreted against the exact commit SHA under review.
- Do not weaken tolerances or add baselines/suppressions merely to make CI green.
- Physical speed remains gated by validated calibration plus exact timestamp provenance when required.
- Native speed remains preferred through the native-first adapter only after result validation; Kotlin remains the reference fallback.
- The exact PTS path must not be promoted to a measurement-grade claim until real-device media compatibility is validated.
- Resource ownership must be explicit: callers own supplied `FrameSource` instances; creators own sources they create.

## Current verification state
Run #296 (`33875804871`) corresponded to commit `fe9062724479aec679a4632e9639d0d194df27a3`, with Native C++ Parity and Python tests passing while Android was still in progress. It was superseded by later ownership/test changes.

Run #297 on the prior documentation commit failed in Android unit tests with exactly two stale assertions. Those assertions were corrected in commits `9b61fb220c43e621e9dd431e45f29c901f709b1d` and `56fd7c206458e7bd116c5f09a180ebafb5bcb7a6`. A follow-up failure-path lifecycle test was added in `4edb21cb6346e1bd5e2204a7d3cd604c32f1a389`.

A documentation synchronization commit after the test corrections is commit `4948a7f267c5bb3ec5d4c6d3523fb2a1742991a0`, which triggered Run #300 (`33876771482`). At the latest inspection Run #300 was still pending. The subsequent failure-path test commit `4edb21cb6346e1bd5e2204a7d3cd604c32f1a389` is newer and therefore requires a newer exact-HEAD CI run before the state can be called green.

## Current stopping point
Latest code areas hardened in this session:
- API-26/27 guard for indexed MediaMetadataRetriever use;
- unified session exactly-once resource closure;
- explicit FrameSource ownership boundary between session, engine and runner;
- lifecycle and ownership regression tests;
- exact-PTS decoder ImageReader queue and PTS/image timestamp consistency validation;
- corrected stale unit-test expectations exposed by Run #297;
- explicit cleanup verification for factory-created sources on analysis failure.

The next engineering gate is a fully green CI run on the exact current `main` HEAD, followed by real-device Exact PTS validation and only then deeper tracking/speed benchmark work.
