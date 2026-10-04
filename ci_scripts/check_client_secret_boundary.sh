#!/usr/bin/env bash
set -euo pipefail

if git grep -nE \
  '(OPEN_SYMBOLS_SECRET|OPENSYMBOLS_SECRET|setOpenSymbolsSecret|setSharedSecret|WINGMATE_AZURE_KEY)' \
  -- androidApp iosApp shared core; then
  echo "Server/developer credentials must not be handled by client code." >&2
  exit 1
fi

if git grep -nEi '<key>[^<]*(secret|password|private.?key|access.?token|subscription.?key)' -- 'iosApp/**/*.plist'; then
  echo "Secret-like Info.plist entries are forbidden because plist values ship in the app." >&2
  exit 1
fi

echo "No forbidden server/developer credential handling found in client code."
