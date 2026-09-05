# Smart Traffic Monitoring System — Master Engineering Plan

## Purpose
This document records the governing plan for the current major engineering pass. The goal is a strong, integrated traffic-monitoring system with professional failure containment, measurable tracking/measurement quality, and no hidden placeholders presented as finished functionality.

## Non-negotiable engineering standard
Build success is not product readiness. Release readiness requires:
`Build -> Static/Lint -> Unit -> Instrumented -> Physical Device -> Media/Model Compatibility -> Stress/Memory -> Crash/ANR -> Accuracy/Tracking Benchmark -> Release Gate`.

The system must never silently fake unsupported functionality, silently convert failed measurements into valid numbers, or hide native/runtime failures behind broad exception catches.

## Target architecture
`Compose UI -> ViewModel/StateFlow -> Application AnalysisHost/Supervisor -> Domain Pipeline -> Source/Runtime/Detector/Tracker/Geometry/Speed/ANPR/Rules/Evidence`

The application-level AnalysisHost/Supervisor is the authoritative owner of one active analysis execution. ViewModels observe it rather than independently creating competing sessions.

Runtime lifecycle:
`NOT_INITIALIZED -> INITIALIZING -> READY -> RUNNING -> DEGRADED -> FAILED -> CLOSED`

High-risk stages write durable startup markers before native/ML/media initialization so a process-level crash can be correlated on the next application launch.

## Detection and radar/tracking requirements
The radar is a real multi-object tracking system.

Every vehicle receives a unique monotonic session-local Track ID. IDs are never reused within an analysis session. A track contains class, bounding box, confidence, lifecycle state, age, hits, misses, occlusion/recovery information, and quality statistics.

Association combines validated motion/IoU gating with optional appearance only when an appearance cue is actually available and inside its validity contract. Detector misses do not immediately create a new vehicle. Short gaps use prediction/recovery. Track state is explicit: TENTATIVE, CONFIRMED, LOST, REMOVED.

The pipeline must expose measurable tracking quality: ID switches, fragmentations, successful recoveries, recovery latency, matched detection rate, track retention, and confidence distribution. The UI displays the pipeline's authoritative Track ID, never a separately generated display ID.

## Measurement/radar requirements
Perception and measurement are separate contracts.

Maintain distinct timing metrics for source nominal FPS, decoded/produced FPS, inference FPS, processed FPS, and end-to-end throughput.

Physical speed is emitted as measurement-grade only when all required gates pass: validated calibration, authoritative timestamps, sufficient observations, sufficient track duration, acceptable temporal continuity, bounded gaps, reliable ground-plane projection, acceptable trajectory residual, plausible speed range, and adequate track confidence/quality.

Missing observations and temporal gaps must be represented explicitly. Speed rejection reasons must distinguish at least: NOT_ELIGIBLE, CALIBRATION_INVALID, TIMESTAMP_INVALID, INSUFFICIENT_DATA, DISCONTINUOUS_TRACK, GEOMETRY_QUALITY_FAILURE, ROBUST_ESTIMATOR_REJECTION, PLAUSIBILITY_REJECTION, and INTERNAL_ERROR.

A displayed speed must never look valid merely because a numeric value was computed.

## ANPR requirements
Plate recognition is a real pipeline, not a UI switch:
`vehicle -> plate detector -> bounded crop -> perspective correction -> plate-quality gate -> OCR -> normalization -> plate-format validation -> temporal consensus -> evidence`.

The chosen detector/OCR assets must be Android-deployable and licensed appropriately. Target characters relevant to the intended region must be supported. Low-resolution, blurred, oblique, occluded, or unsupported-script plates produce explicit low-confidence/unsupported results rather than guessed text.

## Evidence requirements
Evidence consists of actual bounded artifacts plus metadata:
- source/evidence frame;
- vehicle crop;
- plate crop when available;
- source timestamp/PTS precision;
- Track ID;
- detector/tracker/measurement quality;
- rule and threshold that triggered the event;
- calibration profile/version;
- model/runtime identity and model SHA;
- evidence SHA-256/integrity hash;
- atomic persistence and bounded retention.

