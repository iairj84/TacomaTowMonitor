# Guarded write-path design — from the 12-setting mapping (no write code)

Status: RETIRED in v0.9.32 — the send path is deleted and the app is read-only again (a dedicated customize app covers settings changes). Kept as the record of what the mapping observed. Original status: phase 1 implemented (v0.9.31). `WritableCatalog` (vehicle-matched, single-bit, allowlisted, 0x11 held) + `Elm327Client.writeGatewayLid` (single-frame, 7B/7F-3B-NRC parsed) + `ObdRepository.writeKnownByte` (engine-off reread, RPM/speed/volts live checks, backup-first, cooldown, audit) + one in-app sender (safest setting only: 750/0x40 0x14 byte 0 bit 3). `WRITE-SAFETY.md` + `WritePolicy` still govern; nothing here authorizes a second setting.

## 1. Source mapping (owner-observed, 2019 TRD Off-Road, cal 896)

12 unique transitions, engine RUNNING / parked, one radio setting per there-and-back confirm:

| Setting | Header | LID | Byte | Before → After | XOR (bits) |
|---|---|---|---|---|---|
| Auto relock 120 → 60 | 750/0x40 | 0x14 | 1 | 66 → 65 | 0x03 (bits 0–1) |
| Auto relock 120 → 30 | 750/0x40 | 0x14 | 1 | 66 → 64 | 0x02 (bit 1) |
| Auto relock 120 → off | 750/0x40 | 0x14 | 1 | 66 → 60 | 0x06 (bits 1–2) |
| Remote two-press unlock on → off | 750/0x40 | 0x14 | 1 | 66 → 26 | 0x40 (bit 6) |
| Lock/unlock feedback lights on → off | 750/0x40 | 0x14 | 0 | 9A → 92 | 0x08 (bit 3) |
| Auto door lock: shift-from-P → by speed | 750/0x40 | 0x11 | 1 | 06 → 0A | 0x0C |
| Auto door lock: shift-from-P → off | 750/0x40 | 0x11 | 1 | 06 → 02 | 0x04 |
| Auto door unlock: shift-from-P → by driver door | 750/0x40 | 0x11 | 1 | 06 → 05 | 0x03 |
| Auto door unlock: shift-from-P → off | 750/0x40 | 0x11 | 1 | 06 → 04 | 0x02 |
| Doors to unlock: all → driver | 750/0x40 | 0xAB | 0 | 06 → 05 | 0x03 |
| Auto A/C mode on → off | 7C4 | 0x81 | 0 | 00 → 40 | 0x40 (bit 6) |
| Headlamp auto-off timer 30 → 60 | 750/0x40 | 0x15 | 3 | 40 → 80 | 0xC0 (bits 6–7) |

Provenance: year 2019, trim off road, calibration 896 on every entry. A `verified` flag is therefore only meaningful for that exact calibration; anything else is reference-only until re-observed.

## 2. Shared-byte risks (the reason a byte write is never just a byte)

**Risk A — 0x14 byte 1 carries two settings.** Relock timer lives in the low bits (0–2 range from the four observations) while two-press unlock is bit 6 of the same byte. Writing `66 → 65` for relock while carrying a stale bit 6 silently flips two-press. Mitigation: read-modify-write at bit granularity — re-read the block, patch only the timer bits, preserve bit 6, send the whole block.

**Risk B — 0x14 byte 0 vs byte 1: same LID, different settings.** Feedback lights (byte 0, bit 3) and relock/two-press (byte 1) share LID 0x14. Service `3B` rewrites the whole block, so a byte-1 write must carry byte 0's current value. A cached byte 0 is a corruption vector; the pre-write re-read must supply every byte being sent, not just the target byte.

**Risk C — 0x11 byte 1 is one enum, not two bitfields (highest risk).** Lock and unlock transitions all start from base `06` with four different XORs (`0x0C/0x04/0x03/0x02`). There is no clean bit split: changing lock without knowing unlock's current half of the enum rewrites unlock. Only 4 combos from base `06` are mapped; combinations such as by-speed + by-driver-door are unobserved. Rule: **no write to 0x11 until the full matrix is mapped** — every reachable whole-byte value from real radio states, with each value labeled by both its lock and unlock meaning.

## 3. Allowlist risks (refusals that must stay refusals)

`WritePolicy.ALLOWED_LIDS` is `0x01..0x3F`. Two of the twelve mapped settings fall outside it and are refused today:

- 750/0x40 `0xAB` (171) — doors to unlock.
- 7C4 `0x81` (129) — auto A/C mode.

Widening is per-LID verification, never a range bump: each block must be proven a stored setting (confirms there-and-back, never drifts across idle scans, full payload captured), and the header allowlist (`750`, `7C0`, `7C4`) plus the gateway-sub rule (750 requires sub `0x40`) stay as-is. The function list in WRITE-SAFETY.md §1 remains the authority — no airbag/braking/steering/immobiliser/gateway-config/odometer/powertrain LID enters the allowlist regardless of observation.

## 4. Frame-size gate (still unproven)

Single-frame `3B` needs payload ≤ 5 bytes (ELM327 does not auto-segment transmits); the mapping records only the changed byte, not full block lengths. Before any write candidate is admitted: capture and store the full payload per LID (`0x14`, `0x11`, `0x15`, `0xAB`, `7C4 0x81`), record byte length, and mark any block over the single-frame limit as multi-frame — excluded from phase 1 with no segmentation workaround.

## 5. Guardrails carried forward (unchanged, restated for this mapping)

- No `0x27` seed/key, ever (refuse on NRC `0x33`); phase 1 is single-frame blocks only.
- Map engine-on, write engine-off. Same-value write first as zero-risk proof (send current value back, expect `7B`, confirm no change), then one real setting on the author's own truck.
- Every write: verified-for-this-calibration, same-session engine-off backup that parses, pre-write re-read matches backup, exactly one LID + one byte, speed 0 × 5, volts ≥ 12.0, clean adapter, 60 s cooldown, block never volatile. Restore passes through the same gate.
- Refusals name the rule and the remedy. No write-all / restore-all. Append-only audit log (request bytes, NRC, calibration, backup timestamp) local and exportable.
- Interaction: pick setting → re-read current → choose new value → typed-LID confirm → one write → flow ends.

## 6. What is still missing (no code until these exist)

1. Full payloads + lengths for the five LIDs above (single-frame proof).
2. Full `0x11` matrix with lock+unlock labels per whole-byte value.
3. Idle-drift verdict per block (three no-change scans; any drift = live data = no write).
4. Same-value `3B` transmit + NRC decoding spike results (`7B` vs `7F 3B <NRC>`, `0x78` wait handling) on the author's truck only.
5. Per-LID admission of `0xAB` and `0x81` after (3), or they stay read-only.

## 7. Phase order

1. This doc (done) — no code.
2. Payload-length + drift survey on the five LIDs — reads only.
3. `0x11` matrix completion — reads only.
4. `3B` transmit + NRC spike, same-value first, author's truck, engine off — first and only write exposure.
5. Single real setting (safest single-bit candidate, e.g. `0x14` byte 0 bit 3) behind the full gate — only if 1–4 pass.
