# Smart Traffic Vision & Measurement Architecture

Last updated: 2026-09-02

## Goal

Build a camera-first traffic analytics engine that converts live video or local recordings into auditable vehicle observations:

`frames -> detection -> tracking -> geometry -> metric distance -> velocity -> plate/OCR -> rules -> evidence`

The engine must report uncertainty and validation metrics rather than presenting an unverified number as ground truth.

## 1. Detection

### Baseline candidate

Use a current real-time detector with a configurable model backend. YOLO26 is a strong baseline for this project because current documentation exposes small through large variants and emphasizes edge deployment; the comparison data also shows a useful accuracy/latency trade-off across model sizes. The project should not hard-code one model forever.

Recommended deployment tiers:
- **Mobile / low compute:** small detector, quantized when supported.
- **Higher-end Android:** medium detector when latency budget allows.
- **Offline research:** larger detector for accuracy experiments.

The training target should eventually be traffic-specific rather than relying only on generic COCO classes. Classes should include at least car, truck, bus, motorcycle and optionally bicycle/person for configured safety analytics.

### Detection outputs

For every frame:
- class;
- bounding box;
- confidence;
- timestamp/frame index;
- optional segmentation/keypoints for advanced geometry.

Small/distant vehicles require explicit evaluation because object size and occlusion strongly affect detection quality. Recent 2026 edge-traffic work emphasizes robustness under illumination, density and occlusion constraints.

## 2. Multi-object tracking

Tracking is not simply "the next box". Each vehicle receives a persistent track identity and a temporal state.

Candidate trackers:
- **ByteTrack:** fast baseline; associates high- and low-confidence detections in stages.
- **BoT-SORT:** preferred general baseline when camera motion compensation or optional appearance ReID is useful.
- **OC-SORT / Deep OC-SORT:** useful alternatives for difficult motion and occlusion scenarios.

The implementation should expose tracker selection as configuration and benchmark them on the same clips. Current Ultralytics tracking documentation supports these tracker families and describes BoT-SORT's optional ReID and camera-motion compensation. 

Track state:
- track ID;
- class;
- bounding box history;
- velocity state;
- trajectory;
- visibility/age;
- detection confidence history;
- occlusion state;
- track confidence;
- event associations.

## 3. Ground contact point

Do not use the bounding-box center as the road position by default. For ground vehicles, estimate a road-contact point near the lower center of the vehicle box. When a trained vehicle keypoint model is available, use a stable wheel/footprint-related keypoint or learned contact point.

This reduces perspective-induced speed errors compared with using an arbitrary box center.

## 4. Camera calibration and road geometry

The core physical measurement layer is the mapping from image coordinates to the road plane.

### Preferred calibrated path

1. Intrinsic camera calibration.
2. Lens distortion correction.
3. Identify a road-plane reference.
4. Estimate a homography from image points to metric road coordinates.
5. Project each track's contact point into meters.
6. Compute displacement in the metric plane.

OpenCV provides standard camera calibration and perspective/homography primitives suitable for this layer.

### Calibration profiles

Each camera must have a saved calibration profile containing:
- image resolution;
- intrinsic matrix;
- distortion coefficients;
- road-plane homography;
- calibration reference points;
- coordinate convention;
- validity/version;
- calibration quality/error estimate.

Calibration must be tied to a camera/profile and not treated as a universal constant.

### Research fallback

A calibration-light / calibration-free research mode may be evaluated for scenes where roadway references are unavailable. A recent 2026 monocular-speed paper uses vehicle keypoint templates with per-frame homography updates and reports dataset-specific errors; this is research evidence, not permission to claim enforcement-grade accuracy. The production prototype should prefer explicit road calibration whenever possible.

## 5. Metric distance

For a tracked contact point `p_t`:

`P_t = H * p_t`

then normalize homogeneous coordinates and obtain ground-plane coordinates in meters.

Distance between samples:

`d = hypot(x_2 - x_1, y_2 - y_1)`

Cumulative traveled distance should be separated from direct displacement so curves/turns can be handled correctly.

Reject or down-weight points when:
- the point is outside the calibrated road polygon;
- homography confidence is poor;
- the detection is partially lost;
- frame timestamps are invalid;
- the track has an identity switch.

## 6. Speed estimation

Never calculate speed from raw pixel movement.

Primary method:

`v = distance_meters / delta_time_seconds`

Convert to km/h only at the presentation boundary:

`km/h = m/s * 3.6`

### Robust estimator

For each track:
1. collect metric positions over time;
2. resample only when necessary;
3. smooth position with a state estimator;
4. calculate velocity from multiple samples instead of one-frame differences;
5. reject outliers;
6. compute a robust local estimate using median/weighted regression;
7. output instantaneous and stabilized speed;
8. attach speed confidence/error bounds.

A Kalman-style state model can estimate position and velocity, while robust regression/median filtering handles noisy detections. The exact filter parameters must be tuned against validation videos rather than guessed.

### Track quality gates

Do not publish a speed event unless:
- track has existed for a minimum duration;
- enough metric samples are available;
- calibration is valid;
- position residual is below threshold;
- velocity estimate is stable;
- identity confidence is sufficient.

## 7. Direction and lanes

Direction should be derived from the ground-plane trajectory, not screen-space left/right alone.

Compute:
- signed longitudinal velocity;
- heading angle;
- lane/zone membership;
- entry/exit crossing events.

The lane/zone model should be configurable per calibrated camera.

## 8. Event measurement

