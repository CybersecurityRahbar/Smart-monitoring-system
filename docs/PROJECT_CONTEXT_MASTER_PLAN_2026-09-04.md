# Smart Traffic Monitoring System — Master Engineering Context

Last verified context update: 2026-09-08
Repository: `CybersecurityRahbar/Smart-monitoring-system`

## 1. Engineering rule
The project must expose only functionality that is actually implemented and verified. A computed number is not automatically a trusted measurement. Speed outputs are therefore typed by measurement mode and carry uncertainty/confidence where available.

Release gate:
`Build -> Static/Lint -> Unit -> Instrumented -> Physical Device -> Media/Model Compatibility -> Stress/Memory -> Crash/ANR -> Accuracy/Tracking Benchmark -> Release Gate`

A green CI run certifies only the checks that completed at that exact commit SHA. It does not prove zero bugs, production readiness, physical-device stability, or enforcement-grade accuracy.

## 2. Target architecture
`Compose UI -> ViewModel/StateFlow -> Application AnalysisHost/Supervisor -> Domain Pipeline -> Source/Runtime/Detector/Tracker/Geometry/Speed/ANPR/Rules/Evidence`

`AnalysisHost` is application-scoped and owns `UnifiedAnalysisSession`. Lifecycle ownership is centralized so screen recomposition cannot silently create duplicate analysis sessions or close a newer session with an old resource owner.

## 3. Detector/model
LiteRT model: `android/app/src/main/assets/models/yolo26n.tflite`

SHA-256: `d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73`
Size: 2,875,553 bytes
Input: 640 NCHW
Output: `[1,84,8400]`, 80 classes

`DetectorModelRegistry` validates the model contract. The current mobile path uses CPU by default. GPU/native delegates are not enabled merely because they exist; they require explicit physical-device validation because native crashes/aborts are a release risk. Instrumentation has a real LiteRT startup smoke path.

## 4. Tracking
The Android tracker is ByteTrack-inspired, with Hungarian association, Kalman prediction, confidence tiers, class gating, optional appearance association, actual timestamp delta handling, motion/acceleration/reversal checks, adaptive gates, bounded history, and explicit states:
`TENTATIVE`, `CONFIRMED`, `LOST`, `REMOVED`.

Track IDs are session-local monotonic IDs and are displayed as `car ID: N`. A detector miss does not immediately create a new vehicle. Recovery/occlusion is represented in track state and metrics.

ID-switch and fragmentation metrics remain `null` until a labelled ground-truth benchmark is available.

## 5. Speed system — two legitimate modes
Speed is deliberately split into two modes:

### A. `CALIBRATED_GROUND_PLANE`
Preferred physical/metric estimate. Uses validated road/camera calibration, authoritative source timestamps, ground-plane projection, continuity checks, robust trajectory estimation, automatic two-line timing gate, plausibility checks, and uncertainty/error reporting.

Current automatic gate:
- collects contact-point observations within each individual track;
- infers dominant traffic direction from within-track motion only;
- places two robust cross-flow timing lines using scene quantiles;
- in calibrated mode the lines are constructed in metric ground coordinates and inverse-projected to the image;
- `SpeedGateEstimator` accepts either line order, interpolates crossing timestamps, computes separation / crossing-time speed, and compares it with robust trajectory slope;
- confidence combines timing quality, observations, agreement and gate geometry quality.

`RobustSpeedEstimator` is the general backend: bounded pair gaps, robust pairwise slopes, MAD outlier rejection, plausible-speed filtering, residual and quality scoring. Historical Kotlin/native parity fixtures have passed for linear, diagonal and variable-timestamp trajectories.

This is still not an exact ground-truth measurement. Exact/enforcement-grade accuracy requires independent reference instrumentation and empirical calibration/validation.

### B. `CALIBRATION_FREE_ESTIMATE`
This mode is now implemented and is enabled by default through `AnalysisConfig.enableCalibrationFreeSpeedEstimate = true`.

It is intentionally an estimate for ordinary phone videos where validated camera calibration is unavailable. Current implementation:
- extracts bottom-center vehicle image motion from the tracked bounding box;
- estimates a dominant motion axis from within-track image motion;
- projects each interval onto that axis;
- estimates local metric scale from a class-dependent robust vehicle-width prior;
- refreshes scale per interval rather than using one global width, reducing perspective bias as a vehicle approaches/recedes;
- applies robust median/MAD rejection to interval metric speeds;
- reports `confidence` and `errorKmh`;
- returns `SpeedEstimateMode.CALIBRATION_FREE_ESTIMATE` so the result cannot be mistaken for calibrated metric speed.

