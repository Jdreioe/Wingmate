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

A storage option appears only when its public OAuth client ID is configured. These IDs are public client identifiers, not secrets ([SECRET_BOUNDARIES.md](SECRET_BOUNDARIES.md)). All of the following is free.

**Google Cloud** (one project):

1. Enable the Google Drive API.
2. Configure the OAuth consent screen with the `.../auth/drive.file` scope.
3. Create an **iOS** OAuth client for bundle ID `com.hojmoseit.wingmate`. Put its client ID in `WINGMATE_GOOGLE_IOS_CLIENT_ID` (Xcode Cloud environment variable, or `iosApp/Configuration/Config.xcconfig` locally).
4. Create an **Android** OAuth client for package `com.hojmoseit.wingmate` (and `com.hojmoseit.wingmate.dev` for debug builds) with each signing certificate SHA-1, including Play App Signing. Android signs in through Google Play services, so the app only needs `WINGMATE_GOOGLE_DRIVE_ANDROID_ENABLED=true` in `local.properties` or Gradle properties.

**Microsoft Entra** (one app registration):

1. Supported account types: personal Microsoft accounts and accounts in any organizational directory.
2. Platform "Mobile and desktop applications" with redirect URI `wingmate-auth://oauth`; allow public client flows.
3. Delegated Microsoft Graph permissions `Files.ReadWrite` and `offline_access`.
4. Put the Application (client) ID in `WINGMATE_MICROSOFT_CLIENT_ID` for iOS (Xcode Cloud or `Config.xcconfig`) and Android (`local.properties` or Gradle properties).

Supporters who edit on a PC need a browser editor; see [issue #381](https://github.com/Jdreioe/Wingmate/issues/381).
