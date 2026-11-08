#!/usr/bin/env bash
set -euo pipefail

python3 ci_scripts/check_ios_localizations.py

simulator_id="$(
  xcrun simctl list devices available \
    | sed -nE '/iPhone/ { s/.*\(([0-9A-Fa-f-]{36})\).*/\1/p; q; }'
)"

if [[ -z "$simulator_id" ]]; then
  echo "No available iPhone simulator was found." >&2
  exit 1
fi

# WINGMATE_IOS_BUILD_ONLY=1 compiles the app and test bundle without booting
# the simulator; the destination only selects the simulator SDK and arch.
action=test
if [[ "${WINGMATE_IOS_BUILD_ONLY:-}" == "1" ]]; then
  action=build-for-testing
fi

exec xcodebuild "$action" \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -parallel-testing-enabled NO
