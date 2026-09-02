#include "traffic_core.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

namespace smarttraffic {
namespace {

constexpr long long kMaxPairGapMs = 1200;
constexpr double kMaxPlausibleSpeedKmh = 250.0;
constexpr double kMadScale = 1.4826;

struct PairVelocity {
    double vx;
    double vy;
    double speed;
};

struct TimedPoint {
    double x;
    double y;
    long long timestamp_ms;
};

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

double percentile(std::vector<double> values, double probability) {
    if (values.empty()) return std::numeric_limits<double>::quiet_NaN();
    std::sort(values.begin(), values.end());
    const double position = std::clamp(probability, 0.0, 1.0) * static_cast<double>(values.size() - 1U);
    const auto lower = static_cast<std::size_t>(position);
    const auto upper = std::min(values.size() - 1U, lower + 1U);
    if (lower == upper) return values[lower];
    return values[lower] + (values[upper] - values[lower]) * (position - static_cast<double>(lower));
}

}  // namespace

Point2d project_homography(const double* h9, double x, double y) {
    if (h9 == nullptr || !std::isfinite(x) || !std::isfinite(y)) {
        return {std::numeric_limits<double>::quiet_NaN(),
                std::numeric_limits<double>::quiet_NaN()};
    }
    const double w = h9[6] * x + h9[7] * y + h9[8];
    if (!std::isfinite(w) || std::abs(w) < 1e-12) {
        return {std::numeric_limits<double>::quiet_NaN(),
                std::numeric_limits<double>::quiet_NaN()};
    }
    const double px = (h9[0] * x + h9[1] * y + h9[2]) / w;
    const double py = (h9[3] * x + h9[4] * y + h9[5]) / w;
    if (!std::isfinite(px) || !std::isfinite(py)) {
        return {std::numeric_limits<double>::quiet_NaN(),
                std::numeric_limits<double>::quiet_NaN()};
    }
    return {px, py};
}

SpeedResult robust_speed(const double* x_meters,
                         const double* y_meters,
                         const long long* timestamps_ms,
                         std::size_t count,
                         std::size_t minimum_samples) {
    if (x_meters == nullptr || y_meters == nullptr || timestamps_ms == nullptr ||
        count < minimum_samples || count < 2U) {
        return {0.0, 0.0, 0.0, static_cast<int>(count)};
    }

    std::vector<TimedPoint> points;
    points.reserve(count);
    for (std::size_t i = 0U; i < count; ++i) {
        if (!std::isfinite(x_meters[i]) || !std::isfinite(y_meters[i])) continue;
        points.push_back({x_meters[i], y_meters[i], timestamps_ms[i]});
    }
    if (points.size() < minimum_samples) {
        return {0.0, 0.0, 0.0, static_cast<int>(points.size())};
    }
    std::stable_sort(points.begin(), points.end(), [](const TimedPoint& a, const TimedPoint& b) {
        return a.timestamp_ms < b.timestamp_ms;
    });

    const long long duration_ms = points.back().timestamp_ms - points.front().timestamp_ms;
    if (duration_ms <= 0) return {0.0, 0.0, 0.0, 0};

    std::vector<PairVelocity> slopes;
    slopes.reserve(points.size() * 8U);
    for (std::size_t i = 0U; i + 1U < points.size(); ++i) {
        for (std::size_t j = i + 1U; j < points.size(); ++j) {
            const long long dt_ms = points[j].timestamp_ms - points[i].timestamp_ms;
            if (dt_ms <= 0 || dt_ms > kMaxPairGapMs) continue;
            const double dt_seconds = static_cast<double>(dt_ms) / 1000.0;
            const double vx = (points[j].x - points[i].x) / dt_seconds;
            const double vy = (points[j].y - points[i].y) / dt_seconds;
            const double speed = std::hypot(vx, vy);
            if (std::isfinite(vx) && std::isfinite(vy) && std::isfinite(speed) &&
                speed <= kMaxPlausibleSpeedKmh / 3.6) {
                slopes.push_back({vx, vy, speed});
            }
        }
    }

    const std::size_t minimum_pairs = std::max<std::size_t>(6U, minimum_samples * 2U);
    if (slopes.size() < minimum_pairs) {
        return {0.0, 0.0, 0.0, static_cast<int>(slopes.size())};
    }

    std::vector<double> speeds;
    speeds.reserve(slopes.size());
    for (const auto& slope : slopes) speeds.push_back(slope.speed);
    const double speed_median = median(speeds);

    std::vector<double> deviations;
    deviations.reserve(slopes.size());
    for (const auto& slope : slopes) deviations.push_back(std::abs(slope.speed - speed_median));
    const double speed_mad = median(deviations);
    const double robust_scale = std::max(1e-6, kMadScale * speed_mad);
    const double gate = std::max(0.20, 3.5 * robust_scale);

    std::vector<PairVelocity> inliers;
    inliers.reserve(slopes.size());
    for (const auto& slope : slopes) {
        if (std::abs(slope.speed - speed_median) <= gate) inliers.push_back(slope);
    }
    if (inliers.size() < minimum_pairs) {
        return {0.0, 0.0, 0.0, static_cast<int>(inliers.size())};
    }

    std::vector<double> vx_values;
    std::vector<double> vy_values;
    std::vector<double> inlier_speed_values;
    vx_values.reserve(inliers.size());
    vy_values.reserve(inliers.size());
    inlier_speed_values.reserve(inliers.size());
    for (const auto& slope : inliers) {
        vx_values.push_back(slope.vx);
        vy_values.push_back(slope.vy);
        inlier_speed_values.push_back(slope.speed);
    }
    const double vx = median(vx_values);
    const double vy = median(vy_values);
    const double speed = std::hypot(vx, vy);
    if (!std::isfinite(speed) || speed > kMaxPlausibleSpeedKmh / 3.6) {
        return {0.0, 0.0, 0.0, static_cast<int>(inliers.size())};
    }

    const double median_t = [&points]() {
        std::vector<double> values;
        values.reserve(points.size());
        for (const auto& point : points) values.push_back(static_cast<double>(point.timestamp_ms));
        return median(values);
    }();
    const double t0 = static_cast<double>(points.front().timestamp_ms);

    std::vector<double> x_values;
    std::vector<double> y_values;
    x_values.reserve(points.size());
    y_values.reserve(points.size());
    for (const auto& point : points) {
        x_values.push_back(point.x);
        y_values.push_back(point.y);
    }
    const double x0 = median(x_values) - vx * ((median_t - t0) / 1000.0);
    const double y0 = median(y_values) - vy * ((median_t - t0) / 1000.0);

    std::vector<double> residuals;
    residuals.reserve(points.size());
    for (const auto& point : points) {
        const double dt = static_cast<double>(point.timestamp_ms) - t0;
        const double predicted_x = x0 + vx * (dt / 1000.0);
        const double predicted_y = y0 + vy * (dt / 1000.0);
        residuals.push_back(std::hypot(point.x - predicted_x, point.y - predicted_y));
    }
    std::vector<double> residual_copy = residuals;
    const double median_residual = median(residual_copy);
    const double p90_residual = percentile(residuals, 0.90);

    std::vector<double> velocity_residuals;
    velocity_residuals.reserve(inliers.size());
    for (const auto& slope : inliers) velocity_residuals.push_back(std::abs(slope.speed - speed));
    const double velocity_mad = median(velocity_residuals);

    const double coverage = static_cast<double>(inliers.size()) / static_cast<double>(slopes.size());
    const double stability = std::clamp(
        1.0 - (kMadScale * velocity_mad) / std::max(speed, 0.25), 0.0, 1.0);
    const double trajectory_quality = std::clamp(1.0 - median_residual / 2.0, 0.0, 1.0);
    const double duration_quality = std::clamp(static_cast<double>(duration_ms) / 2000.0, 0.0, 1.0);
    const double confidence = std::clamp(
        0.30 * coverage + 0.30 * stability + 0.25 * trajectory_quality + 0.15 * duration_quality,
        0.0, 1.0);

    const double uncertainty_mps = std::max(
        kMadScale * velocity_mad * 2.0,
        p90_residual / std::max(static_cast<double>(duration_ms) / 1000.0, 0.1));

    return {speed, confidence, uncertainty_mps * 3.6, static_cast<int>(inliers.size())};
}

}  // namespace smarttraffic
