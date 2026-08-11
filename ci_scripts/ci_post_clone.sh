#!/bin/bash
set -euo pipefail

# Xcode Cloud post-clone script for KMP (Kotlin Multiplatform)
# Builds shared iOS framework before Xcode build

# CI_WORKSPACE_PATH is the repo root provided by Xcode Cloud. Keep the older
# CI_WORKSPACE fallback for existing workflow configurations.
CI_WORKSPACE="${CI_WORKSPACE_PATH:-${CI_WORKSPACE:-}}"
if [ -z "$CI_WORKSPACE" ]; then
  echo "warning: Xcode Cloud workspace path not set — assuming repo root is parent of ci_scripts"
  CI_WORKSPACE="$(cd "$(dirname "$0")/.." && pwd)"
fi

cd "$CI_WORKSPACE"

# === Tag-driven release version ===
# Xcode Cloud supplies CI_TAG when a Git Tag Change start condition triggered
# the build. The workflow should use prod/* or staging/* as its tag pattern.
if [ "${CI_XCODE_CLOUD:-}" = "TRUE" ] && [ "${REQUIRE_RELEASE_TAG:-}" = "TRUE" ]; then
  if [ -z "${CI_TAG:-}" ]; then
    echo "error: Release builds must be started by a prod/1.x.y or staging/1.x.y tag"
    exit 1
  fi

  RELEASE_CHANNEL="${CI_TAG%%/*}"
  RELEASE_VERSION="${CI_TAG#*/}"

  case "$RELEASE_CHANNEL" in
    prod|staging) ;;
    *)
      echo "error: Unknown release channel: $RELEASE_CHANNEL"
      exit 1
      ;;
  esac

  if ! [[ "$RELEASE_VERSION" =~ ^1\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: Release tags must be prod/1.x.y or staging/1.x.y"
    exit 1
  fi

  if ! [[ "${CI_BUILD_NUMBER:-}" =~ ^[0-9]+$ ]]; then
    echo "error: Xcode Cloud did not provide a numeric CI_BUILD_NUMBER"
    exit 1
  fi

  XCCONFIG="iosApp/Configuration/Config.xcconfig"
  perl -pi -e "s/^MARKETING_VERSION=.*/MARKETING_VERSION=$RELEASE_VERSION/" "$XCCONFIG"
  perl -pi -e "s/^CURRENT_PROJECT_VERSION=.*/CURRENT_PROJECT_VERSION=$CI_BUILD_NUMBER/" "$XCCONFIG"
  echo "Configured $RELEASE_CHANNEL release version $RELEASE_VERSION (build $CI_BUILD_NUMBER)"
fi

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
      JAVA_HOME="$(/usr/libexec/java_home -v 21)"
      export JAVA_HOME
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
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  export JAVA_HOME
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
# Use a single-use daemon so its heap is returned before xcodebuild starts.
# --stacktrace keeps the actionable compiler error visible in Xcode Cloud logs.
./gradlew :shared:linkReleaseFrameworkIosArm64 \
  --no-daemon \
  --max-workers=2 \
  --stacktrace

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
