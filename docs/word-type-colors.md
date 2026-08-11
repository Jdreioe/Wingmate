# Word-type colors

Wingmate can automatically apply Fitzgerald-style colors to Screen buttons. The
feature is off by default, so existing boards keep their appearance until it is
enabled in Display settings.

Color precedence is deliberately simple:

1. A button's explicit OBF `background_color` always wins.
2. A manually selected word type (`ext_wingmate_word_type`) is used next.
3. Otherwise Wingmate conservatively infers the type from the button label and
   locale. English and Danish are supported; unknown words and locales retain the
   normal theme color.

Manual word types and explicit colors are stored in OBF/OBZ exports. Inferred
colors are presentation-only, so enabling a scheme never rewrites author data.
The generated palette uses black text and meets WCAG AA contrast for normal text.
