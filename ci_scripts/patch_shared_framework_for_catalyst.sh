#!/bin/sh
# Mac Catalyst workaround (KT-40442): Kotlin/Native cannot emit MACCATALYST
# binaries. Gradle emits a framework whose Mach-O carries the iOS-simulator
# (or macOS) build version. The LC_BUILD_VERSION platform must be rewritten to
# MACCATALYST and the Info.plist platform fixed so the framework can be linked
# into and embedded in a Catalyst app. vtool only rewrites a single slice, so a
# fat framework is thin-extracted, patched per-architecture, then re-combined.
# Xcode re-codesigns the embedded copy in the "Embed Frameworks" phase after.
set -eu

FW=shared/build/xcode-frameworks/$CONFIGURATION/$SDK_NAME/Shared.framework

if [ ! -d "$FW" ]; then
  echo "warning: Shared.framework not found at $FW; skipping Catalyst patch"
  exit 0
fi

if [ -d "$FW/Versions" ]; then
  CUR=$(cd "$FW/Versions/Current" && pwd -P 2>/dev/null || echo "$FW/Versions/A")
  BIN=$CUR/Shared
  PLIST=$CUR/Resources/Info.plist
else
  BIN=$FW/Shared
  PLIST=$FW/Info.plist
fi

if [ ! -f "$BIN" ]; then
  echo "warning: framework binary missing at $BIN; skipping Catalyst patch"
  exit 0
fi

STAGE_DIR=$(mktemp -d)
trap 'rm -rf "$STAGE_DIR"' EXIT

ARCHS=$(lipo -archs "$BIN")
echo "Patching Shared.framework slices [$ARCHS] to MACCATALYST"
THINNED=""
for a in $ARCHS; do
  thin=$STAGE_DIR/$a
  if [ "$(echo "$ARCHS" | wc -w | tr -d ' ')" = "1" ]; then
    cp "$BIN" "$thin"
  else
    lipo -thin "$a" "$BIN" -output "$thin"
  fi
  rm1=$STAGE_DIR/${a}.rm1
  rm2=$STAGE_DIR/${a}.rm2
  out=$STAGE_DIR/${a}.out
  if ! vtool -remove-build-version iossim -output "$rm1" "$thin" 2>/dev/null; then
    cp "$thin" "$rm1"
  fi
  if ! vtool -remove-build-version macos -output "$rm2" "$rm1" 2>/dev/null; then
    cp "$rm1" "$rm2"
  fi
  vtool -set-build-version maccatalyst 18.0 "$(xcrun --sdk macosx --show-sdk-version)" -output "$out" "$rm2"
  THINNED="$THINNED $out"
done

# Accept a single-slice BIN too; when patching in place the slices exist.
if [ "$(echo "$ARCHS" | wc -w | tr -d ' ')" = "1" ]; then
  out=$(find "$STAGE_DIR" -name '*.out' | head -1)
  cp "$out" "$BIN"
else
  lipo -create $THINNED -output "$BIN"
fi

# macOS frameworks must use the non-shallow "Versions" layout. Gradle emits a
# flat iOS-style bundle, so restructure before Xcode validates/embeds it.
if [ -f "$FW/Info.plist" ]; then
  BIN=$FW/Versions/A/Shared
  PLIST=$FW/Versions/A/Resources/Info.plist
  ( cd "$FW" && \
    mkdir -p Versions/A/Headers Versions/A/Modules Versions/A/Resources && \
    mv Shared Versions/A/Shared && \
    mv Headers/* Versions/A/Headers/ && \
    mv Modules/* Versions/A/Modules/ && \
    mv Info.plist Versions/A/Resources/ && \
    rm -rf _CodeSignature && \
    ln -s A Versions/Current && \
    ln -s Versions/Current/Shared Shared && \
    ln -s Versions/Current/Headers Headers && \
    ln -s Versions/Current/Modules Modules && \
    ln -s Versions/Current/Resources Resources )
fi

if [ -f "$PLIST" ]; then
  plutil -replace CFBundleSupportedPlatforms -json '["maccatalyst"]' "$PLIST" &&
    plutil -replace MinimumOSVersion -string 18.0 "$PLIST"
else
  echo "warning: no Info.plist at $PLIST"
fi

# The framework Gradle emitted carries the original platform and sits in two
# places the linker/embedder read from: the bare Build/Products dir used during
# the final link, and the app bundle's Frameworks dir. Replace both with the
# patched copy so the maccatalyst Mach-O is linked in. Xcode's "Embed
# Frameworks" phase then re-codesigns on copy.
echo "Replacing build-products Shared.framework with patched copy"
rm -rf "$BUILT_PRODUCTS_DIR/Shared.framework"
cp -R "$FW" "$BUILT_PRODUCTS_DIR/Shared.framework"
rm -rf "$BUILT_PRODUCTS_DIR/$FRAMEWORKS_FOLDER_PATH/Shared.framework"
cp -R "$FW" "$BUILT_PRODUCTS_DIR/$FRAMEWORKS_FOLDER_PATH/Shared.framework"