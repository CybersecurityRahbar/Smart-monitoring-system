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

The fix was a direct runtime SDK guard immediately before the API-28 call. Android 26/27 now skip indexed decoding and remain on the timestamp-based fallback; Android 28+ may use indexed decoding. `minSdk=26` was retained. No lint baseline and no suppressing annotation were used.

## Unified Analysis Session lifecycle hardening — 2026-09-04
`UnifiedAnalysisSession.kt` previously closed the source/runtime in `stop()` and again in the coroutine `finally` block. Although the current source implementations are intended to tolerate repeated close calls, this was an unnecessary ownership ambiguity.

The session now tracks two explicit lifecycle flags:
- `activeSourceClosed`
- `activeCloseableClosed`

`stop()` claims the resources under the mutex, closes the source before cancellation so blocking decoders/streams can be released, cancels and joins the analysis Job, then closes the runtime. The coroutine `finally` closes only resources that were not already claimed by the stop path. Natural completion/failure still closes unclaimed resources in `finally`.

The mutex is never held while waiting for the analysis Job to join. This preserves the deadlock fix from 2026-09-03.

## Lifecycle regression tests — 2026-09-04
Added `UnifiedAnalysisSessionTest.kt` with three tests:
1. `stopClosesSourceAndRuntimeExactlyOnce` proves a stopped session closes source and runtime once each and reaches `STOPPED`.
2. `onlyOneAnalysisCanBeActive` proves a second analysis cannot start while one is active and that the rejected source is not closed by the session.
3. `resetReturnsSessionToIdleAfterStop` proves reset is legal after stop and returns state to `IDLE`.

The test initially used an invalid `SupervisorJob` context lookup during cleanup; that was caught before CI and corrected to the standard `Job` context key.

## Important architectural observation
The current Local and Live ViewModels each instantiate their own `UnifiedAnalysisSession`. They share the same lifecycle abstraction and pipeline contract, but this is not yet a single app-level session instance. A future app-level host/coordinator is still required if Dashboard/Radar/Live/Local must observe exactly one process-wide analysis execution instead of one session per ViewModel.

Do not describe the current implementation as a process-wide singleton session.

## Exact PTS decoder review findings
`ExactPtsVideoFrameSource.kt` uses `MediaExtractor + MediaCodec + ImageReader` and timestamps returned frames with `MediaCodec.BufferInfo.presentationTimeUs`. It validates non-negative and monotonic presentation timestamps and preserves video rotation metadata.

The current source still needs physical-device validation on representative H.264/H.265 files. In particular, the `MediaCodec` output-to-`ImageReader` handoff must be verified for frame loss, output-format/crop behavior, rotation, memory pressure, and sustained decode throughput. The current `acquireRenderedImage()` polling path can return null after its bounded wait; if that occurs after an output buffer has been released for rendering, the output image may be lost. This is a design review item for the next decoder-hardening pass rather than an assumption that the source is already production-grade.

## Native parity state
Run #286 confirms the existing native speed/parity vectors and Python research tests are green. Native homography projection is already included in the host parity harness, so the previous context item saying homography parity had not yet been added is stale and should not be repeated.

The next meaningful parity improvement is to move from a small hard-coded vector set toward shared/generated deterministic fixtures so Kotlin and native implementations cannot silently drift in their test data.

## Reliability policy reinforced
- Build-green does not imply device-green.
- CI must always be interpreted against the exact commit SHA under review.
- Do not weaken tolerances or add baselines/suppressions merely to make CI green.
- Physical speed remains gated by validated calibration plus exact timestamp provenance when required.
- Native speed remains preferred through the native-first adapter only after result validation; Kotlin remains the reference fallback.
- The exact PTS path must not be promoted to a measurement-grade claim until real-device media compatibility is validated.

## Current stopping point
Latest commit created during this session: `2347879016f937305bb00a6f316b9d57701bad36` (`test: protect unified analysis session lifecycle`). Its CI run is still the active verification target at the time this document was written. Do not call that commit green until its exact Run #290 completes successfully.