Metadata-only event storage is not considered complete forensic evidence.

## Reliability/resource requirements
No unbounded frame buffers, detection histories, or per-track observation lists. Use bounded queues and a defined backpressure policy.

Live analysis should prefer controlled frame dropping/latest-frame semantics over unbounded queuing. Preview rendering is throttled and never determines analysis throughput.

Bitmaps and crops are bounded to required dimensions. Large intermediate allocations are reused or released promptly. Main-thread work is UI-only. Cancellation and close operations are idempotent and owned by one layer.

Any native/JNI runtime is initialized only behind an explicit capability and validation boundary. Kotlin try/catch is not treated as protection against native process aborts.

## Device-crash diagnostics
Record before each high-risk stage:
- device manufacturer/model;
- Android API and ABI;
- memory class and current available memory when available;
- analysis stage;
- runtime backend;
- LiteRT version;
- model ID and SHA;
- expected/observed tensor contract;
- media MIME, dimensions, duration, rotation, and timestamp precision;
- failure classification.

On the next launch, inspect supported process-exit history so the UI can distinguish ordinary Kotlin failure, native crash/abort, low-memory kill, and other termination causes where possible.

## Testing strategy
Detector: asset SHA + input/output contract + one-frame smoke test.

Tracker: deterministic association scenarios, detector misses, crossing vehicles, adjacent vehicles, occlusion and recovery, ID stability, plus quantitative metrics.

Geometry: homography/golden vectors and invalid-calibration cases.

Speed: synthetic trajectories, irregular PTS, duplicates, gaps, outliers, acceleration, wrong calibration, and rejection semantics. Kotlin/native implementations must share canonical test fixtures.

Media: H.264/H.265, portrait/landscape rotation, common resolutions, long files, malformed/edge files, exact-PTS consistency, memory pressure and sustained throughput.

Lifecycle: start/stop/restart, repeated starts, cancellation during every stage, screen rotation/background/foreground where applicable, and exact-once resource close.

Android/device: instrumented smoke tests and a physical-device matrix before release claims. CI must always be checked at the exact current `main` SHA.

## Mandatory audit targets
Search and eliminate or justify all TODO/FIXME placeholders, broad silent catches, `UnsupportedOperationException`, fake/mock production backends, eager dangerous static initialization, UI capability labels that do not match actual runtime support, unbounded collections, blocking main-thread calls, and duplicated ownership of closeable resources.

## Workstream order
1. Verify exact `main` HEAD and CI truth.
2. Repository-wide reliability/gap audit.
3. Application AnalysisHost/Supervisor and durable stage diagnostics.
4. Detector/runtime contract and one-frame smoke tests.
5. Bounded frame processing and memory-safe previews.
6. Professional tracker state/IDs/metrics and benchmark fixtures.
7. Measurement-grade speed gates and rejection semantics.
8. Real ANPR/OCR backend and temporal consensus.
9. Real evidence artifacts, hashes, and retention.
10. Reintroduce exact-PTS, JNI/native, and optional GPU one at a time after validation.
11. Instrumentation, physical-device, stress, crash/ANR validation.
12. Final accuracy/reliability benchmark and release gate.

## Current known non-complete areas
The first physical-device crash in Local Analysis remains a historical incident whose original native/runtime root cause was not proven from device logs in the repository. Local Lab is deliberately simplified to CPU + Kotlin geometry/speed + ordinary video source and appearance association disabled for isolation.

The current repository has real bounded evidence artifacts at `android/app/src/main/java/com/smarttraffic/app/data/evidence/FileEvidenceStore.kt`. The evidence layer is no longer metadata-only: frame/vehicle/plate artifacts (where supplied) are SHA-256 content-addressed, serialized through a coroutine mutex, flushed before publication, atomically moved when supported, integrity-verified on read, and orphan-pruned under retention limits. Existing limitations remain around capturing the exact live MJPEG event frame and wiring a real ANPR/plate crop into the evidence path.

Exact source PTS for local video is still unvalidated. A real ANPR/OCR backend is still unimplemented. Physical ESP32 camera validation is still pending. Quantitative tracking quality metrics such as ID switches/fragmentation/recovery latency still need benchmark fixtures and end-to-end reporting. Stress/memory and crash/ANR validation still require actual devices.

