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
The current project context already records the first physical-device crash in Local Analysis and the stabilization attempts. These attempts did not yet prove the root cause. The Local Lab was deliberately simplified to CPU + Kotlin geometry/speed + ordinary video source and appearance association disabled for isolation.

Known remaining gaps include stale UI runtime text, nominal-vs-measured decode FPS semantics, speed rejection conflation, insufficient track-quality gates for physical speed, unvalidated ExactPts decoder, eager native library loading, unimplemented ANPR/OCR, metadata-only evidence, ViewModel-scoped sessions instead of app-level orchestration, and the need for a repository-wide placeholder/resource/static-initializer audit.

## Professional tracking behavior requested by project owner
The radar should visually and semantically behave like the strong Python traffic projects the user tested: each detected vehicle receives a stable numbered box such as vehicle 162 and vehicle 163, classification is attached to that same track, and the ID follows the vehicle through the scene rather than being regenerated every frame.

This behavior is a core product requirement, not a cosmetic UI feature.

## Release honesty rule
Never claim 'zero bugs' or 'production ready' from static inspection alone. The strongest defensible status is 'implemented and verified by [specific test layers]'. Device-specific native/runtime stability requires actual device evidence.
