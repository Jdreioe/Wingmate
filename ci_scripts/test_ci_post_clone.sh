#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP_BASE="$(mktemp -d)"
trap 'rm -rf "$TMP_BASE"' EXIT

assert_file_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fqx "$expected" "$file"; then
    echo "FAIL: expected line '$expected' in $file" >&2
    cat "$file" >&2
    exit 1
  fi
}

setup_fake_repo() {
  local dir="$1"
  mkdir -p "$dir/ci_scripts" "$dir/iosApp/ci_scripts" "$dir/iosApp/Configuration"
  cp "$REPO_ROOT/ci_scripts/ci_post_clone.sh" "$dir/ci_scripts/ci_post_clone.sh"
  cp "$REPO_ROOT/iosApp/ci_scripts/ci_post_clone.sh" "$dir/iosApp/ci_scripts/ci_post_clone.sh"
  cp "$REPO_ROOT/iosApp/Configuration/Config.xcconfig" "$dir/iosApp/Configuration/Config.xcconfig"
  chmod +x "$dir/ci_scripts/ci_post_clone.sh" "$dir/iosApp/ci_scripts/ci_post_clone.sh"
}

echo "Test 1: iosApp/ci_scripts/ci_post_clone.sh is executable and delegates via CI_PRIMARY_REPOSITORY_PATH"
test -x "$REPO_ROOT/iosApp/ci_scripts/ci_post_clone.sh"
test -x "$REPO_ROOT/ci_scripts/ci_post_clone.sh"

WS="$TMP_BASE/workspace"
FAKE_REPO="$WS/repository"
setup_fake_repo "$FAKE_REPO"

# Simulate Xcode Cloud where CI_WORKSPACE_PATH is /Volumes/workspace (not the repo)
# and CI_PRIMARY_REPOSITORY_PATH is /Volumes/workspace/repository.
(
  cd "$FAKE_REPO/iosApp/ci_scripts"
  CI_WORKSPACE_PATH="$WS" \
  CI_PRIMARY_REPOSITORY_PATH="$FAKE_REPO" \
  CI_XCODE_CLOUD=TRUE \
  REQUIRE_RELEASE_TAG=TRUE \
  CI_TAG="staging/1.10.1" \
  CI_BUILD_NUMBER="205" \
  WINGMATE_APTABASE_APP_KEY="  A-EU-0000000000  " \
  WINGMATE_GOOGLE_IOS_CLIENT_ID="  123456789012-abcDEF_0123-xyz.apps.googleusercontent.com  " \
  WINGMATE_MICROSOFT_CLIENT_ID="  11111111-2222-3333-4444-555555555555  " \
  WINGMATE_CI_CONFIGURE_ONLY=1 \
  ./ci_post_clone.sh
)

CFG="$FAKE_REPO/iosApp/Configuration/Config.xcconfig"
assert_file_contains "$CFG" "MARKETING_VERSION=1.10.1"
assert_file_contains "$CFG" "CURRENT_PROJECT_VERSION=205"
assert_file_contains "$CFG" "APTABASE_APP_KEY = A-EU-0000000000"
assert_file_contains "$CFG" "WINGMATE_GOOGLE_IOS_CLIENT_ID = 123456789012-abcDEF_0123-xyz.apps.googleusercontent.com"
assert_file_contains "$CFG" "WINGMATE_GOOGLE_IOS_URL_SCHEME = com.googleusercontent.apps.123456789012-abcDEF_0123-xyz"
assert_file_contains "$CFG" "WINGMATE_MICROSOFT_CLIENT_ID = 11111111-2222-3333-4444-555555555555"

echo "Test 2: Re-running is idempotent"
(
  cd "$FAKE_REPO/iosApp/ci_scripts"
  CI_WORKSPACE_PATH="$WS" \
  CI_PRIMARY_REPOSITORY_PATH="$FAKE_REPO" \
  WINGMATE_GOOGLE_IOS_CLIENT_ID="123456789012-abcDEF_0123-xyz.apps.googleusercontent.com" \
  WINGMATE_MICROSOFT_CLIENT_ID="11111111-2222-3333-4444-555555555555" \
  WINGMATE_CI_CONFIGURE_ONLY=1 \
  ./ci_post_clone.sh
)
assert_file_contains "$CFG" "WINGMATE_GOOGLE_IOS_CLIENT_ID = 123456789012-abcDEF_0123-xyz.apps.googleusercontent.com"
assert_file_contains "$CFG" "WINGMATE_GOOGLE_IOS_URL_SCHEME = com.googleusercontent.apps.123456789012-abcDEF_0123-xyz"
assert_file_contains "$CFG" "WINGMATE_MICROSOFT_CLIENT_ID = 11111111-2222-3333-4444-555555555555"

