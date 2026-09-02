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

**Implemented:** an actual **Incident Reports** input screen is now present. It includes incident type, location, vehicle/plate, priority, details and submit flow, plus recent report examples.

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

Web research performed on 2026-09-02 examined professional traffic-control and video-management visual patterns, including multi-camera/video-wall layouts, maps + analytics panels, operator workspaces, status/alert hierarchy, and high-density monitoring screens. Android official guidance was also checked for navigation, adaptive layouts, predictive back and Picture-in-Picture.

Design conclusions adopted:
- integrated command-center layout: live video + metrics + events + operator actions;
- strong visual hierarchy for live state and alerts;
- dense but structured information, avoiding decorative clutter;
- video should be a first-class workspace, not a simple centered placeholder;
- secondary workflows should be grouped behind an operations hub rather than putting every function in bottom navigation;
- mobile bottom navigation should stay compact. Android Material guidance recommends 3–7 items for navigation rails and responsive navigation should adapt by window size; the current compact-phone experience uses 5 primary items.

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
- responsive scrolling for dense controls;
- room for future tablets/desktops.

## 8. Language and theme support — implemented

The app has a persisted application preference layer in:
`android/app/src/main/java/com/smarttraffic/app/core/AppSettings.kt`

Supported languages:
- **English** — default on first installation.
- **Arabic** — switches the application UI to Arabic and changes layout direction to RTL.

The preference is stored locally and survives restarts.

Night mode:
- a real **Night mode** switch is available in Application Settings;
- it persists locally;
- current default is dark mode to preserve the command-center visual identity;
- turning it off switches to a lighter professional palette.

The common operator surfaces have been reviewed so visible controls do not fall back to unresolved translation keys. Remaining future work is moving all strings to Android resource files after the screen architecture stabilizes.

## 9. Navigation/back behavior — fixed

Previous implementation used a single selected-screen state without an actual back stack. On nested screens this meant Android Back could close the Activity and leave the application.

Current behavior:
- `AppNavHost` uses Compose `BackHandler`;
- nested screens show an explicit top app bar with a back arrow;
- system Back on nested screens returns to the Operations Hub;
- system Back on a primary screen returns to Dashboard;
- system Back on Dashboard is consumed rather than immediately exiting the application.

This intentionally favors operator safety and prevents accidental application exit. Android's current navigation guidance recommends supported predictive-back APIs and `BackHandler`/Navigation Compose for custom navigation behavior.

## 10. Dashboard review — fixed

The user observed that some English dashboard content was clipped/missing while Arabic showed more of the layout, and horizontal metric/action controls were difficult to move.

Corrections:
- Dashboard now has vertical scrolling for the entire content area;
- dense horizontal actions explicitly scroll horizontally;
- long English labels can use two-line display where appropriate;
- operational buttons are no longer constrained by a fixed two-column width;
- status and camera cards use the same localization helper as Arabic;
- direct incident-report actions remain visible.

## 11. Traffic rules — upgraded

The previous screen contained only two static switches and displayed fixed example values.

It is now an editable rule editor with persisted local settings:
- enable/disable traffic rule processing;
- legal speed limit;
- warning threshold;
- evidence capture threshold;
- minimum computer-vision confidence;
- capture evidence action;
- create operator alert action;
- preserve evidence action;
- Save rules;
- Reset to defaults.

Default prototype values:
- legal speed limit: 80 km/h;
- warning: 70 km/h;
- evidence capture: 80 km/h;
- minimum confidence: 70%.

These are prototype defaults only; the operator can edit them. The future analysis/event engine must consume this settings model instead of hard-coded thresholds.

## 12. Intelligent Radar review — fixed/refined

The previous radar screen mixed placeholder controls, unclear buttons and literal English strings.

Current radar UI:
- clearly separated operational header;
- Running/Standby state control;
- reusable video viewport;
- Full / Standard / Compact video modes;
- target policy card;
- minimum-speed control;
- Vehicles filter;
- High-confidence filter;
- Forward-direction filter;
- Analyze media action;
- tracking status summary;
- localized Arabic/English controls.

