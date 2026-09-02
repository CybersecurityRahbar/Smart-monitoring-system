# Smart Monitoring System — Project Context

> Living context document for the Smart Traffic / vehicle monitoring project. Update this document after each substantive project discussion so the project can be resumed without losing decisions.
>
> Last updated: 2026-09-02

## 1. Project identity

- GitHub repository: `CybersecurityRahbar/Smart-monitoring-system`
- Working system name: **Smart Traffic Monitoring System**
- Android product/app name: **Smart Traffic**
- Project type: low-cost university prototype intended to evolve toward an intelligent traffic/vehicle monitoring system.
- It is a prototype, not a legally certified traffic enforcement device.
- No Map Widget unless explicitly requested by the user.

## 2. Main objective

Build a modular central Android command system that can:
- receive camera/video;
- detect vehicles and other configured objects;
- track objects continuously;
- estimate vehicle speed from video when possible, without mandatory physical sensors;
- detect license plates and OCR them;
- associate speed/time/image/plate into vehicle records;
- execute configurable monitoring/traffic rules;
- manage a watchlist/report list;
- show live camera and intelligent-radar analytics in Android;
- control ESP32-CAM devices;
- analyze local media from the phone without physical hardware;
- later scale to multiple cameras and alternative transports.

## 3. Hardware starting point

Start with:
- ESP32-CAM + OV2640
- Verify exact physical board from a photo before final wiring/firmware.
- ESP32-CAM initially handles capture, Wi-Fi, simple events and later sensors.
- Heavy vision/AI remains primarily on Android.

Longer-term optional upgrade:
- ESP32-S3-CAM N16R8 + OV2640, 16MB Flash + 8MB PSRAM, if verified available.

## 4. First hardware milestone

**v0.1:**
`ESP32-CAM + OV2640 → local Wi-Fi → Android → live stream`

No Internet, radio, sensors, OCR or AI are required for this first hardware milestone.

## 5. Communication

Initial:
```text
OV2640 → ESP32-CAM → local Wi-Fi → Android
```

Proposed first endpoints:
- `/stream` — MJPEG/HTTP live stream.
- `/capture` — still image.
- `/status` — device status.

Later:
- HTTP/WebSocket for control/events/telemetry.
- Backend/cloud for remote Internet mode.
- Radio modem/gateway as a replaceable future transport layer.

## 6. Android = central command center

The Android app is the main operator console and visual face of the system. It must control and configure every supported ESP32 capability through a capability-aware device abstraction and must never be tied to one hard-coded IP/port.

### Core screen families

1. **Dashboard / Monitoring Center**
- date/time;
- current system state;
- vehicle/object counts;
- current/average speed;
- speeding count;
- watchlist hits;
- alerts/warnings;
- device health;
- future voltage/current/power telemetry;
- quick actions.

2. **Live Camera**
- live stream;
- capture;
- camera controls;
- connection diagnostics;
- device status.

3. **Intelligent Radar / Live Analytics**
A separate analytics workspace over live or selected media.

Required direction:
- automatic vehicle/object detection;
- persistent IDs;
- tracking and trajectory trails;
- speed/direction when measurable;
- confidence;
- filters by object class and speed range;
- focus on objects moving at configured speeds;
- expandable algorithm controls.

Conceptual pipeline:
```text
Video
 ↓
Detection
 ↓
Multi-object tracking
 ↓
Motion/trajectory
 ↓
Camera + road calibration
 ↓
Ground-plane projection
 ↓
Speed estimation
 ↓
Filtering + confidence
 ↓
Radar overlay + events
```

4. **Local Analysis / Test Lab**
A dedicated hardware-free engineering laboratory.
- pick video/image from device storage;
- run analysis on local media;
- inspect detections/tracks/trajectory/speed;
- apply calibration where required;
- view results and analysis metadata;
- later compare algorithm versions on fixed test media.

Important principle: live ESP32 frames and local files must enter the same abstract analysis pipeline.

5. **Images / Evidence**
- captures;
- evidence;
- vehicle records;
- plate/OCR;
- speed/time/metadata.

