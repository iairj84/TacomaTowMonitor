# Notes

## 2026-11-17 — Dashboard visual pass

Why: the phone dashboard read as a generic Material card kit — every section
had the same border, every label shouted in all caps, and the two trans
gauges were identical fills with no sense of where WARN/CRIT sit on the
needle. Glanceability at a dash mount matters more than decoration.

Decisions:
- One memorable element: the tow-status banner ("All clear / Ease off /
  Stop — cool down") with a single color dot, instead of a status pill on
  every card.
- Trans gauges show their working band as three segments (ok / ease off /
  stop) over 100–crit+20 °F, with plain-language ticks ("Ease off 220")
  instead of WARN/CRIT codes. Converter shows its delta over pan
  ("+12° over pan"), the signal that matters on a climb.
- Secondary readings (coolant, rpm, oil, speed) collapsed into one divided
  telemetry strip — one card, not four.
- Sentence case everywhere except the brand line; tightened label tracking
  in Theme (1.4→0.4sp) so captions stop shouting.
- Log trip is now the filled primary button; Tow mode is outlined. Helper
  copy shortened to one line.
- Numbers moved from accent-colored to on-surface with a medium-weight
  display face, so a WARN/CRIT state is carried by the banner, pill, and
  band — not by the whole numeral changing color at a glance.

Kept: charcoal/mint/amber palette, dark-first theme, all polling/logging
logic untouched. Verified with `./gradlew assembleDebug` and
`./gradlew testDebugUnitTest` (both pass, offline).
