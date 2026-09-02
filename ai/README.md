# Smart Traffic AI Research Engine

This directory is the offline research/benchmark layer for the Android project.

## Goals

- benchmark modern vehicle detectors and trackers on the same clips;
- compare detection/tracking quality before selecting a mobile runtime;
- evaluate calibrated homography speed against a vehicle-keypoint/dynamic-homography research path;
- produce reproducible JSON/CSV metrics for the Android validation workflow;
- keep model weights outside Git unless explicitly approved.

## Runtime strategy

The reference engine is Python-first for research. Android receives the selected model through a replaceable inference adapter.

Recommended research candidates:
- YOLO26 detect/pose/segmentation variants;
- RT-DETRv2 as an accuracy comparison;
- BoT-SORT, ByteTrack, OC-SORT and Deep OC-SORT;
- optional appearance Re-ID;
- optional segmentation refinement.

The production speed estimator remains metric/calibration-first. The keypoint + dynamic-homography path is experimental and must be reported with empirical error.

## Install

Use Python 3.11+ in a virtual environment, then:

```bash
pip install -r requirements.txt
```

The default CLI does not download weights automatically. Pass an explicit model path or name and follow the model/license terms.

## Example

```bash
python run_traffic_analysis.py \
  --source samples/traffic.mp4 \
  --model yolo26n.pt \
  --tracker botsort.yaml \
  --conf 0.35 \
  --output runs/example
```

Use `--keypoints-model path/to/vehicle_keypoints.pt` to enable a trained vehicle-keypoint model. The repository intentionally does not invent or bundle a 36-keypoint checkpoint; the model must be supplied and validated against the project's traffic data.

## Outputs

- `summary.json` — run metadata and aggregate metrics;
- `tracks.jsonl` — track-level observations;
- `events.jsonl` — optional speed/rule events;
- `annotated.mp4` — optional visualization;
- `metrics.csv` — validation metrics when reference truth is supplied.
