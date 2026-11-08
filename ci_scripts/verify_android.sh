#!/usr/bin/env bash

set -euo pipefail

# Keep this task list shared by pull-request and Play release verification so
# publishing cannot drift to a weaker gate. Both run on GitHub-hosted Linux
# runners (4 cores, 16 GB), so override gradle.properties' Xcode Cloud limits and
# reuse task outputs that setup-gradle restores from main.
exec ./gradlew \
  :androidApp:assembleDebug \
  :androidApp:lintDebug \
  :androidApp:testDebugUnitTest \
  jvmTest \
  --build-cache \
  --parallel \
  --max-workers=4 \
  --console=plain
