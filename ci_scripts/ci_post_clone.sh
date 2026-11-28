#!/bin/bash
set -euo pipefail

# Xcode Cloud post-clone script for KMP (Kotlin Multiplatform)
# Builds shared iOS framework before Xcode build

# Locate the cloned repository root. On Xcode Cloud, CI_PRIMARY_REPOSITORY_PATH
# points to the Git repo (e.g. /Volumes/workspace/repository), whereas
# CI_WORKSPACE_PATH is its parent directory (/Volumes/workspace).
SCRIPT_REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT=""
for candidate in \
  "${CI_PRIMARY_REPOSITORY_PATH:-}" \
  "${CI_WORKSPACE_PATH:-}" \
  "${CI_WORKSPACE_PATH:+${CI_WORKSPACE_PATH}/repository}" \
  "${CI_WORKSPACE:-}" \
  "$SCRIPT_REPO_ROOT"; do
  if [ -n "$candidate" ] && [ -f "$candidate/iosApp/Configuration/Config.xcconfig" ]; then
    REPO_ROOT="$candidate"
    break
  fi
done

if [ -z "$REPO_ROOT" ]; then
  echo "error: Could not locate Wingmate repository root containing iosApp/Configuration/Config.xcconfig"
  exit 1
fi

cd "$REPO_ROOT"

XCCONFIG="iosApp/Configuration/Config.xcconfig"

trim_whitespace() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

set_xcconfig_value() {
  local key="$1"
  local value="$2"
  XCCONFIG_KEY="$key" XCCONFIG_VALUE="$value" perl -i -pe '
    BEGIN { $matched = 0; }
    if (m/^(\Q$ENV{XCCONFIG_KEY}\E)([ \t]*=)[^\r\n]*(\r?\n?)\z/) {
      my ($k, $eq, $eol) = ($1, $2, $3);
      my $sep = ($eq =~ m{[ \t]}) ? " " : "";
      $_ = $k . $eq . $sep . $ENV{XCCONFIG_VALUE} . $eol;
      $matched = 1;
    }
    END { exit($matched ? 0 : 2); }
  ' "$XCCONFIG" || {
    echo "error: failed to update $key in $XCCONFIG"
    exit 1
  }
}

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

  if ! [[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: Release tags must be prod/X.Y.Z or staging/X.Y.Z"
    exit 1
  fi

  if ! [[ "${CI_BUILD_NUMBER:-}" =~ ^[0-9]+$ ]]; then
    echo "error: Xcode Cloud did not provide a numeric CI_BUILD_NUMBER"
    exit 1
  fi

  set_xcconfig_value "MARKETING_VERSION" "$RELEASE_VERSION"
  set_xcconfig_value "CURRENT_PROJECT_VERSION" "$CI_BUILD_NUMBER"
  echo "Configured $RELEASE_CHANNEL release version $RELEASE_VERSION (build $CI_BUILD_NUMBER)"
fi

# === Aptabase app key ===
# A public client identifier (docs/SECRET_BOUNDARIES.md). Without it the app
# keeps feature usage reporting off.
APTABASE_KEY="$(trim_whitespace "${WINGMATE_APTABASE_APP_KEY:-}")"
if [ -n "$APTABASE_KEY" ]; then
  if ! [[ "$APTABASE_KEY" =~ ^A-(EU|US)-[A-Za-z0-9]+$ ]]; then
    echo "error: WINGMATE_APTABASE_APP_KEY is not an Aptabase EU/US app key"
    exit 1
  fi
  set_xcconfig_value "APTABASE_APP_KEY" "$APTABASE_KEY"
  echo "Configured Aptabase app key"
fi

# === Cloud drive OAuth client IDs ===
# Public client identifiers for Google Drive and OneDrive sign-in. Empty hides that option.
GOOGLE_CLIENT_ID="$(trim_whitespace "${WINGMATE_GOOGLE_IOS_CLIENT_ID:-}")"
if [ -n "$GOOGLE_CLIENT_ID" ]; then
  if ! [[ "$GOOGLE_CLIENT_ID" =~ ^[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$ ]]; then
    echo "error: WINGMATE_GOOGLE_IOS_CLIENT_ID must be a Google iOS OAuth client ID (<number>-<id>.apps.googleusercontent.com)"
    exit 1
  fi
  GOOGLE_URL_SCHEME="com.googleusercontent.apps.${GOOGLE_CLIENT_ID%.apps.googleusercontent.com}"
  set_xcconfig_value "WINGMATE_GOOGLE_IOS_CLIENT_ID" "$GOOGLE_CLIENT_ID"
  set_xcconfig_value "WINGMATE_GOOGLE_IOS_URL_SCHEME" "$GOOGLE_URL_SCHEME"
  echo "Configured Google Drive client ID and OAuth URL scheme"
else
  echo "note: WINGMATE_GOOGLE_IOS_CLIENT_ID is unset; Google Drive sync stays hidden"
fi

MICROSOFT_CLIENT_ID="$(trim_whitespace "${WINGMATE_MICROSOFT_CLIENT_ID:-}")"
if [ -n "$MICROSOFT_CLIENT_ID" ]; then
  if ! [[ "$MICROSOFT_CLIENT_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
    echo "error: WINGMATE_MICROSOFT_CLIENT_ID must be a Microsoft Entra application (client) ID UUID"
    exit 1
  fi
  set_xcconfig_value "WINGMATE_MICROSOFT_CLIENT_ID" "$MICROSOFT_CLIENT_ID"
  echo "Configured OneDrive client ID"
else
  echo "note: WINGMATE_MICROSOFT_CLIENT_ID is unset; OneDrive sync stays hidden"
fi

if [ "${WINGMATE_CI_CONFIGURE_ONLY:-0}" = "1" ]; then
  exit 0
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
    brew link --overwrite --force openjdk@21 || true
    # Register with java_home via user-level symlink (no sudo)
    mkdir -p ~/Library/Java/JavaVirtualMachines
    for f in /usr/local/opt/openjdk@21/libexec/openjdk.jdk \
             /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk; do
      if [ -d "$f" ]; then
        ln -sfn "$f" ~/Library/Java/JavaVirtualMachines/openjdk-21.jdk
        if [ -x "$f/Contents/Home/bin/java" ]; then
          JAVA_HOME="$f/Contents/Home"
          export JAVA_HOME
          return 0
        fi
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
