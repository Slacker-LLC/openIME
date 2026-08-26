cmake_minimum_required(VERSION 3.22.1)

project(local_rime)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# Rime's Android build uses the same small set of native dependencies as the
# desktop core. Keep the engine embedded in this app; no Trime frontend is
# linked or packaged.
include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
set(WITH_GTEST OFF CACHE BOOL "Use Google Test" FORCE)
option(WITH_UNWIND "Enable libunwind support" OFF)
set(BUILD_TESTING OFF CACHE BOOL "Disable native dependency tests" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build native dependencies statically" FORCE)

add_subdirectory(vendor/librime/deps/glog EXCLUDE_FROM_ALL)
target_compile_options(glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/librime/deps/yaml-cpp EXCLUDE_FROM_ALL)

set(SNAPPY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SNAPPY_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(SNAPPY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SNAPPY_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(SNAPPY_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/snappy EXCLUDE_FROM_ALL)

set(HAVE_SNAPPY TRUE)
set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/librime/deps/leveldb EXCLUDE_FROM_ALL)

set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/librime/deps/marisa-trie EXCLUDE_FROM_ALL)
target_compile_options(marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
set(LIBMARISA marisa)
set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)

set(ENABLE_GTEST OFF CACHE BOOL "" FORCE)
set(ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/OpenCC EXCLUDE_FROM_ALL)
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_CURRENT_SOURCE_DIR}/vendor/librime/include")
include_directories("${X11Keysym}")

# Do not auto-discover optional Rime plugins. The base engine already provides
# the phonetic/table translators used by luna_pinyin and keeps the APK small.
set(ENV{RIME_PLUGINS} "")
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_DATA OFF CACHE BOOL "" FORCE)
set(BUILD_MERGED_PLUGINS ON CACHE BOOL "" FORCE)
set(ENABLE_LOGGING OFF CACHE BOOL "" FORCE)
set(ENABLE_TIMESTAMP OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/librime)

add_library(local_rime SHARED local_rime_jni.cc)
target_include_directories(local_rime PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/librime/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/librime/include"
    "${CMAKE_BINARY_DIR}/vendor/librime/src"
    "${CMAKE_BINARY_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/OpenCC"
)
target_link_libraries(local_rime rime-static ${Opencc_LIBRARY})
