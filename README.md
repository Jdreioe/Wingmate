# Wingmate

Wingmate is a free and open-source AAC application for people who cannot rely
on natural speech. It is developed by Jonas, who has cerebral palsy and draws
on his own experience with communication devices. It supports device voices
plus bring-your-own-key Azure and Google Cloud Text-to-Speech.

Wingmate has two peer communication workspaces. **Typing** supports free text,
predictions, and saved phrases. **Screens** supports visual vocabulary arranged
across linked Pages using OBF and OBZ.

Wingmate ships on Android and iOS. The paused desktop client and its native
gaze work are preserved at the [`desktop-archive`](https://github.com/Jdreioe/Wingmate/tree/desktop-archive) tag.

See [current capabilities](docs/CAPABILITIES.md) and
[supported platforms](docs/PLATFORM_SUPPORT.md).

For pointer, switch, head-tracking, and eye-tracking setup, see
[Pointer input and Rest mode](docs/HEAD_EYE_TRACKING.md).

## Export to Smartbox Grid 3

Choose **Export → Export to Grid** in a Screen's menu on Android or iOS to share
a `.gridset` file directly from Wingmate. In Grid Explorer, use
**Add Grid Set → Grid set file** to import it.
[Smartbox's Grid Explorer guide](https://hub.smartboxaac.com/knowledgebase/grid-explorer-in-grid-3/)

The exporter carries over linked Pages, grid positions and spans, labels,
vocalizations, colors (including word-type colors), common message controls,
effective speech and return settings, and supported PNG, JPEG, GIF, BMP, WebP,
WAV, MP3, M4A, and WMA media. With the Message bar enabled, it adds a Grid chat
row above the vocabulary. Grid plays supported recordings on selection and uses
text for composed messages. Wingmate prediction, Page elements, background
pictures, and per-button voice settings do not transfer.

## OpenSymbols proxy

OpenSymbols search is routed through the Cloudflare Worker in
[`infra/opensymbols-proxy`](infra/opensymbols-proxy). Infisical syncs the
OpenSymbols shared secret directly to the Worker; the Android and iOS apps
receive only its public URL. See the proxy README for deployment and
client configuration. The handling rules for other credentials are documented
in [`docs/SECRET_BOUNDARIES.md`](docs/SECRET_BOUNDARIES.md).

License: GPL 3.0

Credits:

**Logo:** Anna Thaulov
**Name idea:** Jeppe Forchmann's awesome documentary **_Wingman_** 
