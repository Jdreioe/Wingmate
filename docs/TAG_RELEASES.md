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

## iOS (Xcode Cloud)

iOS releases use channel-prefixed tags:

- `staging/1.2.3` builds version `1.2.3` for staging distribution.
- `prod/1.2.3` builds version `1.2.3` for production distribution.

Xcode Cloud workflows are configured in Xcode or App Store Connect, not in a
repository workflow file. Create or update two workflows:

1. Give the staging workflow a **Git Tag Change** start condition matching
   `staging/*`, and configure its archive/TestFlight distribution action.
2. Give the production workflow a **Git Tag Change** start condition matching
   `prod/*`, and configure its production archive/distribution action.
3. Remove the **Branch Changes** start condition from both release workflows.
4. Add a non-secret environment variable named `REQUIRE_RELEASE_TAG` with the
   value `TRUE` to both release workflows.

The post-clone script validates `CI_TAG`, assigns the part after the slash to
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
