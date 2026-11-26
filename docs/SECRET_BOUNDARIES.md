# Secret boundaries

Infisical is the source of truth for developer and deployment secrets, but it
must not inject all of them into every build. Client binaries are public
artifacts: values compiled into them can be extracted.

| Value | Classification | Destination |
|---|---|---|
| `OPENSYMBOLS_SECRET` | Developer-owned secret | Cloudflare Worker only, through an Infisical Worker Secret Sync |
| OpenSymbols proxy URL | Public configuration | Android BuildConfig or iOS Info.plist build setting |
| Aptabase app key | Public client identifier | Android BuildConfig or iOS Info.plist build setting (Xcode Cloud `WINGMATE_APTABASE_APP_KEY`) |
| Azure Speech subscription key | User-owned secret | Android Keystore or iOS Keychain |
| Google Cloud Text-to-Speech API key | User-owned secret | Android Keystore or iOS Keychain |
| Google Drive / OneDrive OAuth client IDs | Public client identifiers | Android BuildConfig, or iOS Info.plist via gitignored `CloudDriveOAuth.xcconfig`. Xcode Cloud fetches Infisical `prod` secrets `WINGMATE_GOOGLE_IOS_CLIENT_ID` and `WINGMATE_MICROSOFT_CLIENT_ID` when `INFISICAL_CLIENT_ID`, `INFISICAL_CLIENT_SECRET`, and `INFISICAL_PROJECT_ID` are set on the workflow. |
| Remote Screen password, token, or cloud drive sign-in | User-owned secret | Android Keystore or iOS Keychain. Cloud drive tokens stay in shared Kotlin; native UI only holds an opaque account reference. Screen exports and backups omit remote links and credentials. |
| Android keystore and passwords | Build secret | Android release CI process only |
| Play service-account JSON | Deployment secret | Android release CI process only |
| App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY`) | Deployment secret | iOS Xcode Cloud trigger CI workflow only |
| `INFISICAL_CLIENT_ID` and `INFISICAL_CLIENT_SECRET` | CI control-plane secrets | GitHub Actions secrets for release workflows only |
| Cloudflare API token | Deployment secret | Worker deployment environment only |

## Infisical layout

Use separate folders and machine identities with least-privilege access:

- `/runtime/opensymbols-proxy`: `OPENSYMBOLS_SECRET`; synchronize this folder
  directly to the Worker. Client build identities must not read it.
- `/ci/android`: signing keystore, signing passwords, Play service account, and
  the Aptabase app key. Only the Google Play workflow identity may read it.
  Xcode Cloud reads the public OAuth client IDs from Infisical `prod` folder `/`
  with the machine identity in `INFISICAL_CLIENT_ID` / `INFISICAL_CLIENT_SECRET`.
  `WINGMATE_APTABASE_APP_KEY` on the Xcode Cloud workflow remains a manual copy
  of the same public key.
- `/ci/worker`: Cloudflare deployment credentials if deployment is later
  automated. The Worker runtime itself does not need these.

The proxy URL is not confidential and can be stored in normal build
configuration. Azure BYOK credentials do not belong in Infisical because each
user supplies and owns their own key.

The CI security check rejects known server-secret environment names in client
code and secret-like iOS plist keys. This complements secret scanning; it does
not make an intentionally embedded value safe.
