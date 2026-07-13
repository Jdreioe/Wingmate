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
  echo "Installing JDK 21..."

  # Try Homebrew
  if command -v brew &>/dev/null; then
    echo "Using Homebrew..."
    HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALLED_DEPENDENTS_CHECK=1 \
      brew install --quiet openjdk@21
    brew link --overwrite --force openjdk@21
    # Register with java_home via user-level symlink (no sudo)
    mkdir -p ~/Library/Java/JavaVirtualMachines
    for f in /usr/local/opt/openjdk@21/libexec/openjdk.jdk \
             /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk; do
      if [ -d "$f" ]; then
        ln -sfn "$f" ~/Library/Java/JavaVirtualMachines/openjdk-21.jdk
        break
      fi
    done
    if /usr/libexec/java_home -v 21 &>/dev/null; then
      export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
      return 0
    fi
  fi

  # Fallback: download Adoptium JDK directly (no brew, no sudo)
  echo "Downloading JDK 21 from Adoptium..."
  ARCH=$(uname -m)
  [ "$ARCH" = "arm64" ] && ARCH="aarch64" || ARCH="x64"
  curl -sL "https://api.adoptium.net/v3/binary/latest/21/ga/mac/osx/${ARCH}/jdk/hotspot/normal/eclipse" \
    -o /tmp/jdk21.tar.gz
  rm -rf /tmp/jdk21
  mkdir -p /tmp/jdk21
  tar xzf /tmp/jdk21.tar.gz -C /tmp/jdk21 --strip-components=1
  JAVA_HOME=$(find /tmp/jdk21 -maxdepth 3 -name java -path "*/bin/*" | head -1 | sed 's|/bin/java||')
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export JAVA_HOME
    # Also register for java_home
    mkdir -p ~/Library/Java/JavaVirtualMachines
    ln -sfn "$JAVA_HOME" ~/Library/Java/JavaVirtualMachines/openjdk-21.jdk 2>/dev/null || true
    return 0
  fi

  echo "error: failed to install JDK 21"
  exit 1
}

# Unset any Xcode Cloud JAVA_HOME that points to old JDK
unset JAVA_HOME

if /usr/libexec/java_home -v 21 &>/dev/null; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
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
