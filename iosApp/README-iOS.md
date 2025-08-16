# Wingmate iOS host app

This folder contains a SwiftUI iOS host for the KMP shared module.

Quick start:

1. Build a placeholder Kotlin framework (one time):
   ./gradlew :shared:generateDummyFramework

2. In Xcode, create a new iOS App project named `WingmateiOS` in this `iosApp` folder. Make sure the bundle id is unique for your team.

3. Close Xcode and run CocoaPods install:
   cd iosApp
   pod install

4. Open the generated `WingmateiOS.xcworkspace` in Xcode and run on a device/simulator.

Swift notes:
- AppDelegate initializes Koin via `initKoin(nil)` and registers iOS overrides (NSUserDefaults config + AVSpeechSynthesizer service).
- `ContentView` presents a glass (blur) aesthetic and drives data via `KoinBridge` suspend wrappers.

Xcode Run Script to embed and sign the KMP framework:

1) In your Xcode target, add a new Run Script phase after "Embed Frameworks" and before "Compile Sources".
2) Paste the following script (adjust paths if your workspace root is different):

```
#!/bin/sh
set -euo pipefail

# Resolve repo root (this script lives in the iOS project inside the repo)
REPO_ROOT="${SRCROOT}/.."

# Ensure Kotlin/Gradle are available
if [ ! -f "${REPO_ROOT}/gradlew" ]; then
   echo "Gradle wrapper not found at ${REPO_ROOT}/gradlew" >&2
   exit 1
fi

# Forward Xcode environment variables that the Gradle task consumes
export CONFIGURATION
export SDK_NAME="${SDK_NAME}"
export ARCHS="${ARCHS:-}"
export TARGET_BUILD_DIR
export FRAMEWORKS_FOLDER_PATH
export EXPANDED_CODE_SIGN_IDENTITY

"${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" :shared:embedAndSignAppleFrameworkForXcode --console=plain --quiet
```

What the script does:
- Builds the correct iOS variant (iosArm64 / iosSimulatorArm64 / iosX64) based on SDK and ARCHS.
- Copies `Shared.framework` into Xcode's `${TARGET_BUILD_DIR}/${FRAMEWORKS_FOLDER_PATH}`.
- Codesigns the framework when `EXPANDED_CODE_SIGN_IDENTITY` is provided by Xcode.