Current vehicle-width priors are assumptions, not measured scene geometry: motorcycle 0.85 m, car 1.80 m, van 2.00 m, truck 2.50 m, bus 2.55 m, unknown 1.90 m. Consequently the error bound is deliberately broad and this output must never be described as exact or enforcement-grade.

The current implementation is a pragmatic fallback, not a reproduction of the strongest calibration-free research method. The 2026 paper **Calibration-Free Vehicle Speed Estimation: A Monocular Keypoint-Template Approach** uses a 36-keypoint vehicle template and dynamic homography and reports nonzero error on hundreds of clips; its results support treating calibration-free speed as a benchmarked estimate rather than an exact measurement. Future work can add a dedicated vehicle-keypoint backend and dynamic homography to strengthen this mode. Reference: arXiv 2608.16785 (2026-08-17).

A separate GitHub calibration-free implementation (`damiadedayo/Vehicle-Speed-Estimation`) uses YOLOv8n + DeepSORT and a learned XGBoost speed regression trained from labelled speeds; it is useful as a reference for a future learned calibration-free backend, not as code copied into this Android implementation.

A simpler fixed-camera OpenCV reference (`swhan0329/vehicle_speed_estimation`) uses Lucas–Kanade optical flow, forward/backward consistency and scene-specific `px_to_meter` calibration. Its main lesson is that pixel-to-metre scale is camera/scene-specific unless a physical prior or calibration model is supplied.

## 6. Speed gating and UI semantics
The automatic gate is visual in both modes, but only calibrated mode has a physical separation in metres.

Recorded-video playback is intended to be deterministic analyze-then-replay: analysis runs over the source first, then ExoPlayer starts at natural playback speed with complete track histories. This is the required product behavior because it prevents inference latency from changing the apparent speed of the recorded source.

Current speed labels distinguish modes:
- calibrated: `speed: X km/h ± E`
- calibration-free: `speed: X km/h ± E (calibration-free estimate)`

This explicit label is required because the calibration-free result is useful but materially weaker than a validated metric result.

## 7. Timestamp rules
`ExactPtsVideoFrameSource` propagates decoder PTS and enforces monotonic source timing, but it is not yet physically certified.

`LocalVideoFrameSource` uses requested sample times and is not measurement-grade by itself.

Live MJPEG currently uses `LOCAL_MONOTONIC_ARRIVAL`; therefore calibrated physical live speed remains blocked until authoritative source timing is available. Calibration-free estimation can technically run on the live track stream because it does not require calibrated metres, but it must remain labelled as an approximate estimate.

## 8. Pipeline behavior
`AnalysisPipelineRunner` now chooses speed as follows:
1. If validated calibration + allowed timestamps are present, compute calibrated speed.
2. Otherwise, when enabled, compute `CALIBRATION_FREE_ESTIMATE` from tracked image motion and vehicle-size prior.
3. If neither path produces a robust result, retain a typed rejection reason instead of displaying a fabricated number.

Rejection reasons include:
`CALIBRATION_INVALID`, `TIMESTAMP_INVALID`, `INSUFFICIENT_OBSERVATIONS`, `INSUFFICIENT_DURATION`, `TRACK_QUALITY_LOW`, `DISCONTINUOUS_TRACK`, `GROUND_GEOMETRY_INCOMPLETE`, `PLAUSIBILITY_REJECTION`, `ROBUST_ESTIMATOR_REJECTION`.

Traffic-rule enforcement remains intentionally stricter: `TrafficRuleEngine` requires a validated physical calibration and therefore does not turn a calibration-free estimate into an enforcement event.

## 9. Tests added in this cycle
`CalibrationFreeSpeedEstimatorTest` covers:
- successful calibration-free estimate from a synthetic vehicle track;
- explicit `CALIBRATION_FREE_ESTIMATE` mode;
- confidence and nonzero uncertainty;
- rejection of a large observation gap.

`AutoSpeedGateTest` covers stable image-space gate creation, calibrated forward crossing speed, and reverse-direction crossing order. A nullable velocity assertion that previously blocked Android unit compilation was fixed before the next CI cycle.

