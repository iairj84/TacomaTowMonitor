# Changelog

- 2026-11-18: Scan 3 of there-and-back re-reads only the scan-2 movers plus scan-2 dropouts instead of all 100+ blocks; unasked blocks ride scan 2 forward so they can neither confirm nor false-confirm, and the banked baseline is never shrunk by the narrowed re-read.

- 2026-11-18: Shared there-and-back report shows only the confirmed block with all three payloads, then one compressed line each for one-time movers and drift; baseline timestamp on the card no longer reads 1970 for a restored snapshot.

- 2026-11-18: Scan for change is now there-and-back (baseline, changed, put-back): only a block that returns to its baseline byte counts, one-time movers hide behind Details.

- 2026-11-18: Customize explorer names the answer itself — top-ranked mover in one card with Save / Not-this instead of per-block Record-or-Skip triage; rescans never block on unresolved movers.

- 2026-11-17: The trans gauges now carry a marker for the current temperature on the band. The old bar showed only the zones, so it read as a fill with no "you are here"; the tick labels were also evenly spaced and did not line up with the zone edges, which is what made the gauge hard to read.
- 2026-11-17: Dashboard visual pass — sentence-case labels, tow-status banner with plain-language guidance, segmented threshold bars on the trans gauges with converter delta over pan, single telemetry strip instead of mini-card grid, slimmer connection row with Log trip as the primary action.
