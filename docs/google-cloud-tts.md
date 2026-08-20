# Google Cloud Text-to-Speech (BYOK)

Wingmate can use a Google Cloud API key supplied by the user on Android, iOS,
and Linux. Wingmate does not provide a shared Google credential or proxy speech
through a Wingmate server.

## Setup

Wingmate's Welcome flow and Settings → Speech both provide the same guided
setup. It links directly to the relevant Google Cloud pages and walks through:

1. Creating or selecting a Google Cloud project with billing configured.
2. Enabling the Cloud Text-to-Speech API.
3. Creating an API key and restricting its API access to Cloud Text-to-Speech.
4. On Android or iOS, adding the application restriction shown by Wingmate.
   Wingmate supplies the matching application identity headers on requests.
5. Pasting the key into Wingmate and choosing **Save securely and verify**.

Wingmate verifies the candidate by loading the Google voice catalog before it
switches speech engines. If verification fails, the candidate is removed and
the previous credential and engine selection are restored.

## Voices and models

The voice picker only shows the catalog for the selected cloud provider. When
Google Cloud is selected, it also provides model filters for Gemini 3.1 Flash,
the Gemini 2.5 TTS models, Chirp 3 HD, Studio, Neural2, WaveNet, Standard, and
any remaining Google tiers.

Google's locale-prefixed catalog rows are combined by model and speaker. For
example, the picker shows `WaveNet F` instead of `da-DK-Wavenet-F`, and one
`Achird` entry contains every locale in which Google offers Achird for that
model. The Gemini and Chirp 3 entries remain separate even when their speaker
names match.

Gemini TTS additionally requires the authenticated principal to have
`aiplatform.endpoints.predict` (for example through Vertex AI User). A regular
API key does not represent an IAM principal; projects using Gemini TTS through
key authentication therefore need a supported service-account-bound
authorization key. This Google feature is Preview and may be unavailable to
personal projects without an organization. Traditional Google TTS tiers do not
require that additional Vertex AI permission.

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
[Gemini TTS](https://cloud.google.com/text-to-speech/docs/gemini-tts),
[Google API key best practices](https://cloud.google.com/docs/authentication/api-keys-best-practices), and
[authorization keys](https://cloud.google.com/docs/authentication/api-keys).
