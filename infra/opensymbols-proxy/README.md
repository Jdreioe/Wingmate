# OpenSymbols proxy

This Cloudflare Worker keeps `OPENSYMBOLS_SECRET` out of every Wingmate client.
It accepts a validated symbol search, obtains a short-lived OpenSymbols token
server-side, forces safe search, and rate-limits callers without storing their
IP addresses.

## Deploy

1. Install dependencies and authenticate Wrangler:

   ```bash
   cd infra/opensymbols-proxy
   npm install
   npx wrangler login
   npm run deploy
   ```

   The first deployment can succeed without the secret, but returns HTTP 503
   until the next step is complete.

2. In Infisical, create an `OPENSYMBOLS_SECRET` secret in the production
   environment. Add a **Cloudflare Workers Secret Sync**, select the
   `wingmate-opensymbols-proxy` script, and map only that secret. Enable
   automatic synchronization.

3. Rotate the OpenSymbols credential that was previously shipped in the iOS
   plist, then update its value in Infisical. Do not place the replacement in
   this repository, GitHub Actions, an app build setting, or `local.properties`.

## Configure clients

The URL is public configuration, not a secret. Use the deployed Worker origin
without the `/v1/opensymbols/search` suffix.

- Android: set `WINGMATE_OPENSYMBOLS_PROXY_URL=https://...workers.dev` in
  `local.properties` or the build environment.
- iOS: set `OPEN_SYMBOLS_PROXY_URL` in `Configuration/Config.xcconfig`. Xcode
  config files require the slash escape form, for example
  `OPEN_SYMBOLS_PROXY_URL = https:/$()/example.workers.dev`.

For local client testing, `http://localhost` and loopback addresses are
accepted. Production client URLs must use HTTPS.

## Verify

```bash
npm test
npm run check
```
