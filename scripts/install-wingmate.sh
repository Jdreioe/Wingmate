#!/usr/bin/env bash
# Install for the current user; elevate only the optional USB permission setup.
set -euo pipefail

usage() {
    cat <<'HELP'
Usage: bash install-wingmate.sh [--version TAG | --appimage PATH] [--setup-gaze]

Install the latest stable Linux x86_64 release, or a specific release/local build.
--appimage PATH  Install a trusted local AppImage (no download/checksum verification).
--version TAG    Download this GitHub release, including prereleases.
--setup-gaze     Install USB access rules for TD-I13 and Eye Tracker 5 using sudo.
-h, --help      Show this help.

Run as your normal desktop user. Requires curl and python3 for downloads.
HELP
}
fail() { echo "Error: $*" >&2; exit 1; }
version=''
appimage=''
setup_gaze=false
while (($#)); do
    case "$1" in
        --version|--appimage)
            (($# >= 2)) || fail "$1 requires a value"
            [[ -n "$2" ]] || fail "$1 requires a value"
            if [[ "$1" == --version ]]; then version=$2; else appimage=$2; fi
            shift 2 ;;
        --setup-gaze) setup_gaze=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "Unknown argument: $1" ;;
    esac
done
[[ $(uname -s) == Linux ]] || fail 'This installer supports Linux only.'
((EUID != 0)) || fail 'Run as your normal desktop user, without sudo.'
[[ -z "$version" || -z "$appimage" ]] || fail 'Choose --version or --appimage.'
if $setup_gaze; then
    command -v sudo >/dev/null || fail 'sudo is required for --setup-gaze.'
    command -v udevadm >/dev/null || fail 'udevadm is required for --setup-gaze.'
fi
work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT
if [[ -n "$appimage" ]]; then
    [[ -f "$appimage" && -r "$appimage" ]] || fail "Cannot read AppImage: $appimage"
    cp -- "$appimage" "$work_dir/Wingmate.AppImage"
else
    [[ $(uname -m) == x86_64 ]] || fail 'Published AppImages currently support x86_64 only.'
    for command in curl python3 sha256sum; do
        command -v "$command" >/dev/null || fail "Install $command first."
    done
    release_path=latest
    if [[ -n "$version" ]]; then
        [[ "$version" =~ ^[a-zA-Z0-9._-]+$ ]] || fail 'Invalid release tag.'
        release_path="tags/$version"
    fi
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        "https://api.github.com/repos/Jdreioe/Wingmate/releases/$release_path" \
        -o "$work_dir/release.json" || fail 'Release unavailable. Use --version TAG or --appimage PATH for a local build.'
    python3 - "$work_dir" <<'PY'
import json
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
assets = json.loads((work / "release.json").read_text())["assets"]
images = [a for a in assets if a["name"].endswith(".AppImage")]
sums = [a for a in assets if a["name"] == "SHA256SUMS"]
if len(images) != 1 or len(sums) != 1:
    sys.exit("Release must contain exactly one AppImage and SHA256SUMS.")
for name, asset in [("image", images[0]), ("checksums", sums[0])]:
    url = asset["browser_download_url"]
    if not url.startswith("https://github.com/Jdreioe/Wingmate/releases/download/"):
        sys.exit("Unexpected release asset URL.")
    (work / (name + ".url")).write_text(url)
(work / "image.name").write_text(images[0]["name"])
PY
    for asset in image checksums; do
        curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
            "$(cat "$work_dir/$asset.url")" -o "$work_dir/$asset"
    done
    python3 - "$work_dir" <<'PY'
import pathlib
import re
import sys

work = pathlib.Path(sys.argv[1])
name = (work / "image.name").read_text()
matches = []
for line in (work / "checksums").read_text().splitlines():
    match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *](.+)", line)
    if match and match[2] == name:
        matches.append(match[1])
if len(matches) != 1:
    sys.exit("Missing or ambiguous AppImage checksum.")
(work / "check").write_text(matches[0] + "  image\n")
PY
    (cd "$work_dir" && sha256sum --check --status check) || fail 'AppImage checksum mismatch.'
    mv -- "$work_dir/image" "$work_dir/Wingmate.AppImage"
fi

# Desktop entry Exec values require quoting independently of shell quoting.
data_dir=${XDG_DATA_HOME:-$HOME/.local/share}
[[ "$data_dir" == /* ]] || fail 'XDG_DATA_HOME must be an absolute path.'
[[ "$data_dir" != *$'\n'* && "$data_dir" != *$'\r'* ]] || fail 'Installation path cannot contain newlines.'
install_dir="$data_dir/wingmate-app"
mkdir -p -- "$install_dir" "$data_dir/applications"
staged_image=$(mktemp "$install_dir/.Wingmate.XXXXXX")
trap 'rm -rf -- "$work_dir"; rm -f -- "${staged_image:-}"' EXIT
install -m 755 -- "$work_dir/Wingmate.AppImage" "$staged_image"
mv -f -- "$staged_image" "$install_dir/Wingmate.AppImage"
exec_path="$install_dir/Wingmate.AppImage"
exec_path=${exec_path//\\/\\\\\\\\}
exec_path=${exec_path//\"/\\\\\"}
exec_path=${exec_path//\$/\\\\\$}
exec_path=${exec_path//\`/\\\\\`}
exec_path=${exec_path//%/%%}
cat > "$work_dir/wingmate.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Wingmate
Comment=Augmentative and alternative communication
Exec="$exec_path"
Icon=accessibility
Terminal=false
Categories=Education;Accessibility;
DESKTOP
install -m 644 -- "$work_dir/wingmate.desktop" "$data_dir/applications/io.github.jdreioe.wingmate.desktop"
echo "Installed Wingmate at $install_dir/Wingmate.AppImage"

if $setup_gaze; then
    # Run before systemd's 73-seat-late.rules so uaccess applies to the active seat.
    cat > "$work_dir/70-wingmate-tobii.rules" <<'RULES'
# Wingmate: active local desktop user access to runtime trackers only.
SUBSYSTEM=="usb", ENV{DEVTYPE}=="usb_device", ATTR{idVendor}=="2104", ATTR{idProduct}=="031e", TAG+="uaccess"
SUBSYSTEM=="usb", ENV{DEVTYPE}=="usb_device", ATTR{idVendor}=="2104", ATTR{idProduct}=="0313", TAG+="uaccess"
RULES
    echo 'Installing tracker USB permissions (administrator authentication may be required).'
    sudo install -m 644 -- "$work_dir/70-wingmate-tobii.rules" /etc/udev/rules.d/70-wingmate-tobii.rules
    sudo udevadm control --reload-rules
    echo 'Reconnect the tracker to apply permissions. Start your calibrated custom tobiifreed separately.'
fi
echo 'Launch Wingmate from your application menu. Linux speech requires speech-dispatcher.'
