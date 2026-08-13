#!/usr/bin/env bash

set -euo pipefail

# Keep this task list shared by pull-request and Play release verification so
# publishing cannot drift to a weaker gate.
exec ./gradlew \
  :androidApp:assembleDebug \
  :androidApp:lintDebug \
  :androidApp:testDebugUnitTest \
  jvmTest \
  --console=plain
