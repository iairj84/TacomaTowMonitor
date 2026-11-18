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

Follow-up (same day, v0.9.24): the band as shipped had no marker, so it read as
an unfilled progress bar — "196 °F" next to a mostly-green bar says nothing
about how close that is to a limit. Added a tick at the value's position and
replaced the evenly spaced 100 / Ease off / Stop ticks, which implied
positions the zone edges do not have (SpaceBetween, not the real fractions),
with one caption naming both thresholds. Verified with
`./gradlew assembleDebug` and `./gradlew testDebugUnitTest` (both pass,
offline).

## 2026-11-18 — Explorer answers instead of triaging

Why: five movers and five Record/Skip decisions put the work on the driver —
the app already ranks movers (single-bit first), so it should just say which
one is yours.

Decisions:
- Step 3 shows one answer card (top-ranked mover) with Save + "Not this"
  cycling to the next candidate; other movers listed as truck state.
- Removed resolved-keys, pending-change guards, Skip-all: rescans and
  handoffs never block. Baseline still rolls forward every scan.
- Saving writes records for the shown answer only, stamped with the Step 3
  label and the truck profile.

## 2026-11-18 — There-and-back confirm

Why: with the engine running the body ECUs never sit still, so one before/after
read always shows a dozen movers and the learned noise mask never catches up.

Decisions:
- Scan for change is three taps: baseline (A), changed (B, held not banked),
  reverted (C). `confirmToggle(A,B,C)` keeps only blocks where the same byte
  moved A->B and returned to the exact baseline byte B->C.
- Save is gated on a confirmed block; one-time movers and idle noise sit
  behind a Details expander, never in the headline answer.
- Mid-flight state (B) lives in memory only; banking still happens on C so
  the next setting chains. Handoff return resets the toggle.
