# Smart Traffic on-device models

The first Android detector is now checked into this directory:

- `yolo26n.tflite` — official Ultralytics Android LiteRT `yolo26n_w8a32.tflite` asset from release `v0.6.6`.
- Runtime input contract: `1 x 3 x 640 x 640`, FP32 values, NCHW.
- Runtime output contract: `1 x 84 x 8400`, the traditional one-to-many YOLO detection layout.
- The 4 box channels are normalized `xywh`; the remaining 80 channels are COCO class scores.
- The Android adapter restricts traffic detections to COCO classes: car (2), motorcycle (3), bus (5), truck (7), then restores letterboxed coordinates and performs class-aware NMS.

Pinned SHA-256:
`d9cef07ce652ccfa9ce58e4ac8a4df98ff037739a9dad20a8afcae21b545df73`

Official source:
`https://github.com/ultralytics/yolo-flutter-app/releases/download/v0.6.6/yolo26n_w8a32.tflite`

The Android Gradle build verifies the local asset checksum before `preBuild`. A checksum failure or missing model is a hard build failure; the application does not silently fall back to a missing or incompatible detector.

Licensing: verify Ultralytics YOLO26/asset licensing against the intended distribution model before publishing or commercial deployment. The model/framework documentation identifies the default YOLO license as AGPL-3.0, with separate enterprise licensing for other use cases.
