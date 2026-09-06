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

The repository already contains a custom ByteTrack-inspired tracker with Kalman prediction, two-stage high/low confidence matching, appearance signatures, motion gates, acceleration bounds, and bounded history. The latest refinement therefore avoids altering the analytical measurements merely to make the UI look smooth. Instead, `AnalysisVideoPlayback.kt` adds a render-only cinematic trajectory layer.

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

### Track-state separation

Commit `1dd15ffa7bf0d214f99c0b018d42748e3c61d375` introduced an explicit `renderTracks` field in `AnalysisPreviewFrame`. `tracks` remains the analytics-active set, while `renderTracks` is reserved for short-lived render-only predictions through brief detector gaps. This establishes the correct contract for the next implementation step: render continuity must never be fed back into speed estimation or enforcement analytics.

### Video/track clock alignment

The renderer currently derives a timeline origin from available track observations and offsets that by `player.currentPosition`. This reduces common non-zero-start PTS misalignment, but it is not yet definitive because the earliest visible track observation is not guaranteed to equal the media PTS origin.

A fixed source-start timestamp field from `FrameSource` metadata remains required for the final implementation and must be validated with videos whose media starts at non-zero PTS.

### Calibration-free speed and the 36-keypoint research path

The current `CalibrationFreeSpeedEstimator` is a robust engineering estimate based on bottom-center image motion, local per-interval scale from vehicle-width priors, dominant-motion projection, trimmed observations, cumulative pseudo-metric trajectory fitting, Theil-Sen regression, estimator agreement, and explicit uncertainty. It is not a reproduction of the 2026 36-keypoint research method.

The paper `Calibration-Free Vehicle Speed Estimation: A Monocular Keypoint-Template Approach` (arXiv:2608.16785, Aug. 17, 2026) proposes a 36-keypoint metric vehicle template and a homography re-estimated at every frame. It selects approximately coplanar semantic keypoints for vehicle facets; at least four non-collinear correspondences are used, with RANSAC for outlier rejection. It then supports both sparse semantic-keypoint tracking and dense warped optical flow within the selected facet. citeturn715612view1

The current repository does not contain the paper authors' 36-keypoint trained checkpoint or a verified public inference artifact. Therefore the project must not claim that its current estimator reproduces that model or its reported error rates.

## 36-keypoint model/dataset research completed — 2026-09-06

### Existing YOLO vehicle-pose implementation checked

`Habib0905/Vehicle-Pose-Estimation` is a useful YOLOv8 vehicle-pose reference, but its configuration is explicitly `kpt_shape: [14, 3]`, not 36. It is based on CarFusion plus additional Bangladesh traffic data and provides downloadable `best.pt` / `last.pt` weights. It is therefore a training/reference baseline, not the required 36-keypoint solution. citeturn343474view1

Changing `kpt_shape` from 14 to 36 in that project would change the output shape expected by the network, but it would not create labels or a trained 36-point model. A real 36-point dataset and corresponding annotations are still required.

### CarFusion checked

The official CMU CarFusion project provides 14 semantic keypoints for 100,000 vehicle instances across 53,000 images from 18 moving cameras at Pittsburgh intersections. Access is provided for research purposes through the project page. citeturn343474view2 The public conversion repository exposes the 14-keypoint annotation pipeline. This makes CarFusion valuable for bootstrapping vehicle-pose training, but it cannot directly supply the 36-keypoint target schema.

A current public Hugging Face artifact `kiselyovd/vehicle-keypoints` provides a YOLO26-pose checkpoint trained on CarFusion with the canonical 14-keypoint schema; its code and weights are listed as MIT, while the underlying CarFusion dataset retains Carnegie Mellon terms. It is explicitly a research/educational artifact and not validated for safety-critical deployment. citeturn822412search0turn273799search2

### SKoPe3D checked

SKoPe3D is a synthetic CARLA-based roadside traffic dataset containing 33 keypoints per vehicle, more than 25,000 images, 28 scenes, over 150,000 vehicle instances, and roughly 4.9 million keypoints according to its paper. The project evaluates Keypoint R-CNN and explicitly targets traffic-monitoring viewpoints. citeturn912271academia68turn715612view0

SKoPe3D is therefore highly relevant to this project because it matches roadside traffic monitoring better than CarFusion and provides dense vehicle keypoint supervision. However, it is 33 points, not 36. The published material identifies the project/paper license as CC BY-NC-SA 4.0. citeturn273799search8 Any use in this project must preserve that license constraint and must not be treated as an unrestricted commercial dataset.

### Exact 36-keypoint target status

The exact 36-keypoint target required for the calibration-free speed method is currently the paper's metric vehicle template, not an already verified public dataset/checkpoint found in this search. The research paper describes a sedan-based metric template with 36 semantic keypoints and uses subsets associated with approximately planar vehicle facets. citeturn715612view1

Therefore the implementation plan is:

1. keep the existing YOLO26n detector for object detection;
2. keep ByteTrack-inspired identity tracking independent from keypoint inference;
3. add a `VehicleKeypointEstimator` abstraction;
4. prototype the keypoint pipeline using an available 14-point YOLO26-pose CarFusion model where useful for parser/inference integration tests;
5. use SKoPe3D as a possible synthetic pretraining/transfer source, subject to its non-commercial license;
6. build or obtain an actual 36-point labeled training set matching the paper's semantic/template ordering;
7. train/fine-tune a small YOLO pose model with `kpt_shape: [36, 3]` (or an equivalent architecture) rather than pretending that changing the config alone creates a 36-point model;
8. export a compatible Android inference artifact only after verifying output layout, latency, and keypoint quality;
9. feed reliable keypoint correspondences into dynamic per-frame homography + RANSAC, then use sparse keypoint tracking and/or facet-restricted warped optical flow for calibration-free metric displacement;
10. retain the current width-prior speed estimator as a fallback when the keypoint backend is unavailable or insufficiently confident.

### Model-selection conclusion

`YOLOv8-Pose` or `YOLO11-Pose` is a valid architectural family for a custom 36-point model, but neither one becomes a 36-point vehicle model merely by changing `nk`/`kpt_shape`. The network must be trained with 36-point vehicle annotations. `Keypoint R-CNN` is also a legitimate alternative, and SKoPe3D itself used it as a baseline, but integrating it into this Android CPU-only repository is a larger deployment path than a compact YOLO pose head.

For this repository, the preferred production-engineering direction is therefore a compact YOLO pose backend with a custom 36-point vehicle schema, while keeping the detector (`yolo26n.tflite`) and tracker separate. The 14-point CarFusion model is a temporary compatibility/reference model only; it must never be labeled as the final 36-keypoint model.

## Current repository model note

The repository already contains `android/app/src/main/assets/models/yolo26n.tflite`; this remains the object detector and is not the vehicle-keypoint model. The keypoint backend must be introduced as a separate model artifact and interface so detection/tracking continue to work if the keypoint model is absent or disabled.

## Validation / stopping point

The latest repository history contains the render-track separation commit `1dd15ffa7bf0d214f99c0b018d42748e3c61d375` and the context documentation commit `bab0dcf220d1851c0153e2cbe3d0b824519e3b35`. Validation must be checked against the actual latest commit before claiming CI completion.

The next engineering milestone is not to tune another arbitrary tracker coefficient. It is to implement the explicit render-state lifecycle and source-PTS alignment, then introduce the `VehicleKeypointEstimator` abstraction and a measured 36-keypoint training/inference path backed by real annotations.
