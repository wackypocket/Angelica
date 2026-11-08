# VK-GL-CTS target: glcts as a library loaded by a JVM running glemu. build_cts.py copies this into targets/angelica.
message("*** Using the Angelica target")

set(DEQP_TARGET_NAME "Angelica")
set(DEQP_ANGELICA_SHARED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

find_package(JNI REQUIRED COMPONENTS JVM)
include_directories(${JNI_INCLUDE_DIRS})

set(TCUTIL_PLATFORM_SRCS ${CMAKE_CURRENT_LIST_DIR}/tcuAngelicaPlatform.cpp)
