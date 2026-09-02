# Advanced Vision Stack — Smart Traffic

Last updated: 2026-09-02

## Objective

Build the strongest practical camera-first traffic analytics stack that can run in two modes:

1. **Live mode:** ESP32-CAM/local camera → Android → analysis engine → operator UI.
2. **Research mode:** recorded video/image → identical analysis engine → repeatable metrics.

The architecture is deliberately modular. No single neural model is treated as universally best; detector, tracker, geometry, segmentation, OCR and speed-estimation components are independently replaceable and benchmarked.

## Perception stack

### 1. Primary vehicle detector

Candidates to benchmark:
- **YOLO26**: edge-oriented current baseline with small-to-large model variants.
- **RT-DETRv2**: transformer-based real-time detector for accuracy-oriented comparisons.
- Future traffic-specialized detector trained/fine-tuned on the project's own camera domain.

YOLO26 should be the first Android/edge candidate, while RT-DETRv2 remains an offline/research comparison. Public comparison tables show the expected accuracy/latency trade-off rather than a universal winner, so final selection must be made on the target phone and real traffic clips.

### 2. Multi-object tracking

Expose tracker selection:
- BoT-SORT — default general candidate, with Kalman prediction, optional ReID and camera-motion compensation.
- ByteTrack — low-overhead baseline.
- OC-SORT — observation-centric motion handling.
- Deep OC-SORT — OC-SORT plus optional appearance matching.
- FastTracker — rollback/occlusion-oriented option.
- TrackTrack — multi-cue association and duplicate-ID suppression.

The tracker is evaluated with HOTA, IDF1, MOTA, ID switches, fragmentation and track survival duration where annotations are available.

### 3. Camera motion compensation

For a fixed traffic camera this can normally be minimized, but the engine supports:
- sparse optical flow;
- ORB feature alignment;
- ECC alignment;
- SIFT where available.

BoT-SORT already exposes configurable global-motion compensation backends. This layer is also useful for shake and moving-camera test footage.

## Geometry and physical measurement

### 4. Camera calibration

Use intrinsic calibration and lens-distortion correction whenever possible.

Store per-camera:
- focal parameters;
- principal point;
- distortion coefficients;
- resolution;
- calibration version;
- reprojection error;
- validity state.

### 5. Road-plane homography

Map image points to metric road coordinates:

`P_ground ~ H * p_image`

with homogeneous normalization.

Use RANSAC/robust estimation in the native/OpenCV implementation when correspondences contain outliers. OpenCV provides `findHomography` and `perspectiveTransform` primitives for this class of operation.

### 6. Vehicle contact geometry

Never assume the bounding-box center is the road position.

Priority order:
1. learned ground/contact keypoint when available;
2. lower-center contact estimate from the vehicle mask/box;
3. fallback geometry point only when quality gates allow it.

## Vehicle keypoints + dynamic homography research mode

A dedicated experiment switch exists in Local Analysis:

**Vehicle Keypoints + Dynamic Homography**

This is based on a recent 2026 monocular-speed research direction using a **36-keypoint vehicle template**, per-frame homography updates and either keypoint-only tracking or warped optical flow. Reported accuracy is dataset-specific; the paper reports MAE values that improve after trimming edge-of-frame outliers, demonstrating that the approach is promising but not a license to claim universal or enforcement-grade accuracy.

In our architecture this method is strictly a benchmarkable research mode. It must produce its own calibration/geometry quality and speed-error statistics.

## Motion estimation

### 7. Optical flow

Use optical flow as a supporting signal rather than blindly replacing object tracking. Potential roles:
- camera-motion compensation;
- tracking refinement between detector frames;
- dense motion evidence inside vehicle regions;
- recovery/diagnostics when detector confidence temporarily drops.

### 8. Temporal state estimation

Maintain object position and velocity as a temporal state. Candidate approaches:
- Kalman filtering for efficient online state prediction;
- robust median filtering for resistant speed estimates;
- robust regression over multiple metric samples;
- outlier rejection with MAD/Huber-style gating;
- track-quality gates before publishing speed.

## Optional segmentation refinement

SAM 2 / SAM 2.1 is retained as a research/quality-refinement component for difficult vehicle boundaries, annotation, selected-target refinement and occlusion analysis. It is not the default per-frame Android detector because its full video foundation-model workload is heavier than the primary real-time detector/tracker path.

Grounded-SAM-2 demonstrates combinations of open-vocabulary detection/grounding with SAM 2 video segmentation/tracking, and can be valuable for offline dataset creation, difficult-scene experiments and annotation assistance.

## Speed estimation architecture

