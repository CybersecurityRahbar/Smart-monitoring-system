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
- detect vehicles;
- track vehicles;
- estimate vehicle speed;
- detect license plates;
- OCR license plates;
- associate speed/time/image/plate into vehicle records;
- detect configurable violations;
- show live camera and events in Android;
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

## 6. Planned staged evolution

### v0.1 — Camera
- camera startup
- Wi-Fi
- Android live view
- still capture

### v0.2 — Vehicle event
- one vehicle-detection sensor optionally added
- timestamp/event capture
- image capture and event delivery

### v0.3 — Speed prototype
Two options were discussed:
1. dual-sensor timing (IR1/IR2) as a reference/prototype;
2. **camera-only computer-vision speed estimation**, now a major desired direction.

### v0.4 — Vehicle record
Combine:
- Vehicle ID
- timestamp
- speed
- images
- later plate/OCR
- violation status

### v0.5 — Computer vision
- vehicle detection
- vehicle tracking
- license-plate detection
- OCR
- association of results

### v1.0 — Integrated system
Android dashboard for:
- live camera
- vehicle count
- violations
- speeds
- average speed
- vehicle records
- detected plates
- images
- reports

## 7. Important design decision: camera-only speed estimation

The user proposed measuring vehicle speed from the camera/video alone, without relying on physical sensors. This is feasible in principle and is now an important research/engineering direction.

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
Vehicle speed (km/h)
```

Key difficulty: pixels do not directly equal meters. A calibrated camera/road geometry model is required. The project must not claim radar-level accuracy without validation.

Useful algorithmic components under consideration:
- camera calibration;
- homography / projective geometry;
- vehicle detection;
- multi-object tracking;
- optical flow / feature motion where useful;
- ground-plane projection;
- Kalman filtering / smoothing;
- robust regression for speed estimation;
- confidence/error estimation.

Possible future sensor fusion:
`vision speed + dual-beam timing reference`, but sensors are optional.

## 8. Sensor discussion retained for reference

If physical sensors are later needed for a prototype reference:
- Prefer proper through-beam photoelectric/IR sensors over cheap short-range robot obstacle sensors for multi-meter timing experiments.
- Example discussed: OMRON E3Z-T61 (through-beam IR, 15 m sensing distance) as an industrial reference; E3Z-T62 was also discussed as a longer-range variant.
- Such industrial outputs must not be connected directly to an ESP32 GPIO without proper voltage/interface protection.
- No sensor purchase is required for v0.1.

## 9. Networking discussion retained for future

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

## 10. Software/language direction

The project uses a multi-language architecture with clear boundaries:
- **Kotlin** for the Android application/UI and app-level logic.
- **Jetpack Compose** for Android UI.
- **C++** for performance-sensitive native computer-vision/math components through Android NDK/JNI when needed.
- **Python** for research, experimentation, dataset preparation, algorithm prototyping and evaluation.
- **C/C++** for ESP32 firmware.

Current Android direction uses AGP 9.x with built-in Kotlin rather than the legacy `org.jetbrains.kotlin.android` plugin. This follows current Android guidance that AGP 9 enables built-in Kotlin by default.

## 11. Repository structure implemented

```text
Smart-monitoring-system/
├── android/
│   ├── app/
│   │   └── src/main/java/com/smarttraffic/app/
│   │       ├── core/
│   │       ├── domain/
│   │       ├── features/
│   │       │   ├── dashboard/
│   │       │   ├── live/
│   │       │   ├── evidence/
│   │       │   ├── devices/
│   │       │   ├── control/
│   │       │   ├── rules/
│   │       │   ├── watchlist/
│   │       │   ├── alerts/
│   │       │   ├── systemsettings/
│   │       │   └── appsettings/
│   │       ├── navigation/
│   │       └── ui/theme/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   ├── PROJECT_STRUCTURE.md
│   └── README.md
├── esp32/
│   └── README.md
├── ai/                 # planned
├── backend/            # planned
├── docs/
│   ├── PROJECT_CONTEXT.md
│   └── ARCHITECTURE.md
└── README.md
```

## 12. Android application requirements captured on 2026-09-02

The Android application is the **central command center** and the visual face of Smart Traffic. The user explicitly wants a premium, advanced, elegant UI and a system that can later control every available ESP32-CAM capability.

Functional screen/feature families:
1. **Dashboard / Monitoring Center**
   - real-time metrics and numbers;
   - current date/time;
   - vehicle counts;
   - speed statistics;
   - camera/device status;
   - warnings/alerts;
   - future electrical/power telemetry.
2. **Live Camera**
   - live video;
   - stream/connection diagnostics;
   - camera controls;
   - capture button.
3. **Images / Evidence**
   - captured images;
   - vehicle/event records;
   - plate/OCR when available;
   - speed, timestamp and evidence details.
4. **ESP32-CAM Device Control**
   - on-demand photo capture;
   - camera controls supported by firmware;
   - status;
   - safe device actions such as restart/shutdown when supported.
5. **Device Connection / Connectivity**
   - add/select devices;
   - configurable IP/host;
   - configurable ports/endpoints;
   - local Wi-Fi settings;
   - connection test;
   - timeouts/reconnect;
   - device identity;
   - no hard-coded single IP.
6. **Traffic Rules / Policies**
   - editable speed limits;
   - capture thresholds;
   - alarm thresholds;
   - object/vehicle classes;
   - configurable violation rules.
7. **Watchlist / Reports**
   - manually enter reported vehicle/plate records;
   - example reported plate: `1/52863`;
   - configurable response when a matching plate is detected;
   - create capture/event/evidence record containing plate, image, timestamp, speed and available metadata.
8. **Alerts / Notifications**
   - speeding;
   - watchlist matches;
   - device offline/online;
   - camera faults;
   - storage/power warnings;
   - system faults.
9. **System Settings**
   - traffic-system configuration;
   - devices, calibration, processing, retention and system policies.
10. **Application Settings**
   - theme;
   - notifications;
   - language;
   - accessibility;
   - display and app behavior.

The screen list is not final; features can be split into nested screens where appropriate. Primary navigation should remain compact and clear rather than putting every setting into the bottom bar.

## 13. UI/design direction

The UI should look like a premium control center, not a basic CRUD application.

Requirements:
- strong visual hierarchy;
- modern high-end dark-first direction is preferred as a starting point;
- sophisticated cards, status surfaces and data visualization;
- coherent typography, spacing, icons and motion;
- reusable design-system components;
- responsive/adaptive layouts for phones and larger screens;
- avoid visual clutter;
- animations should communicate state and transitions.

## 14. Android implementation already added

Initial project shell now exists in GitHub:
- `android/settings.gradle.kts`
- `android/build.gradle.kts`
- `android/gradle.properties`
- `android/app/build.gradle.kts`
- AndroidManifest and base theme
- `MainActivity.kt`
- `SmartTrafficApp.kt`
- `navigation/AppNavHost.kt`
- premium dark-first theme foundation
- initial Dashboard, Live Camera, Devices, Alerts and Settings screens
- feature documentation for Evidence, Device Control, Traffic Rules, Watchlist, System Settings and Application Settings.

The current UI is a **structural shell**, not the final premium design. The next UI iterations should replace placeholders with the complete design system and real state/data.

## 15. Architecture decision for Android

The UI should follow state-driven unidirectional data flow:

```text
Compose UI
    ↓ events
