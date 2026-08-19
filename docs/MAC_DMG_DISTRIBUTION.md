# Mac Catalyst .dmg distribution

Wingmate's iPad-first SwiftUI app ships on macOS as a **Mac Catalyst** app
(`SUPPORTS_MACCATALYST = YES`), so the same codebase produces a real macOS
`.app` that can be signed, notarized, and distributed as a `.dmg` outside the
Mac App Store. The "(Designed for iPad)" experience maps onto the Catalyst build
rather than the iOS-on-Apple-Silicon sideload path.

## Build

```
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Release \
  -destination 'generic/platform=macOS,variant=Mac Catalyst' \
  -derivedDataPath build/catalyst-rel build \
  -allowProvisioningUpdates
```

App lands at `build/catalyst-rel/Build/Products/Release-maccatalyst/Wingmate.app`.

### Kotlin framework quirks (KT-40442)

Kotlin/Native cannot emit `MACCATALYST` binaries. `ci_scripts/patch_shared_framework_for_catalyst.sh`
(launched from the Xcode `Compile Kotlin Framework` phase) rewrites the
`Shared.framework` Mach-O `LC_BUILD_VERSION` platform to `MACCATALYST` per
architecture slice, restructures the flat iOS-style bundle into the macOS
`Versions` layout, and fixes `CFBundleSupportedPlatforms`. It must run after
Gradle's `embedAndSignAppleFrameworkForXcode` and replaces the copies Gradle
placed in the build products so the linker picks up the patched binary.

## Entitlements

`iosApp/iosApp.iosApp.entitlements`:

- `com.apple.security.app-sandbox` — Catalyst apps must be sandboxed.
- `com.apple.security.network.client` — OpenSymbols proxy, Azure TTS, Aptabase.
- `com.apple.security.device.audio-input` — phrase recording.
- `keychain-access-groups` — required by `IosConfigRepository` when it clears
  the Azure Keychain item. Missing this crashes at startup with
  `errSecMissingEntitlement (-34018)`.

## Package the .dmg

```
./ci_scripts/package_mac_dmg.sh \
  --app build/catalyst-rel/Build/Products/Release-maccatalyst/Wingmate.app \
  --out build/Wingmate-0.7.dmg \
  --vol Wingmate
```

Produces a drag-to-`/Applications` UDZO volume.

## Production (Gatekeeper-clean) release

Ad-hoc signing (no `--identity`) yields a volume whose app launches only via
right-click > Open. For one-click installs:

1. Enable Mac Catalyst + a Mac App ID in the developer portal, and request a
   **Developer ID Application** certificate.
2. Create App Store Connect API keys for notarization.
3. Sign with Developer ID, notarize, staple:

```
./ci_scripts/package_mac_dmg.sh \
  --app build/catalyst-rel/Build/Products/Release-maccatalyst/Wingmate.app \
  --out build/Wingmate-0.7.dmg \
  --vol Wingmate \
  --identity "Developer ID Application: Name (TEAMID)" \
  --key-id XXXX --issuer-id UUID --key /path/AuthKey_XXXX.p8 --team-id TEAMID
```

The script signs with `--options runtime` (hardened runtime), submits the dmg
to `notarytool`, and staples the ticket on success. Verify acceptance with
`spctl --assess --type execute --verbose=4 Wingmate.app` (must report `accepted`);
otherwise users hit "app is damaged / cannot be opened".

## Notes

- Catalyst builds are universal (`x86_64 arm64`) — they run on Intel and
  Apple Silicon Macs.
- The bundle ID is shared with iOS (`com.hojmoseit.wingmate`); the Mac App
  Store and Catalyst use separate provisioning.
- Run-time testing surfaced the keychain entitlement above — always smoke-test
  the packaged app once before shipping.