# Smart Traffic — 2026-09-06 Continuation / Build Fixes + Timeline Hardening

## Scope
This note continues `PROJECT_CONTEXT_SESSION_2026-09-06_BUGFIX.md` without replacing it. It records the subsequent build-error investigation, fixes, validation, and the next technical stopping point.

## Build-error investigation

The first 36-keypoint implementation pass reached CI Run #495 but Android compilation failed.

### Error 1 — Kotlin `buildList`
`LiteRtVehicleKeypointEstimator.kt` used `buildList(spec.keypointCount) { index -> ... }`. Kotlin's builder takes a receiver lambda; the indexed form is invalid. The parser was corrected to iterate explicitly over `0 until spec.keypointCount` and call `add(...)` only for confident keypoints.

### Error 2 — Homography projection shadowing
`VehicleKeypointHomography.kt` exposed a member `HomographyFit.project(...)` while the RANSAC fitting loop also worked with raw `DoubleArray` matrices. The code attempted `candidate.project(...)` and `refined.project(...)` even though `fitDlt()` returns `DoubleArray`. This caused the final Run #498 compile error:

`Unresolved reference 'project'`

The correction was to use the explicit matrix helper `VehicleKeypointHomography.projectPoint(...)` for raw matrices, while keeping `HomographyFit.project(...)` as a convenience wrapper.

## Validation result

After the fixes, CI Run #499 on commit `d2cc79d042815060ca9263b502eaab8851abb9af` passed all substantive jobs:

- Android `assembleDebug`: success.
- Android `assembleDebugAndroidTest`: success.
- Android `testDebugUnitTest`: success.
- Android `lintDebug`: success.
- Native C++ parity vectors: success.
- Offline research math tests: success.
- AI-Thinker ESP32-CAM firmware build: success.
- ESP32-S3-N16R8 firmware build: success.

The compile failures are therefore resolved. Cache-reservation messages in the failed run were unrelated to the Kotlin source errors.

## Timeline hardening implemented after the green build

The earlier renderer inferred playback timeline origin from the earliest visible track observation. That was not guaranteed to equal the media PTS origin: a vehicle can appear after the video starts.

### Source model
`MediaSource.timelineStartTimestampMs` was added. It is nullable because not every future live source has an absolute media origin available.

### Frame model
`AnalysisFrame.timelineStartTimestampMs` was added so the timestamp origin travels with the actual source frame rather than through an unrelated UI/global state mechanism.

### Exact PTS decoder
`ExactPtsVideoFrameSource` now reads the selected track's first extractor sample time and propagates it as the source timeline origin. Every decoded frame carries that origin. This is important for non-zero-start PTS recordings because playback position zero is relative to the media timeline while analysis timestamps can retain the source timestamp domain.

### Indexed local decoder
`LocalVideoFrameSource` declares a zero-based timeline origin and propagates it with each emitted frame because its requested/indexed timestamps are an analysis-relative clock, not proof of source PTS.

### Preview model
`AnalysisPreviewFrame.timelineStartTimestampMs` is derived from `frame.timelineStartTimestampMs` by default. This removes the need for a separate process-global holder and preserves the separation between analytics and rendering.

## Important incomplete part

`AnalysisVideoPlayback.kt` has not yet been changed to consume `preview.timelineStartTimestampMs` instead of calculating `timelineOriginMs` from track observations. The field is now present at the source/frame/preview contracts, but the visual renderer still needs one dedicated commit to use it.

That renderer change should be validated with:

1. a video whose first video PTS is non-zero;
2. a video where no vehicle appears until well after playback begins;
3. a normal zero-origin recording;
4. an analysis source whose decode FPS is lower than playback FPS.

## 36-point lineage status

The project now has a strong machine-readable lineage for the target 36-point vehicle representation through NVlabs/PAMTRI/Deep MANTA-era research. PAMTRI uses 36 vehicle joints, explicit left/right flip pairs `0↔18 ... 17↔35`, and 13 vehicle-surface segments. It also contains pretrained HRNet pose checkpoints. This is a reference/training-schema source, not an Android-ready model.

The current project still does **not** claim to have the 2026 paper's trained 36-point checkpoint. The `vehicle_pose_36.yaml` file is a training contract, not a trained model. The current LiteRT adapter is a real deployment parser, but it should remain disabled until a verified model artifact is installed.

## Geometry status

`VehicleKeypointHomography.kt` now provides normalized DLT + deterministic RANSAC with degeneracy rejection and reprojection metrics. It is intentionally semantic-agnostic. The remaining research step is to provide the verified 36-point metric template coordinates/index mapping and then feed high-confidence keypoints into per-frame dynamic homography.

## Next implementation milestone

The next work item is to finish source-to-render clock usage and reduce cinematic extrapolation to a conservative short horizon (~250–350 ms). After that, build a render-only speed-label smoother and replace the current 'first speed gate wins' behavior with a robust delayed gate built after enough stable trajectories are available.

Only after those presentation/clock invariants are stable should dynamic 36-point homography be connected to calibration-free metric speed.
