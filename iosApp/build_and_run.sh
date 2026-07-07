#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "=== Step 1: Building shared Kotlin framework for iOS Simulator (arm64) ==="
./gradlew shared:linkDebugFrameworkIosSimulatorArm64

FRAMEWORK_SRC="$ROOT/shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework"
FRAMEWORK_DST="$ROOT/shared/build/xcode-frameworks/Debug/iphonesimulator/Shared.framework"

if [ ! -d "$FRAMEWORK_SRC" ]; then
    echo "Error: Framework not found at $FRAMEWORK_SRC"
    exit 1
fi

echo "=== Step 2: Copying framework to Xcode-expected path ==="
mkdir -p "$(dirname "$FRAMEWORK_DST")"
rm -rf "$FRAMEWORK_DST"
cp -R "$FRAMEWORK_SRC" "$FRAMEWORK_DST"
echo "Framework copied to $FRAMEWORK_DST"

echo "=== Step 3: Building iOS app for Apple Silicon Simulator ==="
cd "$ROOT/iosApp"

# Find the first available iPhone simulator
SIM_NAME=$(xcrun simctl list devices available 2>/dev/null | grep -o 'iPhone [^(]*' | head -1 | xargs)
if [ -z "$SIM_NAME" ]; then
    echo "Error: No iPhone simulator found. Please create one in Xcode."
    exit 1
fi
echo "Using simulator: $SIM_NAME"

xcodebuild -project iosApp.xcodeproj \
  -scheme iosApp \
  -destination "platform=iOS Simulator,name=$SIM_NAME" \
  -derivedDataPath "$ROOT/build/iosDerivedData" \
  build

echo ""
echo "✅ Build successful! To install on the running simulator:"
echo "  xcrun simctl install booted $ROOT/build/iosDerivedData/Build/Products/Debug-iphonesimulator/Wingmate.app"
