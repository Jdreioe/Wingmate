cmake_minimum_required(VERSION 3.16)

project(WingmateKDE VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

# Find Qt6 and KF6
find_package(ECM REQUIRED NO_MODULE)
set(CMAKE_MODULE_PATH ${ECM_MODULE_PATH})

find_package(Qt6 REQUIRED COMPONENTS Core Quick Qml QuickControls2)
find_package(KF6 REQUIRED COMPONENTS Kirigami)

# Add the executable
add_executable(wingmate-kde
    src/main.cpp
    src/resources.qrc
)

# Link libraries
target_link_libraries(wingmate-kde
    Qt6::Core
    Qt6::Quick
    Qt6::Qml
    Qt6::QuickControls2
    KF6::Kirigami
)

# Set up Java classpath - the shared module's JVM jar
set(SHARED_JAR_PATH "${CMAKE_SOURCE_DIR}/../shared/build/libs/shared-jvm.jar")
add_definitions(-DSHARED_JAR_PATH="${SHARED_JAR_PATH}")

install(TARGETS wingmate-kde DESTINATION bin)
