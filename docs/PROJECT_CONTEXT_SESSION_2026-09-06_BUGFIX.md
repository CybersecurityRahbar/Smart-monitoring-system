# Smart Traffic — 2026-09-06 Local Analysis Bugfix Context

This append-only session note records the production issues reproduced by the user after installing the latest APK, the code-level diagnosis, external implementation comparison, and fixes applied directly to `main`.

## User-reported failures

When selecting a traffic video in Local Analysis and running real analysis:

1. The video appeared completely frozen during analysis.
2. Only one red line was visible, and it was not aligned with the vehicle traffic corridor.
3. The Tracking Radar panel updated with green points/IDs, but the actual video did not show moving tracked boxes/IDs.
4. Smooth vehicle boxes were not visible on the source video.
5. After processing, the session ended with:
   `Analysis error: Traffic rules require a validated physical calibration`.
6. Speed labels were not practically visible in the video during the run, and the operator could not use the video itself as the live analysis surface.

## Code-level root causes

### A. Frozen recorded video was intentional in the current playback component
`AnalysisVideoPlayback` used `ExoPlayer` but explicitly set `playWhenReady = false` and only called `play()` when `preview.playbackReady == true`. `LocalAnalysisViewModel` deliberately published in-progress video previews with `playbackReady = false`.

Therefore the analysis preview surface showed a paused video while the detector/tracker continued processing frames in the background. The separate radar panel could update, creating the exact mismatch reported by the user.

### B. Tracking existed but was rendered separately from the moving source
The `AnalysisRadarPreview` component correctly embedded `AnalysisVideoPlayback`, but the playback component was paused during analysis. Its overlay could therefore never visually follow the moving source during the run.

The track data itself is real: `AnalysisPipelineRunner` builds `liveTracks`, and the preview observer publishes current tracks and speed estimates. The failure was presentation/scheduling, not absence of tracking code.

### C. Traffic rules could abort an otherwise valid analysis
`AnalysisPipelineRunner` calls `TrafficRuleEngine.evaluate()` whenever rules are enabled. `LocalAnalysisViewModel` merges persisted rule preferences into the local analysis config.

The old `TrafficRuleEngine` implementation used a hard `require(calibration != null)` and did not inspect the speed estimate mode. Thus an uncalibrated analysis with rules enabled could throw at the end of the run, changing a successful detector/tracker/speed analysis into an `Analysis error` state.

This behavior violated the intended policy boundary: calibration-free speed may be displayed as an estimate, but traffic enforcement events must never be generated from calibration-free speed.

### D. The old uncalibrated visual gate was too generic for operator use
`AutoSpeedGateBuilder` correctly avoided inventing metric separation for uncalibrated video, but its visual image-space line construction could span the entire image corridor and was not explicitly restricted to the observed vehicle flow corridor. The user therefore saw a line that could appear misplaced or visually unusable.

For uncalibrated video, visual timing lines are presentation aids only. The stronger source of speed is the calibration-free vehicle-motion estimator; the visual lines must still be spatially meaningful and visible on the roadway.

## Fixes applied

### 1. Recorded video now plays continuously during analysis
`AnalysisVideoPlayback.kt` was changed so `ExoPlayer` starts with `playWhenReady = true` and remains enabled whenever the preview has a video URI.

The completed preview no longer seeks the already-running player back to zero. The same player continues from its current position after the analysis completes.

### 2. Uncalibrated visual speed gate was made traffic-corridor aware
`AnalysisVideoPlayback.kt` computes a presentation-only visual gate from current tracked vehicle observations when `preview.calibrated == false`.

The visual gate estimates dominant motion, places two cross-flow lines at longitudinal quantiles, enforces minimum separation, derives the segment from the observed transverse vehicle corridor, and clips it to image bounds.

### 3. Traffic rules are non-fatal and physical-speed-only
`TrafficRules.kt` was changed so enabled rules with no calibration return no events instead of throwing, and only `CALIBRATED_GROUND_PLANE` estimates can create traffic-rule violation events.

### 4. Fallback video decoder resource lifecycle was fixed
`LocalVideoFrameSource.kt` now releases its fallback retriever consistently at EOF and uses an idempotent release path.

## Deep tracking/speed refinement — 2026-09-06 follow-up

The next pass focused on making the on-video tracking visually continuous instead of merely showing the latest detector box. Current research and reference implementations indicate that robust traffic tracking should separate three concerns: identity association, state prediction during missed observations/occlusion, and render-time temporal smoothing. Current Ultralytics documentation describes ByteTrack as the lightweight baseline, BoT-SORT as adding camera-motion compensation and optional ReID, and OC-SORT as adding observation-centric correction/recovery for non-linear motion and occlusion. citeturn320283search6turn320283search0turn320283search2

