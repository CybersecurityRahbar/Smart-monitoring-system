# Smart Traffic Monitoring System Architecture

## Core principle
Android is the central control center. UI must not communicate directly with ESP32 implementation details.

```text
Compose UI
    ↓
ViewModels / StateFlow
    ↓
Use Cases / Domain
    ↓
Repositories
    ↓
Data Sources
    ├── ESP32 Local Network
    ├── Future Internet Backend
    └── Future Radio Gateway
```

The transport can change without changing the domain/UI layers.

## Major Android areas
- Dashboard — real-time monitoring and system overview.
- Live Camera — live video and stream diagnostics.
- Evidence — captured images, event evidence, vehicle records.
- Devices — device list, connection profiles, device status.
- Control — camera/device commands.
- Rules — speed limits, object rules, violation policies.
- Watchlist — reported plates/vehicles and actions.
- Alerts — warnings, violations, faults and device status changes.
- Settings — system settings and application settings.

## Device communication abstraction
The Android app should depend on a `DeviceRepository`/device service contract rather than hard-coded HTTP calls.

The contract will later cover operations such as:
- connect/disconnect
- health/status
- live stream URL/session information
- capture still image
- camera state/control
- device restart/shutdown where safely supported
- telemetry
- event subscription
- configuration read/write

The exact protocol is deliberately not frozen until the physical ESP32-CAM variant and firmware are verified.

## Vision architecture
```text
Video Source
   ↓
Frame Pipeline
   ↓
Vehicle Detector
   ↓
Tracker
   ↓
Scene/Camera Calibration
   ↓
Ground Projection / Homography
   ↓
Motion & Speed Estimator
   ↓
Plate Detector
   ↓
OCR
   ↓
Event / Violation Engine
```

The native C++ vision engine will be isolated behind an Android-facing interface. Python remains the research/evaluation environment.

## Data entities (initial conceptual model)
- CameraDevice
- ConnectionProfile
- CameraStatus
- StreamSession
- VehicleTrack
- VehicleObservation
- SpeedMeasurement
- PlateObservation
- EvidenceItem
- ViolationEvent
- WatchlistEntry
- TrafficRule
- Alert
- DeviceTelemetry

## Design requirements
- No single hard-coded IP.
- Multiple device profiles must be possible.
- Connection settings must be editable at runtime.
- UI uses one coherent design system.
- Prefer state-driven Compose UI and unidirectional data flow.
- Keep screens testable by passing immutable state and event callbacks.
- Adaptive layouts should support phone and larger screens.
