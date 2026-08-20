# Google Cloud Text-to-Speech (BYOK)

Wingmate can use a Google Cloud API key supplied by the user on Android, iOS,
and Linux. Wingmate does not provide a shared Google credential or proxy speech
through a Wingmate server.

## Setup

1. Create or select a Google Cloud project with billing configured.
2. Enable the Cloud Text-to-Speech API.
3. Create an API key and restrict its API access to Cloud Text-to-Speech.
4. On Android or iOS, add the matching Android application or iOS bundle
   restriction. Wingmate supplies the required application identity headers.
5. In Wingmate, open Settings → Speech, select Google, and enter the key.
6. Open voice selection and refresh the catalog.

Google recommends both API and application restrictions. A locally installed
Linux desktop application cannot strongly protect a client-side key with an
application restriction, so Linux users should use a dedicated restricted key,
set a conservative quota/budget alert, and monitor usage.

## Credential and phrase handling

- The key is stored separately from Azure credentials using Android Keystore,
  iOS Keychain, or Linux Secret Service/KWallet.
- Wingmate status APIs expose only whether a key is configured.
- Keys are excluded from `.wingmate-backup` archives and diagnostics.
- The key is sent in the `x-goog-api-key` header, never in a URL.
- Credential-bearing requests use the fixed
  `https://texttospeech.googleapis.com` authority and do not follow redirects.
- Text is sent to Google only when Google Cloud is the selected speech engine.

System text-to-speech remains available when no key is configured, the device
is offline, or Google synthesis fails. Google usage, quotas, billing, retention,
and availability remain governed by the user's Google Cloud project and terms.

References: [Text-to-Speech synthesis API](https://cloud.google.com/text-to-speech/docs/reference/rest/v1/text/synthesize),
[voice list API](https://cloud.google.com/text-to-speech/docs/reference/rest/v1/voices/list), and
[Google API key best practices](https://cloud.google.com/docs/authentication/api-keys-best-practices).
