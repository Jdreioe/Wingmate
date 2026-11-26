# Remote Screens

Android and iOS can link a Screen to storage that the Communicator or a Supporter already has, and keep it in sync as an `.obz` Vocabulary package. Each device keeps its own editable local copy, so communication works offline while Supporters edit on another device. Wingmate runs no relay, storage service, or account for this, and it costs Wingmate nothing.

## Storage options

Sync connections live in **Settings → Sync** (Google Drive, OneDrive, or any OS folder). Wingmate creates a `Wingmate` folder inside each connected drive or folder and automatically pulls and pushes `.obz` Screens from it while keeping every local Screen usable offline.

- **Google Drive** (Android, iOS) signs in with Google and uses the Drive API with the non-sensitive `drive.file` scope. Wingmate can reach only files it created or that a person opened with Wingmate. Connecting Google Drive in Settings → Sync creates a `Wingmate` folder and keeps `.obz` Screens inside it in sync; share a file in Google Drive with the people who help edit. Drive has no conditional upload, so Wingmate compares the file's `headRevisionId` immediately before each upload and verifies downloads against the file's MD5.
- **OneDrive** (Android, iOS) signs in with a personal, work, or school Microsoft account and uses Microsoft Graph with `Files.ReadWrite` and `offline_access`. Connecting OneDrive in Settings → Sync keeps `.obz` Screens in the `Wingmate` folder in sync. Uploads use `If-Match` with the item eTag. A school tenant may block user consent for third-party apps; its IT team can approve Wingmate once.
- **iCloud sharing** (iOS to iOS) shares a Screen with other Apple Accounts through CloudKit (`CKShare`). The owner's device keeps one `WingmateScreen` record per shared Screen in the `WingmateScreens` zone of the owner's private database (container `iCloud.com.hojmoseit.wingmate`) and invites people with Apple's sharing sheet, choosing edit or view-only access. Participants accept by tapping the iCloud link. Record change tags provide optimistic concurrency. Data counts against the owner's iCloud storage. Disconnecting stops sharing for the owner (deleting the record, which clears stale links on other devices on their next sync) or leaves the share for a participant; every device keeps its local Screen.
- **Linked folder / file** connects a folder in **Settings → Sync** (auto-syncing its `Wingmate` subfolder) or links an individual `.obz` file managed by the operating system or a sync app (Android Storage Access Framework persistable URI, iOS security-scoped bookmark).

## Sync rules

Remote `.obz` files are limited to 50 MiB. Linked Screens sync with no user input: a local edit triggers a debounced push on writable links, and Wingmate checks for remote changes on start, on resume, and every 45 seconds while the Screens workspace is open. A pull updates the local Screen in place and keeps its local Screen ID, Screen lock state, and sentence-caching preference, so a locked Communicator Screen still receives Supporter updates while staying locked against local edits.

If both copies changed, Wingmate never silently overwrites either side. It flags a conflict so a Supporter can replace the local copy, overwrite the remote copy (when writable), or save the remote copy as a separate Screen. Saving a copy leaves the remote file intact and keeps local edits marked as changed, so a later remote change cannot overwrite them. Editing access protects linking, opening, disconnecting, and conflict resolution; automatic background sync continues for Screens that are already linked.

Remote storage receives only the chosen Screen's Pages, media, name, and Screen defaults. Passwords, tokens, and cloud drive sign-ins live in the Android Keystore or iOS Keychain and never appear in OBZ exports or backups; a restored backup contains the local Screen and needs a new link. For Google Drive and OneDrive the tokens stay inside the shared Kotlin code: the native UI only receives an opaque account reference.

## Setting up Google Drive and OneDrive

A storage option appears only when its public OAuth client ID is configured. These IDs are public client identifiers, not secrets ([SECRET_BOUNDARIES.md](SECRET_BOUNDARIES.md)). Android and iOS share one Microsoft application ID. Google uses a separate iOS client and separate Android clients. All of the following is free.

On iOS, `ci_scripts/ci_post_clone.sh` runs `ci_scripts/configure_ios_cloud_drive_oauth.sh`. That script installs the Infisical CLI, logs in with universal auth, and reads the two secret names below from environment `prod`. It writes gitignored `iosApp/Configuration/CloudDriveOAuth.xcconfig`, which `Config.xcconfig` includes. Xcode copies the IDs into Info.plist. It also registers the Google reversed client ID and `msauth.com.hojmoseit.wingmate` as URL schemes, which the system sign-in sheet requires before it will open. When the Infisical credentials are absent, the script warns, leaves the IDs empty, and the build still succeeds with both providers hidden.

### Infisical secrets (environment `prod`, folder `/`)

Create these in the same `prod` root the Play workflow already reads:

