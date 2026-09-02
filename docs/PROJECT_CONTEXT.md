# Smart Monitoring System — Project Context

> Living context document for the Smart Traffic / vehicle monitoring project. Update this document after each substantive project discussion so the project can be resumed without losing decisions.
>
> Last updated: 2026-09-02

## Project identity

- GitHub repository: `CybersecurityRahbar/Smart-monitoring-system`
- Working system name: **Smart Traffic Monitoring System**
- Android product/app name: **Smart Traffic**
- Project type: low-cost university prototype intended to evolve toward an intelligent traffic/vehicle monitoring system.
- It is a prototype, not a legally certified traffic enforcement device.
- No Map Widget unless explicitly requested by the user.

## Main objective

Build a modular central Android command system that can receive camera/video, detect and track vehicles, estimate physical speed from calibrated video, detect and OCR plates, apply configurable rules, manage watchlists/alerts/evidence, control ESP32-CAM devices, and analyze local media without hardware.

## Hardware starting point

Start with **ESP32-CAM + OV2640**, but verify the exact board and camera physically before final firmware/wiring. Heavy vision/AI remains primarily on Android. Optional future hardware: verified ESP32-S3-CAM N16R8 + OV2640.

## First hardware milestone

`ESP32-CAM + OV2640 → local Wi-Fi → Android → live stream`

No sensors, OCR or AI are required for this first transport milestone.

Initial endpoint contract:
- `/stream` — MJPEG/HTTP live stream;
- `/capture` — still image;
- `/status` — device health.

## Android command-center architecture

Android remains the central command/visual console. Primary navigation is:
1. Dashboard
2. Intelligent Radar
3. Live Camera
4. Alerts
5. More / Operations Hub

The Operations Hub exposes Reports, Analysis Lab, Devices, Traffic Rules, Watchlist, Evidence and Settings.

Architecture direction:
`Compose UI → ViewModel/StateFlow → Use Cases/Domain → Repositories → Data Sources`

Analysis transport abstraction:
`ESP32 live / local video / image → FrameSource → shared AnalysisEngine → results`

## UI direction and current state

The user requires a professional/international traffic operations control-room aesthetic, not a generic student UI: live video and operational numbers are primary, information is dense but structured, alerts have strong hierarchy, and secondary functions stay behind Operations Hub.

Implemented/fixed:
- five-item primary navigation;
- explicit in-app top Back button on nested screens;
- system Back no longer exits immediately from nested screens;
- dashboard vertical scrolling and horizontal action scrolling;
- editable/persistent traffic rules;
- Arabic RTL + English language preference;
- persistent Night mode;
- radar control cleanup;
- shared Full/Standard/Compact video viewport and PiP preparation;
- Incident Reports workflow;
- localized common operator screens.

The current translation system is still a Kotlin key map in `AppSettings.kt`; migrate to Android string resources after the structural UI pass stabilizes.

## Device connectivity foundation

Implemented:
- `core/DeviceSettings.kt` stores host/IP, HTTP port and `/stream`, `/capture`, `/status` paths locally;
- `MainActivity` loads `DeviceSettings` at startup;
- Devices screen provides editable connection profile;
- Save + Test performs an asynchronous HTTP health request to the configured `/status` endpoint;
- no single hard-coded runtime IP/port is required.

The user reported that the endpoint field text was visually unclear in the Arabic UI. The technical endpoint fields were updated to use an explicit left-to-right text direction and a larger readable text style. The build must be re-verified after this change.

## Local Analysis Lab — user-required workflow

The user explicitly identified a missing capability: the lab had only file selection and no real test workflow or explanation of what gets measured. This is now a required feature, not optional documentation.

The lab UI now defines:
- media source: video or image;
- test mode: Full Pipeline, Detection + Tracking, Speed/Calibration, Plate/OCR;
- known reference distance in meters;
- reference frame window;
- minimum confidence;
- optional radar/trajectory overlay;
- explicit Start Analysis Test control;
- test state explaining that no fake measurements are reported until the real engine is connected;
- an operator-facing description of the measurements the final engine will produce.

Important architecture decision:
**The Intelligent Radar is a visualization/operations layer over the same analysis engine, not a separate measurement algorithm.** Local Analysis replays the same engine on recorded media; Live Radar feeds the same engine from the live camera source.

## Vision/measurement algorithm architecture

A detailed architecture document was added at:
`docs/AI_VISION_ALGORITHM_PLAN.md`

The implementation target is:
`frames → detection → multi-object tracking → ground contact point → camera/road calibration → image-to-ground projection → metric distance → robust speed estimation → plate/OCR → traffic rules → evidence`

### Detection

Use a replaceable real-time detector backend. Current 2026 research/documentation makes YOLO26 a strong starting baseline for accuracy/latency studies on constrained systems, but the project must not lock itself to one model. Model size will be selected using measurements on the actual Android device.

### Tracking

Benchmark multiple current trackers rather than assuming one is universally best:
- ByteTrack for a fast baseline;
- BoT-SORT as the preferred general baseline when camera motion compensation and optional ReID are useful;
- OC-SORT/Deep OC-SORT as alternatives for difficult motion/occlusion.

Track state must include persistent ID, class, boxes/history, trajectory, visibility, confidence, occlusion status and event links.

### Geometry

