#!/bin/bash
set -euo pipefail

# Xcode Cloud looks for ci_scripts next to iosApp/iosApp.xcodeproj.
# Delegate to the single post-clone script at the repository root.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [ -n "${CI_PRIMARY_REPOSITORY_PATH:-}" ] && [ -f "$CI_PRIMARY_REPOSITORY_PATH/ci_scripts/ci_post_clone.sh" ]; then
  REPO_ROOT="$CI_PRIMARY_REPOSITORY_PATH"
fi

exec bash "$REPO_ROOT/ci_scripts/ci_post_clone.sh" "$@"
