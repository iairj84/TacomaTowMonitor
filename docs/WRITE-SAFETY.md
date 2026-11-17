# Write-safety spec (not yet implemented)

The explorer is read-only today. This document specifies what a write path would
have to do before it may exist, so the rules are agreed while there is nothing
to lose — not argued afterwards with a dead module on someone's driveway.

Two principles drive everything below:

1. **A refusal is worth more than a warning.** A rule enforced in code is a
   defence; a rule written in a document is a liability with good intentions.
2. **The user's choice is real, but only if it is an informed one.** "At the
   user's discretion" means nothing if the app made the dangerous path the
   easy one.

Not legal advice. This is engineering policy plus the wording that policy needs.

Reads are not writes and this document does not govern them: asking the truck
for stored, pending or permanent trouble codes (modes 03/07/0A, MIL via 01 01)
changes nothing and needs no safeguard from this spec. Clearing codes (mode 04)
is a write like any other and inherits every refusal rule below.

---

## 1. Refusal rules — absolute, not settings

These are checked before any write and cannot be overridden by a preference, a
flag, an intent extra, or a debug build.

### Refuse by function (protected blocks)

Never write to any block belonging to:

- occupant restraint / airbag / SRS
- ABS, VSC, TRAC, or any braking or stability controller
- electric power steering
- immobiliser, certification, or smart-key security services
- CAN gateway or network configuration
- instrument cluster odometer or mileage storage
- engine or transmission control (calibration, injector coding, adaptations)

Address list is a starting point and must be confirmed per platform before
implementation; **the function list is the authority, not the address list**.
If a header is not recognised as safe, it is refused.

### Refuse by entry state

A write is refused unless all of these are true:

| Condition | Why |
|---|---|
| Entry has `verified` provenance for **this** calibration | An unverified byte is a guess |
| A stored backup of the full block exists and parses | Restore path must exist before the write |
| The backup was taken in this session, with the engine off | Stale values hide intervening changes |
| The block re-read immediately before the write matches the backup | Otherwise something else changed it |
| Exactly one LID and one byte is being written | No batches, ever |
| Vehicle speed reads 0 and has read 0 for the last 5 samples | A write while driving is indefensible |
| Battery voltage ≥ 12.0 V | Interrupted writes are how modules die |
| The adapter is answering cleanly (no `CAN ERROR`, no `?`) | A degraded link must not carry a write |
| The block never drifted across idle scans with nothing changed | A block that moves on its own is live data, not a setting — a byte write would send back a stale copy of its live bytes |

### Refuse by situation

- Engine running: refuse for body modules (state the reason; do not silently proceed).
- Adapter is a known-unreliable clone: refuse writes entirely, explain, suggest
  a specific class of adapter.
- The same LID was written in the last 60 seconds: refuse with "wait, then re-read".
- The module returned `7F` to the read immediately before the write: refuse.

### Refusal is never silent

Every refusal shows what was refused, which rule fired, and what the user can do
about it. A refusal the user cannot understand reads as a bug and gets worked
around.

---

## 2. Warning text (exact strings)

Short, specific, plain. No "could potentially cause issues in some cases".

### Before the first write in a session — required acknowledgement

> **This changes a setting in your truck's body computer.**
>
> If the value is wrong for your vehicle, or the write is interrupted, a module
> can be left in a state that only a dealer can recover. That includes doors that
> will not lock, lights that will not switch off, and warning chimes that cannot
> be silenced.
>
> This app knows the value was correct on a 2019 TRD Off-Road with calibration
> `{saved_cal}`. Your truck reports `{current_cal}`.
>
> Your original value is saved and can be written back at any time.
>
> Nobody can promise this will work on your truck. If that is not acceptable, do
> not continue.

Acknowledgement: a checkbox **plus** typing the block LID (for example `14`).
A single tap is not consent for this.

### Per-write confirmation

> Changing **{setting}** from `{before}` to `{after}`
> in block `0x{lid}`, byte `{index}` (bit `{bit}`) on the {ecu_name}.
>
> Original value `{before_hex}` is stored. Restore is one tap away.

### Unverified entry, read-only display

> This setting was observed on a different calibration (`{entry_cal}`), not on
> yours. It is shown for reference and cannot be written until it is verified on
> your truck.

### Refused — protected block

> **Refused: safety system.** This block controls {airbag / braking / steering /
> immobiliser}. This app never writes to safety systems, and no setting can
> enable it.

### Refused — preconditions not met

> Not writing: {reason}. Nothing was sent to the vehicle.

### After writing — confirmation failed

