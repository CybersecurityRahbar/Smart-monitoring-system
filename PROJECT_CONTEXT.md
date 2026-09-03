# Smart Traffic Monitoring System — Persistent Conversation / Engineering Context

Last updated: 2026-09-03
Branch: main

## Mission
Build a reliable Smart Traffic Monitoring System for Android + ESP32 camera sources. The system must prefer measured/validated results over plausible-looking numbers. No fake algorithms, no silent no-op fallbacks, and no accuracy claims without ground truth.

## Runtime architecture
Compose UI -> ViewModel/StateFlow -> Use Cases/Domain -> Repositories/Data Sources -> FrameSource -> AnalysisPipelineRunner -> detector -> tracker -> geometry/speed -> plate/OCR -> rules -> evidence.

Runtime split:
- Kotlin/Compose: UI, orchestration, configuration and persistence.
- LiteRT: on-device ML inference.
- C++/NDK: performance-sensitive CV/numerical paths.
- Python: offline research, datasets and reproducible experiments.

## Hardware target
Initial: ESP32-CAM + OV2640 -> local Wi-Fi -> Android MJPEG stream.
Endpoints intended: /status, /capture, /stream.
A second board was found: ESP32-S3-CAM N16R8 with OV5640CSP; physical pin mapping still requires board-specific verification before firmware is claimed ready.

## Android toolchain
- AGP 9.3.2
- Gradle 9.5.0
- Built-in Kotlin support; org.jetbrains.kotlin.android was removed.
- Compose plugin 2.2.10
- compileSdk/targetSdk 37, minSdk 26
- Java 17
- NDK 27.2.12479018
- CMake 3.22.1
- Compose BOM 2026.08.00
- LiteRT 2.2.0
- android.uniquePackageNames=false retained as compatibility workaround for LiteRT 2.2.0 duplicate namespace packaging.

## Detector
Official YOLO26n LiteRT asset is committed at:
android/app/src/main/assets/models/yolo26n.tflite
SHA-256: d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73
Size: 2,875,553 bytes.
Registry contract: 640x640 NCHW RGB, FP32 activations, classic YOLO output [1,84,8400], COCO traffic classes car=2, motorcycle=3, bus=5, truck=7. Build task verifyYolo26nModel verifies the exact SHA-256.

## Analysis pipeline state
Implemented:
- strict frame index ordering and gap metrics
- detector error propagation (no silent inference fallback)
- two-stage high/low-confidence tracking
- Kalman box prediction
- global linear assignment
- class-aware matching
- confirmation/lost-track handling
- bounded center-distance recovery for temporary non-overlap
- deterministic appearance signature as a secondary association cue (NOT learned Re-ID)
- homography projection to metric ground plane
- RANSAC homography fitting
- robust median/MAD speed estimator
- calibration quality gates
- temporal plate consensus using actual reading timestamps
- traffic rule evaluation from measured speed
- persistent evidence records
- live processed-frame preview and radar in Analysis Lab

Explicitly NOT yet installed as production backends:
- learned appearance Re-ID
- optical-flow refinement
- vehicle keypoint/pose model
- dynamic keypoint homography
- segmentation refinement
- plate detector + OCR backend integrated into Android
- true decoder PTS frame source
- production evidence image/crop extraction and export

## Reliability rules
1. Pixel displacement is never physical speed.
2. Physical speed requires validated metric calibration.
3. Physical speed also requires exact source timestamps when configured with requireExactTimestampsForPhysicalSpeed=true.
4. A computed number is not a trusted measurement until all quality gates pass.
5. Calibration must have finite non-singular homography, measured reprojection error, and sufficient inlier ratio.
6. Detector/tracker failure must surface as an error or explicit rejected result.
7. Tracking appearance is only a secondary cue; geometry remains mandatory.
8. Accuracy claims require annotated ground truth and metrics such as precision/recall and MOT-style ID metrics; speed requires reference measurements.

## Timestamp provenance
FrameTimestampPrecision values:
- EXACT_SOURCE_CLOCK
- REQUESTED_SAMPLE_TIME
- UNKNOWN
LocalVideoFrameSource currently uses MediaMetadataRetriever sampling and marks the source as REQUESTED_SAMPLE_TIME because requested sample time is not equivalent to decoded PTS. Therefore configured exact-timestamp physical-speed runs are blocked for this source. A sequential MediaExtractor/MediaCodec source with actual presentation timestamps is required before claiming exact video speed timing.

