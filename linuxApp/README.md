# Wingmate KDE - Linux Native App

This directory contains the native Linux UI for Wingmate using KDE's Kirigami framework.

## Prerequisites

- CMake >= 3.16
- Qt6 (Core, Quick, Qml, QuickControls2)
- KDE Frameworks 6 (Kirigami)
- Kotlin/Native static library from the `shared` module

On Arch-based distributions (including CachyOS):
```bash
sudo pacman -S cmake qt6-base qt6-declarative kirigami
```

## Building

1. First, build the Kotlin/Native static library:
```bash
cd ..
./gradlew :shared:linkReleaseStaticLinuxX64
```

2. Then build the KDE application:
```bash
cd linuxApp
cmake -B build -S .
cmake --build build
```

3. Run the application:
```bash
./build/wingmate-kde
```

## Installation

To install system-wide:
```bash
cd build
sudo cmake --install .
```

## Architecture

- **main.cpp**: Entry point, initializes Qt/QML engine and Kotlin/Native runtime
- **main.qml**: Kirigami-based UI with navigation, speech input, and phrase grid
- **CMakeLists.txt**: Build configuration linking Qt6, Kirigami, and Kotlin/Native
- **resources.qrc**: Qt resource file for embedding QML files

The application uses the shared Kotlin Multiplatform business logic compiled as a static library.
