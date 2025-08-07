#!/bin/bash

# Wingmate Flatpak Build Script - Fixed Version
# A voice for people who cannot speak

set -e

echo "🎯 Building Wingmate for Flatpak distribution..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Flutter is installed
if ! command -v flutter &> /dev/null; then
    print_error "Flutter is not installed or not in PATH"
    exit 1
fi

# Check if Flatpak tools are installed
if ! command -v flatpak-builder &> /dev/null; then
    print_error "flatpak-builder is not installed"
    print_status "Install with: sudo pacman -S flatpak-builder (Arch) or sudo apt install flatpak-builder (Ubuntu)"
    exit 1
fi

print_status "Cleaning previous builds..."
flutter clean
rm -rf build-dir .flatpak-builder wingmate.flatpak

print_status "Getting Flutter dependencies..."
flutter pub get

print_status "Enabling Linux desktop support..."
flutter config --enable-linux-desktop

print_status "Building Flutter app for Linux..."
flutter build linux --release

if [ $? -ne 0 ]; then
    print_error "Flutter build failed"
    exit 1
fi

print_success "Flutter build completed!"

print_status "Creating Flatpak metadata files..."

# Create desktop file
cat > io.github.jdreioe.Wingmate.desktop << 'EOF'
[Desktop Entry]
Name=Wingmate
Comment=A voice for people who cannot speak, using Azure Neural Voices
GenericName=AAC Communication App
Exec=wingmate
Icon=io.github.jdreioe.Wingmate
Type=Application
Categories=Accessibility;Education;Utility;
Keywords=speech;communication;accessibility;azure;tts;aac;cerebral-palsy;
StartupNotify=true
StartupWMClass=wingmate
MimeType=text/plain;
X-Flatpak-RenamedFrom=wingmate.desktop;
EOF

# Create app icon (using a simple SVG - you should replace with your actual logo)
cat > io.github.jdreioe.Wingmate.svg << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<svg width="128" height="128" viewBox="0 0 128 128" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="grad1" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#4CAF50;stop-opacity:1" />
      <stop offset="100%" style="stop-color:#2196F3;stop-opacity:1" />
    </linearGradient>
  </defs>
  <rect width="128" height="128" rx="16" fill="url(#grad1)"/>
  <!-- Speech bubble -->
  <ellipse cx="64" cy="50" rx="35" ry="25" fill="white" opacity="0.9"/>
  <!-- Sound waves -->
  <path d="M 75 35 Q 85 40 85 50 Q 85 60 75 65" stroke="white" stroke-width="3" fill="none" opacity="0.8"/>
  <path d="M 80 30 Q 95 40 95 50 Q 95 60 80 70" stroke="white" stroke-width="2" fill="none" opacity="0.6"/>
  <!-- Wing symbol -->
  <path d="M 45 85 Q 55 75 70 85 Q 85 75 95 85 Q 85 95 70 85 Q 55 95 45 85" fill="white" opacity="0.9"/>
</svg>
EOF

