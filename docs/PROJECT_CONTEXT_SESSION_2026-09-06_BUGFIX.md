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

The next pass focused on making the on-video tracking visually continuous instead of merely showing the latest detector box. Current research and reference implementations indicate that robust traffic tracking should separate three concerns: identity association, state prediction during missed observations/occlusion, and render-time temporal smoothing. Current Ultralytics documentation describes ByteTrack as the lightweight baseline, BoT-SORT as adding camera-motion compensation and optional ReID, and OC-SORT as adding observation-centric correction/recovery for non-linear motion and occlusion.

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

Commit `1dd15ffa7bf0d214f99c0b018d42748e3c61d375` introduced an explicit `renderTracks` field in `AnalysisPreviewFrame`. `tracks` remains the analytics-active set, while `renderTracks` is reserved for short-lived render-only predictions through brief detector gaps. This establishes the correct contract: render continuity must never be fed back into speed estimation or enforcement analytics.

### Video/track clock alignment

The renderer currently derives a timeline origin from available track observations and offsets that by `player.currentPosition`. This reduces common non-zero-start PTS misalignment, but it is not yet definitive because the earliest visible track observation is not guaranteed to equal the media PTS origin.

A fixed source-start timestamp field from `FrameSource` metadata remains required for the final implementation and must be validated with videos whose media starts at non-zero PTS.

### Calibration-free speed and the 36-keypoint research path

The current `CalibrationFreeSpeedEstimator` is a robust engineering estimate based on bottom-center image motion, local per-interval scale from vehicle-width priors, dominant-motion projection, trimmed observations, cumulative pseudo-metric trajectory fitting, Theil-Sen regression, estimator agreement, and explicit uncertainty. It is not a reproduction of the 2026 36-keypoint research method.

The paper `Calibration-Free Vehicle Speed Estimation: A Monocular Keypoint-Template Approach` (arXiv:2608.16785, Aug. 17, 2026) proposes a 36-keypoint metric vehicle template and a homography re-estimated at every frame. It selects approximately coplanar semantic keypoints for vehicle facets; at least four non-collinear correspondences are used, with RANSAC for outlier rejection. It then supports both sparse semantic-keypoint tracking and dense warped optical flow within the selected facet. This is the target geometry architecture for the project, not a claim that the present estimator reproduces the paper.

The current repository does not contain the paper authors' 36-keypoint trained checkpoint or a verified public inference artifact. Therefore the project must not claim that its current estimator reproduces that model or its reported error rates.

## 36-keypoint model/dataset research completed — 2026-09-06

### Existing YOLO vehicle-pose implementation checked

`Habib0905/Vehicle-Pose-Estimation` is a useful YOLOv8 vehicle-pose reference, but its configuration is explicitly `kpt_shape: [14, 3]`, not 36. It is based on CarFusion plus additional Bangladesh traffic data and provides downloadable `best.pt` / `last.pt` weights. It is a training/reference baseline, not the required 36-keypoint solution.

Changing `kpt_shape` from 14 to 36 in that project would change the configured output dimensionality, but it would not create 36-point labels or a trained 36-point model. A real 36-point dataset and corresponding annotations are required.

### CarFusion checked

The official CMU CarFusion project provides 14 semantic keypoints for large-scale vehicle data and is useful for bootstrap pose experiments. It cannot directly supply the final 36-keypoint target schema.

A current public Hugging Face artifact `kiselyovd/vehicle-keypoints` provides a YOLO26-pose checkpoint trained on CarFusion with the canonical 14-keypoint schema. It is a compatibility/reference model only and must never be called the project's final 36-point model.

### SKoPe3D checked

SKoPe3D is a synthetic CARLA-based roadside traffic dataset containing 33 keypoints per vehicle, more than 25,000 images, 28 scenes, over 150,000 vehicle instances, and roughly 4.9 million keypoints according to its paper. The project evaluates Keypoint R-CNN and explicitly targets traffic-monitoring viewpoints.

SKoPe3D is highly relevant because it matches roadside traffic monitoring better than ordinary vehicle datasets, but it is 33 points, not 36, and its published license is non-commercial/share-alike. It must not be treated as an unrestricted dataset.

### Exact 36-point lineage found: Deep MANTA / Ansari / PAMTRI

The research search found a direct, mature lineage for the 36-point vehicle model rather than only generic pose architectures.

Deep MANTA describes a 36-part vehicle model and uses vehicle geometry to perform 2D/3D analysis. The Ansari vehicle-pose work also uses a 36-keypoint vehicle wireframe. PAMTRI's official code is especially valuable because its `PoseEstNet` contains the actual 36-joint training/evaluation data contract used for the vehicle pose model.

The PAMTRI repository explicitly sets `self.num_joints = 36` and provides the exact left/right symmetry mapping:

`0↔18, 1↔19, 2↔20, 3↔21, 4↔22, 5↔23, 6↔24, 7↔25, 8↔26, 9↔27, 10↔28, 11↔29, 12↔30, 13↔31, 14↔32, 15↔33, 16↔34, 17↔35`.

Its annotations are stored as:

`image_name,width,height,(x,y,visibility) * 36`.

The official README also states that the 36-keypoint vehicle model is used with 13 vehicle-surface segments. This is the strongest available machine-readable 36-point contract found during this session. The semantic names themselves are not exposed as a simple authoritative text table in the repository, so the project must continue using index-based names until the exact Figure/annotation semantics are frozen.

PAMTRI also provides pretrained pose models and code, but its implementation is an older HRNet-based research stack rather than a lightweight Android-ready model. Its source code is under the NVIDIA Source Code License. It is therefore a reference for the 36-point schema/training representation, not a binary to copy blindly into the Android APK.

## 36-keypoint implementation work committed to `main`

### 1. Vehicle pose model registry

Commit `041d7a4c3213303571d994216917759e7407585c` added:

`android/app/src/main/java/com/smarttraffic/app/data/vision/VehiclePoseModelRegistry.kt`

The registry defines separate metadata for:

- a 14-point CarFusion/YOLO26-pose reference model;
- the target 36-point vehicle-template model.

The 36-point entry is deliberately `readyForInference=false` and requires a real trained artifact before inference. It declares that an ordinary one-class Ultralytics-style pose export with 8400 candidates would have `5 + 36*3 = 113` output channels.

### 2. LiteRT vehicle keypoint backend

Commit `a7f966f4e171f236685c298975ee3daf904fdd2f` added:

`android/app/src/main/java/com/smarttraffic/app/data/vision/LiteRtVehicleKeypointEstimator.kt`

The backend:

- runs independently from YOLO26n object detection;
- uses CPU LiteRT like the existing detector;
- crops each detected vehicle and applies the existing letterbox preprocessing;
- validates the expected flattened output size before parsing;
- supports the classic channel-major YOLO pose layout `[1, 5 + 3*K, 8400]`;
- maps crop/letterbox coordinates back to source-frame pixels;
- returns only confident keypoints;
- returns an empty keypoint list rather than altering detector/tracker identity if pose inference fails.

This is a deployment adapter, not proof that the final 36-point model exists.

### 3. Runtime integration

Commit `452c4068a48df52b22ed82ae23a0d5154a5db5ad` first added runtime construction for optional pose. It was then cleaned up in `9ef470d8eaf779c15da59674056e6e39e8b76aaa` so the final design uses explicit lifecycle-scoped injection with no global holder.

`AnalysisRuntimeFactory.DetectorRuntime` now contains an optional `keypoints` backend and closes it independently from the detector. The backward-compatible detector-only factory remains available.

Commit `8fd1994ffb18f6472b8474fb9c33feb986c2194e` updated `LocalAnalysisViewModel` to explicitly pass `runtime.keypoints` into `ModularAnalysisEngine` and to enable it only when `AnalysisConfig.useVehicleKeypoints` is true.

Commit `de9d8127324a662706ae81e8476d594ad0bfd84d` keeps `ModularAnalysisEngine` keypoint injection explicit and does not depend on a process-global runtime.

The existing detector and tracker therefore remain independent from pose inference.

### 4. 36-point training contract

Commit `39df7eed0ac3c3d657d177fa66dfa48b3a44da2d` added the dataset contract at:

`ai/vehicle_pose/vehicle_pose_36.yaml`

It was refined in `6ac8ef309dba9c12ad13f1662ac3f57529681caa` to record the PAMTRI 18↔18 flip mapping. The dataset contract uses:

```yaml
kpt_shape: [36, 3]
```

and a one-class `vehicle` task.

The semantic names remain index-based placeholders (`manta_kp_00 ... manta_kp_35`) because inventing names from the figure would be unsafe. The verified flip mapping is authoritative for the PAMTRI index convention.

### 5. PAMTRI-to-YOLO converter

Commit `fb86c4b8b3dc74127b3600121945b4b5c4b5261a` added:

`ai/vehicle_pose/convert_pamtri_veri_to_yolo.py`

It consumes the PAMTRI/VeRi 36-point CSV format and writes single-class Ultralytics pose labels with:

`5 + 36*3 = 113` values per object line after the class id representation is flattened into the normal YOLO pose structure.

Visibility is converted to a YOLO pose visibility value, and a complete annotated bbox is derived from the 36-point extent for bootstrap experiments.

This converter is explicitly for research/bootstrap use; it does not imply that PAMTRI labels are automatically compatible with the 2026 paper's metric template semantics.

Commit `7627858aa30f02a3c61ede70606140ac355a5f7b` added `ai/tests/test_vehicle_pose_converter.py` to validate label field count, visibility handling, and invalid-row rejection.

### 6. Vehicle dynamic homography + RANSAC

Commit `557b80f6140fd534c89f45b198cf64756bb517dd` added:

`android/app/src/main/java/com/smarttraffic/app/domain/analysis/VehicleKeypointHomography.kt`

The implementation is pure Kotlin and independent of the keypoint model semantics. It provides:

- image/template point correspondences;
- normalized DLT homography fitting;
- deterministic RANSAC over four-point subsets;
- non-collinearity/degeneracy rejection;
- reprojection-error gating;
- inlier refinement using the normalized DLT fit;
- median/max reprojection error reporting;
- projective point projection.

