# Secret boundaries

Infisical is the source of truth for developer and deployment secrets, but it
must not inject all of them into every build. Mobile and desktop binaries are
public artifacts: values compiled into them can be extracted.

| Value | Classification | Destination |
|---|---|---|
| `OPENSYMBOLS_SECRET` | Developer-owned secret | Cloudflare Worker only, through an Infisical Worker Secret Sync |
| OpenSymbols proxy URL | Public configuration | Android BuildConfig, iOS Info.plist build setting, Linux environment |
| Aptabase app key | Public client identifier | Android BuildConfig only |
| Azure Speech subscription key | User-owned secret | Android Keystore, iOS Keychain, or Linux Secret Service/KWallet |
| Android keystore and passwords | Build secret | Android release CI process only |
| Play service-account JSON | Deployment secret | Android release CI process only |
| `INFISICAL_TOKEN` | CI control-plane secret | GitHub Actions secret only |
| Cloudflare API token | Deployment secret | Worker deployment environment only |

## Infisical layout

Use separate folders and machine identities with least-privilege access:

- `/runtime/opensymbols-proxy`: `OPENSYMBOLS_SECRET`; synchronize this folder
  directly to the Worker. Client build identities must not read it.
- `/ci/android`: signing keystore, signing passwords, Play service account, and
  the Aptabase app key. Only the Google Play workflow identity may read it.
- `/ci/worker`: Cloudflare deployment credentials if deployment is later
  automated. The Worker runtime itself does not need these.

The proxy URL is not confidential and can be stored in normal build
configuration. Azure BYOK credentials do not belong in Infisical because each
user supplies and owns their own key.

The CI security check rejects known server-secret environment names in client
code and secret-like iOS plist keys. This complements secret scanning; it does
not make an intentionally embedded value safe.
