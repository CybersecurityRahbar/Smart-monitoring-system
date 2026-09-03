# Smart Traffic Monitoring System — Persistent Conversation / Engineering Context

Last updated: 2026-09-03
Current HEAD before checkpoint commit: 15f2739894f9dd316d4b29522074e8d95731aa84
Branch: main

## Mission
Build a reliable Smart Traffic Monitoring System for Android + ESP32 camera sources. Prefer measured and validated results over plausible-looking numbers. No fake algorithms, no silent no-op fallbacks, and no accuracy claims without ground truth.

## Architecture
Compose UI -> ViewModel/StateFlow -> Domain/use cases -> repositories/data sources -> FrameSource -> AnalysisPipelineRunner -> detector -> tracker -> geometry/speed -> plate/OCR -> rules -> evidence.

Runtime split: Kotlin/Compose for UI/orchestration/persistence; LiteRT for on-device ML inference; C++/NDK for performance-sensitive numerical/CV paths; Python for offline research and reproducible experiments.

## Hardware
Initial target: ESP32-CAM + OV2640 -> local Wi-Fi -> Android MJPEG (/status, /capture, /stream).
Also identified: ESP32-S3-CAM N16R8 + OV5640CSP. Exact board pin mapping remains unverified and must not be claimed ready until physically confirmed.

## Android build
AGP 9.3.2; Gradle 9.5.0; Built-in Kotlin; Compose plugin 2.2.10; compileSdk/targetSdk 37; minSdk 26; Java 17; NDK 27.2.12479018; CMake 3.22.1; Compose BOM 2026.08.00; LiteRT 2.2.0. `android.uniquePackageNames=false` is retained as a LiteRT 2.2.0 namespace compatibility workaround.

## Detector
Committed asset: android/app/src/main/assets/models/yolo26n.tflite
SHA-256: d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73
Size: 2,875,553 bytes.
Contract: 640x640 NCHW RGB; classic YOLO [1,84,8400]; COCO traffic classes car=2, motorcycle=3, bus=5, truck=7. Build task verifies the exact hash.

## Implemented pipeline
- strict frame index ordering/gap accounting
- detector exception propagation
- Kalman prediction
- global linear assignment
- high/low-confidence association
- class-aware matching
- track confirmation/lost handling
- bounded motion recovery
- deterministic appearance histogram as a secondary cue (NOT learned Re-ID)
- homography projection and RANSAC fitting
- robust median/MAD speed estimator
- calibration quality gates
- timestamp provenance and exact-timestamp speed gate
- timestamp-aware plate consensus
- traffic-rule evaluation from validated speed
- persistent evidence metadata store
- live processed video preview + radar
- shared traffic-rule preferences

## Not yet production-installed
- learned Re-ID model
- optical-flow refinement backend
- learned vehicle pose/keypoint backend
- dynamic keypoint homography backend
- segmentation refinement backend
- Android plate detector + OCR backend
- true PTS-preserving decoder FrameSource
- actual evidence image/crop extraction/export

## Reliability invariants
1. Pixel displacement is never physical speed.
2. Physical speed requires validated metric calibration.
3. With requireExactTimestampsForPhysicalSpeed=true, physical speed requires EXACT_SOURCE_CLOCK.
4. Missing calibration never enables projection.
5. Calibration requires finite non-singular homography, measured reprojection error and sufficient inlier ratio.
6. Failures must surface; they must not become silent empty success.
7. Appearance is secondary; geometric plausibility remains mandatory.
8. Accuracy claims require independent annotated/reference measurements.

## Timestamp provenance
FrameTimestampPrecision = EXACT_SOURCE_CLOCK | REQUESTED_SAMPLE_TIME | UNKNOWN.
LocalVideoFrameSource currently uses MediaMetadataRetriever sampling and is not exact PTS. Exact physical speed is intentionally blocked for this source until a PTS-preserving MediaExtractor/MediaCodec-style source is implemented.

## Calibration
CalibrationBuilder fits image-to-ground correspondences with RANSAC and records target-unit reprojection error, inlier count and ratio. CalibrationValidator checks dimensions, homography validity, measured error and inlier quality. FileCalibrationStore persists profiles.

A hidden bug was fixed where relaxed calibration validation could allow a null calibration to reach `calibration!!`; missing profiles now always disable projection.

## Tracking
ByteTrack.kt is explicitly ByteTrack-inspired, not reference-equivalent. Association uses predicted/measured IoU, bounded center-distance recovery and optional deterministic appearance similarity.
CI Run #200 exposed nonexistent Detection.centerX/centerY references; fixed in ddeb1f29596b11caa769b2d2e6d88f0c6fea2e32 by deriving centers from box edges.

## Appearance
AppearanceSignature is a deterministic 2x2 spatial RGB histogram with 8 bins/channel. AppearanceAugmentingDetector attaches the signature to Bitmap detections. Current LocalAnalysisViewModel wraps the LiteRT detector with this augmentation when useAppearanceAssociation=true. This is not neural Re-ID.

## Plates / OCR
PlateReading contains timestampMs and PlateConsensus performs temporal aggregation. No Android plate detector/OCR backend is installed; enabling plate recognition must still fail explicitly.

## Rules
TrafficRulePreferences is shared by Rules UI and runtime. TrafficRuleEngine produces SPEEDING events only from validated speed estimates above configured threshold and preserves model/tracker/calibration metadata.

## Evidence
EvidenceRecord stores event/source/frame/time/speed/threshold/confidence/track/plate/calibration/model/tracker metadata. FileEvidenceStore is durable local JSON. A hidden reliability issue was fixed: corrupted JSON, invalid records, write failures and delete failures are surfaced instead of becoming silent empty state.

## Recent CI / fixes
Run #200 failed compiling ByteTrack due Detection.centerX/centerY. Fixed in ddeb1f29.
Run #204 covered calibration/timestamp hardening.
Run #205 covered missing-calibration safety.
Run #206 introduced evidence-store fail-fast hardening.
Current latest code before this checkpoint is commit 15f2739894f9dd316d4b29522074e8d95731aa84.
The latest CI run for the current branch must be checked to completion; queued/cancelled is never equivalent to success.

## UI
Analysis Lab: real local media selection, model readiness, live processed preview, tracking radar and execution metrics. Speed remains intentionally blocked until calibration/timestamp gates pass. Rules UI persists policy. Evidence UI displays durable evidence records.

## Current priorities
P0: Verify current HEAD with complete Android build + unit tests + lint.
P1: Implement true PTS-preserving FrameSource and exact timestamp tests.
P2: Build in-app Calibration Lab with frame selection, point annotation, measured ground distances, RANSAC fit, validation, persistence and Analysis Lab loading.
P3: Generate actual evidence frames/crops from source media.
P4: Integrate bounded optical flow with correctness/performance tests.
P5: Integrate real vehicle pose/keypoints and dynamic homography after model contract validation.
P6: Integrate plate detector + OCR, temporal consensus and regional format validation.
P7: Integrate learned Re-ID and benchmark IDF1/HOTA/MOTA/ID switches.
P8: Add real-device preprocessing/inference/postprocessing/E2E benchmarks and verify accelerator placement.

## Context checkpoint protocol
This file is the persistent handoff source for the project. During long work, update it at least every 2-3 tool operations and after every significant implementation stage with current HEAD, changes/rationale, tests/CI status, discovered issues, and next P0/P1 task. Never mark a queued or cancelled CI run as successful. Never state a feature exists unless it is wired into the runtime and appropriately tested.
