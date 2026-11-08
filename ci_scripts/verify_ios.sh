#!/usr/bin/env bash
set -euo pipefail

python3 ci_scripts/check_ios_localizations.py

# CI boots the simulator early and passes its id so the boot overlaps the build.
simulator_id="${WINGMATE_IOS_SIMULATOR_ID:-$(
  xcrun simctl list devices available \
    | sed -nE '/iPhone/ { s/.*\(([0-9A-Fa-f-]{36})\).*/\1/p; q; }'
)}"

if [[ -z "$simulator_id" ]]; then
  echo "No available iPhone simulator was found." >&2
  exit 1
fi

# Boots the simulator if needed, then waits until it can run tests.
xcrun simctl bootstatus "$simulator_id" -b

exec xcodebuild test \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -parallel-testing-enabled NO