> The module did not confirm the change. **Do not repeat it.**
>
> Re-read the block. If the value is unchanged, your truck probably does not
> support this setting — the factory tool behaves the same way on unsupported
> options. If the value changed but the behaviour did not, the setting is stored
> but takes effect after an ignition cycle.

### Restore

> Write `{before_hex}` back to block `0x{lid}`? This returns the byte to the value
> recorded at {timestamp}.

---

## 3. Interaction rules

- **Two-step, deliberate.** Pick the setting, read the current value, choose the
  new value, confirm with a typed LID. No swipe-to-apply, no "apply all".
- **Read immediately before writing.** Not from cache, not from the last sweep.
- **Show the diff you are about to cause**, in the same before/after form the
  read-only explorer already uses.
- **One write per confirmation.** After the write, the flow ends — the user must
  start again for the next setting.
- **Never offer a "write all" or "restore all" control.** It converts a series of
  deliberate decisions into one accident.

---

## 4. Backup and restore contract

Before the first write to a block, store:

- full block payload **as read**, not as parsed
- header, LID, timestamp, adapter name, app version
- the vehicle profile in force (year, trim, calibration)
- the raw request and raw reply

Rules:

- A backup is **never overwritten** by a later one for the same block; keep the
  oldest and newest with their timestamps. `CustomizeSnapshotStore` implements
  this as two slots: the first snapshot stored is also kept as the original
  (the restore value) and never overwritten, while every save updates the
  latest (the value the reread-match check compares against).
- Only blocks that answered are stored: a backup cannot hold what would not
  read, so restore coverage equals read coverage — an unreadable block has no
  restore path and therefore no write path.
- A write without a parseable backup is impossible, not discouraged.
- Restore is a normal write and passes through every rule in section 1.
- Backups export with the mapping JSON, so a restore value outlives the phone.

---

## 5. Audit log

Append-only, local, exportable. One line per attempt, successful or not:

```
2026-11-16T10:22:41Z  750  3B 14 27  ->  7B 14  OK   cal=3A2B1C  backup=2026-11-16T10:22:38Z
2026-11-16T10:24:02Z  7A0  3B ...     ->  refused: safety system
```

This exists for three reasons: support ("what did it actually send"), the user's
own records, and evidence that the app behaved as specified.

---

## 6. What the terms must say

- No warranty of any kind; the app is a tool, not a diagnosis.
- **The user, not the app, chooses to write.** State plainly that values are
  observations from other vehicles and may be wrong for theirs.
- Not for safety systems, emissions controls, or odometer-related functions —
  and say that the app refuses those by design.
- Limitation of liability capped at the price paid, **with personal injury and
  property damage from gross negligence explicitly not limited** — a clause that
  pretends to cover everything is the one a court throws out.
- Governing law and venue stated; a dispute path (email first) written down.

And in exchange, the marketing must never claim:

- "safe", "verified for your truck", "dealer-level", or "reversible" — unless the
  backup genuinely makes it reversible, which is a claim about a specific change
  on a specific block, not a general promise.

---

## 7. Implementation order, if it ever happens

1. Refusal policy as a pure function with tests against the table above.
2. Backup/restore, exercised on reads only (no writes) — prove the restore path
   reconstructs a block byte-for-byte.
3. A single hard-coded write to **one** verified LID on the author's own truck,
   with the audit log enabled.
4. Only then anything user-selectable.

Step 3 is where the project either has a verified restore path or discovers it
does not. Everything before it is preparation; everything after it is exposure.

---

## 8. Diagnostics console: the gate that already exists (v0.9.1)

The raw console takes free-text header and command, so "never writes" has to
be enforced, not promised. `WritePolicy.consoleRefusal` is an **allowlist of
read services** — 01, 03, 07, 09, 0A, 19, 1A, 21, 22, 3E — not a blocklist. A
blocklist has to be complete to be safe, and this one was not: 14 (clear
codes, UDS form), 11 (ECU reset), 27 (security access) and 85 (DTC control)
all passed, as did `AT PP … SV` and `AT @3`, which write the adapter's own
memory and outlive the session.

The service is checked **after any gateway sub-target is stripped**. A gateway
request is `<sub> <PCI> <service> …`, so `40 05 3B 14 27` is a write to Main
Body; reading its first byte as a service number made it look harmless.

Also refused: AT commands that reset or re-protocol the adapter mid-session
(Z, WS, D, SP, PC), with spaces stripped first so `AT Z` is treated like
`ATZ`; embedded line breaks, which would smuggle a second command past the
ELM; and malformed headers or non-hex commands. Other AT commands are
adapter-local and cannot write the truck, so reads like ATI and ATRV stay
available for diagnosis.
