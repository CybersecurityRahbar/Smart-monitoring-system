#include "traffic_core.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace smarttraffic {
namespace {

double median(std::vector<double>& values) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    const std::size_t middle = values.size() / 2U;
    std::nth_element(values.begin(), values.begin() + middle, values.end());
    double result = values[middle];
    if ((values.size() & 1U) == 0U) {
        std::nth_element(values.begin(), values.begin() + middle - 1U, values.end());
        result = (result + values[middle - 1U]) * 0.5;
    }
    return result;
}

}  // namespace

Point2d project_homography(const double* h9, double x, double y) {
    const double w = h9[6] * x + h9[7] * y + h9[8];
    if (!std::isfinite(w) || std::abs(w) < 1e-12) {
        return {std::numeric_limits<double>::quiet_NaN(),
                std::numeric_limits<double>::quiet_NaN()};
    }
    return {
        (h9[0] * x + h9[1] * y + h9[2]) / w,
        (h9[3] * x + h9[4] * y + h9[5]) / w,
    };
}

SpeedResult robust_speed(const double* x_meters,
                         const double* y_meters,
                         const long long* timestamps_ms,
                         std::size_t count,
                         std::size_t minimum_samples) {
    if (count < minimum_samples || count < 2U) {
        return {0.0, 0.0, 0.0, static_cast<int>(count)};
    }

    std::vector<double> velocities;
    velocities.reserve(count - 1U);
    for (std::size_t i = 1U; i < count; ++i) {
        const long long dt_ms = timestamps_ms[i] - timestamps_ms[i - 1U];
        if (dt_ms <= 0) continue;
        const double dx = x_meters[i] - x_meters[i - 1U];
        const double dy = y_meters[i] - y_meters[i - 1U];
        const double distance = std::hypot(dx, dy);
        const double velocity = distance / (static_cast<double>(dt_ms) / 1000.0);
        if (std::isfinite(velocity) && velocity >= 0.0 && velocity < 100.0) {
            velocities.push_back(velocity);
        }
    }

    if (velocities.size() < minimum_samples - 1U) {
        return {0.0, 0.0, 0.0, static_cast<int>(velocities.size())};
    }

    const double center = median(velocities);
    std::vector<double> deviations;
    deviations.reserve(velocities.size());
    for (double value : velocities) deviations.push_back(std::abs(value - center));
    const double mad = median(deviations);
    const double robust_sigma = 1.4826 * mad;
    const double gate = std::max(0.25, 3.0 * robust_sigma);

    std::vector<double> inliers;
    inliers.reserve(velocities.size());
    for (double value : velocities) {
        if (std::abs(value - center) <= gate) inliers.push_back(value);
    }
    if (inliers.size() < minimum_samples - 1U) {
        return {0.0, 0.0, 0.0, static_cast<int>(inliers.size())};
    }

    const double robust_velocity = median(inliers);
    const double scatter_kmh = robust_sigma * 3.6;
    const double duration_seconds =
        static_cast<double>(timestamps_ms[count - 1U] - timestamps_ms[0U]) / 1000.0;
    const double duration_factor = std::min(1.0, duration_seconds / 2.0);
    const double stability_factor = std::max(0.0, std::min(1.0, 1.0 - scatter_kmh / 15.0));
    const double coverage = static_cast<double>(inliers.size()) /
                            static_cast<double>(velocities.size());
    const double confidence = std::max(
        0.0, std::min(1.0,
            0.45 * duration_factor + 0.30 * stability_factor + 0.25 * coverage));

    return {robust_velocity, confidence, scatter_kmh, static_cast<int>(inliers.size())};
}

}  // namespace smarttraffic