## 10. Evidence / incident integrity
`FileEvidenceStore` is real and bounded: SHA-256 content-addressed artifact names, serialized operations, flushed writes, atomic move where supported, safe fallback, integrity verification, orphan pruning, and bounded count/bytes.

Current evidence limitation: exact live MJPEG event-frame capture and a real ANPR plate crop are not fully wired.

Persistent incident-report storage exists and is tested.

## 11. Radar/video reference review — 2026-09-08

### 11.1 Reference links supplied for this review
The user supplied three YouTube Shorts as visual references:

- `https://youtube.com/shorts/13nMnmQMK38?si=FKYnWki97sOv4woM`
- `https://youtube.com/shorts/PT5eoS7ZrxM?si=dGthtskeuQdYfMk6`
- `https://youtube.com/shorts/AEd7tev39Ns?si=9UFyhuul4R-Ha8Vg`

A direct frame-by-frame playback of these Shorts was attempted, but the current web retrieval path returned YouTube cache/fetch failures for the supplied URLs. Therefore this context **must not claim that the assistant visually verified every frame of the three videos**. The visual requirements below are recorded from the user's described reference behavior and from a direct audit of the current repository implementation. They are requirements to match, not fabricated observations from unavailable frames.

### 11.2 Required reference behavior for vehicle tracking
The reference behavior to reproduce is:
- the recorded video remains the visual master and plays at its native/natural temporal rate;
- tracking boxes remain attached to the same physical vehicle while the vehicle moves through the frame;
- IDs do not jump between nearby vehicles merely because one detector frame is missed;
- the apparent tracker motion is continuous rather than a sequence of slow snapshots;
- the radar representation advances at the same temporal rate as the video, not at the much slower rate of detector callbacks;
- each active vehicle has one stable identity and one current position at the current video time;
- historical trails are time-consistent and should not change duration merely because detector/preview FPS changes;
- visual state should be generated by interpolation/prediction from timestamped observations, not by drawing whichever observation happened to be published most recently;
- seeking and replay must render the same vehicle at the corresponding source timestamp instead of retaining stale state from a previous playback position.

### 11.3 Root cause found in current implementation
The repository currently contains a critical architecture contradiction.

`docs/PROJECT_CONTEXT_MASTER_PLAN_2026-09-04.md` describes recorded playback as analyze-then-replay, but the current code still exposes a video URI during the analysis loop:

`LocalAnalysisViewModel.AnalysisPreviewObserver` copies each progress preview with `videoUri = uri.toString()` and publishes it before analysis completes.

`AnalysisVideoPlayback` then creates ExoPlayer, calls `prepare()`, sets `playWhenReady = true`, and starts playback. As a result, the player can begin advancing through the source while detector/tracker inference is still behind the player's timeline.

The renderer attempts to compensate by asking `cinematicDetection(track, targetTimestampMs)` for a state near the current player PTS, but that track object is only the latest published preview snapshot. For future timestamps beyond the latest analyzed observation, it permits only a 350 ms extrapolation and then returns no detection. This cannot guarantee a real-time visual lock when inference falls behind by more than a small fraction of a second.

This is why the failure is not best described as a simple ByteTrack tuning problem. The visual master clock and the analytics availability clock are still allowed to diverge.

### 11.4 Radar root cause
`AnalysisRadarPreview.RadarPanel` currently selects the latest observation matching `preview.frame.index`, otherwise the last observation in the track. It then draws a fixed-count trail using `takeLast(12)` observations.

No ExoPlayer current-position timestamp is passed into `RadarPanel`. Therefore the radar is not asking: “Where was vehicle ID N at the exact video time the user is currently seeing?” It is asking: “What is the newest state present in the latest preview snapshot?”

This makes the radar advance according to analysis publication timing rather than playback time. When the analysis pipeline publishes slowly, the radar visibly lags or moves in large jumps.

The `takeLast(12)` trail also gives a variable temporal trail length because 12 samples can represent very different durations at different source/detector rates. Trails must instead use a time window (for example, the most recent 1–2 seconds) and be interpolated on the same render clock.

### 11.5 The speed problem is separate but related
The system has two speed modes and the calibration-free estimator is intentionally labelled as an estimate. That distinction is correct and must remain.

