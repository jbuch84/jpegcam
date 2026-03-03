cmake_minimum_required(VERSION 3.4.1)

# This creates our C++ library called "native-lib"
add_library(native-lib SHARED native-lib.cpp)

# This finds the Android log library so we can debug
find_library(log-lib log)

# This links everything together
target_link_libraries(native-lib ${log-lib} jnigraphics)