Do not use raw pixel displacement as physical speed. Prefer:
- intrinsic camera calibration;
- lens distortion correction;
- road-plane reference;
- homography/projective mapping;
- bounding-box ground contact point or learned vehicle keypoint;
- metric X/Y coordinates on the road plane.

Calibration is per-camera/per-profile and must carry version and quality/error metadata.

### Distance and speed

For ground point `p`, project with homography `P = H p` and normalize homogeneous coordinates. Metric displacement is computed in meters. Speed is `distance / time`, converted to km/h only at presentation.

Use multi-frame robust estimation rather than one-frame derivatives:
- state smoothing/Kalman-style filtering;
- median/robust regression;
- outlier rejection;
- minimum track duration/sample gates;
- speed confidence and estimated error.

Do not publish a violation event unless calibration, track quality, timebase and speed stability pass the configured quality gates.

### Direction/zones

Derive direction from ground-plane trajectory, not screen left/right. Use configurable lane/zone polygons and signed longitudinal velocity for direction and crossing events.

### License plates

Separate pipeline:
`vehicle track → plate detector → rectification → enhancement → OCR → temporal consensus → normalized plate`

Never trust a single OCR frame when multiple observations of the same track are available.

### Evidence/events

A confirmed event should record track ID, camera ID, timestamp, speed, limit, confidence, metric position/zone, evidence references, plate result, calibration version and algorithm/model versions.

## Analysis models — added

`android/app/src/main/java/com/smarttraffic/app/domain/analysis/AnalysisModels.kt`

Stable contracts now exist for:
- `MediaSource`;
- `Detection`;
- `GroundPoint`;
- `TrackObservation`;
- `Track`;
- `SpeedEstimate`;
- `PlateReading`;
- `CalibrationProfile`;
- `AnalysisConfig`;
- `AnalysisMetrics`;
- `AnalysisResult`.

These are data contracts only; the actual detector/tracker/geometry engine is still to be implemented.

## Local Analysis test measurements

Required test report metrics are defined in `docs/AI_VISION_ALGORITHM_PLAN.md`:

Detection:
- precision/recall/mAP when annotated ground truth exists;
- confidence distribution.

Tracking:
- HOTA/IDF1/MOTA where ground truth exists;
- ID switches;
- track fragmentation;
- lost-track duration.

Geometry:
- reference-point error in meters;
- homography reprojection error.

Speed:
- MAE, RMSE, median percentage error;
- P50/P90/P95 absolute error;
- percentage within ±5/±10/±20% of reference;
- event false positives/false negatives.

Runtime:
- decode FPS;
- inference latency;
- end-to-end latency;
- dropped frames;
- memory/thermal observations on the target phone.

## Validation strategy

The university prototype must have a controlled reference dataset rather than visual-only inspection.

Use physically measured road distances and, when possible, an independently validated speed reference. Keep a hand-annotated subset for detection/tracking/plate validation.

Test across daylight, dusk/night, glare/shadows, rain when possible, different vehicle sizes, occlusion, dense traffic, multiple lanes, and approaching/receding traffic.

A calibration-free monocular approach may be researched later, but explicit road calibration remains the preferred production-prototype method. Recent 2026 monocular research reports dataset-specific errors rather than universal accuracy, so the project must report empirical error instead of claiming enforcement-grade performance.

## Research basis checked on 2026-09-02

Current web research reviewed:
- Ultralytics multi-object tracking documentation and current tracker options;
- current YOLO26 vs RT-DETR comparisons;
- recent 2026 monocular vehicle-speed estimation research;
- recent 2026 real-time traffic detection/edge-deployment studies.

Key conclusion: use a replaceable modern detector + multi-object tracker, but make camera calibration, metric projection, robust temporal estimation and explicit validation the core of the measurement system.

## CI/CD

GitHub Actions remains mandatory. The last verified build was Run #69:
- Debug APK SUCCESS;
- unit-test task SUCCESS;
- APK artifact upload SUCCESS;
- artifact ID `9862594300`;
- artifact SHA-256 `9840af48b5be5b8f7f2139cff7072e5c3323e182742b3459f3f307966af61989`.

Subsequent commits for connectivity, lab workflow and algorithm contracts require a fresh CI verification before they are considered build-verified.

## Current implementation limitations

Still pending:
- actual ESP32 firmware for the verified physical board;
- actual Android MJPEG stream renderer;
- detector runtime/model packaging;
- multi-object tracker implementation;
- camera calibration UI/storage and homography solver;
- metric distance/speed engine;
- plate detector/OCR;
- persistent event/evidence database;
- real watchlist matching pipeline;
- production alert lifecycle;
- device command layer beyond `/status` health test.

## Development order

1. Verify current CI after the latest Devices/Analysis changes.
2. Verify the endpoint-field readability and Local Analysis workflow on the phone.
3. Confirm exact ESP32-CAM board and OV2640 variant from hardware photo.
4. Implement ESP32 `/status`, `/capture`, `/stream` and test local Wi-Fi.
5. Implement Android live MJPEG source and reconnect/backpressure handling.
6. Implement the deterministic geometry layer first: calibration, homography, metric coordinates and unit-tested speed estimator.
7. Add detector + tracker adapters and trajectory overlays.
8. Connect the shared engine to Local Analysis and Live Radar.
9. Add plate/OCR, temporal plate fusion and configurable rule/event processing.
10. Add evidence persistence, profiling, quantization and accuracy regression testing.
