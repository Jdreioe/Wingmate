# Wingmate
Wingmate is a Free and Open Source Software (FOSS) project aimed at providing an exceptional voice for people who cannot speak, using Azure Neural Voices.


## About the project
Wingmate is developed by Jonas, who has Cerebral Palsy (CP) and extensive experience with various speech devices. The current goal is to offer a high-quality, affordable communication solution that can be built cross platform using KMP.

## Features:

### ✅ Implemented
- Select voice (Azure Neural Voices, offline backup)
- Native UI on iOS, Android, Desktop (Compose), Linux (Qt/QML)
- Select primary language
- Speak (Azure TTS, system fallback)
- Bring your own Speech Resource (Azure subscription)
- Save Sentences & Categories
- Cache Sentences (audio caching to reduce API costs)
- Offline Backup Voices (download voices for offline use)
- Text Prediction (n-gram based on user history)
- Import OBF/OBZ boards (Open Board Format)
- Partner Window (hand gesture recognition prototype)

### 🔄 In Progress
- Waterfall TTS (Cache → Azure → System fallback)
- SQLDelight for audio cache metadata
- Premium subscription (optional: managed Azure keys via token exchange)

### 📋 Future Goals
- Hand Gesture Recognition (full implementation)
- Eye tracking support
- Multi-user support
- Cloud sync

## iOS development

Open `iosApp/iosApp.xcodeproj` in Xcode. Apple-platform dependencies are managed
with Xcode's Swift Package Manager integration; CocoaPods is not used. The local
Kotlin Multiplatform `Shared.framework` is built by the project's Xcode build
phase.

## How to setup (the DYI version):

- Install the app
- Make a free Microsoft Azure account (portal.azure.com) 
- Create a Speech Resource (F0 = 500k free characters a month - that's enough for me)
- Follow the workflow to get through the setup

## OpenSymbols Secret (Secure Config)

OpenSymbols is now configured at runtime and is no longer hardcoded in source.

The project resolves the secret in this order:

1. Infisical (via `gradlew` auto-wrap or direct API)
2. Environment variable: `WINGMATE_OPENSYMBOLS_SECRET`
3. JVM system property: `wingmate.opensymbols.secret`
4. `local.properties` key: `OPENSYMBOLS_SECRET` (or `WINGMATE_OPENSYMBOLS_SECRET`)

### Infisical setup

If you store a secret named `openSymbols` in environment `system_env`, only these are required:

- `INFISICAL_PROJECT_ID` (or `INFISICAL_PROJECT_SLUG`)
- Auth via one of:
	- `INFISICAL_ACCESS_TOKEN`
	- `INFISICAL_CLIENT_ID` + `INFISICAL_CLIENT_SECRET`

Optional overrides:

- `INFISICAL_URL` (default: `https://app.infisical.eu`)
- `INFISICAL_ENV` (default: `system_env`)
- `INFISICAL_SECRET_NAME` (default: `openSymbols`)
- `INFISICAL_SECRET_PATH` (default: `/`)
- `INFISICAL_ORGANIZATION_SLUG`
- `INFISICAL_ENABLED=false` to disable vault lookup

#### Automatic wrapping via Infisical CLI (recommended)

When you set `INFISICAL_PROJECT_ID` (or `INFISICAL_PROJECT_SLUG`) and the
[Infisical CLI](https://infisical.com/docs/cli/overview) is installed, `gradlew`
will automatically wrap itself with `infisical run`, injecting all secrets from
your Infisical project as environment variables. No extra steps are needed:

```bash
export INFISICAL_PROJECT_ID="<your-project-id>"
export INFISICAL_CLIENT_ID="<your-client-id>"
export INFISICAL_CLIENT_SECRET="<your-client-secret>"
./gradlew assembleDebug        # ← auto-wraps with infisical run
```

This works for all Gradle tasks (Android, Desktop, etc.).

#### Direct API resolution (no CLI required)

If the Infisical CLI is not installed, the build scripts and desktop runtime
will reach Infisical directly via HTTP using the same configuration variables.

Example (Linux/macOS):

```bash
export INFISICAL_PROJECT_ID="<your-project-id>"
export INFISICAL_CLIENT_ID="<your-client-id>"
export INFISICAL_CLIENT_SECRET="<your-client-secret>"
./gradlew desktopApp:run
```

### Local fallback

If Infisical is not configured or unavailable, the project falls back to local
runtime config.

Example (Linux/macOS):

```bash
export WINGMATE_OPENSYMBOLS_SECRET="your-secret"
./gradlew desktopApp:run
```

Do not commit secrets to git.
License: GPL 3.0

Credits: 

**Logo:** Anna Thaulov
**Name idea:** Jeppe Forchmann's awesome documentary **_Wingman_** 
