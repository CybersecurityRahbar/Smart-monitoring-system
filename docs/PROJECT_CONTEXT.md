# Smart Monitoring System — Project Context

> Living context document for the Smart Traffic / vehicle monitoring project. Update this document after each substantive project discussion so the project can be resumed without losing decisions.
>
> Last updated: 2026-09-02

## 1. Project identity

- GitHub repository: `CybersecurityRahbar/Smart-monitoring-system`
- Working system name: **Smart Traffic Monitoring System**
- Android product/app name: **Smart Traffic**
- Repository name is intentionally broad (`Smart-monitoring-system`) and the traffic-specific system name is used inside the project.
- Project type: low-cost university prototype, intended to evolve toward an intelligent traffic/vehicle monitoring system.
- It is a prototype, not a legally certified traffic enforcement device.
- No Map Widget should be introduced unless the user explicitly requests it.

## 2. Main objective

Build a modular system that can:
- capture vehicle images/video;
- detect vehicles and other configured objects;
- track objects continuously;
- estimate vehicle speed from camera/video when possible, without mandatory physical sensors;
- detect license plates;
- OCR license plates;
- associate speed/time/image/plate into vehicle records;
- execute configurable traffic/monitoring rules;
- manage a watchlist/report list;
- show live camera, radar/analytics overlays and events in Android;
- control ESP32-CAM devices from Android;
- support local video/image analysis without a physical camera;
- later support multiple cameras and different communication methods.

## 3. Current hardware starting point

Start with:
- ESP32-CAM + OV2640
- The exact physical board must be verified from a photo before wiring/code is finalized.
- ESP32-CAM is responsible initially for camera capture, Wi-Fi communication, simple events, and later sensors.
- Heavy computer vision/AI is intended to run on Android rather than on the ESP32-CAM.

Longer-term optional upgrade:
- ESP32-S3-CAM N16R8 + OV2640, 16MB Flash + 8MB PSRAM, if a verified board becomes available.

## 4. First development milestone

**Version 0.1:**
`ESP32-CAM + OV2640 → local Wi-Fi → Android → live stream`

No Internet, radio, sensors, OCR, or AI are required in the first hardware milestone.

The simplest initial communication model is ESP32-CAM acting as a local Wi-Fi access point and the Android phone connecting directly to it. A router-based local Wi-Fi mode can be added later.

## 5. Communication design

Initial local architecture:

```text
OV2640 → ESP32-CAM → Wi-Fi → Android App
```

Proposed application-level endpoints/streams:
- `/stream` — live MJPEG/HTTP stream for the first prototype.
- `/capture` — capture a still image.
- `/status` — camera/device status.

Later, small telemetry/events can use HTTP or WebSocket. Internet mode can be added later through a backend/cloud service. Radio can be added later as a replaceable communication/backhaul layer.

## 6. Android is the central command center

The Android app is not just a viewer. It is the main operator console and visual face of the system. It should be able to control and configure all supported device capabilities through a stable device abstraction.

Required screen/feature families:

### A. Dashboard / Monitoring Center
- current date/time;
- online/offline state;
- vehicle/object counts;
- average/current speed metrics;
- speeding count;
- watchlist matches;
- alerts and warnings;
- device health and future electrical/power telemetry;
- quick actions.

### B. Live Camera
- live video;
- stream diagnostics;
- capture;
- supported camera controls;
- connection status.

### C. Live Intelligent Radar / Analytics
This is a separate major feature from the basic live camera view.

The user wants an advanced real-time "smart radar" visualization that overlays the video and automatically:
- detects vehicles and configurable moving objects;
- assigns object/vehicle IDs;
- tracks objects across frames;
- displays trajectories/trails;
- estimates motion/speed when the scene is calibrated enough;
- allows filtering by speed range or object class;
- can focus on objects moving at a configured speed or threshold;
- exposes configurable tracking/analytics parameters;
- evolves over time as more algorithms are added.

Conceptual pipeline:

```text
Live Video
   ↓
Frame Acquisition
   ↓
Object Detection
   ↓
Multi-Object Tracking
   ↓
Trajectory / Motion Estimation
   ↓
Camera + Road Calibration
   ↓
Ground-Plane Projection
   ↓
Speed Estimation
   ↓
Filtering / Confidence
   ↓
Radar Overlay + Events
```

The UI should allow the operator to change tracking filters, including a rule such as "show/follow only objects between X and Y km/h". Tracking and speed should remain distinct concepts: an object can be tracked even when its speed estimate is unavailable or low-confidence.

### D. Local Analysis / Test Lab
A dedicated testing screen is required even before physical ESP32 hardware is available.

The user can:
- choose a video from the Android device storage/gallery;
- optionally choose an image sequence/image;
- run the same analysis pipeline used for live video where practical;
- detect and track vehicles/objects;
- estimate speed from video when calibration permits;
- visualize boxes, IDs, trajectories and speed;
- obtain an analysis report/results summary;
- compare algorithm changes on fixed test media.

