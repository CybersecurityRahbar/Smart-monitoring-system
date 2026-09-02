# Smart Traffic on-device models

Place validated LiteRT `.tflite` detector assets in this directory when testing on Android.

Expected first baseline:
- `yolo26n.tflite` exported from a validated Ultralytics YOLO26n checkpoint with `imgsz=640`.
- Current adapter expects a single float input and the current Ultralytics end-to-end `[N, 6]` output: `x1,y1,x2,y2,confidence,classId`.

Do not commit large/private/custom weights without an explicit decision on repository size and licensing. Model cards, export commands, checksums, and benchmark results should be recorded separately.

Suggested export command from the research environment:
`yolo export model=yolo26n.pt format=litert imgsz=640`

For quantized candidates, export and benchmark separately; do not compare quantized and float models using only latency. Record detection metrics and thermal/runtime behavior on the same phone.
