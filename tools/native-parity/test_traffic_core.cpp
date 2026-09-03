#include "traffic_core.h"

#include <cmath>
#include <cstdlib>
#include <iostream>

namespace {

void expect_near(const char* name, double actual, double expected, double tolerance) {
    if (!std::isfinite(actual) || std::abs(actual - expected) > tolerance) {
        std::cerr << name << " expected=" << expected << " actual=" << actual << "\n";
        std::exit(1);
    }
}

void expect_equal(const char* name, int actual, int expected) {
    if (actual != expected) {
        std::cerr << name << " expected=" << expected << " actual=" << actual << "\n";
        std::exit(1);
    }
}

void test_linear() {
    constexpr double x[] = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7};
    constexpr double y[] = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    constexpr long long t[] = {0, 100, 200, 300, 400, 500, 600, 700};

    const auto result = smarttraffic::robust_speed(x, y, t, 8U, 8U);
    expect_near("linear speed", result.meters_per_second, 1.0, 1e-9);
    expect_near("linear vx", result.velocity_x_mps, 1.0, 1e-9);
    expect_near("linear vy", result.velocity_y_mps, 0.0, 1e-9);
    expect_near("linear confidence", result.confidence, 0.6525, 1e-9);
    expect_near("linear residual", result.position_residual_meters, 0.0, 1e-9);
    expect_near("linear uncertainty", result.error_kmh, 0.0, 1e-9);
    expect_equal("linear inliers", result.sample_count, 28);
}

void test_diagonal() {
    constexpr double x[] = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7};
    constexpr double y[] = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7};
    constexpr long long t[] = {0, 100, 200, 300, 400, 500, 600, 700};

    const auto result = smarttraffic::robust_speed(x, y, t, 8U, 8U);
    expect_near("diagonal speed", result.meters_per_second, std::sqrt(2.0), 1e-9);
    expect_near("diagonal vx", result.velocity_x_mps, 1.0, 1e-9);
    expect_near("diagonal vy", result.velocity_y_mps, 1.0, 1e-9);
    expect_near("diagonal confidence", result.confidence, 0.6525, 1e-9);
    expect_near("diagonal residual", result.position_residual_meters, 0.0, 1e-9);
    expect_equal("diagonal inliers", result.sample_count, 28);
}

void test_variable_timestamps() {
    constexpr double x[] = {0.0, 0.25, 0.55, 1.0, 1.3, 1.8, 2.2, 2.5};
    constexpr double y[] = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    constexpr long long t[] = {0, 125, 275, 500, 650, 900, 1100, 1250};

    const auto result = smarttraffic::robust_speed(x, y, t, 8U, 8U);
    expect_near("variable speed", result.meters_per_second, 2.0, 1e-9);
    expect_near("variable vx", result.velocity_x_mps, 2.0, 1e-9);
    expect_near("variable vy", result.velocity_y_mps, 0.0, 1e-9);
    expect_near("variable confidence", result.confidence, 0.69375, 1e-9);
    expect_near("variable residual", result.position_residual_meters, 0.0, 1e-9);
    expect_equal("variable inliers", result.sample_count, 27);
}

void test_homography() {
    constexpr double h[] = {2.0, 0.0, 10.0, 0.0, 3.0, 20.0, 0.0, 0.0, 1.0};
    const auto point = smarttraffic::project_homography(h, 4.0, 5.0);
    expect_near("homography x", point.x, 18.0, 1e-12);
    expect_near("homography y", point.y, 35.0, 1e-12);
}

}  // namespace

int main() {
    test_linear();
    test_diagonal();
    test_variable_timestamps();
    test_homography();
    std::cout << "native parity vectors: PASS\n";
    return 0;
}
