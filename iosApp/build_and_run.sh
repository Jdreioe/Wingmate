#!/bin/bash

# Build the shared framework first
cd "$(dirname "$0")/.."
echo "Building shared framework..."
./gradlew shared:linkDebugFrameworkIosX64

# Check if framework was built
FRAMEWORK_PATH="./shared/build/bin/iosX64/debugFramework/Shared.framework"
if [ ! -d "$FRAMEWORK_PATH" ]; then
    echo "Error: Framework not found at $FRAMEWORK_PATH"
    exit 1
fi

echo "Framework found at: $FRAMEWORK_PATH"

# Build and run the iOS app using xcodeproj (not xcworkspace since we removed CocoaPods)
cd iosApp
echo "Building iOS app..."
xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16' build