This screen is intended as an engineering/research laboratory, not only a media player.

Important design principle: the core analysis engine should be separable from the camera transport so local files can enter the pipeline exactly as live camera frames do.

### E. Images / Evidence
- captured images;
- event evidence;
- vehicle record;
- plate/OCR result;
- speed/time;
- metadata.

### F. ESP32-CAM Device Control
- capture image on demand;
- supported camera parameters;
- device status;
- safe restart/shutdown where firmware/hardware supports it;
- device commands must be capability-aware rather than hard-coded.

### G. Device Connection / Connectivity
- add/select devices;
- editable host/IP;
- editable port(s);
- endpoint/path configuration where needed;
- connection test;
- timeouts/retry/reconnect;
- device identity/profile;
- local Wi-Fi configuration helpers;
- no single hard-coded device address.

### H. Traffic Rules / Policies
- speed limits;
- capture thresholds;
- alarm thresholds;
- object classes;
- direction/zone rules when supported;
- configurable violation actions;
- enable/disable and priority/severity.

### I. Watchlist / Reports
The user can enter reports such as a vehicle plate `1/52863`.

A watchlist match should be able to trigger configurable actions, for example:
- immediate capture;
- create event;
- alert operator;
- preserve evidence;
- report plate/OCR confidence;
- include timestamp, speed and available vehicle metadata.

The system must not assume a single fixed plate format; plate matching should be configurable and normalized carefully.

### J. Alerts / Notifications
- speeding;
- watchlist hits;
- device online/offline;
- camera faults;
- storage warnings;
- power/voltage warnings when telemetry exists;
- analysis failures;
- system health.

### K. System Settings
- traffic rules;
- device fleet;
- camera calibration;
- analysis configuration;
- storage/retention;
- event policy.

### L. Application Settings
- theme;
- notifications;
- language;
- accessibility;
- display;
- application behavior.

## 7. UI/design direction

The application is the face of the system and must have a premium, advanced control-center feel.

Requirements:
- sophisticated dark-first visual direction as the starting point;
- strong information hierarchy;
- data-rich monitoring surfaces without clutter;
- polished cards, indicators, badges and charts;
- high-quality typography, spacing, iconography and motion;
- adaptive/responsive layouts;
- reusable design-system components;
- live analytics overlays must remain readable over video;
- settings can be extensive but should be organized into clear groups.

The bottom navigation should contain only the most important top-level destinations. Complex areas such as rules, watchlist, connectivity, diagnostics and app/system settings should generally live behind the relevant hub screens or nested navigation rather than overflowing the bottom bar.

## 8. Camera-only speed estimation

The preferred research direction is to estimate speed from video without mandatory sensors.

Target pipeline:

```text
Video frames
  ↓
Vehicle detection
  ↓
Multi-object tracking
  ↓
Vehicle ground/contact point estimation
  ↓
Camera + road calibration
  ↓
Perspective / homography mapping
  ↓
Image coordinates → ground coordinates (meters)
  ↓
Position over time
  ↓
Velocity estimation
  ↓
Filtering / regression
  ↓
Speed + confidence/error
```

Key constraint: raw pixel displacement is not a physical speed measurement. The scene needs a defensible mapping from image coordinates to real-world geometry. The project must validate accuracy against a known reference before making strong accuracy claims.

Potential algorithm components:
- camera intrinsics/extrinsics calibration;
- road-plane modeling;
- homography/projective geometry;
- object detection;
- multi-object tracking;
- optical flow/features where useful;
- robust trajectory fitting;
- Kalman filtering/smoothing;
- outlier rejection;
- confidence/error estimation.

## 9. Intelligent radar concept

"Radar" in the UI/project refers to a software computer-vision analytics layer, not a claim that the ESP32-CAM is a physical radar sensor.

The radar UI should present:
- live scene;
- tracked-object markers;
- object IDs;
- velocity/speed where available;
- trajectory trails;
- direction;
- confidence;
- configurable target filters;
- events and violations.

Example configurable tracking filter:

```text
Target class: Vehicle
Minimum speed: 80 km/h
Maximum speed: 160 km/h
Action: track + highlight + event
```

The engine should support a general predicate model so this can later become combinations of speed, class, zone, direction, confidence and watchlist state rather than a hard-coded "speed filter".

## 10. Sensor discussion retained for reference

If physical sensors are later needed for a prototype reference:
- Prefer proper through-beam photoelectric/IR sensors over cheap short-range robot obstacle sensors for multi-meter timing experiments.
- Example discussed: OMRON E3Z-T61 (through-beam IR, 15 m sensing distance) as an industrial reference; E3Z-T62 was also discussed as a longer-range variant.
- Such industrial outputs must not be connected directly to an ESP32 GPIO without proper voltage/interface protection.
- No sensor purchase is required for v0.1.

## 11. Networking discussion retained for future

### Local mode
```text
ESP32-CAM → Wi-Fi → Android
```

### Internet mode (future)
```text
ESP32-CAM → Wi-Fi/4G router → Internet → Backend → Android
```

