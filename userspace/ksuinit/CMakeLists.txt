cmake_minimum_required(VERSION 3.14)
project(ksuinit VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 极限体积优化
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden")
set(CMAKE_CXX_FLAGS_RELEASE "-Os -DNDEBUG -flto")
set(CMAKE_CXX_FLAGS_MINSIZEREL "-Os -DNDEBUG -flto")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,--gc-sections -Wl,--strip-all -flto")

add_executable(ksuinit
    src/main.cpp
    src/init.cpp
    src/loader.cpp
    src/log.cpp
)

target_include_directories(ksuinit PRIVATE src)

# No external dependencies - pure C++ with POSIX/Linux APIs
