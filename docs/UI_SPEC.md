# Smart Traffic Android UI Specification

## Design goal

The Android application is the operator console and visual face of Smart Traffic. The UI should feel like a high-end monitoring/control platform rather than a collection of ordinary forms.

## Visual system

- Dark-first theme with strong contrast and carefully limited accent colors.
- Large, legible numeric metrics for speed, vehicle count, alerts and device health.
- Layered surfaces: application background → panels → elevated cards → critical overlays.
- Consistent 8dp-based spacing scale.
- Rounded but not overly playful surfaces.
- Clear semantic states: connected, analyzing, warning, critical, idle, unavailable.
- Motion should be purposeful: connection changes, new event arrival, tracking lock, analysis progress and transitions.
- Avoid decorative animation while an operator is reviewing evidence.

## Primary areas

### Monitoring Dashboard
The landing screen should answer: What is happening now?

Core regions:
- live system status;
- current time/date;
- active cameras;
- vehicles tracked;
- current/average speed;
- violations;
- watchlist hits;
- recent events;
- device health;
- shortcut actions.

### Live Camera
The live camera surface should prioritize the video. Controls remain accessible without covering the important field of view.

Overlay candidates:
- connection;
- FPS;
- resolution;
- bitrate/latency when available;
- active camera;
- capture;
- analysis mode.

### Intelligent Radar
The radar view is a visual analytics workspace over live or selected media.

Overlay elements:
- bounding boxes;
- stable object IDs;
- speed label where reliable;
- trajectory trails;
- direction arrows;
- confidence;
- selected target state;
- rule/threshold badges.

A side/bottom control surface can define target filters such as class, speed range, confidence threshold and region/zone.

### Local Analysis Lab
The lab should make experimentation easy:
- choose video/image;
- select analysis profile;
- configure calibration if needed;
- start/pause/seek analysis;
- display frame-level results;
- show object/track table;
- show speed estimates and confidence;
- export/save analysis session.

The UI must clearly distinguish measured/estimated values from unavailable values.

### Devices
Device cards should expose:
- device name/ID;
- online state;
- IP/host;
- connection latency;
- firmware/version;
- camera state;
- power/telemetry when available;
- quick control.

### Settings
Settings are grouped into:
- Connectivity;
- Camera;
- Analysis;
- Calibration;
- Traffic Rules;
- Watchlist;
- Alerts;
- Storage;
- System;
- Application.

## Responsive layout

On phones, use a compact navigation bar and stacked information panels.
On larger screens, use a navigation rail and multi-column monitoring layouts.
The radar and live-video surfaces should expand to use available space.

## Error states

Errors are first-class UI states. Every device/analysis operation should expose:
- what failed;
- whether retry is possible;
- what data was preserved;
- what action the operator can take.

Never silently fail a capture, connection attempt or analysis job.