This is the geometry layer required by the dynamic keypoint-template speed architecture. It intentionally does not hard-code the paper's semantic index map.

Commit `e74ebcc188292eb47b2ecb9291198d8863e74166` added JVM tests for:

- recovery of a synthetic projective transform;
- gross-outlier rejection by RANSAC;
- rejection of collinear correspondence sets.

### 7. Pose configuration contract

Commit `70bbeac04ff94aa7983b86d0d6a30e2ddaa4c6b6` added `VehicleKeypointConfig.kt` with:

- `DEFAULT_MODEL_ID = vehicle-keypoints-36-template`;
- `REFERENCE_14_MODEL_ID = vehicle-keypoints-14`;
- `TARGET_KEYPOINT_COUNT = 36`.

The existing `AnalysisConfig.useVehicleKeypoints` remains false by default, so the unfinished 36-point backend cannot accidentally break normal analysis.

### 8. Model contract tests

Commit `162957e3a3145303858a309aa7c858dbffc4d7e5` added tests for the 14-point reference contract and the 36-point `[1,113,8400]` output-size contract, including the requirement that the 36-point spec is not yet marked ready for inference.

Commit `7fa40b816ec44691d83825bb4907ae346bd478a3` added parser tests for letterbox/source coordinate mapping and low-confidence keypoint filtering.

## Important model-selection conclusion

The project must not simply download `yolo26n-pose.pt`, `yolov8-pose`, or `yolo11-pose` and rename it as a 36-point vehicle model. Generic YOLO pose defaults target other keypoint schemas, and the public vehicle model found in this search is 14-point.

The intended final architecture remains:

`YOLO26n detector → ByteTrack identity → VehicleKeypointEstimator → 36 keypoints → dynamic homography/RANSAC → metric trajectory → calibration-free speed → cinematic renderer`

Keypoint inference remains a separate concern from identity tracking. A temporary 14-point model can be used to exercise the inference plumbing, but the final 36-point backend requires labels that match the chosen 36-point semantic/template ordering.

Keypoint R-CNN remains a legitimate alternative. SKoPe3D demonstrates its use for roadside vehicle pose. However, for this Android CPU-first project, a compact YOLO pose backend is the preferred production engineering path because it fits the existing LiteRT deployment and model registry architecture more naturally.

## CI validation state — current implementation head

The repository's latest `main` head at the end of this implementation pass is:

`7627858aa30f02a3c61ede70606140ac355a5f7b`

GitHub Actions Run #494 (`34053108853`) is running against this exact commit. At the latest observed state:

- Offline Research Math: passed;
- Native C++ Parity Vectors: passed;
- ESP32 Camera Firmware: still running, with the AI-Thinker target already passed and the ESP32-S3 target still building;
- Android Build & Test: still running during the last poll, with setup complete and the debug APK build in progress.

The full run must finish before this implementation is declared CI-green. Earlier runs were cancelled because the workflow is configured with `cancel-in-progress: true`; therefore only the final-head run is relevant for validation.

## Local validation limitation

A direct `git clone` attempt in the current execution environment failed because the environment could not resolve `github.com`. Consequently, the implementation was validated through the GitHub repository itself and GitHub Actions rather than a local Android Gradle invocation from a cloned working tree.

## Current engineering state and remaining work

Completed in this pass:

1. verified 36-point research lineage and PAMTRI's machine-readable 36-joint contract;
2. separated YOLO26n detection from vehicle pose;
3. added 14-point and target-36 model metadata contracts;
4. added an optional LiteRT pose runtime;
5. explicitly injected pose into local analysis without a global runtime bridge;
6. added a 36-point dataset contract and PAMTRI converter;
7. added normalized DLT/RANSAC dynamic homography geometry;
8. added unit tests for model contracts, parser transforms, converter, and homography;
9. kept the unfinished 36-point model disabled by default and refused to fabricate a checkpoint.

Still required before claiming the research method is implemented end-to-end:

1. freeze the exact 36 semantic index/template coordinates against the authoritative MANTA/Ansari/PAMTRI representation and the 2026 paper;
2. build or acquire a legal dataset whose 36 labels match that metric template rather than merely having 36 arbitrary vehicle keypoints;
3. train a compact vehicle pose model with exactly that schema;
4. export it to a verified Android-compatible LiteRT artifact and record SHA-256/provenance;
5. integrate per-frame facet selection and dynamic H into `AnalysisPipelineRunner` so keypoint geometry is actually consumed by the speed estimator;
6. add robust metric displacement/speed from the keypoint template and optionally facet-restricted warped optical flow;
7. compare the keypoint speed against the existing width-prior calibration-free estimator and expose uncertainty rather than silently replacing it;
8. finish render-track population, speed-label smoothing, and fixed source-PTS origin;
9. run physical Android-device tests with a real traffic video, including a non-zero media PTS sample;
10. establish a labelled tracking/speed benchmark before reporting numerical accuracy.

The project must not report the 2026 paper's MAE figures as project results until this project's own benchmark reproduces them under a documented protocol.