For each valid track:

`image contact points`
→ `road-plane projection`
→ `metric positions`
→ `time-aligned trajectory`
→ `robust velocity`
→ `confidence/error`

Never derive physical speed directly from pixel displacement.

Use:
- multi-frame windows;
- valid timestamp intervals;
- metric distance, not pixel distance;
- trajectory smoothing;
- robust outlier rejection;
- stability tests;
- explicit uncertainty.

A violation should not be emitted until calibration quality, track duration, sample count, residual/stability and speed confidence pass configured gates.

## License plate stack

Pipeline:

`vehicle track`
→ `plate detector`
→ `plate crop`
→ `perspective rectification`
→ `illumination/contrast/sharpness checks`
→ `OCR`
→ `temporal hypothesis aggregation`
→ `country/region normalization`
→ `plate confidence`

Candidate OCR backends should be benchmarked on locally collected plates rather than selected only from generic OCR benchmarks. Multiple frames from a single vehicle track should be fused, allowing high-quality frames to dominate noisy readings.

## Depth and scene understanding

Monocular depth models such as MiDaS/DPT-family models are useful as a **relative-depth consistency signal** and research feature. They should not be treated as a direct meter-per-pixel speed measurement without a physical scale/calibration source.

Depth can help with:
- ordering vehicles by relative depth;
- detecting implausible trajectories;
- occlusion reasoning;
- research comparisons against calibrated geometry.

## Object identity / Re-ID

Appearance embeddings are optional and should be enabled when ID switches from visually similar vehicles become a problem. Re-ID is most valuable for longer occlusions and crowded scenes; it adds compute, so the lab must compare accuracy gain against latency and battery/thermal cost.

## Detection/track scheduling

Do not run every expensive model on every frame.

A practical scheduler can:
- decode every frame or a controlled sampling rate;
- run primary detector at a configurable cadence;
- propagate tracks between detector frames using motion/optical-flow cues;
- run keypoint/segmentation refinement only on selected tracks;
- increase analysis frequency for fast/uncertain targets;
- reduce workload for stable distant/background objects.

This creates an accuracy/latency budget instead of a single fixed inference cost.

## Quality fusion

Maintain separate scores:
- detection confidence;
- tracking confidence;
- keypoint confidence;
- calibration quality;
- geometric reprojection error;
- temporal stability;
- OCR confidence;
- final event confidence.

Final event confidence should be a conservative fusion of evidence rather than a copy of detector confidence.

## Recommended implementation tiers

### Live mobile tier

`YOLO26-small candidate → BoT-SORT → calibrated homography → robust speed estimator`

Optional Re-ID/optical flow only when profiling shows it is justified.

### Accuracy/research tier

`larger detector or RT-DETRv2 → Deep OC-SORT/TrackTrack → keypoints → dynamic homography → optical-flow refinement → optional SAM2 refinement`

### Calibration-free research tier

`vehicle keypoint template → dynamic homography → keypoint tracking / warped optical flow`

This tier is explicitly experimental and is compared against the calibrated baseline.

## Benchmark matrix

Every candidate configuration must be evaluated on the same fixed clips and, when possible, the same annotations.

Report:
- detection: precision, recall, mAP;
- tracking: HOTA, IDF1, MOTA, ID switches, fragmentation;
- geometry: reprojection error, ground-position error;
- speed: MAE, RMSE, median error, P90/P95, ±5/10/20% coverage;
- OCR: character error rate, exact full-plate match, temporal consensus;
- runtime: FPS, detector latency, tracker latency, end-to-end latency, dropped frames;
- device: memory, battery and thermal observations.

## Current research basis

- 2026 monocular calibration-free vehicle speed estimation: arXiv:2608.16785.
- Ultralytics current tracker documentation lists BoT-SORT, ByteTrack, OC-SORT, Deep OC-SORT, FastTracker and TrackTrack and describes their motion/appearance trade-offs.
- Ultralytics current YOLO26 vs RT-DETRv2 comparison documents the accuracy/latency/parameter trade-off across model sizes.
- OpenCV provides production-tested homography primitives (`findHomography`, `perspectiveTransform`) suitable for the native geometry implementation.
- Meta's SAM 2/SAM 2.1 provides streaming-memory video segmentation/tracking and can serve as a research/refinement component.
- Grounded-SAM-2 demonstrates combinations of grounding, segmentation and tracking that are useful for offline traffic-data annotation and difficult-scene analysis.

## Design rule

The system must prefer **measured evidence over model hype**. A component enters the production path only after it wins an experiment on our actual traffic data under the target device's compute budget.
