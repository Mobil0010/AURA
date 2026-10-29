cmake_minimum_required(VERSION 3.22)
project(aura_audio LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_TESTS
    OFF
    CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES
    OFF
    CACHE BOOL "" FORCE)
set(WITH_OPENMP
    OFF
    CACHE BOOL "" FORCE)
set(WITH_LSR_BINDINGS
    OFF
    CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS
    ON
    CACHE BOOL "" FORCE)
add_subdirectory(vendor/soxr)
add_library(
    usb STATIC
    vendor/libusb/libusb/core.c
    vendor/libusb/libusb/descriptor.c
    vendor/libusb/libusb/hotplug.c
    vendor/libusb/libusb/io.c
    vendor/libusb/libusb/strerror.c
    vendor/libusb/libusb/sync.c
    vendor/libusb/libusb/os/events_posix.c
    vendor/libusb/libusb/os/threads_posix.c
    vendor/libusb/libusb/os/linux_usbfs.c
    vendor/libusb/libusb/os/linux_netlink.c)
target_include_directories(usb PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} vendor/libusb/libusb)
target_compile_definitions(usb PRIVATE _GNU_SOURCE)
add_library(aura_audio SHARED audio.cpp usb_audio.cpp)
target_include_directories(aura_audio PRIVATE vendor/soxr/src vendor/libusb/libusb)
target_link_libraries(aura_audio PRIVATE soxr usb log)
