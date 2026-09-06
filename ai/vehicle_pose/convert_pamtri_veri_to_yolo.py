"""Convert PAMTRI/VeRi 36-keypoint CSV annotations to Ultralytics pose labels.

Input CSV format follows NVlabs/PAMTRI PoseEstNet/lib/dataset/veri.py:
  image_name,width,height,(x,y,visibility) * 36

The converter derives a full vehicle bbox from the annotated keypoint extents and writes one
single-class YOLO pose label per image. It intentionally preserves all 36 indexed keypoints;
the semantic names remain index-based until the final template schema is frozen.
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

KEYPOINTS = 36


def clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def convert(csv_path: Path, labels_dir: Path) -> int:
    labels_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    with csv_path.open(newline="", encoding="utf-8") as stream:
        for row in csv.reader(stream):
            if not row:
                continue
            if len(row) < 3 + KEYPOINTS * 3:
                raise ValueError(f"{csv_path}: expected {3 + KEYPOINTS * 3} columns, got {len(row)}")
            name = row[0]
            width = float(row[1])
            height = float(row[2])
            if width <= 0 or height <= 0:
                raise ValueError(f"{csv_path}: invalid image size for {name}: {width}x{height}")

            points: list[tuple[float, float, int]] = []
            for index in range(KEYPOINTS):
                base = 3 + index * 3
                x = float(row[base])
                y = float(row[base + 1])
                visible = int(float(row[base + 2]))
                if not all(map(lambda value: value == value and abs(value) != float("inf"), (x, y))):
                    raise ValueError(f"{csv_path}: non-finite keypoint {index} for {name}")
                points.append((x, y, visible))

            # PAMTRI stores projected coordinates for all semantic vertices and a separate
            # visibility flag. The bbox should cover the complete annotated wireframe, not only
            # visible points, so that occluded vehicle parts do not shrink the object box.
            min_x = min(point[0] for point in points)
            max_x = max(point[0] for point in points)
            min_y = min(point[1] for point in points)
            max_y = max(point[1] for point in points)
            box_width = max(1.0, max_x - min_x)
            box_height = max(1.0, max_y - min_y)
            center_x = (min_x + max_x) * 0.5
            center_y = (min_y + max_y) * 0.5

            fields = [
                "0",
                f"{clamp01(center_x / width):.8f}",
                f"{clamp01(center_y / height):.8f}",
                f"{clamp01(box_width / width):.8f}",
                f"{clamp01(box_height / height):.8f}",
            ]
            for x, y, visible in points:
                fields.extend((
                    f"{clamp01(x / width):.8f}",
                    f"{clamp01(y / height):.8f}",
                    str(2 if visible > 0 else 0),
                ))

            label_path = labels_dir / (Path(name).stem + ".txt")
            label_path.write_text(" ".join(fields) + "\n", encoding="utf-8")
            written += 1
    return written


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path, help="PAMTRI annot/label_<split>.csv")
    parser.add_argument("labels_dir", type=Path, help="Destination labels directory")
    args = parser.parse_args()
    print(f"wrote {convert(args.csv, args.labels_dir)} YOLO pose labels")


if __name__ == "__main__":
    main()