| Secret name | Value |
|---|---|
| `WINGMATE_GOOGLE_IOS_CLIENT_ID` | iOS OAuth client ID, ending in `.apps.googleusercontent.com` |
| `WINGMATE_MICROSOFT_CLIENT_ID` | Entra application (client) ID, a UUID. Android release builds read this same name. |

### Xcode Cloud environment variables

On each release workflow, add:

| Variable | Secret | Value |
|---|---|---|
| `INFISICAL_CLIENT_ID` | yes | Universal Auth client ID of a machine identity that can read `prod` |
| `INFISICAL_CLIENT_SECRET` | yes | That identity's client secret |
| `INFISICAL_PROJECT_ID` | yes | Project ID from Infisical → Project Settings → Copy Project ID |

The GitHub Actions variable `INFISICAL_PROJECT_SLUG` is a different string. The CLI reads `INFISICAL_PROJECT_ID`. The same Universal Auth client ID and client secret used by the Play workflow can be copied here; Xcode Cloud has its own environment and does not see GitHub Actions secrets.

Optional overrides:

| Variable | Default |
|---|---|
| `INFISICAL_DOMAIN` | `https://eu.infisical.com` |
| `INFISICAL_ENV` | `prod` |
| `INFISICAL_SECRET_PATH` | `/` |

`WINGMATE_GOOGLE_IOS_CLIENT_ID` and `WINGMATE_MICROSOFT_CLIENT_ID`, when set directly on the workflow, are used as-is and Infisical is not asked for that value.

For a local build, create gitignored `iosApp/Configuration/CloudDriveOAuth.xcconfig`:

```
WINGMATE_GOOGLE_IOS_CLIENT_ID = 123456789012-abcdef.apps.googleusercontent.com
WINGMATE_GOOGLE_IOS_URL_SCHEME = com.googleusercontent.apps.123456789012-abcdef
WINGMATE_MICROSOFT_CLIENT_ID = 11111111-2222-3333-4444-555555555555
```

The URL scheme is `com.googleusercontent.apps.` plus the client ID with `.apps.googleusercontent.com` removed. The configure script derives it. Keep real IDs out of `Config.xcconfig`.

### Google Cloud

1. Open Google Cloud Console → APIs & Services → Library, and enable **Google Drive API**.
2. Open the OAuth consent screen. Add the scope `https://www.googleapis.com/auth/drive.file`.
3. Open Credentials → Create credentials → OAuth client ID.
4. Application type: **iOS**. Bundle ID: `com.hojmoseit.wingmate`. Team ID `9BB3E6JDX4` is optional.
5. Create the client and copy the client ID. It looks like `123456789012-abcdef.apps.googleusercontent.com`. Leave the client secret blank; an iOS client does not have one.
6. Store it as Infisical secret `WINGMATE_GOOGLE_IOS_CLIENT_ID` in environment `prod`, folder `/`.

Google fixes the iOS redirect at `com.googleusercontent.apps.<client-id-prefix>:/oauth2redirect`. The build registers that scheme from the client ID. There is no redirect field to fill in on the iOS client.

Android signs in through Google Play services and embeds no Google client ID. Set `WINGMATE_GOOGLE_DRIVE_ANDROID_ENABLED=true` (Infisical `prod`, `local.properties`, or a Gradle property). In the same Google Cloud project, create one Android OAuth client per signing key: package `com.hojmoseit.wingmate` with the Play App Signing SHA-1, another with the upload-key SHA-1, and debug package `com.hojmoseit.wingmate.dev` with the debug-key SHA-1. A missing match makes Google answer `UNREGISTERED_ON_API_CONSOLE` (Wingmate shows "Google error 8").

### Microsoft Entra

1. Open Microsoft Entra admin center → App registrations → New registration. Name it `Wingmate`.
2. Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**.
3. Register the app. Open Authentication → Add a platform → **Mobile and desktop applications**.
4. Add both redirect URIs on that platform:
   - iOS: `msauth.com.hojmoseit.wingmate://auth`
   - Android: `wingmate-auth://oauth`
5. Turn on **Allow public client flows**. Leave the client secret blank.
6. Open API permissions → Add a permission → Microsoft Graph → Delegated. Add `Files.ReadWrite` and `offline_access`. Personal Microsoft accounts can consent themselves. A school tenant may still require its IT team to approve Wingmate once.
7. Copy the Application (client) ID from Overview.
8. Store it as Infisical secret `WINGMATE_MICROSOFT_CLIENT_ID` in environment `prod`, folder `/`. Use the same value for Android (`WINGMATE_MICROSOFT_CLIENT_ID` in `local.properties`, a Gradle property, or the environment). If an Entra app already exists for Android, add the iOS redirect URI to that app and reuse its application ID.

Supporters who edit on a PC need a browser editor; see [issue #381](https://github.com/Jdreioe/Wingmate/issues/381).
