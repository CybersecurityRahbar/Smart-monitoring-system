cmake_minimum_required(VERSION 3.22.1)
project(smarttraffic_native)

add_library(smarttraffic_native SHARED
    traffic_core.cpp
    traffic_core_jni.cpp
)

target_compile_features(smarttraffic_native PRIVATE cxx_std_20)

target_compile_options(smarttraffic_native PRIVATE
    -O3
    -fno-rtti
)

target_link_libraries(smarttraffic_native
    android
    log
)
