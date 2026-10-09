cmake_minimum_required(VERSION 3.22.1)
project(cardboard LANGUAGES CXX)

set(CARDBOARD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/cardboard)

# The Cardboard SDK itself (libGfxPluginCardboard.so). Only its OpenGL ES rendering is used, so
# Vulkan rendering and the Unity plugin are left out.
set(CARDBOARDSDK_RENDERING_VULKAN OFF)
set(CARDBOARDSDK_UNITY_PLUGIN OFF)
add_subdirectory(${CARDBOARD_DIR}/sdk ${CMAKE_CURRENT_BINARY_DIR}/sdk)
# The SDK's version script exports symbols from the Unity plugin and Vulkan rendering, which aren't
# built.
target_link_options(GfxPluginCardboard PRIVATE -Wl,--undefined-version)

# The native side of the SDK's Java API, built as in the SDK's paperscope sample.
find_library(LOG_LIB log)
find_library(GLESv2_LIB GLESv2)

add_library(cardboard_sdk_jni SHARED
    ${CARDBOARD_DIR}/java_api/src/main/jni/cardboard_jni.cc
    ${CARDBOARD_DIR}/sdk/jni_utils/android/jni_utils.cc
)
target_include_directories(cardboard_sdk_jni PRIVATE ${CARDBOARD_DIR}/sdk)
target_link_libraries(cardboard_sdk_jni PRIVATE
    GfxPluginCardboard
    ${LOG_LIB}
    ${GLESv2_LIB}
)