However, even a mathematically valid speed estimate is not useful to the user when the displayed vehicle state is temporally stale. A speed label belongs to the current rendered track state, and its timestamp/quality must correspond to the same vehicle ID and source time being displayed.

For recorded-video replay, speed and vehicle state should therefore be resolved from completed timestamped track history using the player's current source timestamp. A single final `speedEstimates` map is insufficient as a visual time-series representation when speed changes materially during the video.

### 11.6 Required UI behavior derived from the third reference
The desired information presentation is explicitly **inside the video frame but outside the evidence area**, similar to a professional telemetry overlay rather than a blocking dashboard card.

Required rules:
- no large black panel in the top-center of the video;
- no opaque/large translucent card covering vehicles or road evidence;
- vehicle telemetry should be presented as compact text rows/chips anchored to the left and/or right safe margins of the video;
- each visible row corresponds to one vehicle ID and its current speed, for example `ID 7  •  52 km/h`;
- when more vehicles are active, rows are appended downward rather than widening a central panel over the video;
- the list may be split into a left column and right column when the number of vehicles is large, preserving visibility of the central roadway;
- rows must be bounded by the video viewport and use a deterministic ordering, such as stable ID or scene position, so the list does not reshuffle every frame;
- inactive vehicles are removed after a small time-out rather than instantly disappearing on one detector miss;
- text size and background treatment should be minimal and legible over varied footage; the background should be a small text shadow/outline or very small rounded backing behind each row, not a full-width panel;
- the primary evidence region remains the video itself; telemetry is subordinate.

### 11.7 Proposed information layout
The target recorded-video UI should conceptually be:

`VIDEO FRAME`

`LEFT SAFE MARGIN                         RIGHT SAFE MARGIN`
`ID 03   48 km/h                          ID 07   71 km/h`
`ID 11   36 km/h                          ID 09   54 km/h`
`ID 14   --                                ID 12   63 km/h`
`...                                      ...`

The vehicle boxes/labels stay attached to the actual cars in the roadway. The telemetry list stays attached to the screen edges. The two must not be conflated.

When the list grows beyond the available vertical space, rows should continue downward up to a bounded maximum, then use the second side column, and only then compact/scroll a telemetry area. The video itself must never become a scrolling black list.

### 11.8 Required new rendering architecture
The desired implementation should use one canonical render timestamp:

`ExoPlayer currentPosition -> source timeline timestamp -> TrackHistoryRenderer -> video overlay + radar projection + telemetry list`

The pipeline should retain timestamped observations for the full recorded media session. Rendering then performs:
1. choose the current source timestamp;
2. query each active track history at that timestamp;
3. interpolate between bracketing observations when available;
4. use bounded prediction only for a short detector gap;
5. mark the track temporarily predicted rather than silently switching to a new ID;
6. remove the visual state only after an explicit time-out;
7. project the same resolved state into the radar;
8. derive the same current-state speed/direction/confidence for telemetry.

The renderer must not maintain a second independent tracking algorithm.

### 11.9 Recorded-video synchronization policy
For local recorded videos, the safest default product flow is:

`Analyze complete source -> retain full timestamped track history -> enable playback -> start ExoPlayer at natural speed -> render everything from player PTS`

This means detector speed is allowed to be faster or slower than real time during preprocessing, but it cannot alter the user's visual playback speed afterward.

The current implementation does not yet enforce this policy because progress previews expose `videoUri` before completion and playback starts immediately. The documentation requirement and the code path therefore must be reconciled before the next UI/tracking fix is considered complete.

### 11.10 Non-zero PTS requirement
`publishCompletedVideoPreview()` currently reconstructs the final preview with `AnalysisFrame(0L, 0L, ...)`. The source contract already contains `timelineStartTimestampMs`. The final preview must preserve that origin so recorded replay remains correct for files whose first decoder PTS is not zero.

Required invariant:
`preview.timelineStartTimestampMs == result.source.timelineStartTimestampMs`
for completed recorded-video analysis.

### 11.11 Required radar behavior after the fix
The radar is not a second video player and not a second detector. It is a synchronized projection of the same resolved track state.

At every render tick:
- current player source timestamp is calculated;
- each track obtains `stateAt(timestamp)`;
- the video overlay consumes that state;
- the radar consumes that same state;
- the telemetry list consumes that same state;
- all three therefore move together frame-for-frame in source time.

