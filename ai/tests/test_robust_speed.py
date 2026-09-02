"""Deterministic unit tests for the offline robust speed estimator."""

from __future__ import annotations

import unittest

from ai.robust_speed import estimate_speed


class RobustSpeedTest(unittest.TestCase):
    def test_recovers_one_mps_with_large_outlier(self) -> None:
        samples = []
        for i in range(20):
            x = i * 0.1
            if i == 11:
                x += 8.0
            elif i % 2 == 0:
                x += 0.01
            else:
                x -= 0.01
            samples.append((i * 0.1, x, 0.0))

        result = estimate_speed(samples, min_samples=8, min_duration_ms=500)
        self.assertIsNotNone(result)
        assert result is not None
        self.assertAlmostEqual(result.kilometers_per_hour, 3.6, delta=0.35)
        self.assertGreaterEqual(result.confidence, 0.0)
        self.assertLessEqual(result.confidence, 1.0)

    def test_stationary_jitter_is_not_large_speed(self) -> None:
        samples = []
        for i in range(16):
            samples.append((i * 0.1, 0.01 if i % 2 == 0 else -0.01, 0.01 if i % 3 == 0 else -0.01))

        result = estimate_speed(samples, min_samples=8, min_duration_ms=500)
        self.assertIsNotNone(result)
        assert result is not None
        self.assertLess(result.kilometers_per_hour, 2.0)


if __name__ == "__main__":
    unittest.main()
