# Smart Monitoring System — Project Context

> Living context document for the Smart Traffic / vehicle monitoring project. Update this document after each substantive project discussion so the project can be resumed without losing decisions.
>
> Last updated: 2026-09-02

## 1. Project identity

- GitHub repository: `CybersecurityRahbar/Smart-monitoring-system`
- Working system name: **Smart Traffic Monitoring System**
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
- Cheap locally available board was identified previously in Sana'a; the exact physical board must be verified from a photo before wiring/code is finalized.
- ESP32-CAM is responsible initially for camera capture, Wi-Fi communication, simple events, and later sensors.
- Heavy computer vision/AI is intended to run on Android rather than on the ESP32-CAM.

Longer-term optional upgrade:
- ESP32-S3-CAM N16R8 + OV2640, 16MB Flash + 8MB PSRAM, if a verified board becomes available.

## 4. First development milestone

**Version 0.1:**
`ESP32-CAM + OV2640 → local Wi-Fi → Android → live stream`

No Internet, radio, sensors, OCR, or AI in the first milestone.

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
Two options are being evaluated:
1. dual-sensor timing (IR1/IR2) as a reference/prototype;
2. **camera-only computer-vision speed estimation**, which is now considered a major desired direction.

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

The user proposed measuring vehicle speed from the camera/video alone, without relying on physical sensors. This is feasible in principle and is now an important research/engineering direction for the project.

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

Key difficulty: pixels do not directly equal meters. A calibrated camera/road geometry model is required. The project should not claim radar-level accuracy without validation.

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
`vision speed + dual-beam timing reference`, but sensors are not mandatory for the first vision-only prototype.

## 8. Sensor discussion retained for reference

If physical sensors are later needed for a prototype reference:
- Prefer a proper through-beam photoelectric/IR sensor over cheap short-range robot obstacle sensors for multi-meter timing experiments.
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

## 10. Software/language direction discussed

The goal is not to force the whole project into one language. The preferred architecture is a **multi-language system with clear boundaries**:

- **Kotlin** for the Android application/UI, lifecycle, networking orchestration, storage, permissions, and app-level logic.
- **C++** for performance-sensitive native computer-vision/math components on Android via the Android NDK when needed.
- **Python** for research, experimentation, dataset preparation, algorithm prototyping, evaluation, and offline tooling.
- **C/C++ (Arduino/ESP-IDF style)** for ESP32 firmware, depending on the selected ESP32 development stack.

Reasoning:
- Android officially supports Kotlin and also supports C/C++ through the NDK/JNI.
- Heavy vision/math code can be isolated into native C++ instead of forcing the Android UI layer to handle it.
- Python is valuable for rapid CV/ML experimentation but is not the sole language for the shipping Android application.

Current official Android documentation confirms C/C++ can be added under `src/main/cpp`, built with CMake/NDK, and called from Kotlin/Java via JNI; Android also states Kotlin is preferred over Groovy for Gradle configuration. See the project conversation sources/notes for references.

## 11. Repository structure target

```text
Smart-monitoring-system/
├── android/        # Android application
├── esp32/          # ESP32-CAM firmware
├── ai/             # Computer vision / ML experiments and reusable assets
├── backend/        # Future server/cloud components
├── docs/           # Architecture, context, calibration, testing, decisions
└── README.md
```

Only `android/`, `esp32/`, and `docs/` are needed initially. `ai/` and `backend/` can be populated later.

## 12. Development rules

- Work step-by-step and verify each milestone before advancing.
- Do not dump dozens of files at once.
- For each code file, state exactly where it belongs.
- Use real build/runtime logs when debugging.
- Do not assume unverified hardware variants.
- Do not buy all components before the first milestone succeeds.
- Avoid premature OCR, sensors, radio, cloud, or complex AI.
- Keep communication and processing layers modular so transport can later change without rewriting the whole system.
- Do not claim memories/details that are not present in this context document.

## 13. Conversation log / decisions captured so far

### User-provided project context
The user supplied a detailed project context document dated 2026-09-01 covering the project objective, ESP32-CAM starting hardware, staged versions 0.1–1.0, communication strategy, possible sensors, local stores, and constraints.

### Discussion: local Wi-Fi
We established that the first prototype can operate without Internet or radio:
`ESP32-CAM → Wi-Fi → Android`.
The ESP32 can act as an access point, allowing the phone to connect directly.

### Discussion: Internet connectivity
We established that ESP32-CAM does not have cellular Internet by itself; it can use Wi-Fi supplied by a router/hotspot/modem. A future scalable architecture should use a backend instead of exposing a device directly to the Internet.

### Discussion: radio
We established that future radio can be used as a digital data/backhaul link, including for video, but it is unnecessary for the small first prototype.

### Discussion: physical speed sensors
We discussed dual IR/through-beam timing as a simple experimental speed reference. We also distinguished industrial photoelectric sensors from short-range robot obstacle sensors.

### Discussion: camera-only speed
The user proposed using the camera and accurate algorithms to identify and track a vehicle in video and calculate speed without physical sensors. We agreed this is feasible in principle and outlined a computer-vision geometry pipeline based on calibration, homography/ground-plane mapping, tracking, time, and filtering.

### Discussion: naming
The working system name was chosen as **Smart Traffic Monitoring System**. The GitHub repository remains `Smart-monitoring-system`.

## 14. Context maintenance rule

This file is a living project context. After every substantive project discussion, update it with:
- the user's new question/request;
- the technical answer/decision;
- changed architecture or requirements;
- next action;
- any unresolved issue.

The purpose is to preserve project continuity across future chats.

## 15. Immediate next step

Before implementing the Android application, inspect the repository state and establish the initial project skeleton. Then identify the exact ESP32-CAM board variant and programming method when the physical hardware is available/photographed.
