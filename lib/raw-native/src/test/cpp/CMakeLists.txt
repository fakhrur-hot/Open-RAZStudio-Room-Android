cmake_minimum_required(VERSION 3.22)
project(studioroom_sampler_layout_test CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(sampler_layout_test sampler_layout_test.cpp)
target_include_directories(sampler_layout_test PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../main/cpp/v3"
)
