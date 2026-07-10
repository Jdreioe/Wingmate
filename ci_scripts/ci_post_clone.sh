#!/bin/bash
set -euo pipefail

# Xcode Cloud post-clone script for KMP (Kotlin Multiplatform)
# Builds shared iOS framework before Xcode build

# CI_WORKSPACE is repo root in Xcode Cloud
if [ -z "${CI_WORKSPACE:-}" ]; then
  echo "warning: CI_WORKSPACE not set — assuming repo root is parent of ci_scripts"
  CI_WORKSPACE="$(cd "$(dirname "$0")/.." && pwd)"
fi

cd "$CI_WORKSPACE"

# === JDK Setup ===
# Xcode Cloud requires JDK 21 for Kotlin Multiplatform (jvmToolchain 21)
install_jdk() {
  echo "Installing JDK 21 via Homebrew..."
  HOMEBREW_NO_AUTO_UPDATE=1 brew install --quiet openjdk@21
  # Homebrew openjdk paths differ on Intel vs Apple Silicon
  if [ -f /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
    export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  elif [ -f /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  else
    echo "error: openjdk@21 installed but java binary not found at expected path"
    exit 1
  fi
}

if /usr/libexec/java_home -v 21 &>/dev/null; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  echo "Using JAVA_HOME from environment: $JAVA_HOME"
elif [ -f /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
  export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
elif [ -f /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
else
  install_jdk
fi

# Verify JDK works
if ! "$JAVA_HOME/bin/java" -version &>/dev/null; then
  echo "error: JAVA_HOME points to invalid JDK: $JAVA_HOME"
  exit 1
fi

echo "JAVA_HOME=$JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# === Gradle Wrapper Permissions ===
chmod +x gradlew

# === Build Shared Framework for iOS Device (Release) ===
# Xcode Cloud typically builds Release + iphoneos for archiving
echo "Building shared Kotlin framework for iosArm64 (Release)..."
./gradlew :shared:linkReleaseFrameworkIosArm64 --quiet

# === Copy Framework to Xcode Search Path ===
FRAMEWORK_SRC="shared/build/bin/iosArm64/releaseFramework/Shared.framework"
FRAMEWORK_DST="shared/build/xcode-frameworks/Release/iphoneos/Shared.framework"

if [ ! -d "$FRAMEWORK_SRC" ]; then
  echo "error: Framework not found at $FRAMEWORK_SRC"
  exit 1
fi

mkdir -p "$(dirname "$FRAMEWORK_DST")"
rm -rf "$FRAMEWORK_DST"
cp -R "$FRAMEWORK_SRC" "$FRAMEWORK_DST"
echo "Framework copied to $FRAMEWORK_DST"