Important limitation remains: this is still an analysis UI shell. Actual detection/tracking/speed calculation is not yet connected to the camera stream.

## 13. Live video and PiP

Reusable component:
`android/app/src/main/java/com/smarttraffic/app/core/ui/VideoViewport.kt`

Live Camera and Intelligent Radar now share:
- Fullscreen display mode;
- Standard display mode;
- Compact in-app display mode;
- PiP action.

`MainActivity` prepares Android `PictureInPictureParams` and the manifest declares Picture-in-Picture support and stable configuration changes.

Android's current official guidance recommends the Picture-in-Picture APIs for video playback and recommends supported predictive-back navigation APIs for custom back behavior.

Current limitation: no actual ESP32 stream is connected yet, so PiP and real video continuity still need on-device validation once the video player and network stream are implemented.

## 14. Other screen review

The following screens were also rechecked for localization and consistency:
- Operations Hub;
- Incident Reports;
- Alerts;
- Devices;
- Local Analysis Lab;
- Watchlist;
- Evidence;
- Settings.

The newly reviewed versions now avoid the earlier half-English/half-Arabic presentation on common operator surfaces.

Current intentional prototype placeholders remain:
- device connection settings were previously not backed by real network transport;
- alert records are not yet database-backed;
- evidence vault is not yet persistent;
- local analysis runs only the media selection shell until the native analysis engine is connected.

## 15. Camera-only speed estimation

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

## 16. Intelligent radar model

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

## 17. Sensors retained for reference

If physical timing sensors are needed later:
- prefer proper through-beam photoelectric/IR sensors over cheap robot obstacle sensors for multi-meter experiments;
- discussed example: OMRON E3Z-T61 (through-beam IR, 15 m reference), with E3Z-T62 as a longer-range variant;
- industrial sensor outputs require appropriate interface/voltage protection before ESP32 GPIO;
- no sensors are required for v0.1.

## 18. Software/language direction

- **Kotlin** — Android UI/application/domain orchestration.
- **Jetpack Compose** — Android UI.
- **C++** — performance-sensitive vision/math through Android NDK/JNI when needed.
- **Python** — research, dataset preparation, prototyping, evaluation and benchmarking.
- **C/C++** — ESP32 firmware.

The analysis interface must be independent of transport so live camera, local files and future sources can share the same engine API.

## 19. Repository structure

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

## 20. CI/CD and APK

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

**Latest verified run #69:**
- Debug APK build: **SUCCESS**;
- unit-test task: **SUCCESS**;
- APK artifact upload: **SUCCESS**;
- artifact ID: `9862594300`;
- artifact ZIP size: `18,178,244` bytes;
- artifact SHA-256: `9840af48b5be5b8f7f2139cff7072e5c3323e182742b3459f3f307966af61989`.

Current Android build uses AGP 9.3.2 and Gradle 9.5.0 in CI. Keep these aligned unless a future verified release requires another combination.

## 21. Runtime launch bug found and fixed

The old `AndroidManifest.xml` incorrectly declared `android:name=".SmartTrafficApp"` on `<application>` while `SmartTrafficApp.kt` is a `@Composable` function rather than an `android.app.Application` subclass.

Fix already applied:
- removed `android:name=".SmartTrafficApp"`;
- kept `MainActivity` as launcher;
- `MainActivity` continues to call `setContent { SmartTrafficApp() }`.

The user confirmed the corrected APK could be installed and opened successfully. The previous immediate-close bug is therefore resolved for the tested build.

## 22. Current implementation state

Implemented in the current UI pass:
- premium command-center dashboard refinement;
- five-item primary navigation;
- operations hub for secondary workflows;
- actual incident-report intake screen;
- editable persistent traffic rules;
- watchlist action controls;
- evidence screen;
- localized alerts/devices/live/analysis/settings surfaces;
- application language preference with English default and Arabic RTL;
- persisted night-mode toggle;
- shared live-video viewport with Full/Standard/Compact modes;
- Android Picture-in-Picture preparation;
- predictive-back-compatible manifest configuration;
- dashboard scrolling/layout fixes;
- radar layout/control cleanup.

