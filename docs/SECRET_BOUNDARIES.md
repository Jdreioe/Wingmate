# Secret boundaries

Infisical is the source of truth for developer and deployment secrets, but it
must not inject all of them into every build. Client binaries are public
artifacts: values compiled into them can be extracted.

| Value | Classification | Destination |
|---|---|---|
| `OPENSYMBOLS_SECRET` | Developer-owned secret | Cloudflare Worker only, through an Infisical Worker Secret Sync |
| OpenSymbols proxy URL | Public configuration | Android BuildConfig or iOS Info.plist build setting |
| Aptabase app key | Public client identifier | Android BuildConfig only |
| Azure Speech subscription key | User-owned secret | Android Keystore or iOS Keychain |
| Google Cloud Text-to-Speech API key | User-owned secret | Android Keystore or iOS Keychain |
| Android keystore and passwords | Build secret | Android release CI process only |
| Play service-account JSON | Deployment secret | Android release CI process only |
| Apple Developer ID certificate and notarization credentials | Build secrets | macOS desktop release job only |
| Windows Authenticode certificate | Build secret | Windows desktop release job only |
| Linux release-signing key | Build secret | Desktop release publishing job only |
| `INFISICAL_TOKEN` | CI control-plane secret | GitHub Actions secret only |
| Cloudflare API token | Deployment secret | Worker deployment environment only |

## Infisical layout

Use separate folders and machine identities with least-privilege access:

- `/runtime/opensymbols-proxy`: `OPENSYMBOLS_SECRET`; synchronize this folder
  directly to the Worker. Client build identities must not read it.
- `/ci/android`: signing keystore, signing passwords, Play service account, and
  the Aptabase app key. Only the Google Play workflow identity may read it.
- `/ci/desktop`: Apple Developer ID certificate, Apple notarization credentials,
  Windows Authenticode certificate, and Linux release-signing key. Expose each
  value only to its operating-system release step.
- `/ci/worker`: Cloudflare deployment credentials if deployment is later
  automated. The Worker runtime itself does not need these.

The proxy URL is not confidential and can be stored in normal build
configuration. Azure BYOK credentials do not belong in Infisical because each
user supplies and owns their own key.

An Infisical secret sync exposes these values as GitHub Actions secrets for the
desktop release workflow:

- `APPLE_CERTIFICATE`, `APPLE_CERTIFICATE_PASSWORD`,
  `APPLE_SIGNING_IDENTITY`, `APPLE_ID`, `APPLE_PASSWORD`, and `APPLE_TEAM_ID`
- `WINDOWS_CERTIFICATE` and `WINDOWS_CERTIFICATE_PASSWORD`
- `LINUX_GPG_PRIVATE_KEY` and `LINUX_GPG_PASSPHRASE`

The certificate values are base64-encoded PKCS #12 files. The Linux value is an
ASCII-armored private key. The workflow imports them into temporary runner key
stores and removes the platform certificate after packaging.

The CI security check rejects known server-secret environment names in client
code and secret-like iOS plist keys. This complements secret scanning; it does
not make an intentionally embedded value safe.
