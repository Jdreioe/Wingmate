#!/usr/bin/env bash
set -euo pipefail

simulator_id="$(
  xcrun simctl list devices available \
    | sed -nE '/iPhone/ { s/.*\(([0-9A-Fa-f-]{36})\).*/\1/p; q; }'
)"

if [[ -z "$simulator_id" ]]; then
  echo "No available iPhone simulator was found." >&2
  exit 1
fi

exec xcodebuild test \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -parallel-testing-enabled NO