The repository already contains a custom ByteTrack-inspired tracker with Kalman prediction, two-stage high/low confidence matching, appearance signatures, motion gates, acceleration bounds, and bounded history. The latest refinement therefore avoids altering the analytical measurements merely to make the UI look smooth. Instead, `AnalysisVideoPlayback.kt` now adds a render-only cinematic trajectory layer.

### Cinematic renderer architecture

The source video remains the master visual clock and continues at natural playback speed. For each track, the renderer:

1. converts detector boxes to timestamped center/size samples;
2. applies a short causal weighted trailing smoother to reduce single-frame detector jitter;
3. interpolates between timestamped samples with cubic Hermite motion rather than snapping from box to box;
4. computes recent velocity with a median estimator for short prediction;
5. extrapolates for at most 800 ms when inference lags behind the video, with damping so the box does not accelerate unrealistically;
6. bounds interpolation overshoot near neighboring observations;
7. leaves the underlying track history, speed estimation, and enforcement analytics untouched.

This is intentionally a rendering model, not a measurement rewrite. A visually smooth rectangle must not contaminate the evidence used by the speed estimator.

### Video/track clock alignment

The renderer no longer blindly treats the player position as an absolute detector timestamp. It derives the timeline origin from the earliest available track observation and offsets that by `player.currentPosition`, reducing common non-zero-start PTS misalignment during the analysis preview.

A dedicated source-start timestamp field is still desirable for a final implementation because the earliest currently visible track is not guaranteed to be the actual media PTS origin. A physical-device test with videos whose media starts at non-zero PTS is still required before closing this risk.

### Calibration-free speed position

The current `CalibrationFreeSpeedEstimator` is a robust engineering estimate based on bottom-center image motion, local per-interval scale from a vehicle-width prior, dominant-motion projection, trimmed observations, cumulative pseudo-metric trajectory fitting, Theil-Sen regression, estimator agreement, and explicit uncertainty. This is materially more robust than simple pixel displacement divided by frame time, but it is not the exact 2026 research method.

The newly published August 17, 2026 calibration-free research framework uses a learned 36-keypoint vehicle template and a homography updated per frame, with an alternative warped optical-flow strategy, and reports validation on more than 400 video clips. citeturn320283academia12 The repository does not currently contain that trained keypoint backend, so the project must not claim reproduction of those reported error rates. The engineering roadmap is to add such a model only after a compatible Android inference artifact and benchmark protocol exist.

## Current commits at this stopping point

- `fd28da1f...` — traffic rules non-fatal and physical-speed-only.
- `9b3b47c4...` — continuous recorded-video playback and corridor-aware uncalibrated visual gate.
- `38a24e8e...` — fallback retriever EOF cleanup.
- `ca825862...` — regression test for traffic-rule safety policy.
- `dbc64fd6...` — idempotent fallback retriever release.
- `44d4e99b...` — cinematic render-only tracking smoothing/interpolation/extrapolation for the video overlay.

`main` currently points to `44d4e99bb0aae4fd804c0f22d6915aa374062613`.

## Validation state after cinematic change

GitHub Actions Run #471 (`34051237156`) was automatically started from `44d4e99bb0aae4fd804c0f22d6915aa374062613`. At the time of this note, Native C++ Parity and Offline Research Math had completed successfully, while Android Build & Test and ESP32 firmware were still running. Do not mark the cinematic change fully validated until the remaining CI jobs complete and the exact APK is exercised on the target Android device with the same traffic video.

## Required physical validation checklist

The same Local Analysis traffic video should visibly show, simultaneously:

`moving source video → green box follows vehicle continuously → stable ID → speed estimate on the vehicle → two useful speed lines aligned with observed traffic flow`

The side Tracking Radar should agree with the on-video trajectory, remain secondary to the source video, and not become a separate truth source.

Success criteria for the next device test:

- the source video never freezes while analysis is running;
- boxes do not jump several pixels on ordinary detector noise;
- IDs remain stable through ordinary partial occlusion;
- a brief detector miss does not immediately make the box disappear;
- the speed estimate changes smoothly rather than frame-to-frame wildly;
- calibration-free speed is clearly labelled as an estimate and never creates enforcement events;
- visual speed lines stay in the vehicle corridor rather than spanning arbitrary image space;
- analysis completes without a calibration-related exception.

## Remaining research/engineering priorities

1. Replace active-only preview tracks with a short-lived predicted/render state for recently missed tracks so the box can survive detector gaps without changing analytics.
2. Make radar rendering use the same timestamped smoothed trajectory as the video overlay instead of raw last detections, so both surfaces are visually synchronized.
3. Add a fixed source PTS origin from `FrameSource` metadata rather than inferring it from visible tracks.
4. Measure detector-to-video lag on the device and expose a bounded freshness indicator instead of silently hiding boxes after the prediction horizon.
5. Benchmark IDF1/HOTA/ID-switch/fragmentation on labelled traffic sequences; no such traffic benchmark has yet been established for this project.
6. Build a separate benchmark for calibration-free speed against known ground-truth speeds before reporting numerical accuracy.
