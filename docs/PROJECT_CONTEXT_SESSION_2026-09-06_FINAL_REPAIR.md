# Smart Traffic — 2026-09-06 Final Repair / Functional Baseline

## Verified state

The repository was intentionally moved back to commit `e94af7698b51a059e42033acd20e5189fa6d8061`, the last known clean code state before an accidental manual overwrite of `LocalAnalysisViewModel.kt`. This removes the temporary repair workflows/markers and preserves the completed implementation from the verified 36-keypoint milestone plus the JUnit import correction.

## Run #517 failure

Run #517 built the debug APK and Android instrumentation APK successfully. The Android unit-test stage failed only because `VehicleKeypointMetricSpeedEstimatorTest.kt` imported `kotlin.test.*`, while this project uses JUnit. The test imports were corrected to `org.junit.*`. ESP32-CAM, ESP32-S3, native parity, and offline research tests were successful in that run.

## Functional product path

The normal video analysis path is executable without a 36-keypoint checkpoint:

`YOLO26n LiteRT detector -> ByteTrack/Kalman/global assignment -> calibration-free speed estimator -> AnalysisResult -> AnalysisPreviewFrame -> AnalysisRadarPreview -> AnalysisVideoPlayback`

`AnalysisRadarPreview` is the actual UI integration point for recorded video. It invokes `AnalysisVideoPlayback` whenever the preview has a video URI, including full-screen mode. The playback component uses the recorded source video as its visual master clock and renders green tracking boxes, vehicle IDs, and the available speed estimate above each tracked vehicle.

The default `AnalysisConfig.enableCalibrationFreeSpeedEstimate` is true. Consequently a confirmed track with enough observations can receive a calibration-free engineering speed estimate even when validated road homography is not configured. This value is explicitly an estimate, not enforcement-grade evidence.

## 36-keypoint status

The project contains:

- a 36-keypoint model contract and LiteRT parser;
- normalized-DLT + deterministic-RANSAC homography code;
- a 36-keypoint metric-speed backend wired into `AnalysisPipelineRunner` behind `useDynamicKeypointHomography`;
- unit-test coverage for the synthetic metric-speed path.

The 36-keypoint inference path remains disabled by default because the repository does not contain a verified trained 36-point checkpoint and does not yet have a verified machine-readable metric coordinate table for the exact paper template. This is deliberate fail-closed behavior, not a missing implementation disguised as completion.

## Video playback clock

`AnalysisVideoPlayback.kt` consumes `preview.timelineStartTimestampMs` and uses `preview.renderTracks`. Cinematic interpolation/extrapolation is bounded to a short horizon. The source video is played at natural speed.

One remaining edge case is documented rather than hidden: `LocalAnalysisViewModel.publishCompletedVideoPreview()` reconstructs the final preview with a zero timestamp. That is harmless for the default `LocalVideoFrameSource` zero-origin timeline, but should be fixed before claiming exact non-zero-PTS calibrated replay. The source/frame/preview contracts already carry the authoritative timeline origin.

## Product claim boundary

Ready now: detector, tracking, green boxes, stable IDs, calibration-free speed estimates, and synchronized recorded-video replay.

Not claimed: enforcement-grade speed measurement; verified 36-point trained model in APK; exact paper-template metric coordinates; legal evidence certification.
