# Smart Monitoring System — Project Context

Last updated: 2026-09-02

## Project identity
- Repository: `CybersecurityRahbar/Smart-monitoring-system`
- Product: Smart Traffic Monitoring System / Android app Smart Traffic.
- University prototype; not legally certified traffic enforcement equipment.
- Android is the command center; ESP32-CAM is the camera/Wi-Fi endpoint; heavy AI/CV is intended for Android/native acceleration.
- No Map Widget unless explicitly requested.

## Architecture
`Compose UI → ViewModel/StateFlow → Use Cases/Domain → Repositories → Data Sources`

Shared media architecture:
`ESP32 live / local video / image → FrameSource → AnalysisEngine → detection/tracks/geometry/speed/plates/events`

Primary nav:
Dashboard / Intelligent Radar / Live Camera / Alerts / More.
More contains Reports, Analysis Lab, Devices, Traffic Rules, Watchlist, Evidence, Settings.

## Hardware and first milestone
Initial board: ESP32-CAM + OV2640; exact board must be verified from the physical device/photo before final firmware/wiring.
First hardware milestone:
`ESP32-CAM + OV2640 → local Wi-Fi → Android → live MJPEG stream`
Endpoints: `/status`, `/capture`, `/stream`.
No sensors are mandatory for v0.1.

## UI fixes already implemented
- dashboard responsive/scrolling fixes;
- Arabic RTL + English;
- persistent Night mode;
- explicit top Back for nested screens and controlled Android Back behavior;
- editable/persistent Traffic Rules;
- Radar control cleanup;
- shared video viewport Full/Standard/Compact + PiP preparation;
- Incident Reports;
- common operator localization;
- Devices endpoint fields now use larger LTR technical text for `/status`, `/stream`, etc.

## Devices
`core/DeviceSettings.kt` persists host/IP, port and endpoint paths. Devices screen supports editing, Save + Test, Reset and asynchronous `/status` health checks. Runtime IP/port are not hard-coded.

## Local Analysis Lab — required behavior
The user explicitly identified that file selection alone was insufficient. The Lab is a repeatable experiment workspace.

Current UI includes:
- video/image source;
- Full Pipeline / Detect+Track / Speed+Calibration / Plate+OCR test modes;
- known reference distance;
- analysis sample window;
- minimum confidence;
- Start Analysis Test;
- radar/trajectory overlay switch;
- measurement explanations.

Advanced experimental switches now exposed:
- calibrated road homography;
- Vehicle Keypoints;
- **Vehicle Keypoints + Dynamic Homography research mode**;
- Optical Flow refinement;
- Segmentation refinement;
- appearance Re-ID;
- radar/trajectory overlay.

Radar is a visualization/operations layer over the shared AnalysisEngine, not a separate speed algorithm.

## Vision algorithm stack
The goal is the strongest practical modular camera-first stack, selected by measured validation rather than model marketing.

### Detection candidates
- YOLO26: first mobile/edge baseline candidate.
- RT-DETRv2: accuracy/research comparison.
- Future traffic-domain fine-tuned detector.

### Tracking candidates
- BoT-SORT: general candidate with Kalman prediction, optional Re-ID and global-motion compensation.
- ByteTrack: fast baseline.
- OC-SORT.
- Deep OC-SORT.
- FastTracker.
- TrackTrack.

### Geometry
Preferred physical-measurement route:
1. intrinsic camera calibration;
2. lens distortion correction;
3. metric road-plane reference;
4. robust/RANSAC homography;
5. vehicle ground-contact point or learned keypoint;
6. image → metric road coordinates;
7. metric trajectory → velocity.

Never use raw pixel displacement as physical speed.

### Vehicle Keypoints + Dynamic Homography
This is explicitly included as an **optional research mode** in Local Analysis. The current architecture is compatible with the recent 2026 monocular direction using a 36-keypoint vehicle template, dynamic homography updates and keypoint/warped optical-flow alternatives. Results must be benchmarked and reported with actual error; no enforcement-grade accuracy is assumed.

### Supporting methods
- Optical Flow: camera-motion compensation, propagation and motion refinement.
- SAM2/SAM2.1-style segmentation: selected-target refinement, annotation and difficult-scene research, not default heavy mobile per-frame processing.
- Grounded-SAM2-style grounding/segmentation/tracking: offline research and dataset creation.
- Re-ID embeddings: long-occlusion/look-alike vehicle identity recovery.
- Monocular depth: relative-depth consistency/research only, not metric speed without physical scale.

