#!/bin/bash
# Package Wingmate's Mac Catalyst app into a distributable .dmg.
#
# Usage:
#   ./ci_scripts/package_mac_dmg.sh \
#     --app   /path/to/Wingmate.app \
#     --out   dist/Wingmate-0.7.dmg \
#     --vol   Wingmate \
#     [--identity "Developer ID Application: Name (TEAM)"] \
#     [--key-id XXXX --issuer-id UUID --key /path/to/AuthKey_XXXX.p8] \
#     [--team-id XXXX]
#
# --identity: codesign a Developer ID Application identity. Required for
#   Gatekeeper-clean, notarizable distribution outside the Mac App Store.
# --key-id/--issuer-id/--key: App Store Connect API keys for notarization.
#   When provided the .dmg is notarized (--team-id also required).
#
# Without --identity the app is re-signed ad-hoc. It will launch only when the
# user right-clicks > Open (Apple Silicon + Intel), bypassing Gatekeeper's
# initial block. Use Developer ID + notarization for production releases.
set -euo pipefail

APP=""
OUT=""
VOL="Wingmate"
IDENTITY=""
KEY_ID=""
ISSUER=""
KEY_PATH=""
TEAM_ID=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app) APP="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --vol) VOL="$2"; shift 2 ;;
    --identity) IDENTITY="$2"; shift 2 ;;
    --key-id) KEY_ID="$2"; shift 2 ;;
    --issuer-id) ISSUER="$2"; shift 2 ;;
    --key) KEY_PATH="$2"; shift 2 ;;
    --team-id) TEAM_ID="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$APP" || -z "$OUT" ]]; then
  echo "error: --app and --out are required" >&2
  exit 2
fi
if [[ ! -d "$APP" ]]; then
  echo "error: app bundle not found at $APP" >&2
  exit 2
fi

APP_NAME=$(basename "$APP" .app)
OUT_DIR=$(dirname "$OUT")
mkdir -p "$OUT_DIR"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

# 1. Stage drag-to-Applications layout.
cp -R "$APP" "$STAGE/$APP_NAME.app"
ln -s /Applications "$STAGE/Applications"

# 2. Re-sign the app copy.
if [[ -n "$IDENTITY" ]]; then
  echo "Signing with identity: $IDENTITY"
  codesign --force --deep --timestamp --options runtime \
    --entitlements "$(dirname "$0")/../iosApp/iosApp/iosApp.entitlements" \
    --sign "$IDENTITY" "$STAGE/$APP_NAME.app"
else
  echo "warning: no --identity; signing ad-hoc (users must right-click > Open)"
  codesign --force --deep --sign - "$STAGE/$APP_NAME.app"
fi

# 3. Create the .dmg.
rm -f "$OUT"
hdiutil create -volname "$VOL" -srcfolder "$STAGE" \
  -ov -format UDZO "$OUT" >/dev/null

echo "Created $OUT"

# 4. Notarize (when App Store Connect API credentials were supplied).
if [[ -n "$KEY_ID" && -n "$ISSUER" && -n "$KEY_PATH" && -n "$TEAM_ID" ]]; then
  echo "Submitting $OUT for notarization..."
  SUBMIT=$(xcrun notarytool submit "$OUT" \
    --key "$KEY_PATH" --key-id "$KEY_ID" --issuer "$ISSUER" \
    --team-id "$TEAM_ID" --wait 2>/dev/null)
  echo "$SUBMIT"
  if echo "$SUBMIT" | grep -q "Accepted"; then
    echo "Stapling ticket..."
    xcrun stapler staple "$OUT"
    echo "Notarized and stapled: $OUT"
  else
    echo "error: notarization did not succeed" >&2
    exit 1
  fi
fi

echo "Done: $OUT"