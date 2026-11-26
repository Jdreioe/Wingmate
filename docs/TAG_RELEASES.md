# Tag-driven mobile releases

Android and iOS releases are started by semantic version tags, but each platform
uses its own tag pattern.

## Android (GitHub Actions)

- `v1.2.3` starts `.github/workflows/deploy-play.yml`, which publishes version
  `1.2.3` to Google Play's open-testing `beta` track.
- `production-v1.2.3` on the same commit starts
  `.github/workflows/promote-play-production.yml`, which promotes that beta
  release to the `production` track.

The Android version code is derived from the semantic version, so each Play
artifact has a deterministic, increasing version code. Localized Play release
notes must exist before tagging; see
[`release-notes/README.md`](../release-notes/README.md) for the file layout and
release sequence.

Ordinary branch pushes do not deploy the Android app.

## iOS (Xcode Cloud via GitHub Actions)

iOS releases use channel-prefixed tags:

- `staging/1.2.3` starts `.github/workflows/trigger-xcode-cloud.yml`, which calls
  the App Store Connect API (`POST /v1/ciBuildRuns`) to run the staging Xcode
  Cloud workflow against that tag.
- `prod/1.2.3` starts `.github/workflows/trigger-xcode-cloud.yml` against the
  production Xcode Cloud workflow.
- You can also re-run any existing tag manually via **Actions → Trigger Xcode
  Cloud release build → Run workflow** (`workflow_dispatch`).

### Required credentials and configuration

Create an App Store Connect API key (App Manager or Admin role) and store these
secrets in Infisical (fetched automatically by the workflow) or in GitHub
Actions repository secrets:

- `ASC_KEY_ID` — App Store Connect API Key ID
- `ASC_ISSUER_ID` — App Store Connect Issuer ID (UUID)
- `ASC_PRIVATE_KEY` — contents of the `.p8` key file (raw PEM, `\n`-escaped
  PEM, or base64-encoded PEM)

Workflow selection (optional GitHub repository variables or Infisical values):

- Staging defaults to looking up the workflow named `Default` on App Store
  Connect app `6743526254`. Override with `ASC_XCODE_CLOUD_STAGING_WORKFLOW_ID`
  (exact UUID) or `ASC_APP_ID` + `ASC_XCODE_CLOUD_STAGING_WORKFLOW_NAME`.
- Production requires either `ASC_XCODE_CLOUD_PROD_WORKFLOW_ID` or
  `ASC_XCODE_CLOUD_PROD_WORKFLOW_NAME` (it never falls back to the staging
  workflow).

### Xcode Cloud workflow settings in App Store Connect

1. Configure your staging workflow with an archive / TestFlight distribution
   action, and your production workflow with its production archive /
   distribution action.
2. Remove **Branch Changes** and **Git Tag Change** start conditions so GitHub
   Actions is the single trigger (on tag pushes the script also skips creating
   a duplicate run if an active build for that tag is already queued/running).
3. Add a non-secret environment variable named `REQUIRE_RELEASE_TAG` with the
   value `TRUE` to both release workflows.
4. Add the Infisical machine-identity variables from
   [Remote Screens](REMOTE_SCREENS.md#setting-up-google-drive-and-onedrive) so
   Google Drive and OneDrive are included: secret `INFISICAL_CLIENT_ID`, secret
   `INFISICAL_CLIENT_SECRET`, and `INFISICAL_PROJECT_ID` (the project ID, which
   is separate from the GitHub variable `INFISICAL_PROJECT_SLUG`). The script
   reads Infisical `prod` secrets `WINGMATE_GOOGLE_IOS_CLIENT_ID` and
   `WINGMATE_MICROSOFT_CLIENT_ID`. When the workflow variables are absent, the
   build still succeeds and hides both providers.

Because `POST /v1/ciBuildRuns` targets the tag's `scmGitReferences` resource,
Xcode Cloud still populates `CI_TAG` (`staging/1.2.3` or `prod/1.2.3`). The
post-clone script validates `CI_TAG`, assigns the part after the slash to
`MARKETING_VERSION`, and uses Xcode Cloud's `CI_BUILD_NUMBER` as
`CURRENT_PROJECT_VERSION`. With `REQUIRE_RELEASE_TAG=TRUE`, a non-tag build
intentionally fails so that a release cannot be created by an ordinary push.
Other Xcode Cloud workflows are unaffected unless they opt into that variable.

## Creating an iOS release

For example:

```bash
git tag staging/1.2.3
git push origin staging/1.2.3

git tag prod/1.2.3
git push origin prod/1.2.3
```
