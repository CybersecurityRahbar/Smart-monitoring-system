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
- quick actions;
- direct incident-report entry should be prominent.

2. **Live Camera**
- live stream;
- capture;
- camera controls;
- connection diagnostics;
- device status;
- operator video presentation modes: fullscreen, standard and compact/floating-in-app;
- system Picture-in-Picture (PiP) path so video can continue while the user leaves the app.

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
- expandable algorithm controls;
- same flexible video presentation modes as Live Camera.

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

**New:** an actual **Incident Reports** input screen is now present. It includes incident type, location, vehicle/plate, priority, details and submit flow, plus recent report examples.

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

The user explicitly wants the UI to feel like an **international / professional traffic operations control system**, not a generic student app.

Web research performed on 2026-09-02 examined professional traffic-control and video-management visual patterns, including multi-camera/video-wall layouts, maps + analytics panels, operator workspaces, status/alert hierarchy, and high-density monitoring screens. Examples reviewed included GoodVision, TomTom traffic monitoring imagery, municipal traffic control rooms, and traffic operations-center references.

Design conclusions adopted:
- integrated command-center layout: live video + metrics + events + operator actions;
- strong visual hierarchy for live state and alerts;
- dense but structured information, avoiding decorative clutter;
- video should be a first-class workspace, not a simple centered placeholder;
- secondary workflows should be grouped behind an operations hub rather than putting every function in bottom navigation;
- mobile bottom navigation should stay compact. Android Material guidance recommends roughly 3–5 primary navigation items, so the app now uses 5 top-level items.

Current top-level navigation:
1. Dashboard
2. Intelligent Radar
3. Live Camera
4. Alerts
5. More / Operations Hub

The Operations Hub exposes:
- Incident Reports
- Analysis Lab
- Devices
- Traffic Rules
- Watchlist
- Evidence
- Settings

Visual language:
- dark-first command-center aesthetic;
- refined high-contrast surfaces;
- teal/blue operational accents;
- warning/error colors reserved for actual state;
- rounded but controlled geometry;
- large readable operational numbers;
- adaptive spacing and room for future tablets/desktops.

## 8. Language and theme support — implemented

The app now has a persisted application preference layer in:
`android/app/src/main/java/com/smarttraffic/app/core/AppSettings.kt`

Supported languages:
- **English** — default on first installation.
- **Arabic** — switches the application UI to Arabic and changes layout direction to RTL.

The preference is stored locally and survives restarts.

Night mode:
- a real **Night mode** switch was added to Application Settings;
- it persists locally;
- current default is dark mode to preserve the command-center visual identity;
- turning it off switches to a lighter professional palette.

A shared translation helper currently provides the common labels used by the new operator UI. This is an incremental localization layer and should later be expanded so every remaining legacy feature string is localized.

## 9. Video presentation and Picture-in-Picture — implemented at UI layer

A reusable component was added:
`android/app/src/main/java/com/smarttraffic/app/core/ui/VideoViewport.kt`

Both Live Camera and Intelligent Radar now support:
- **Fullscreen** mode;
- **Standard** mode;
- **Compact** mode for a smaller/floating-in-app presentation;
- a **PiP** action that enters Android system Picture-in-Picture mode.

`MainActivity` now prepares `PictureInPictureParams` and the manifest enables `android:supportsPictureInPicture="true"`.

Android official guidance confirms that PiP is the appropriate system multi-window mechanism for video apps and is available from Android 8.0/API 26. The PiP window is system-controlled and can remain visible while navigating elsewhere.

Current limitation: the project still uses a visual video placeholder because the actual ESP32 stream connection is not implemented yet. Therefore PiP/UI behavior is prepared, but a real camera stream has not yet been validated inside PiP.

## 10. Camera-only speed estimation

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

## 11. Intelligent radar model

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

## 12. Sensors retained for reference

If physical timing sensors are needed later:
- prefer proper through-beam photoelectric/IR sensors over cheap robot obstacle sensors for multi-meter experiments;
- discussed example: OMRON E3Z-T61 (through-beam IR, 15 m reference), with E3Z-T62 as a longer-range variant;
- industrial sensor outputs require appropriate interface/voltage protection before ESP32 GPIO;
- no sensors are required for v0.1.

## 13. Software/language direction

