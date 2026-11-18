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
- Sharing a confirmed result uses `describeToggle`, not the two-leg diff:
  the diff still warns "3 modules changed" and lists every mover, which is
  the noise the confirmed card removed. Confirmed first with baseline /
  changed / reverted payloads, then one line per group for the rest.
- First real truck run (2019 TRD, engine running): auto relock 120 -> 60 =
  0x14 @ 750/0x40 byte 1, 66 -> 65 (bits 0-1), returning to 9A 66 20. 22
  other blocks moved once; none returned.
- Scan 3 narrows to `narrowConfirmPlan` (movers + dropouts): only a block
  that moved A->B can confirm, so re-asking the ~90 quiet blocks buys nothing
  but minutes. Dropouts are re-asked rather than assumed, and `carryForward`
  pins unasked blocks to their scan-2 values so they cannot false-confirm.
  The narrowed re-read is never banked — the baseline stays the full sweep.

## 2026-11-18 — First full mapping pass (12 settings, 2019 TRD Off-Road cal 896)

Why: the owner mapped almost every radio-configurable setting in ~15 minutes
with the there-and-back flow (change one setting, confirm, revert). This is
what the data says about a future write path — no code changed, read-only
still holds.

Observed (13 entries, 1 exact duplicate 66->65 + one `::` typo = 12 unique):
- 750/0x40 LID 0x14 byte 1 holds TWO settings: relock timer (66->65 bits 0-1,
  66->64 bit 1, 66->60 bits 1-2) and two-press unlock (66->26 bit 6). A write
  to the timer must preserve bit 6 and vice versa — bit-level, not byte-level.
- 750/0x40 LID 0x11 byte 1 holds lock AND unlock as one enum (06->0A/02 lock,
  06->05/04 unlock, XORs 0xC/0x4/0x3/0x2 from the same base). Only 4 combos
  from base 06 are known; the full matrix (e.g. by-speed + by-driver-door)
  is unmapped. This LID is the most dangerous to write today.
- Same LID, two bytes: 0x14 byte 0 is feedback lights (9A->92 bit 3) while
  byte 1 is relock/two-press above. `3B` rewrites the whole block, so a
  byte write must carry the other byte's current value — stale-copy risk.
- Singles: 750/0x40 0x15 byte 3 headlamp timer 40->80 (bits 6-7), 7C4 0x81
  byte 0 auto-AC 00->40 (bit 6), 750/0x40 0xAB byte 0 doors-to-unlock 06->05.
- Policy gap by design: `WritePolicy.ALLOWED_LIDS` is 0x01..0x3F, so 0xAB
  (171) and 0x81 (129) are currently refused. Widening needs each block
  proven as stored settings (no idle drift) first — not a range bump.
- Missing for any write: full block payloads (to prove single-frame 3B,
  payload <= 5 bytes) and the 0x11 full matrix. Owner picked the design doc:
  `docs/superpowers/specs/write-path.md` now spells out the shared-byte
  risks (0x14 bit-level, 0x11 full-matrix hold), the 0xAB/0x81 allowlist
  refusals, and the phase order. Owner picked option A; v0.9.31 implements
  phase 1: vehicle-matched writable list, observed-values-only, 0x11 held
  with its matrix reason shown in-app, single-frame 3B (7B vs 7F 3B NRC),
  engine-off reread with live RPM/speed/volts checks, backup-first,
  cooldown, audit log, typed-LID + ack — starting with the safest
  single-bit setting (750/0x40 0x14 byte 0 bit 3).
