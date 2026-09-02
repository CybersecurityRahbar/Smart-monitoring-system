# Smart Monitoring System

**Smart Traffic Monitoring System** is a modular prototype for intelligent vehicle and traffic monitoring.

## Current direction

```text
ESP32-CAM + OV2640
        ↓
     Local Wi-Fi
        ↓
  Android Control Center
```

The Android app is the central operator interface. The architecture is prepared for future Internet/cloud and radio transports without coupling the UI to one transport.

## Technology stack
- Android: Kotlin + Jetpack Compose
- Native vision: C++ / Android NDK
- Research & evaluation: Python
- ESP32 firmware: C/C++

## Planned capabilities
- Live camera
- Device control
- Configurable device connections/IP/ports
- Vehicle detection and tracking
- Camera-based speed estimation
- License plate detection + OCR
- Evidence/images
- Configurable traffic rules
- Watchlist/report handling
- Alerts and notifications
- Device telemetry, including future power/electrical data

## Repository layout

```text
android/   Android control-center application
esp32/     ESP32-CAM firmware
ai/        Computer vision research and evaluation (planned)
backend/   Internet/cloud backend (planned)
docs/      Architecture and living project context
```

## Project context
See [`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md) for the continuously maintained project decisions and requirements.