A rule event should contain:
- event ID;
- track ID;
- camera ID;
- event timestamp;
- event type;
- measured speed;
- configured limit;
- confidence;
- metric position/zone;
- evidence frame references;
- plate result when available;
- calibration version;
- algorithm version.

This makes later auditing and reprocessing possible.

## 9. License plate pipeline

Plate recognition should be a separate stage from vehicle detection:

`vehicle track -> plate detector/crop -> perspective rectification -> image enhancement -> OCR -> temporal aggregation -> normalized plate`

Do not trust a single OCR frame. Aggregate plate hypotheses across multiple frames of the same track, weighting by plate crop quality, sharpness and OCR confidence.

Plate normalization must be configurable because formats differ by country/region. The example project format `1/52863` is only a sample.

## 10. Evidence capture

When a rule or watchlist event is confirmed, save a reproducible evidence package:
- best vehicle frame;
- optional plate crop;
- pre/post event frames when available;
- measured speed;
- timestamps;
- track trajectory;
- camera/calibration IDs;
- rule version;
- model/tracker versions;
- confidence values.

Evidence should be immutable after creation in the future storage layer, with retention policy handled separately.

## 11. Radar screen = visualization layer

The "Intelligent Radar" is not a separate physical measurement algorithm.

It visualizes the same analysis engine state:
- detections;
- persistent tracks;
- trajectories;
- direction;
- metric speed;
- confidence;
- violations;
- watchlist matches.

Local Analysis and Live Radar must call the same engine interface so a test clip can be replayed and compared with live operation.

## 12. Local Analysis Lab test methodology

Every test clip should support repeatable experiments.

### Test setup

Operator chooses:
- media file;
- test profile;
- detector/tracker configuration;
- calibration profile;
- optional road zones/lanes;
- minimum confidence;
- reference distance;
- expected/reference speed where available;
- output overlay settings.

### Test stages

`Preflight -> Decode -> Detect -> Track -> Geometry -> Speed -> Plate -> Rules -> Evidence -> Metrics`

The operator may run the full pipeline or an isolated stage.

### Measurements

Detection:
- precision/recall/mAP when annotated ground truth exists;
- confidence distribution.

Tracking:
- IDF1/HOTA/MOTA where ground truth is available;
- ID switches;
- track fragmentation;
- lost-track duration.

Geometry:
- reference-point error in meters;
- homography reprojection error.

Speed:
- MAE in km/h;
- mean/median percentage error;
- RMSE;
- percentile absolute error (P50/P90/P95);
- percentage within +/-5, +/-10 and +/-20% of reference;
- event false-positive/false-negative counts.

Plate:
- character accuracy;
- full-plate exact-match rate;
- confidence vs correctness;
- temporal consensus stability.

Runtime:
- decode FPS;
- inference latency;
- end-to-end latency;
- dropped frames;
- memory/thermal observations on supported devices.

## 13. Ground-truth strategy

For the university prototype, create a controlled validation set rather than relying only on visual inspection.

Recommended reference sources:
- known road distances measured physically;
- known-distance calibration markers;
- reference speed from a validated independent measurement source when available;
- hand-annotated tracks for a subset of clips;
- hand-verified plates.

Validation clips should cover:
- daylight;
- dusk/night;
- shadows/glare;
- rain when possible;
- different vehicle sizes;
- partial occlusion;
- dense traffic;
- approaching and receding vehicles;
- multiple lanes;
- different camera heights/angles.

## 14. Model strategy for Android

Do not immediately ship the largest model.

The Android runtime should have an inference abstraction so the same application can switch among:
- a lightweight CPU/mobile backend;
- an NNAPI/GPU/NPU backend where available;
- a research backend for offline analysis.

Model file size, latency, memory and accuracy must be measured on the actual phone. A model that wins desktop benchmarks may lose on the target Android device.

## 15. Development order

Phase A — data contracts
- Detection
- Track
- GroundPoint
- SpeedEstimate
- PlateReading
- TrafficEvent
- AnalysisConfig
- CalibrationProfile

Phase B — deterministic geometry
- camera calibration profile;
- homography solver;
- image-to-ground projection;
- metric distance;
- basic speed estimator;
- unit tests using synthetic coordinates.

Phase C — perception
- detector adapter;
- tracker adapter;
- trajectory manager;
- quality gates.

Phase D — local analysis
- video decoder;
- frame clock;
- engine runner;
- overlays;
- metrics report.

Phase E — ESP32 live source
- MJPEG stream;
- frame timestamps;
- reconnect/backpressure;
- live engine input.

Phase F — plate/OCR and event system
- plate detector;
- OCR;
- temporal fusion;
- rules/watchlist/evidence.

Phase G — optimization and validation
- quantization;
- GPU/NPU where available;
- profiling;
- accuracy regression tests;
- calibration validation.

## Research basis

Current web research on 2026-09-02 reviewed current Ultralytics tracking documentation, current YOLO26/RT-DETR comparisons, recent 2026 vehicle-speed research, and recent edge vehicle-detection work. The selected architecture deliberately combines mature geometric measurement with replaceable modern detection/tracking backends rather than betting the entire project on one model.

References:
- Ultralytics multi-object tracking documentation: https://docs.ultralytics.com/modes/track
- Ultralytics tracker reference: https://docs.ultralytics.com/reference/trackers/bot_sort
- Ultralytics YOLO26 vs RT-DETR comparison: https://docs.ultralytics.com/compare/yolo26-vs-rtdetr
- 2026 monocular speed estimation research: https://arxiv.org/abs/2608.16785
- 2026 real-time vehicle detection edge study: https://link.springer.com/article/10.1007/s44163-026-00885-1
