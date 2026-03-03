cmake_minimum_required(VERSION 3.4.1)

set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_EXECUTABLES OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_JPEG8 ON CACHE BOOL "" FORCE)

set(WITH_SIMD OFF CACHE BOOL "" FORCE)

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(libjpeg-turbo)
add_library(native-lib SHARED native-lib.cpp)

target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo
    ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo
)

set_target_properties(native-lib PROPERTIES LINK_FLAGS "-Wl,-Bsymbolic -Wl,--exclude-libs,ALL")

# SAFE COMPILER OPTIMIZATIONS
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "-O3")
set(CMAKE_C_FLAGS_DEBUG "-O3")

find_library(log-lib log)
target_link_libraries(native-lib jpeg-static ${log-lib})