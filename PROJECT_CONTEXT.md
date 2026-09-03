# Smart Traffic Monitoring System — Persistent Conversation / Engineering Context

Last updated: 2026-09-03
Current HEAD: 42190a49ce8a49bc0780557432d30617eccb9fe4
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
Official YOLO26n LiteRT asset is committed at android/app/src/main/assets/models/yolo26n.tflite.
SHA-256: d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73
Size: 2,875,553 bytes.
Registry contract: 640x640 NCHW RGB, FP32 activations, classic YOLO output [1,84,8400], COCO traffic classes car=2, motorcycle=3, bus=5, truck=7. Build task verifyYolo26nModel verifies the exact SHA-256.

## Analysis pipeline — implemented
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
- timestamp provenance and physical-speed timestamp gate
- temporal plate consensus using timestamps
- traffic rule evaluation from measured speed
- persistent evidence records
- live processed-frame preview and radar in Analysis Lab
- shared persisted traffic-rule configuration

## Explicitly NOT installed as production backends
- learned appearance Re-ID
- optical-flow refinement
- vehicle keypoint/pose model
- dynamic keypoint homography
- segmentation refinement
- Android plate detector + OCR backend
- true decoder PTS-preserving video source
- production evidence image/crop extraction/export

## Reliability rules
1. Pixel displacement is never physical speed.
2. Physical speed requires validated metric calibration.
3. Physical speed requires exact source timestamps when requireExactTimestampsForPhysicalSpeed=true.
4. A computed number is not a trusted measurement until all quality gates pass.
5. Calibration must have finite non-singular homography, measured reprojection error, and sufficient inlier ratio.
6. Detector/tracker failure must surface as an error or explicit rejection.
7. Appearance is only a secondary cue; geometry remains mandatory.
8. Accuracy claims require annotated ground truth; tracking should use IDF1/HOTA/MOTA/ID switches and speed requires reference measurements.

## Timestamp provenance
FrameTimestampPrecision values: EXACT_SOURCE_CLOCK, REQUESTED_SAMPLE_TIME, UNKNOWN.
LocalVideoFrameSource currently uses MediaMetadataRetriever sampling and must not be treated as exact PTS. Therefore exact-timestamp physical-speed runs are blocked for this source until a MediaExtractor/MediaCodec-style source supplies actual presentation timestamps.

## Calibration
CalibrationBuilder creates versioned profiles from image/ground correspondences to metric ground points using RANSAC homography and stores target-unit reprojection error + inlier statistics.
CalibrationStore persists profiles locally.
CalibrationValidator checks dimensions, homography validity, reprojection error, inlier ratio and minimum inlier count.

## Tracking
ByteTrack.kt is ByteTrack-inspired, not reference-equivalent. Association uses predicted/measured IoU, bounded motion recovery, and optional deterministic appearance similarity. No learned Re-ID claim is allowed until a real embedding model/backend is installed and benchmarked.

## Appearance signature
AppearanceSignature is a deterministic 2x2 spatial RGB histogram with 8 bins/channel. AppearanceAugmentingDetector can compute it from Bitmap crops. It is not a neural Re-ID embedding.

## Plates / OCR
PlateReading contains timestampMs. PlateConsensus uses temporal aggregation. Android still lacks a real plate detector + OCR backend; enabling plate recognition must remain rejected until such a backend exists.

## Rules / evidence
TrafficRulePreferences is shared between the Rules screen and analysis configuration.
TrafficRuleEngine evaluates rules from completed tracks + validated speed estimates.
EvidenceRecord stores event/source/frame/time/speed/threshold/confidence/track/plate/calibration/model/tracker metadata. FileEvidenceStore persists locally. Evidence UI reads the persisted records.

## Latest failure and repair
CI Run #200 failed at Kotlin compilation in ByteTrack.kt because Detection has no centerX/centerY properties. The repair derives the detection center from left/right/top/bottom in commit ddeb1f29596b11caa769b2d2e6d88f0c6fea2e32.
The persistent context document was then created and the shared-rule/evidence work was preserved. Current HEAD after restoring this checkpoint is 42190a49ce8a49bc0780557432d30617eccb9fe4; the latest post-fix CI must still be checked and never assumed green.

## Recent milestones
- eaaee7fa... valid Compose drawing APIs for Analysis Lab preview.
- bb1428c5... detector registry and exact letterbox/model hardening.
- d349ad7f... temporal plate consensus + traffic rules tests.
- 24b04f09... persisted traffic evidence UI.
- e174c181... shared persisted traffic rule configuration.
- a0d66871... appearance-assisted bounded vehicle tracking integration.
- 00cd878f... timestamp provenance / physical-speed gating tests.
- ddeb1f29... fix Detection center calculation after Run #200.
- 42190a49... persistent PROJECT_CONTEXT.md restored.

## UI state
Analysis Lab supports real local video/image selection, model readiness, detector/tracker status, live processed preview, tracking radar and execution metrics. Speed is intentionally blocked unless calibration/timestamp gates pass. Rules screen persists configuration. Evidence screen lists persisted evidence records.

## Current priorities
P0: Verify current HEAD in CI; fix every build/test/lint issue before adding another backend.
P1: Add a true PTS-preserving video FrameSource and exact timestamp tests.
P2: Build an in-app Calibration Lab: choose source frame, tap image correspondences, enter measured ground distances, fit/validate homography, persist/load profile, and feed Analysis Lab.
P3: Produce real evidence frames/crops from source media instead of metadata-only records.
P4: Integrate bounded optical-flow refinement with independent tests and benchmarks.
P5: Integrate a real learned vehicle pose/keypoint model and dynamic homography only after model-contract validation.
P6: Integrate plate detector + OCR backend, then temporal consensus + regional plate-format validation.
P7: Integrate learned Re-ID and benchmark IDF1/HOTA/MOTA/ID switches on annotated traffic sequences.
P8: Add real-device preprocessing/inference/postprocessing/E2E benchmarks and verify accelerator placement on hardware.

## Context checkpoint protocol
This file is the persistent handoff source for this project. After every significant implementation stage, and at minimum every 2-3 tool operations during long work, update:
- current HEAD commit
- what changed and why
- tests/CI status
- newly discovered issues
- next P0/P1 task
Never mark a cancelled CI run as success. Never state that a feature exists unless it is actually connected to the runtime path and covered by an appropriate test.
