cmake_minimum_required(VERSION 3.16)
project(smarttraffic_native_parity LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(smarttraffic_native_parity
    test_traffic_core.cpp
    ../../android/app/src/main/cpp/traffic_core.cpp
)

target_include_directories(smarttraffic_native_parity PRIVATE
    ../../android/app/src/main/cpp
)

if (MSVC)
    target_compile_options(smarttraffic_native_parity PRIVATE /W4)
else()
    target_compile_options(smarttraffic_native_parity PRIVATE -Wall -Wextra -Wpedantic)
endif()

enable_testing()
add_test(NAME smarttraffic_native_parity COMMAND smarttraffic_native_parity)