- **Kotlin** — Android UI/application/domain orchestration.
- **Jetpack Compose** — Android UI.
- **C++** — performance-sensitive vision/math through Android NDK/JNI when needed.
- **Python** — research, dataset preparation, prototyping, evaluation and benchmarking.
- **C/C++** — ESP32 firmware.

The analysis interface must be independent of transport so live camera, local files and future sources can share the same engine API.

## 14. Repository structure

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

Important new Android files:
- `android/app/src/main/java/com/smarttraffic/app/core/AppSettings.kt`
- `android/app/src/main/java/com/smarttraffic/app/core/ui/VideoViewport.kt`
- `android/app/src/main/java/com/smarttraffic/app/features/reports/IncidentReportsScreen.kt`
- `android/app/src/main/java/com/smarttraffic/app/features/more/OperationsHubScreen.kt`
- `android/app/src/main/java/com/smarttraffic/app/features/rules/TrafficRulesScreen.kt`
- `android/app/src/main/java/com/smarttraffic/app/features/watchlist/WatchlistScreen.kt`
- `android/app/src/main/java/com/smarttraffic/app/features/evidence/EvidenceScreen.kt`

## 15. CI/CD and APK

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

The user verified an earlier workflow run on 2026-09-02 with:
- Debug APK build: **SUCCESS**;
- unit-test task: **SUCCESS** (currently no unit-test sources, task reported `NO-SOURCE`);
- APK artifact uploaded successfully;
- artifact size reported around 18 MB as a ZIP artifact.

Current Android build uses AGP 9.3.2 and Gradle 9.5.0 in CI. Keep these aligned unless a future verified release requires another combination.

## 16. Runtime launch bug found and fixed

The old `AndroidManifest.xml` incorrectly declared `android:name=".SmartTrafficApp"` on `<application>` while `SmartTrafficApp.kt` is a `@Composable` function rather than an `android.app.Application` subclass.

Fix already applied:
- removed `android:name=".SmartTrafficApp"`;
- kept `MainActivity` as launcher;
- `MainActivity` continues to call `setContent { SmartTrafficApp() }`.

The user has now confirmed the corrected APK can be **installed and opened successfully** and that the application shows the existing screens. This resolves the previous immediate-close runtime problem for the tested build.

A new post-UI-change CI run is currently in progress and must be confirmed before the latest UI commit is called install-ready.

## 17. Current implementation state

The new UI pass now includes:
- premium command-center dashboard refinement;
- five-item primary navigation;
- operations hub for secondary workflows;
- actual incident-report intake screen;
- traffic-rules screen;
- watchlist screen;
- evidence screen;
- application language preference with English default and Arabic RTL;
- persisted night-mode toggle;
- shared live-video viewport with fullscreen/standard/compact modes;
- Android system Picture-in-Picture preparation for live video;
- refined dark/light theme colors.

The UI is still a prototype layer. The following are not yet connected to actual backends/vision:
- real ESP32 stream;
- live detection/tracking;
- OCR;
- physical or camera-only speed calculation;
- persistent event/report database;
- real device commands;
- real evidence storage.

## 18. Development rules

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

## 19. Conversation continuity rule

After every substantive project discussion, update this document with:
- the user's new requirements/questions;
- decisions and corrections;
- implementation changes;
- build/test results;
- runtime findings;
- unresolved items;
- next actions.

The goal is to allow a future conversation to resume from this file without depending on chat memory.

## 20. Immediate next actions

1. Confirm the newest CI run after the UI changes is green.
2. Download/install the newest APK and test: navigation, Reports, Arabic switch, RTL, Night mode, video modes and PiP.
3. Continue the visual pass across every remaining screen so legacy shells use the same command-center design system.
4. Replace the temporary translation map with a complete resource-based localization architecture once the UI structure settles.
5. Define stable domain models for Device, CameraStream, MediaSource, Detection, Track, SpeedEstimate, Event, Rule and Watchlist entry.
6. Define the analysis-engine interface shared by Local Analysis and Live Radar.
7. Implement actual local-media analysis incrementally.
8. Implement configurable ESP32-CAM device profiles and local HTTP communication.
9. When hardware is available, verify exact ESP32-CAM board and implement live stream + still capture.
10. Develop and benchmark camera-only speed estimation with calibrated scenes and known references.
