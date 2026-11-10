cmake_minimum_required(VERSION 3.22.1)
project("photo2model_core")

add_library(photo2model_core SHARED
    native_bridge.cpp
    image_probe.cpp
    mask_ops.cpp
    voxel_carve.cpp
    surface_nets.cpp
)

target_include_directories(photo2model_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)

target_link_libraries(photo2model_core ${log-lib})

target_compile_options(photo2model_core PRIVATE -Wall -Wextra -O2)
