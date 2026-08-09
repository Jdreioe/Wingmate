# Wingmate
Wingmate is a Free and Open Source Software (FOSS) project aimed at providing an exceptional voice for people who cannot speak, using Azure Neural Voices.


## About the project
Wingmate is developed by Jonas, who has Cerebral Palsy (CP) and extensive experience with various speech devices. The current goal is to offer a high-quality, affordable communication solution that can be built cross platform using KMP.

## Features:
For a list of features, look at FEATURES.md

PhraseScreen is the screen, I primarily have used.
OBF/OBZ is still work in progress

See [Supported platforms](docs/PLATFORM_SUPPORT.md) for the client support and
feature-parity policy used when planning and accepting new work.


## OpenSymbols proxy

OpenSymbols search is routed through the Cloudflare Worker in
[`infra/opensymbols-proxy`](infra/opensymbols-proxy). Infisical syncs the
OpenSymbols shared secret directly to the Worker; the Android, iOS, and Linux
apps receive only its public URL. See the proxy README for deployment and
client configuration. The handling rules for other credentials are documented
in [`docs/SECRET_BOUNDARIES.md`](docs/SECRET_BOUNDARIES.md).

License: GPL 3.0

Credits: 

**Logo:** Anna Thaulov
**Name idea:** Jeppe Forchmann's awesome documentary **_Wingman_** 
