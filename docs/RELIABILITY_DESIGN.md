# Smart Traffic — Reliability and Measurement Design

Last reviewed: 2026-09-03

## Core principle
A computed number is not automatically a trustworthy measurement. The system separates perception, tracking, metric geometry, robust estimation, validity gates, and independent validation.

## Detection
The mobile baseline is an explicitly registered YOLO26 LiteRT model. Current Ultralytics documentation describes YOLO26's default one-to-one head as end-to-end and NMS-free with `(N, 300, 6)` output; the one-to-many head is `(N, nc+4, 8400)` and requires NMS. These are export-contract facts, not assumptions to be copied to every future model. The app must inspect/validate the actual artifact before runtime use. citeturn856605search0turn856605search2

Detection acceptance should use precision, recall, mAP50, mAP50-95, per-class recall, localization error and difficult-condition false positives when ground truth exists.

## Tracking
The Android tracker is a ByteTrack-inspired baseline. The important reference behavior is: Kalman prediction, global linear assignment, then a second association stage using lower-confidence detections. The official implementation uses linear assignment and explicitly performs the low-score second association. citeturn856605search6

BoT-SORT remains a future comparison candidate because it combines motion and appearance cues with camera-motion handling. OC-SORT is another candidate focused on motion/occlusion robustness.

Tracking must be evaluated with an established evaluator instead of a project-specific imitation metric. TrackEval implements HOTA, CLEAR MOT metrics including MOTA, identity metrics including IDF1, and related diagnostics; it is the official reference implementation for HOTA. citeturn856605search1turn856605search7

## Geometry
Physical speed requires a metric road model. A homography is scene/camera specific, so the system stores calibration metadata and its validation measurements.

The Kotlin calibration fallback now uses normalized point coordinates and deterministic RANSAC. The denormalization order is `H = T_target^-1 * H_normalized * T_source`, which is the correct normalized-coordinate composition.

Production calibration should move to native/OpenCV SVD/RANSAC after parity testing. Calibration records should include image size, homography, intrinsics when available, distortion coefficients, reprojection error, inlier count/ratio and a version.

## Speed
The old adjacent-frame Euclidean-step approach was rejected because localization jitter is non-negative after taking a magnitude and can create a systematic positive-speed bias. The current estimator works in metric road coordinates and uses pairwise vector velocities, MAD rejection, robust median velocity components, trajectory residuals and temporal coverage.

This is conceptually related to Theil-Sen robustness, where median pairwise slopes reduce sensitivity to outliers; it is not a claim that the traffic estimator is identical to Theil-Sen regression. The estimator's quality score is not a calibrated probability and its uncertainty is not ground-truth error.

Independent ground-truth measurements are required before any accuracy claim. BrnoCompSpeed-style calibrated road video with independent vehicle speed ground truth is an appropriate benchmark family.

## Dynamic keypoints / homography
Recent monocular speed research proposes vehicle keypoint templates and frame-updated homographies. This repository treats that technique as a research candidate that must be compared against fixed calibration using measured validation error. It is not enabled as a fake or no-op backend.

## Runtime
Benchmarks must record exact model identity/checksum, input size/type, accelerator, preprocessing, inference, postprocessing, full pipeline throughput, dropped frames, memory and thermal observations. A website number or another phone is not a project benchmark.

## Reliability gates
A physical speed result requires:
- a finite vehicle detection;
- an observation associated with the same track ID on the current frame;
- valid monotonic timestamps;
- a calibration that passed its configured validation gates;
- enough metric samples and duration;
- plausible velocity;
- robust outlier retention;
- residual and uncertainty recorded.

Unavailable components are rejected explicitly. The engine never silently substitutes a missing keypoint model, OCR backend, Re-ID, segmentation, optical flow, rule engine or evidence store.

## Ground truth protocol
Track annotations should record stable IDs plus occlusion/outside information. CVAT track mode is suitable for producing frame-level track labels. Evaluation should then use TrackEval rather than a home-made HOTA implementation.

## Current implementation status
Implemented:
- LiteRT detector adapter and explicit model registry;
- real local frame pipeline;
- normalized/RANSAC calibration fallback;
- metric robust speed estimator;
- Kalman + Hungarian ByteTrack-inspired tracker;
- frame-gap and inference timing metrics;
- calibration validity gates;
- Android reliability unit tests;
- offline Python robust speed module and tests;
- real Local Analysis execution path with explicit runtime errors.

Still requiring empirical validation or further backend work:
- actual validated `.tflite` weights on the target phone;
- CPU/GPU/NPU benchmark on that phone;
- HOTA/IDF1/MOTA on labeled traffic clips;
- independent speed MAE/RMSE/P50/P90/P95 validation;
- production native/OpenCV calibration and optical flow;
- trained vehicle-keypoint model and dynamic homography;
- plate detector/OCR and temporal fusion;
- Re-ID, rules and evidence persistence;
- ESP32 field validation.
