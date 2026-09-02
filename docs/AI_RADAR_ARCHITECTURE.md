# Intelligent Radar / Computer Vision Architecture

## Meaning of "Smart Radar"

The project uses the term **Smart Radar** for a software vision-analytics layer. It is not a claim that ESP32-CAM is a physical radar sensor.

The layer should become a reusable engine that receives frames from different sources and emits structured tracks/events.

## Input adapters

```text
Live ESP32 stream ─┐
Phone camera      ─┼──> FrameSource ──> Analysis Engine
Local video file  ─┤
Image sequence    ─┘
```

The analysis engine must not depend on the source adapter.

## Processing pipeline

```text
Frame
 ↓
Pre-processing
 ↓
Object Detection
 ↓
Data Association
 ↓
Multi-Object Tracking
 ↓
Trajectory Estimation
 ↓
Scene / Road Geometry
 ↓
Ground Projection
 ↓
Velocity Estimation
 ↓
Temporal Filtering
 ↓
Confidence + Quality Assessment
 ↓
Track/Event Output
```

## Track model

Conceptually each active track should contain:
- stable `trackId`;
- object class;
- bounding box;
- image-space center/contact point;
- ground-space position when calibration is available;
- estimated velocity vector;
- scalar speed in km/h when available;
- direction;
- confidence;
- timestamps;
- track age and quality state;
- optional plate association.

## Target filtering

Do not hard-code a single speed filter. Use a composable predicate system.

Examples:

```text
class == vehicle
speed >= 80 km/h
confidence >= 0.85
inside zone A
moving direction == inbound
```

Predicates can later be combined with AND/OR logic and used for highlighting, tracking priority, alert creation or evidence capture.

## Speed estimation

The engine must distinguish:
- detection confidence;
- tracking confidence;
- calibration quality;
- speed-estimation confidence.

A valid speed estimate requires a calibrated mapping from image motion to real-world distance/time. If calibration is not adequate, the UI should show the speed as unavailable or low-confidence rather than inventing a precise number.

Candidate methods:
- frame-to-frame motion;
- tracked contact-point displacement;
- ground-plane projection using homography;
- trajectory regression;
- optical flow/features as supporting evidence;
- Kalman filtering/smoothing;
- robust outlier rejection.

## Radar overlay

The renderer should support:
- boxes;
- IDs;
- labels;
- speed;
- trajectories;
- direction arrows;
- zones/lines;
- selected-target emphasis;
- alert markers.

The overlay is a visualization of the engine's state, not a separate duplicated algorithm.

## Local Analysis Lab

The same frame pipeline should operate on:
- local MP4/video files;
- image files;
- future captured evidence;
- live ESP32 frames.

Each analysis creates a session containing:
- input metadata;
- algorithm/profile version;
- calibration parameters;
- analysis settings;
- processing statistics;
- tracks/events;
- result summaries;
- optional exported evidence.

This enables regression testing when the algorithm changes.

## Future model strategy

The architecture should permit replacing components independently:
- detector A → detector B;
- tracker A → tracker B;
- speed estimator v1 → v2;
- CPU → native C++ acceleration;
- local model → optimized/mobile model.

The Android application should interact with domain interfaces rather than directly depending on a specific ML framework.