## Current AI/math implementation
Files under `android/app/src/main/java/com/smarttraffic/app/domain/analysis/`:
- `AnalysisModels.kt`: MediaSource, Detection, VehicleKeypoint, TrackObservation, Track, GroundPoint, SpeedEstimate, PlateReading, CalibrationProfile, AnalysisConfig, AnalysisMetrics, AnalysisResult.
- `AnalysisEngine.kt`: ObjectDetector, MultiObjectTracker, VehicleKeypointEstimator, PlateRecognizer, AnalysisEngine and ModularAnalysisEngine coordinator skeleton.
- `GeometryAndSpeed.kt`: HomographyProjector + robust multi-sample/MAD outlier speed estimator.
- `HomographyEstimator.kt`: dependency-free homography solver for deterministic tests; native/OpenCV robust/RANSAC path remains planned.
- `ValidationMetrics.kt`: speed MAE/RMSE/median/P90/P95 and ±5/10/20% tolerance metrics.

A duplicate VehicleKeypoint declaration in `AnalysisEngine.kt` was found and removed; `VehicleKeypoint` is now defined once in `AnalysisModels.kt`.

## Current AnalysisConfig switches
- detector model;
- tracker;
- ground-plane calibration;
- Vehicle Keypoints;
- Dynamic Keypoint Homography;
- Optical Flow refinement;
- Segmentation refinement;
- Re-ID;
- OCR/rules/evidence;
- radar overlay.

## Validation methodology
Detection: precision/recall/mAP when ground truth exists.
Tracking: HOTA/IDF1/MOTA, ID switches, fragmentation, lost duration.
Geometry: reprojection error and ground-position error.
Speed: MAE, RMSE, median/P90/P95 error, ±5/10/20% coverage.
OCR: character error, exact full-plate match, temporal consensus.
Runtime: decode FPS, inference latency, end-to-end latency, dropped frames, memory/thermal observations.

Controlled test clips should cover daylight, dusk/night, glare/shadows, rain when possible, dense traffic, occlusion, multiple lanes and approaching/receding traffic.

## Plate/OCR
`vehicle track → plate detector → crop → rectification → enhancement → OCR → temporal consensus → normalization`
Do not trust one OCR frame when multiple observations of a track exist.

## Events/evidence
Store track ID, camera ID, timestamp, speed, rule threshold, confidence, metric position/zone, evidence refs, plate result and calibration/model/tracker versions.

## Research basis checked 2026-09-02
- Ultralytics current multi-object tracking docs: BoT-SORT, ByteTrack, OC-SORT, Deep OC-SORT, FastTracker, TrackTrack.
- Ultralytics YOLO26 vs RT-DETRv2 comparison.
- 2026 monocular vehicle-speed research using vehicle keypoints + dynamic homography.
- OpenCV homography/projective-geometry primitives.
- Meta SAM2/SAM2.1 video segmentation/tracking.
- Grounded-SAM2 grounding/segmentation/tracking workflows.

## CI/CD
GitHub Actions is mandatory. Previously verified Run #69 succeeded for Debug APK + tests + artifact.

New AI/lab commits triggered **Run #87**; at last check its job was in progress. Do not call the newest AI changes build-verified until Run #87 completes successfully.

## Current status
The project has moved from UI-only scaffolding into the deterministic AI/measurement foundation. The real ML runtime is **not finished yet**: frame decoding, detector adapters/model packaging, tracker implementations, native/OpenCV calibration/RANSAC, actual keypoint model, live ESP32 stream rendering, OCR runtime and persistence remain to be connected.

## Next execution order
1. Verify latest CI.
2. Install/test Lab and Devices UI.
3. Verify exact ESP32-CAM + OV2640 hardware.
4. Implement ESP32 `/status`, `/capture`, `/stream`.
5. Implement Android MJPEG source/decoder + reconnect/backpressure.
6. Connect real decoded frames to AnalysisEngine.
7. Integrate and benchmark detector candidates on the actual phone.
8. Integrate and benchmark tracker candidates.
9. Add camera calibration UI and native/OpenCV robust/RANSAC homography.
10. Implement production camera-only speed measurement and quality gates.
11. Implement actual Vehicle Keypoint + Dynamic Homography research backend and compare against calibrated baseline.
12. Add plate detector/OCR + temporal fusion + rules/watchlist/evidence.
13. Optimize with frame scheduling, quantization, GPU/NPU/native acceleration based on measured results.
