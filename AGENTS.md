# Agent Instructions

## Gradle & Infisical

Always use `infisical run` when running any gradle command. For example:

```bash
infisical run -- ./gradlew :desktopApp:run
infisical run -- ./gradlew :androidApp:installDebug
infisical run -- ./gradlew :shared:test
```

The `gradlew` script has built-in auto-wrapping logic (lines 67-76) that does this automatically when `INFISICAL_PROJECT_ID` or `INFISICAL_PROJECT_SLUG` is set and the Infisical CLI is available. However, for consistency and to ensure secrets are always available, explicitly prefix gradle commands with `infisical run -- `.

## CI/CD — Google Play Console

A GitHub Actions workflow at `.github/workflows/deploy-play.yml` handles Play Console deployment.

### Required Infisical secrets

Set the following secrets in the `wingmate` Infisical project (or another project accessible to CI):

| Secret name | Description |
|---|---|
| `keystoreBase64` | Upload keystore encoded as base64 |
| `keystorePassword` | Keystore password |
| `keyAlias` | Key alias for the upload key |
| `keyPassword` | Key alias password |
| `playServiceAccountJson` | Google Cloud service account JSON (base64-encoded) |

The GitHub Actions workflow uses `INFISICAL_TOKEN` as a GitHub secret to authenticate with Infisical.

### Deploy commands

```bash
# Build and sign the release AAB locally
infisical run -- ./gradlew :androidApp:bundleRelease

# Build, sign, and publish to Play Console (production track)
infisical run -- ./gradlew :androidApp:publishRelease
```

### Release notes

Edit `androidApp/src/main/play/release-notes/en-US/production.txt` before each release.

### Initial setup (one time)

1. **Create a Google Cloud service account** at https://console.cloud.google.com/apis/credentials
2. **Enable the Android Publisher API** for the project
3. **Invite the service account** to Google Play Console at https://play.google.com/console/developers — give it "Releases" permissions
4. **Store secrets in Infisical** using `infisical secrets set`:
   ```bash
   infisical secrets set keystoreBase64="$(base64 -w0 androidApp/release.keystore)"
   infisical secrets set keystorePassword="..." keyAlias="..." keyPassword="..."
   infisical secrets set playServiceAccountJson="$(base64 -w0 path/to/service-account.json)"
   ```
5. **Add `INFISICAL_TOKEN`** as a GitHub Actions secret (create a machine identity token in Infisical)