ViewModel / StateFlow
    ↓
Use Cases / Domain
    ↓
Repositories
    ↓
Data Sources
    ├── ESP32 local network
    ├── future Internet backend
    └── future radio gateway
```

The ESP32 protocol must remain behind interfaces. A device connection profile must be data/configuration, not source code.

## 16. Development rules

- Work step-by-step and verify each milestone before advancing.
- Do not dump dozens of unrelated files at once without explanation.
- For each code file, state exactly where it belongs.
- Use real build/runtime logs when debugging.
- Do not assume unverified hardware variants.
- Do not buy all components before the first milestone succeeds.
- Avoid premature OCR, sensors, radio, cloud, or complex AI.
- Keep communication and processing layers modular so transport can later change without rewriting the whole system.
- Do not hard-code one ESP32 IP/port.
- No map widget unless explicitly requested.

## 17. Conversation continuity requirement

The user explicitly asked that project context be preserved in a dedicated context document and updated with substantive conversation progress. This is an explicit standing project rule. The document should continue to capture new user requirements, decisions, corrections, implementation results and next actions.

## 18. Immediate next actions

1. Verify/fix the Android project build setup with the selected AGP/Compose versions and generate a buildable APK when the development environment is available.
2. Replace the structural UI placeholders with the final premium Smart Traffic design system.
3. Add device-domain models and the configurable ESP32 connection/repository abstraction.
4. Then implement the first real local connectivity milestone with the physical ESP32-CAM: live stream + still capture.
5. Continue updating this context document after each substantive milestone.