echo "Test 3: Falls back to script repo root when CI_WORKSPACE_PATH points to parent and CI_PRIMARY_REPOSITORY_PATH is unset"
FAKE_REPO_2="$TMP_BASE/fallback/repository"
setup_fake_repo "$FAKE_REPO_2"
(
  cd "$FAKE_REPO_2/iosApp/ci_scripts"
  CI_WORKSPACE_PATH="$TMP_BASE/wrong-dir" \
  WINGMATE_GOOGLE_IOS_CLIENT_ID="9999-test123.apps.googleusercontent.com" \
  WINGMATE_CI_CONFIGURE_ONLY=1 \
  ./ci_post_clone.sh
)
assert_file_contains "$FAKE_REPO_2/iosApp/Configuration/Config.xcconfig" "WINGMATE_GOOGLE_IOS_CLIENT_ID = 9999-test123.apps.googleusercontent.com"
assert_file_contains "$FAKE_REPO_2/iosApp/Configuration/Config.xcconfig" "WINGMATE_GOOGLE_IOS_URL_SCHEME = com.googleusercontent.apps.9999-test123"

echo "Test 4: Unset env vars keep non-empty placeholder URL scheme and empty client IDs"
FAKE_REPO_3="$TMP_BASE/empty/repository"
setup_fake_repo "$FAKE_REPO_3"
(
  cd "$FAKE_REPO_3/iosApp/ci_scripts"
  WINGMATE_CI_CONFIGURE_ONLY=1 \
  ./ci_post_clone.sh
)
assert_file_contains "$FAKE_REPO_3/iosApp/Configuration/Config.xcconfig" "WINGMATE_GOOGLE_IOS_CLIENT_ID ="
assert_file_contains "$FAKE_REPO_3/iosApp/Configuration/Config.xcconfig" "WINGMATE_GOOGLE_IOS_URL_SCHEME = com.hojmoseit.wingmate.google-oauth"
assert_file_contains "$FAKE_REPO_3/iosApp/Configuration/Config.xcconfig" "WINGMATE_MICROSOFT_CLIENT_ID ="

echo "Test 5: Rejects malformed client IDs"
if (
  cd "$FAKE_REPO_3/iosApp/ci_scripts"
  WINGMATE_GOOGLE_IOS_CLIENT_ID="com.googleusercontent.apps.12345-abc" \
  WINGMATE_CI_CONFIGURE_ONLY=1 \
  ./ci_post_clone.sh >/dev/null 2>&1
); then
  echo "FAIL: expected reversed Google client ID to be rejected" >&2
  exit 1
fi

if (
  cd "$FAKE_REPO_3/iosApp/ci_scripts"
  WINGMATE_MICROSOFT_CLIENT_ID="not-a-uuid" \
  WINGMATE_CI_CONFIGURE_ONLY=1 \
  ./ci_post_clone.sh >/dev/null 2>&1
); then
  echo "FAIL: expected invalid Microsoft client ID to be rejected" >&2
  exit 1
fi

echo "Test 6: Info.plist is valid XML and registers both OAuth callback URL schemes"
python3 - "$REPO_ROOT/iosApp/iosApp/Info.plist" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as fh:
    data = plistlib.load(fh)

assert data.get("WINGMATE_GOOGLE_IOS_CLIENT_ID") == "$(WINGMATE_GOOGLE_IOS_CLIENT_ID)"
assert data.get("WINGMATE_MICROSOFT_CLIENT_ID") == "$(WINGMATE_MICROSOFT_CLIENT_ID)"

schemes = [
    scheme
    for entry in data.get("CFBundleURLTypes", [])
    for scheme in entry.get("CFBundleURLSchemes", [])
]
assert "$(WINGMATE_GOOGLE_IOS_URL_SCHEME)" in schemes, f"Missing Google scheme in {schemes}"
assert "wingmate-auth" in schemes, f"Missing OneDrive scheme in {schemes}"
PY

echo "All Xcode Cloud post-clone and Info.plist tests passed."