## Professional tracking behavior requested by project owner
The radar should visually and semantically behave like the strong Python traffic projects the user tested: each detected vehicle receives a stable numbered box such as vehicle 162 and vehicle 163, classification is attached to that same track, and the ID follows the vehicle through the scene rather than being regenerated every frame.

This behavior is a core product requirement, not a cosmetic UI feature.

## Engineering-pass log — 2026-09-05
Run #331 previously failed Kotlin compilation because of duplicate LocalImageFrameSource declarations, stale/invalid sourceUri references, a missing Compose `nativeCanvas` import, and malformed LiveAnalysisViewModel session initialization. These were corrected.

Run #356 then failed in `LiveAnalysisViewModel.kt` because `FrameSource.droppedFrameCount` had been converted from a function to a property while four callers still invoked it with `()`. All four usages were corrected in commit `cedc14d789e05962b87d7e993ed88702883ae69d`.

CI Run #357 (`33971826268`) completed successfully at that commit across all three jobs: Android build/test, Android instrumentation APK compilation, unit tests, Android lint, native C++ parity vectors, and offline research math validation all passed. This proves the build/test layers at that exact SHA; it does not prove physical-device runtime stability.

Evidence persistence was then hardened in commit `55426379e908213a9bb22d763ed30f2a7ac354c2`, followed by expanded instrumentation coverage in commit `39ad0c62e46a828461f01026f93652b176974570`. The evidence store moved to immutable SHA-addressed artifact filenames, serialized store operations, flushed publication with atomic-move support and safe fallback, integrity verification on read, and orphan pruning under retention. The tests were updated to validate the new content-addressed artifact names and concurrent/tamper behavior.

The tracker-state integrity fix is commit `4dd4f597a401f68d909c6f493a0e9a7986482fe7d`. `AnalysisPipelineRunner` now preserves the tracker's terminal state in its bounded track buffer and returns that state in `AnalysisResult` instead of forcing every completed track to `CONFIRMED`.

CI Run #362 (`33972356642`) failed in Android `compileDebugKotlin`. The compiler reported that `FileEvidenceStore.clear()` did not return a subtype of the `Unit` contract from `EvidenceStore`. The cause was an expression-bodied mutex block whose return type propagated instead of being explicitly `Unit`. This was a preventable compile-time contract error. The correction is commit `a3d83a715eadcc11dbc556b482eda7119314b105`, where `clear(): Unit` was made explicit and persistence was hardened further.

The associated instrumentation test was updated in commit `9e014dad1553408beb070d04024d3064366ba5c9` for hashed artifact names, concurrent writes, and tamper detection.

During pre-verification review of those changes, an additional latent bug was found in `artifactBytes`: constructing pairs containing nullable hashes with `listOfNotNull` does not remove the pair itself, so a metadata-only record could incorrectly reach `requireNotNull`. This was corrected in commit `02fc5e83e1c909b36ac64140297cc905cdba991b`, using only non-null hashes when calculating artifact sizes. The instrumentation suite then gained an explicit metadata-only evidence regression test in commit `64e38afc33130571892d068af42023d42029715d`.

This review caught the issue before another CI result and reinforces the required protocol: after every code change, reread the complete modified implementation, inspect nullable/control-flow/API contracts, reconcile all affected tests, and only then push. A successful CI result is validation evidence, not a substitute for pre-push code review.

The current `main` HEAD at the time of this context update is `64e38afc33130571892d068af42023d42029715d`. Its CI result is pending. The previous Run #357 success does not validate this later state, and the failed Run #362 does not represent the corrected latest tree.

This project owner explicitly requires the cumulative context document under `docs` to be updated as part of every material work cycle and before a response closes, recording decisions, changes, failures, tests, unresolved risks, and the exact repository state needed to resume work without reconstructing prior conversation context.

## Release honesty rule
Never claim 'zero bugs' or 'production ready' from static inspection alone. The strongest defensible status is 'implemented and verified by [specific test layers]'. Device-specific native/runtime stability requires actual device evidence.
