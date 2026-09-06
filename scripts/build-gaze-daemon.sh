#!/usr/bin/env bash
# Build the decoder-pinned daemon and corresponding source for Linux packaging.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
revision=d303e47fa1a6cac452eedd157d3efb0dd08e3732
stage="$root/desktopApp/target/gaze-bundle"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
command -v zig >/dev/null
[[ "$(zig version)" == "0.15.2" ]] || { echo "Zig 0.15.2 is required" >&2; exit 1; }
pkg-config --exists libusb-1.0
git clone --quiet https://github.com/Aetherall/tobiifree "$work/source"
git -C "$work/source" checkout --quiet "$revision"
git -C "$work/source" apply "$root/scripts/tobiifree/td-i13.patch"
(cd "$work/source/applications/tobiifreed" && zig build -Doptimize=ReleaseSafe)
mkdir -p "$stage"
cp -L "$(pkg-config --variable=libdir libusb-1.0)/libusb-1.0.so.0" "$stage/libusb-1.0.so.0"
install -m755 "$work/source/applications/tobiifreed/zig-out/bin/tobiifreed" "$stage/tobiifreed"
cp "$work/source/LICENSE" "$stage/LICENSE"
# Include the exact patched source, build instructions and licence with every binary.
cp "$root/scripts/build-gaze-daemon.sh" "$stage/build-gaze-daemon.sh"
cp "$root/scripts/tobiifree/td-i13.patch" "$stage/td-i13.patch"
cp "$root/scripts/tobiifree/SOURCE.txt" "$stage/SOURCE.txt"
tar --exclude=.git --exclude=.zig-cache --exclude=zig-out -czf "$stage/tobiifree-source.tar.gz" -C "$work" source