For a vehicle moving continuously from left to right, the radar point must move continuously at the same perceived temporal rate as the car's movement. A faster/slower inference callback must not change that relationship.

### 11.12 Required telemetry identity model
Every vehicle row must be keyed by `track.id`, not by array position.

Example logical record:

`VehicleTelemetry(trackId=7, timestampMs=T, speedKmh=52.1, speedMode=CALIBRATION_FREE_ESTIMATE, confidence=0.73, state=CONFIRMED)`

The screen list is a view over those records. It must never use “first item/second item” semantics for vehicle identity.

### 11.13 Important distinction: analytics clock vs render clock
The project must preserve two different concepts:
- **analytics clock:** timestamps attached to source frames as they are decoded and analysed;
- **render clock:** the source timestamp currently presented by the player.

The analytics clock may run ahead of or behind wall-clock time. The render clock must follow the video playback position for recorded media.

The job of the renderer is to reconcile these clocks through timestamped history, not by slowing the video and not by using the latest callback as the truth.

### 11.14 Acceptance tests to add before claiming the fix
A future synchronization/UI fix is not complete until the following are tested:

1. **Natural playback:** video remains at the source playback rate independent of detector throughput.
2. **Track lock:** a vehicle moving across the frame retains one ID throughout ordinary detector gaps.
3. **Clock equality:** for several sampled playback timestamps, video overlay, radar and telemetry resolve the same track state timestamp.
4. **Slow-analysis simulation:** artificially delay inference/publication; vehicle overlay and radar must remain synchronized once playback uses completed history.
5. **Fast-analysis simulation:** publish analysis far ahead of playback; rendering must still show the vehicle at the player's current time rather than the newest analysed state.
6. **Seek:** seek backward/forward; overlay, radar and telemetry must jump to the corresponding historical state without stale carry-over.
7. **Many vehicles:** 1, 4, 8, 12, and 20+ active tracks; telemetry must stack downward and/or split across side columns without covering the video evidence region.
8. **Detector miss:** short misses must use bounded prediction/interpolation and must not create an immediate replacement ID.
9. **Trail duration:** radar trail duration remains fixed in seconds when FPS changes.
10. **Non-zero source origin:** a file with non-zero first PTS renders the first video state at the correct source timestamp.
11. **Speed timestamp consistency:** displayed speed belongs to the currently rendered track/time state, not merely the final session aggregate.

### 11.15 Current implementation status after this review
The following are confirmed from direct repository inspection:
- `AnalysisVideoPlayback.kt` uses ExoPlayer as the video playback clock, performs render-time interpolation and only allows 350 ms extrapolation;
- `LocalAnalysisViewModel.kt` publishes progress previews with `videoUri` before analysis completes, so playback can start before complete track history exists;
- `AnalysisRadarPreview.kt` does not receive player position and currently renders from latest preview observations;
- `AnalysisRadarPreview.kt` draws radar trails using `takeLast(12)` rather than a time-bounded window;
- `VideoAnalysisHud` is a large black translucent surface (`alpha = 0.74f`) and can show multiple vehicle chips plus explanatory text, which explains why the user experiences it as a black panel covering the video;
- `publishCompletedVideoPreview()` still resets the final preview frame timestamp to zero and does not carry the authoritative source timeline origin into the constructed `AnalysisFrame`.

### 11.16 Decision recorded for the next implementation cycle
The next repair cycle must **not** merely tune tracker thresholds or increase preview FPS. The primary fix is architectural:

**Make recorded-video playback authoritative, make timestamped track history authoritative, make video overlay/radar/telemetry three projections of the same `stateAt(playerTimestamp)`, and replace the blocking HUD with side-safe telemetry rows.**

Only after this synchronization foundation is correct should tracker micro-tuning, speed-estimator refinements, or cosmetic polish be evaluated against real-device video samples.

## 12. Working repository baseline

Current main branch head observed during this context update: `bbda96a3eb4bd55e0ddeded7f155683a7aaa8044`.

The most recent code change immediately before this review corrected Kotlin `Pair.first` access in `AnalysisPipelineRunner.speedRejectionReason()`. This was a compile repair; it does not address the newly documented synchronization/UI architecture problem.

The project should not claim the new radar behavior as implemented until the acceptance tests in section 11.14 pass on real recorded-video samples and on a physical Android device.
