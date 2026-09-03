#pragma once

#include <cstddef>

namespace smarttraffic {

struct Point2d {
    double x;
    double y;
};

struct SpeedResult {
    double meters_per_second;
    double confidence;
    double error_kmh;
    int sample_count;
    double velocity_x_mps;
    double velocity_y_mps;
    double position_residual_meters;
};

Point2d project_homography(const double* h9, double x, double y);

SpeedResult robust_speed(const double* x_meters,
                         const double* y_meters,
                         const long long* timestamps_ms,
                         std::size_t count,
                         std::size_t minimum_samples);

}  // namespace smarttraffic
