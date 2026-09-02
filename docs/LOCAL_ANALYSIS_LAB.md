# Local Analysis / Test Lab

## Purpose

Provide a hardware-independent environment inside the Android application for testing computer-vision and speed-estimation algorithms before the ESP32-CAM is available.

## Inputs

- Video selected from device storage/gallery.
- Single image.
- Future image sequence.
- Future captured evidence.

## Session flow

```text
Select media
 ↓
Inspect metadata
 ↓
Choose analysis profile
 ↓
Optional calibration
 ↓
Run analysis
 ↓
Detect objects
 ↓
Track objects
 ↓
Estimate motion/speed
 ↓
Review overlays/results
 ↓
Save analysis session
```

## Results

Display:
- source media;
- processed frame/time;
- object count;
- active tracks;
- track IDs;
- trajectories;
- speed estimate when valid;
- speed confidence/quality;
- processing FPS and latency;
- calibration status;
- warnings/errors.

## Important measurement behavior

The lab must never imply that a speed value is physically valid merely because an object moved in pixels. Speed estimation must report whether real-world calibration exists and whether the estimate is within the configured quality criteria.

## Regression testing

A saved analysis session should preserve enough metadata to rerun a fixed test after algorithm changes. Future versions can compare:
- track continuity;
- detection precision/recall where labeled ground truth exists;
- speed error against known-speed references;
- processing latency/FPS;
- failure cases.

## Architecture rule

The selected video/image is converted into the same logical `FrameSource`/analysis input abstraction used by live camera sources. This prevents the Local Analysis Lab from becoming a separate one-off algorithm implementation.
