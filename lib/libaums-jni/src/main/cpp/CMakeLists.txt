# Rebuild of the libaums 0.10.0 JNI shims (Apache-2.0, magnusja/libaums).
# The published AAR still ships these two libraries at 4 KB ELF alignment.
cmake_minimum_required(VERSION 3.22.1)
project(libaums_jni C)

add_library(errno-lib SHARED src/errno.c)
add_library(usb-lib SHARED src/usb.c)

find_library(log-lib log)
target_link_libraries(usb-lib PRIVATE ${log-lib})

foreach(target errno-lib usb-lib)
    target_link_options(${target} PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
endforeach()
