# Wingmate

Wingmate is a free and open-source AAC application for people who cannot rely
on natural speech. It is developed by Jonas, who has cerebral palsy and draws
on his own experience with communication devices.

Wingmate has two peer communication workspaces. **Typing** supports free text,
predictions, and saved phrases. **Screens** supports visual vocabulary arranged
across linked Pages using OBF and OBZ.

See [current capabilities](docs/CAPABILITIES.md) and
[supported platforms](docs/PLATFORM_SUPPORT.md).

For pointer, switch, head-tracking, and eye-tracking setup, see
[Pointer input and Rest mode](docs/HEAD_EYE_TRACKING.md).


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
