## Layout

- `shared/`, `core/`, `feature/communication/`: Kotlin Multiplatform domain, data and application logic used by both apps.
- `androidApp/`: Jetpack Compose client.
- `iosApp/`: SwiftUI client on the shared Kotlin framework.
- `infra/opensymbols-proxy/`: Cloudflare Worker for symbol search.

The paused desktop client lives at the `desktop-archive` tag, not on `main`.

## Verify changes

- Shared Kotlin: `./gradlew jvmTest`
- Android: `bash ci_scripts/verify_android.sh` (build, lint, unit tests and jvmTest); UI tests need an emulator: `./gradlew :androidApp:connectedDebugAndroidTest`
- iOS (macOS only): `bash ci_scripts/verify_ios.sh`
- Worker: `npm test && npm run check` in `infra/opensymbols-proxy`

## Agent skills

### Issue tracker

Issues and specs are tracked in this repository’s GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the default canonical triage labels. See `docs/agents/triage-labels.md`.

### Domain docs

Uses a single-context layout: root `CONTEXT.md`. See `docs/agents/domain.md`.