6. **ESP32-CAM Device Control**
- capture on demand;
- supported camera parameters;
- status;
- safe restart/shutdown/reset where supported;
- capability-aware commands.

7. **Connectivity**
- add/select devices;
- editable host/IP;
- editable ports;
- endpoint settings;
- connection test;
- timeout/retry/reconnect;
- device identity/profile;
- no hard-coded single address.

8. **Traffic Rules / Policies**
- speed limits;
- capture thresholds;
- alarm thresholds;
- object classes;
- direction/zone rules when supported;
- violation actions;
- priority/severity;
- enable/disable.

9. **Watchlist / Reports**
Example reported plate: `1/52863`.
A match can trigger configurable actions such as capture, event creation, operator alert, evidence preservation and reporting of plate/speed/time/metadata.
Plate format must remain configurable and normalized carefully.

10. **Alerts / Notifications**
- speeding;
- watchlist hits;
- device offline/online;
- camera faults;
- storage warnings;
- power warnings when telemetry exists;
- analysis failures;
- system faults.

11. **System Settings**
- rules;
- devices;
- calibration;
- analysis;
- storage/retention;
- event policies.

12. **Application Settings**
- theme;
- notifications;
- language;
- accessibility;
- display;
- app behavior.

## 7. UI/design direction

The application is the face of the system and must feel like a premium professional control center.

Requirements:
- dark-first, high-contrast technical aesthetic;
- sophisticated cards/panels and live indicators;
- strong numeric hierarchy for operational metrics;
- coherent typography, spacing and iconography;
- restrained accent colors with warning/error colors reserved for states;
- meaningful motion for state/transition feedback;
- adaptive layouts;
- reusable design-system components;
- radar overlays readable over video;
- complex configuration grouped into hubs/nested screens.

The bottom navigation should eventually remain compact. Complex areas belong behind hubs/nested navigation.

A detailed visual direction is recorded in `docs/UI_VISION.md`.

## 8. Camera-only speed estimation

Preferred research path:
```text
Video frames
 ↓
Vehicle/object detection
 ↓
Multi-object tracking
 ↓
Ground/contact point estimation
 ↓
Camera + road calibration
 ↓
Perspective/homography mapping
 ↓
Image coordinates → ground coordinates (meters)
 ↓
Position over time
 ↓
Velocity estimation
 ↓
Filtering/regression/outlier rejection
 ↓
Speed + confidence/error
```

Raw pixel displacement alone is not a physical speed measurement. Accuracy must be validated against known references before strong claims are made.

Potential components:
- intrinsic/extrinsic camera calibration;
- road-plane model;
- homography/projective geometry;
- detection;
- multi-object tracking;
- optical flow/features where useful;
- trajectory fitting;
- Kalman smoothing;
- robust regression;
- outlier rejection;
- confidence/error estimation.

Physical sensor timing can later act as a reference or sensor-fusion input rather than the primary method.

## 9. Intelligent radar model

"Radar" is a software computer-vision analytics layer, not a claim that ESP32-CAM itself is a radar sensor.

The operator should eventually see:
- scene/video;
- object boxes/markers;
- IDs;
- trails;
- direction;
- speed when measurable;
- confidence;
- selected-target state;
- events/violations.

Filters should support general predicates such as:
```text
class
speed range
confidence
zone
direction
watchlist state
```
with AND/OR composition rather than a hard-coded speed-only mode.

## 10. Sensors retained for reference

If physical timing sensors are needed later:
- prefer proper through-beam photoelectric/IR sensors over cheap robot obstacle sensors for multi-meter experiments;
- discussed example: OMRON E3Z-T61 (through-beam IR, 15 m reference), with E3Z-T62 as a longer-range variant;
- industrial sensor outputs require appropriate interface/voltage protection before ESP32 GPIO;
- no sensors are required for v0.1.

## 11. Software/language direction

- **Kotlin** — Android UI/application/domain orchestration.
- **Jetpack Compose** — Android UI.
- **C++** — performance-sensitive vision/math through Android NDK/JNI when needed.
- **Python** — research, dataset preparation, prototyping, evaluation and benchmarking.
- **C/C++** — ESP32 firmware.

