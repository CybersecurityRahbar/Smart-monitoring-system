# Vehicle pose / 36-keypoint workbench

This directory is the source-of-truth for the vehicle-keypoint training and Android-deployment path. The Android application already has a separate `VehicleKeypointEstimator` interface; the YOLO26n object detector remains responsible for vehicle detection and must not be relabeled as a pose model.

## Verified research facts

The August 17, 2026 paper `Calibration-Free Vehicle Speed Estimation: A Monocular Keypoint-Template Approach` uses a 36-semantic-keypoint metric vehicle template and re-estimates an image-to-template homography for each frame. The paper uses approximately planar vehicle facets and requires at least four non-collinear keypoint/template correspondences; RANSAC is used when more correspondences are available. The paper evaluates both sparse keypoint tracking and facet-restricted warped optical flow. See arXiv: https://arxiv.org/abs/2608.16785.

The paper does not currently provide a verified public, Android-ready 36-keypoint checkpoint in this project. Do not add a binary with an invented provenance or claim that a 14/33-point checkpoint is the paper's model.

## Candidate training sources

### CarFusion
CarFusion provides 14 semantic vehicle keypoints and is useful for bootstrapping vehicle-pose training and validating the Android parser. It does not supply the final 36-point annotation schema.

Reference implementation: https://github.com/Habib0905/Vehicle-Pose-Estimation

### SKoPe3D
SKoPe3D provides 33 vehicle keypoints from synthetic CARLA roadside traffic-monitoring scenes and evaluates Keypoint R-CNN. It is highly relevant to roadside viewpoints, but it is not the 36-point target and its published licensing terms are non-commercial/share-alike. Any training use must preserve those terms.

Project: https://github.com/skope3d/skope3d.github.io
Paper: https://arxiv.org/abs/2309.01324

### Public 14-point YOLO26-pose reference
`kiselyovd/vehicle-keypoints` provides a YOLO26-pose vehicle model for the CarFusion 14-point schema. The repository/model artifact is MIT, while the CarFusion data has separate CMU research terms. It is a compatibility/reference model only.

https://github.com/kiselyovd/vehicle-keypoints
https://huggingface.co/kiselyovd/vehicle-keypoints

## 36-point annotation contract

The final model must have exactly:

```yaml
kpt_shape: [36, 3]
```

with one vehicle class. The three values per keypoint are `(x, y, visibility/confidence)` in the annotation convention used by the selected trainer.

Do **not** invent a `flip_idx` table. Left/right swaps depend on the verified semantic ordering of the final 36-point template. Until that mapping is frozen and reviewed, disable horizontal-flip augmentation for the 36-point training configuration (`fliplr: 0.0`) rather than silently corrupting labels.

The semantic names/order must be copied from the verified machine-readable representation of the paper's Figure 4/template before the model is called `vehicle_keypoints_36` in the Android registry. The current Android registry therefore marks its 36-point spec as metadata-only.

## Expected Android tensor contract

For a one-class Ultralytics-style classic pose export with 36 keypoints and 8400 candidates:

```text
input  [1, 3, 640, 640]  FP32 NCHW RGB
output [1, 113, 8400]
```

because `113 = 5 + 36*3`.

The Android adapter must verify the flattened element count before parsing and map letterboxed crop coordinates back to full-frame coordinates. The pose model is invoked on a vehicle crop, while the detector and ByteTrack identity remain independent.

## Training path

The intended first training path is a compact YOLO pose model (YOLO26-pose where a compatible training checkpoint/config is available, otherwise YOLO11/YOLOv8 pose as a research baseline) with a 36-point custom dataset:

```bash
# Example only; do not run until the 36-point dataset is complete.
yolo pose train \
  model=yolo26n-pose.pt \
  data=ai/vehicle_pose/vehicle_pose_36.yaml \
  imgsz=640 \
  epochs=100 \
  fliplr=0.0
```

The exact base checkpoint is a benchmark choice, not a requirement of the geometry. The important requirement is that the final model is actually trained on the same 36 semantic labels used by the metric template.

## Export / acceptance gate

Before copying an artifact into `android/app/src/main/assets/models/vehicle_keypoints_36.tflite`, verify all of the following:

1. the 36 semantic indices match the frozen template definition;
2. the exported tensor really is `[1,113,8400]` (or a separately implemented, verified layout);
3. inference on representative traffic crops returns stable wheel/body/window/side-facet landmarks under scale and perspective changes;
4. keypoint confidence values are calibrated enough for RANSAC correspondence gating;
5. CPU latency and peak memory are measured on the target Android device;
6. the exact binary SHA-256 and provenance are recorded in the Android model registry;
7. the model passes a held-out traffic benchmark before it is used by the calibration-free speed estimator.

Until these gates pass, the existing calibration-free width-prior estimator remains the application's fallback and is explicitly an estimate, not enforcement evidence.