## Calibration
CalibrationBuilder creates versioned profiles from image/ground correspondences to metric ground points using RANSAC homography and stores target-unit reprojection error + inlier statistics.
CalibrationStore persists profiles locally.
CalibrationValidator checks dimensions, homography validity, reprojection error, inlier ratio, and minimum inlier count.

## Tracking
ByteTrack.kt is intentionally described as ByteTrack-inspired, not reference-equivalent. Current association order:
- IoU with Kalman prediction
- IoU with last measured box
- bounded predicted-center distance gate
- optional deterministic appearance cue when both detections contain signatures
No learned Re-ID claim is allowed until a real embedding model/backend is installed and benchmarked.

## Appearance signature
AppearanceSignature is a deterministic 2x2 spatial RGB histogram (8 bins/channel) used only as a secondary identity cue. AppearanceAugmentingDetector can compute it from Bitmap crops. It must not be described as a neural Re-ID embedding.

## Plates / OCR
PlateReading contains timestampMs. PlateConsensus uses temporal weighting/aggregation rather than selecting one arbitrary OCR frame. Android still lacks a real plate detector + OCR backend integration; enabling plate recognition must remain rejected until a backend is installed.

## Rules / evidence
TrafficRulePreferences is the shared persistence contract used by the Rules screen and analysis config.
TrafficRuleEngine evaluates rules from completed tracks + validated speed estimates.
EvidenceRecord stores event/source/frame/time/speed/threshold/confidence/track/plate/calibration/model/tracker metadata. FileEvidenceStore persists these locally. Evidence UI reads persisted records.

## Current known issue / last CI error
CI Run #200 failed at Kotlin compilation because ByteTrack.kt referenced non-existent Detection.centerX and Detection.centerY properties. Detection only stores left/right/top/bottom. The fix was committed by deriving center as ((left+right)/2, (top+bottom)/2).

A new CI run was triggered from the fix commit. Do not call it green until all Android steps (build, unit tests, lint) complete successfully.

## Recent commits / milestones
- eaaee7fa... preview drawing API fix; previous preview compile failure resolved.
- bb1428c5... model registry/letterbox hardening.
- d349ad7f... temporal plate consensus + traffic rules tests.
- 24b04f09... persisted traffic evidence UI.
- e174c181... shared persisted traffic rule configuration.
- a0d66871... appearance-assisted bounded vehicle tracking integration.
- 00cd878f... timestamp provenance and exact-timestamp physical speed gating tests.
- ddeb1f29... corrected tracker detection center calculation after CI Run #200 exposed the compile error.

## UI state
Analysis Lab shows real local-video/image selection, model readiness, detector/tracker status, live processed preview, tracking radar, and execution metrics. Speed is intentionally blocked unless calibration/timestamp gates pass. Rules screen persists rule configuration. Evidence screen lists persisted evidence records.

## Current priorities
P0: Get the current HEAD fully green in CI after the ByteTrack center fix.
P1: Add a true PTS-preserving video FrameSource and tests proving exact timestamp provenance.
P2: Build an in-app Calibration Lab: select source image/video frame, tap correspondences, enter measured ground distances, fit/validate homography, persist profile, load profile into Analysis Lab.
P3: Produce real evidence frames/crops from source media, not metadata-only evidence.
P4: Integrate optical flow as a bounded refinement module with independent tests and benchmarks.
P5: Integrate a real learned vehicle pose/keypoint model and dynamic homography only after model contract validation.
P6: Integrate plate detector + OCR backend; then temporal consensus and plate-format validation.
P7: Integrate learned Re-ID and benchmark IDF1/HOTA/MOTA/ID switches on annotated traffic sequences.
P8: Add device benchmarking for model preprocessing/inference/postprocessing/E2E and accelerator verification on actual hardware.

## Validation status
Offline Python research tests have been repeatedly green in recent runs.
Android CI has a history of green builds, but every new commit must be validated independently. A cancelled run is not a success.

## Context checkpoint protocol
After every significant implementation stage or at least every 2-3 tool operations, update this file with:
- current HEAD commit
- what changed
- why it changed
- tests/CI status
- newly discovered issues
- next P0/P1 task
This protocol is mandatory so future conversations can resume without losing engineering state.
