# Smart Traffic UI Vision

## Product role
Smart Traffic is the central Android control center for the complete monitoring system. The interface should feel like a premium command center rather than a utility app.

## Visual direction
- Dark-first, high-contrast, calm technical aesthetic.
- Deep neutral surfaces, restrained cyan/teal signal color, violet secondary accent, and amber/red only for states and warnings.
- Large numeric hierarchy for operational metrics.
- Dense information is grouped into cards/panels rather than scattered controls.
- Motion communicates live state, connectivity, detection and transitions; it must not become decoration.
- Adaptive layout: compact phone navigation and larger multi-pane/control-center layouts where space permits.

## Primary information hierarchy
1. Operational state: Online/Offline, camera status, current time.
2. Live intelligence: active tracks, highest speed, detections, active alerts.
3. Evidence: latest events, captures and vehicle records.
4. Configuration: devices, rules, watchlist, processing and application settings.

## Core screens
- Dashboard / Monitoring Center
- Live Camera
- Intelligent Radar
- Local Analysis Lab
- Evidence / Images
- Devices
- Device Control
- Connectivity
- Traffic Rules
- Watchlist / Reports
- Alerts
- System Settings
- Application Settings

## Intelligent Radar behavior
The radar screen is a computer-vision workspace layered over a live or selected video source. It must support:
- automatic object/vehicle detection;
- persistent track IDs;
- trajectory trails;
- estimated speed and direction;
- confidence display;
- configurable target filters such as object class and speed range;
- live source from ESP32-CAM;
- local video/image files as analysis sources;
- pause, resume, seek and frame inspection for file analysis;
- future calibration and sensor-fusion overlays.

The visual overlay must remain readable over changing video content and should not obscure the evidence region more than necessary.

## Local Analysis Lab
The lab reuses the same analysis pipeline as live radar. A selected phone video/image is treated as a `MediaSource`; the resulting detections/tracks/speed estimates can be inspected and exported as an analysis session.

## Device control
Device actions are explicit and safety-gated. The app must distinguish harmless queries (status/capture/configuration) from disruptive actions (restart/shutdown/reset). Device profiles are data-driven and never hard-coded to one IP or port.