For scalable remote operation, the camera should maintain a device/session connection to a backend rather than exposing a raw local IP directly to the phone.

### Radio mode (future)
```text
ESP32-CAM → Radio modem → Radio gateway/backhaul → Server → Android
```

Radio is a future replaceable transport layer, not part of v0.1.

## 12. Software/language direction

The project uses a multi-language architecture with clear boundaries:
- **Kotlin** for Android application/UI and app-level logic.
- **Jetpack Compose** for UI.
- **C++** for performance-sensitive native computer-vision/math through Android NDK/JNI when needed.
- **Python** for research, dataset preparation, algorithm prototyping, evaluation and benchmarking.
- **C/C++** for ESP32 firmware.

The analysis API should be defined independently of UI so the same engine can receive frames from live camera input or local files.

## 13. Repository structure target

```text
Smart-monitoring-system/
├── android/
│   ├── app/
│   └── ...
├── esp32/
├── ai/
├── native/
├── backend/
├── docs/
│   ├── PROJECT_CONTEXT.md
│   ├── ARCHITECTURE.md
│   ├── UI_SPEC.md
│   ├── AI_RADAR_ARCHITECTURE.md
│   ├── LOCAL_ANALYSIS_LAB.md
│   └── ANDROID_CI.md
└── README.md
```

## 14. Continuous Integration / APK requirement

GitHub Actions is part of the project, not an optional afterthought.

The Android CI workflow should:
- run on pushes and pull requests;
- use JDK 17;
- provision the required Gradle version;
- build a debug APK;
- run unit tests;
- upload the APK as a workflow artifact;
- upload test reports even when tests fail where possible;
- expose build failures and logs in the GitHub Actions UI.

Current workflow file:
`/.github/workflows/android-ci.yml`

Current CI documentation:
`docs/ANDROID_CI.md`

Current Android build configuration:
- AGP 9.1.2
- Kotlin Compose plugin 2.2.10
- Java 17
- Gradle 9.3.1 in CI
- compileSdk 37

## 15. Development phases

### Phase 0 — Project/build foundation
- repository structure;
- Android project;
- CI/CD APK artifact;
- test baseline;
- design system foundation.

### Phase 1 — Hardware-free Local Analysis Lab
- video/image picker;
- frame extraction;
- basic playback;
- detection/tracking pipeline abstraction;
- results visualization;
- test/report model.

### Phase 2 — ESP32-CAM local connection
- identify exact board;
- firmware camera startup;
- local Wi-Fi;
- live MJPEG stream;
- still capture;
- Android device profile and connection test.

### Phase 3 — Live Intelligent Radar
- detection;
- tracking;
- trajectories;
- target filters;
- initial speed estimation;
- confidence and diagnostics.

### Phase 4 — Calibration + speed research
- camera calibration workflow;
- road-plane mapping;
- homography;
- trajectory smoothing;
- benchmark against known-speed video/test references.

### Phase 5 — Evidence and rules
- vehicle records;
- plate detection/OCR;
- watchlist;
- configurable rules;
- alerts;
- evidence packaging.

### Phase 6 — Device fleet / future transports
- multiple ESP32 devices;
- Internet/backend mode;
- radio/backhaul mode;
- fleet monitoring;
- remote management.

## 16. Development rules

- Work step-by-step and verify each milestone before advancing.
- Do not dump dozens of unrelated files at once without explanation.
- For each code file, state exactly where it belongs.
- Use real build/runtime logs when debugging.
- Do not assume unverified hardware variants.
- Do not buy all components before the first milestone succeeds.
- Avoid premature OCR, sensors, radio and cloud infrastructure.
- Keep communication and processing layers modular so transport can later change without rewriting the whole system.
- Do not hard-code one ESP32 IP/port.
- No map widget unless explicitly requested.
- Separate UI, domain logic, device transport and analysis engine.
- Treat speed estimation as an engineering measurement problem with calibration and validation, not merely an AI prediction.

## 17. Conversation continuity requirement

The user explicitly asked that project context be preserved in a dedicated context document and updated with substantive conversation progress. This is an explicit standing project rule. The document should continue to capture new user requirements, decisions, corrections, implementation results and next actions.

## 18. Current implementation status

Repository state includes the Android project shell, feature package structure, Compose theme foundation, initial navigation/screens, and GitHub Actions Android CI workflow. The exact state of each build must be checked from the latest GitHub Actions run before claiming the APK is successfully built.

## 19. Immediate next actions

1. Run/inspect the Android CI build and fix any real compile/build errors.
2. Build the premium design system and replace structural placeholders.
3. Implement the Local Analysis/Test Lab screen and analysis-session data model.
4. Implement the Intelligent Radar screen architecture and overlay model.
5. Establish the camera/device protocol abstraction before physical ESP32 hardware integration.
6. When the physical ESP32-CAM is available, verify the exact board and implement v0.1 local live stream + capture.
7. Continue updating this context document after every substantive project discussion.
