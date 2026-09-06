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

The overlay continues to interpolate the stored track history against the player position and allows bounded 350 ms extrapolation, so green bounding boxes and IDs can appear directly over the moving source video while analysis is running.

This is intentionally a source-video-master-clock design: the video remains smooth even when the detector is slower than real time, while overlay freshness is governed by the available track history.

### 2. Uncalibrated visual speed gate was made traffic-corridor aware
`AnalysisVideoPlayback.kt` now computes a presentation-only visual gate from current tracked vehicle observations when `preview.calibrated == false`.

The new visual gate:
- estimates the dominant motion axis from actual track motion;
- places two cross-flow lines at longitudinal quantiles;
- enforces minimum visual separation;
- derives the line segment from the observed transverse vehicle corridor instead of blindly spanning the whole frame;
- clips the result to the video image bounds.

Calibrated runs continue using the physical `SpeedGate` produced by the domain gate builder.

### 3. Traffic rules are non-fatal and physical-speed-only
`TrafficRules.kt` was changed so:
- enabled rules with no calibration return no events instead of throwing an exception;
- only `CALIBRATED_GROUND_PLANE` estimates can produce traffic-rule violation events;
- `CALIBRATION_FREE_ESTIMATE` can never become a physical enforcement event.

This restores the separation between analysis/display and enforcement policy without allowing optional rules to destroy the entire analysis result.

### 4. Fallback video decoder resource lifecycle was fixed
`LocalVideoFrameSource.kt` was changed so normal EOF calls the same finish/release path as explicit close. This removes the previously identified path where `finished = true` could cause `close()` to return before releasing `MediaMetadataRetriever`.

## External implementation comparison

External/current references were reviewed during this fix cycle:

- Ultralytics YOLO speed estimation keeps the output attached to the processed video frame and explicitly treats speed as an estimate dependent on camera scale. citeturn747959search0turn747959search2
- Ultralytics tracking keeps boxes/IDs in the same video processing loop; ByteTrack is the lightweight baseline, while BoT-SORT adds camera-motion compensation and optional ReID for moving-camera footage. citeturn457260search3turn457260search0
- `tomasszu/vehicle_counting_demo` demonstrates line-crossing counts with detections, tracks, IDs and line annotations written directly on the processed video, including robustness work for low-frame-rate/occlusion cases. citeturn747959search3
- `swhan0329/vehicle_speed_estimation` uses an explicit ROI/lane-scale calibration workflow for practical fixed-camera speed estimation; the important engineering lesson is that scene geometry/scale must be tied to the actual camera view. citeturn457260search6
- `HasibAlMuzdadid/Real-Time-Traffic-Capacity-and-Speed-Detection` uses detection + ByteTrack + perspective mapping for real-world road coordinates, reinforcing the calibrated road-geometry path for physical speed. citeturn747959search4

## Validation state

The fixes were committed directly to `main` in separate commits:
- `fd28da1...` — non-fatal, physical-speed-only traffic rules.
- `9b3b47c...` — continuous video playback during analysis + corridor-aware visual gate.
- `38a24e8...` — fallback video decoder EOF cleanup.
- `ca825862...` — regression test for traffic-rule policy.

The latest push automatically started Android CI Run #468 (`34050672433`) on `main` at `ca825862aecdcde1faee12e604e0a9618bc1509d`.

At the last check during this session:
- Offline Research Math: passed.
- Native C++ parity: passed.
- Android build/test: still running.
- ESP32 firmware build: still running.

Do not call the fix fully validated until the remaining CI jobs complete successfully and the repaired APK is tested on the user's target device with the same traffic video.

## Remaining technical risks

1. Exact PTS versus ExoPlayer timeline origin still needs a non-zero-start PTS test on a physical device.
2. The corridor-aware visual gate is intentionally presentation-only for uncalibrated video; it is not a source of metric metres.
3. Calibration-free speed still requires independent real-speed benchmark data before any accuracy claim.
4. Live MJPEG still uses local arrival timestamps and must not be treated as calibrated physical speed.
5. Hardware/decoder compatibility and sustained Android memory/thermal behavior remain physical-device validation gates.

## Next stopping point

Once CI is green, install that exact APK and run the same Local Analysis test again. Verify on-screen, in one video surface:

`moving source video + green vehicle boxes + stable car IDs + calibration-free speed label + two useful visual speed lines`

The side Tracking Radar should remain an analytical secondary view, not the only place where tracking appears.