The analysis interface must be independent of transport so live camera, local files and future sources can share the same engine API.

## 12. Repository structure

```text
Smart-monitoring-system/
├── android/
├── esp32/
├── ai/
├── native/
├── backend/
├── docs/
│   ├── PROJECT_CONTEXT.md
│   ├── ARCHITECTURE.md
│   ├── UI_SPEC.md
│   ├── UI_VISION.md
│   ├── AI_RADAR_ARCHITECTURE.md
│   ├── LOCAL_ANALYSIS_LAB.md
│   └── ANDROID_CI.md
└── README.md
```

## 13. CI/CD and APK

GitHub Actions is a permanent project requirement.

Current workflow:
`.github/workflows/android-ci.yml`

It:
- triggers on pushes and pull requests to `main`;
- uses JDK 17;
- provisions Gradle 9.5.0;
- builds Debug APK;
- runs unit tests;
- uploads the APK as `smart-traffic-debug-apk`;
- uploads test reports when present;
- exposes build logs/status in Actions.

The user verified the workflow succeeded on 2026-09-02:
- Debug APK build: **SUCCESS**;
- unit-test task: **SUCCESS** (currently no unit-test sources, task reported `NO-SOURCE`);
- APK artifact uploaded successfully;
- artifact size reported around 18 MB as a ZIP artifact.

Current Android build uses AGP 9.3.2 and Gradle 9.5.0 in CI. Keep these aligned unless a future verified release requires another combination.

## 14. Runtime launch bug found and fixed

The current `AndroidManifest.xml` incorrectly declared `android:name=".SmartTrafficApp"` on `<application>` while `SmartTrafficApp.kt` is a `@Composable` function rather than an `android.app.Application` subclass.

This could make Android try to instantiate the composable as an Application and crash at launch, matching the user's earlier report that a previous APK closed immediately.

Fix applied on 2026-09-02:
- removed `android:name=".SmartTrafficApp"` from `<application>`;
- kept `MainActivity` as the launcher activity;
- `MainActivity` continues to call `setContent { SmartTrafficApp() }`.

A fresh CI run after this fix is required before claiming runtime launch has been verified on a physical device.

## 15. Current implementation state

The repository has:
- Android Compose foundation;
- app navigation;
- premium-direction Dashboard shell;
- Live Camera shell;
- Intelligent Radar shell;
- Local Analysis Lab shell with device-media picker;
- Devices, Alerts and Settings feature areas;
- analysis/design documentation;
- GitHub Actions CI producing a Debug APK artifact.

The latest verified CI run succeeded before the manifest runtime fix. Therefore the next APK must be built from the post-fix commit and tested on-device.

## 16. Development rules

- Work step-by-step and verify milestones.
- Do not dump dozens of unrelated files without explaining where each belongs.
- Use real build/runtime logs to debug.
- Do not assume an unverified hardware variant.
- Do not hard-code one ESP32 IP/port.
- Keep UI, domain, transport and analysis engine separate.
- Do not claim radar-level accuracy without validation.
- No map widget unless explicitly requested.
- Keep sensor integration optional.
- Treat this context document as mandatory living project state.

## 17. Conversation continuity rule

After every substantive project discussion, update this document with:
- the user's new requirements/questions;
- decisions and corrections;
- implementation changes;
- build/test results;
- runtime findings;
- unresolved items;
- next actions.

The goal is to allow a future conversation to resume from this file without depending on chat memory.

## 18. Immediate next actions

1. Confirm CI succeeds on the post-manifest-fix commit.
2. Download/install the new APK and verify the app launches and displays the UI.
3. Continue replacing structural screen shells with the final premium control-center design.
4. Define stable domain models for Device, CameraStream, MediaSource, Detection, Track, SpeedEstimate, Event, Rule and Watchlist entry.
5. Define the analysis-engine interface shared by Local Analysis and Live Radar.
6. Implement actual local-media analysis incrementally.
7. Implement configurable ESP32-CAM device profiles and local HTTP communication.
8. When hardware is available, verify exact ESP32-CAM board and implement live stream + still capture.
9. Develop and benchmark camera-only speed estimation with calibrated scenes and known references.
