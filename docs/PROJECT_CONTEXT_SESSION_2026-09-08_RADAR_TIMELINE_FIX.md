# Smart Traffic — 2026-09-08 Radar / Timeline / Telemetry Repair

## Scope
This session continues the cumulative Smart Traffic context. The objective was to fix the user-observed local-video behavior: tracking must remain locked to vehicles at the video's natural speed, radar must advance with the same video time, calibration-free speed must remain available as an estimate, and telemetry must not cover the video with a large black panel.

## Repository state before this repair
The repository already contained a real YOLO26n LiteRT detector, a ByteTrack-inspired tracker with Kalman prediction and two-stage association, metric/calibration geometry, a calibration-free speed estimator, Exact PTS decoding, and a replay presentation layer. The principal defect was the relationship between analysis-time preview state and recorded-video playback time rather than absence of a detector/tracker.

The previous implementation exposed `videoUri` from `LocalAnalysisViewModel` while analysis was still running. `AnalysisVideoPlayback` consequently started ExoPlayer before complete track history existed. `AnalysisRadarPreview.RadarPanel` did not receive player position and used the newest preview observation plus `takeLast(12)` samples. `VideoAnalysisHud` used a large black translucent central `Surface`.

## External implementation research
Android's current documentation says NNAPI is deprecated as of Android 15 and recommends moving to alternatives such as the LiteRT GPU runtime. NNAPI is therefore not the acceleration path for this project.

LiteRT documentation and current Android examples support `CompiledModel.Options(Accelerator.GPU)`. GPU compilation can fail for model/device combinations, so the project uses a normal-exception GPU attempt followed by an explicit CPU construction fallback. This fallback is a resilience mechanism, not an accuracy reduction. Device validation remains mandatory because native delegate failures can be device-specific.

Current Ultralytics tracking documentation continues to expose ByteTrack, BoT-SORT, OC-SORT, Deep OC-SORT, FastTracker and TrackTrack. BoT-SORT adds appearance/Re-ID and global camera-motion compensation. The current project retains its custom ByteTrack-inspired baseline and should compare stronger trackers quantitatively on labeled traffic data before claiming superiority.

## Code changes implemented

### 1. Canonical timestamped rendering resolver
Added:
`android/app/src/main/java/com/smarttraffic/app/domain/analysis/TrackRenderResolver.kt`

It is the shared render-time resolver for recorded video. It:
- resolves every `Track` at a requested source timestamp;
- interpolates between bracketing timestamped detections using a bounded cubic interpolation;
- performs bounded extrapolation only for short end gaps (default 300 ms);
- provides a time-window trail API rather than a sample-count trail;
- never modifies the underlying analytical track history.

Added JVM coverage:
`android/app/src/test/java/com/smarttraffic/app/domain/analysis/TrackRenderResolverTest.kt`
covering interpolation, bounded prediction, prediction horizon and time-based trail behavior.

### 2. Recorded video now obeys analyze-then-replay
`LocalAnalysisViewModel.kt` no longer exposes `videoUri` during progress previews. During analysis the UI can show the processed frame, but ExoPlayer is not started.

After the analysis completes, `publishCompletedVideoPreview()` creates the replay preview with:
- the complete `result.tracks` history;
- `renderTracks = result.tracks`;
- the authoritative `result.source.timelineStartTimestampMs` copied into both `AnalysisFrame` and `AnalysisPreviewFrame`.

This guarantees that detector throughput cannot slow or outrun the recorded playback timeline.

### 3. Video overlay is driven from the playback timestamp
`AnalysisVideoPlayback.kt` now:
- uses ExoPlayer as the recorded-video master clock;
- emits `onPositionChanged(positionMs)` to its parent;
- resolves track state at `timelineStart + playerPosition` using `TrackRenderResolver`;
- draws the fluorescent green (`#39FF14`) tracking boxes;
- keeps only the small vehicle ID label near the box;
- removes the old central black HUD and red visual speed-gate presentation;
- renders telemetry as simple text rows on the left and right safe margins;
- alternates stable track IDs between the two side columns to preserve the center evidence region;
- retains the exact speed mode semantics (`calibration-free estimate` versus metric speed).

The player remains at natural playback speed and no inference-speed sleep or frame-skip workaround is introduced.

### 4. Radar is synchronized with the video clock
`AnalysisRadarPreview.kt` now owns the latest playback position reported by `AnalysisVideoPlayback` and passes that exact position to `RadarPanel`.

`RadarPanel` now:
- resolves active vehicle state at the same source timestamp used by the video overlay;
- uses `TrackRenderResolver.trail(..., windowMs = 1500)` for a time-based trajectory trail;
- labels the panel `SYNCED TO VIDEO`;
- removes the old latest-preview / `takeLast(12)` temporal behavior.

The radar remains a visualization of the same analytical tracks, not a second tracking algorithm.

### 5. Hardware acceleration policy
`AnalysisRuntimeFactory.kt` now prefers `Accelerator.GPU` for the detector and constructs CPU only when GPU model construction fails through a normal exception path. The selected accelerator is propagated into runtime diagnostics and result presentation.

NNAPI is not used. The reason is platform direction, not lack of interest in hardware acceleration: Android officially deprecated NNAPI in Android 15.

## Speed behavior retained
Calibration-free speed remains enabled and is still a video-derived estimate using tracked image motion and robust vehicle-size priors. The repair does not weaken or replace the estimator. A displayed speed is tied to the same `track.id` as the resolved vehicle overlay. The result remains explicitly non-enforcement-grade until independent reference-speed validation is completed.

## Important product behavior after the repair
For recorded video:

`source video -> analysis to completion -> complete timestamped tracks -> ExoPlayer natural playback -> canonical stateAt(playerTimestamp) -> video boxes + radar + telemetry`

This is deliberately different from live mode. Live MJPEG remains a separate low-latency streaming path where inference can be slower than source arrival and stale frames may be dropped to prevent queue growth.

## Remaining validation gates
The source changes are committed directly to `main`, but an exact-HEAD CI run is still required before calling this repair build-verified. Physical-device testing is still required to measure:
- actual GPU versus CPU inference latency;
- decoder compatibility;
- sustained processing throughput;
- memory/thermal behavior;
- replay/seek behavior;
- visual alignment over multiple videos.

The most important real-device acceptance checks are:
1. a vehicle keeps one ID through ordinary detector gaps;
2. video, green box, radar point and telemetry refer to the same vehicle at the same playback timestamp;
3. seeking backward/forward reproduces historical track positions without stale state;
4. telemetry remains on screen edges and never becomes a central black panel;
5. many vehicles populate rows downward and then across the two side columns without blocking the road;
6. a video with non-zero initial PTS preserves source timeline alignment;
7. calibration-free speed remains an explicitly labelled estimate.

## Current commit sequence in this repair
- `73879bd9...` — canonical timestamped track render resolver.
- `78aeaa00...` — GPU-first detector runtime with CPU fallback.
- `57b496e6...` — enforce analyze-then-replay and preserve completed timeline origin.
- `2dbac3e3...` — synchronize video overlay to playback timestamp and side telemetry.
- `65efd183...` — bind radar to the same playback timestamp and time-window trail.
- `7342ecf...` — regression tests for timestamped track rendering.

The latest commit is `7342ecf7fd487558101d0baca8117e0febe1af1f` unless a newer commit is created after this context entry.

## Honesty boundary
The supplied YouTube Shorts were requested as visual references. Direct frame-by-frame retrieval from the three supplied YouTube Shorts failed in the current environment, so this document records the requested visual behavior and the repository-grounded engineering translation without claiming frame-level visual inspection that did not occur.