# Create metainfo file
cat > io.github.jdreioe.Wingmate.metainfo.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<component type="desktop-application">
  <id>io.github.jdreioe.Wingmate</id>
  <metadata_license>CC0-1.0</metadata_license>
  <project_license>GPL-3.0-or-later</project_license>
  <name>Wingmate</name>
  <summary>A voice for people who cannot speak</summary>
  <description>
    <p>
      Wingmate is a Free and Open Source Software (FOSS) project aimed at providing an exceptional voice for people who cannot speak, using Azure Neural Voices.
    </p>
    <p>
      Developed by Jonas, who has Cerebral Palsy (CP) and extensive experience with various speech devices, Wingmate offers a high-quality, affordable communication solution built cross-platform using Flutter.
    </p>
    <p>Features include:</p>
    <ul>
      <li>Select from multiple Azure Neural Voices</li>
      <li>Multi-language support</li>
      <li>XML tags for speech customization</li>
      <li>Sentence and category saving (planned)</li>
      <li>Offline backup voices (planned)</li>
      <li>Hand gesture recognition (long-term goal)</li>
      <li>Eye tracking support (long-term goal)</li>
    </ul>
  </description>
  <categories>
    <category>Accessibility</category>
    <category>Education</category>
    <category>Utility</category>
  </categories>
  <keywords>
    <keyword>speech</keyword>
    <keyword>communication</keyword>
    <keyword>accessibility</keyword>
    <keyword>azure</keyword>
    <keyword>tts</keyword>
    <keyword>aac</keyword>
  </keywords>
  <url type="homepage">https://github.com/Jdreioe/Wingmate</url>
  <url type="bugtracker">https://github.com/Jdreioe/Wingmate/issues</url>
  <url type="vcs-browser">https://github.com/Jdreioe/Wingmate</url>
  <developer_name>Jonas</developer_name>
  <content_rating type="oars-1.1" />
  <launchable type="desktop-id">io.github.jdreioe.Wingmate.desktop</launchable>
  <provides>
    <binary>wingmate</binary>
  </provides>
</component>
EOF

print_success "Metadata files created!"

print_status "Installing Flatpak runtimes if needed..."
flatpak install --user flathub org.freedesktop.Platform//23.08 org.freedesktop.Sdk//23.08 -y 2>/dev/null || print_warning "Runtimes may already be installed"

# Initialize local repository if it doesn't exist
REPO_DIR="$HOME/.local/share/flatpak/repo"
if [ ! -d "$REPO_DIR" ] || [ ! -f "$REPO_DIR/config" ]; then
    print_status "Initializing local Flatpak repository..."
    mkdir -p "$REPO_DIR"
    ostree init --mode=archive-z2 --repo="$REPO_DIR"
fi

print_status "Building and exporting Flatpak package..."

# Build and export to repository in one step
flatpak-builder --repo="$REPO_DIR" --force-clean build-dir io.github.jdreioe.Wingmate.json

if [ $? -ne 0 ]; then
    print_error "Flatpak build failed"
    exit 1
fi

print_success "Flatpak build and export completed!"

print_status "Creating Flatpak bundle for distribution..."

# Create bundle from repository
flatpak build-bundle "$REPO_DIR" wingmate.flatpak io.github.jdreioe.Wingmate

if [ $? -eq 0 ]; then
    print_success "Flatpak bundle created: wingmate.flatpak"
    echo ""
    print_status "📦 Package Information:"
    echo "  • Bundle: wingmate.flatpak"
    echo "  • App ID: io.github.jdreioe.Wingmate"
    echo "  • Size: $(du -h wingmate.flatpak | cut -f1)"
    echo ""
    print_status "🚀 Installation Instructions:"
    echo "  Install bundle: flatpak install --user wingmate.flatpak"
    echo "  Run app: flatpak run io.github.jdreioe.Wingmate"
    echo "  Uninstall: flatpak uninstall io.github.jdreioe.Wingmate"
    echo ""
    print_status "🧪 Testing Instructions:"
    echo "  Test install: flatpak install --user wingmate.flatpak"
    echo "  Test run: flatpak run io.github.jdreioe.Wingmate"
    echo "  Check logs: flatpak run --log-session-bus io.github.jdreioe.Wingmate"
    echo ""
    print_status "📋 Distribution:"
    echo "  • Share the wingmate.flatpak file with users"
    echo "  • Users need Flatpak installed to use the bundle"
    echo "  • Consider publishing to Flathub for wider distribution"
else
    print_error "Failed to create Flatpak bundle"
    exit 1
fi

print_success "🎉 Wingmate Flatpak build process completed successfully!"

# Optional: Test installation
echo ""
read -p "Would you like to test install the bundle locally? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    print_status "Testing local installation..."
    flatpak install --user wingmate.flatpak -y
    if [ $? -eq 0 ]; then
        print_success "Installation test successful!"
        print_status "You can now run: flatpak run io.github.jdreioe.Wingmate"
    else
        print_error "Installation test failed"
    fi
fi