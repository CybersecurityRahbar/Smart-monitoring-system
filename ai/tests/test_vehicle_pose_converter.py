import csv
import tempfile
import unittest
from pathlib import Path

from ai.vehicle_pose.convert_pamtri_veri_to_yolo import KEYPOINTS, convert


class VehiclePoseConverterTest(unittest.TestCase):
    def test_converts_pamtri_row_to_36_point_yolo_pose_label(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            csv_path = root / "label_train.csv"
            labels = root / "labels"
            row = ["car001.jpg", "100", "80"]
            for index in range(KEYPOINTS):
                row.extend([str(10 + index), str(20 + index), "1" if index % 2 == 0 else "0"])
            with csv_path.open("w", newline="", encoding="utf-8") as stream:
                csv.writer(stream).writerow(row)

            self.assertEqual(1, convert(csv_path, labels))
            fields = (labels / "car001.txt").read_text(encoding="utf-8").split()
            self.assertEqual(5 + KEYPOINTS * 3, len(fields))
            self.assertEqual("0", fields[0])
            self.assertEqual("2", fields[7])
            self.assertEqual("0", fields[10])

    def test_rejects_short_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            csv_path = root / "bad.csv"
            csv_path.write_text("car.jpg,100,80,1,2\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                convert(csv_path, root / "labels")


if __name__ == "__main__":
    unittest.main()