New connectivity foundation implemented after the successful #69 build:
- persistent `DeviceSettings` profile;
- editable host/IP;
- editable HTTP port;
- editable `/stream`, `/capture`, `/status` endpoints;
- URL normalization and generated endpoint URLs;
- Android-local persistence for the profile;
- `/status` HTTP connection test running off the UI thread;
- Devices screen now has Save + Test and Reset controls;
- `MainActivity` loads the persisted device profile at startup.

Still not connected to real backends/vision:
- actual ESP32 stream rendering;
- live detection/tracking;
- OCR;
- physical or camera-only speed calculation;
- persistent event/report database;
- real device control commands;
- real evidence storage.

## 23. Development rules

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

## 24. Conversation continuity rule

After every substantive project discussion, update this document with:
- the user's new requirements/questions;
- decisions and corrections;
- implementation changes;
- build/test results;
- runtime findings;
- unresolved items;
- next actions.

The goal is to allow a future conversation to resume from this file without depending on chat memory.

## 25. Current execution plan

### Phase A — UI stability
Status: **verified build; awaiting physical UI retest by user**.
- English/Arabic layout;
- RTL horizontal scrolling;
- navigation/back behavior;
- editable traffic rules;
- radar control clarity;
- live/PiP modes.

### Phase B — Device connectivity foundation
Status: **implemented; CI verification pending for this new commit**.
- device profile;
- editable host/IP/port/endpoints;
- local persistence;
- status endpoint health check;
- no hard-coded runtime address.

### Phase C — Real camera transport
Next implementation stage after Phase B CI passes.
- verify exact camera board and sensor variant from hardware photo;
- implement ESP32 firmware `/status`, `/capture`, `/stream` for the verified board;
- test local Wi-Fi connection;
- render live MJPEG stream in Android;
- capture still frame from Android;
- show device health/connection state;
- validate reconnect/timeout behavior.

### Phase D — Shared media/analysis model
- define stable `MediaSource`, `Frame`, `Detection`, `Track`, `SpeedEstimate`, `Event`, `WatchlistEntry` models;
- define transport-independent `AnalysisEngine` interface;
- make Local Analysis and Live Radar consume the same abstraction.

### Phase E — Computer vision
- vehicle/object detector;
- multi-object tracking;
- trajectory visualization;
- camera/road calibration;
- camera-only speed estimation;
- confidence/error output;
- violation event generation from configurable rules.

### Phase F — OCR + evidence
- license plate detection;
- OCR;
- normalization/configurable plate rules;
- evidence capture;
- watchlist matching;
- alert/event linkage;
- persistent storage.

### Phase G — hardening
- full Arabic/English resource migration;
- unit tests for settings/rules/transport;
- integration tests where practical;
- performance profiling;
- multi-device readiness;
- release/build documentation.

## 26. Latest discussion record — 2026-09-02

User confirmed that the Android CI build succeeded and explicitly requested continuing the project plan rather than stopping at the APK milestone.

Decision:
- Do not jump directly into AI/OCR.
- Start with a real device connectivity foundation because the first physical milestone is ESP32-CAM → local Wi-Fi → Android live feed.
- Keep the endpoint configurable so the Android app is not tied to one ESP32 IP or port.

Implementation completed in response:
- added `core/DeviceSettings.kt` for persisted device profile/configuration;
- updated `MainActivity.kt` to load the profile at startup;
- upgraded `features/devices/DevicesScreen.kt` with editable host, port and endpoint fields;
- added an asynchronous `/status` HTTP health test;
- added local Save + Test / Reset controls.

Verification state:
- Previous commit `561fc311...` has a verified successful CI run #69 with APK artifact.
- The subsequent connectivity commits are new and must pass a fresh CI run before their build status is considered verified.

Next action:
- verify CI for the new connectivity foundation;
- then implement the actual ESP32-camera transport and Android stream renderer once the exact board is confirmed.
