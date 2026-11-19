# Tacoma Tow Monitor — 2019 Tacoma + ELM327 + Android Auto

Phone dashboard + trip logger for towing with your 2019 Tacoma V6 (A/T),
built around the same Toyota-enhanced PIDs Torque Pro users rely on —
since Toyota doesn't expose trans temp on the cluster and Torque doesn't
ship it by default.

**What you get**
- Big tow-first gauges on the phone: **trans pan, converter-out, coolant, oil**
  + RPM / speed, adapter voltage, WARN/CRIT coloring, demo mode.
- ELM327 over **Bluetooth Classic SPP** (OBDII / ELM327 / VGate-SPP clones)
  **and Bluetooth LE** (the plug-ins Torque shows as `odbble` — VGate iCar,
  Veepeak BLE and clones — which have no classic socket at all).
  Auto init (`ATZ E0 L0 S0 H1 SP0`), per-PID header switching.
- **Tow-trip CSV logging** to app files (`tow-logs/`), one file per trip.
- **Android Auto head-unit screen** with the same 4 temps as slow-refresh
  text rows — with honest limits (below). Full gauges stay on the phone.
- **Self-healing bus watchdog.** A dongle that holds the link open but stops
  answering the truck — the cheap-clone wedge, where `AT I` still replies and
  every vehicle request returns `NO DATA` — is reset in place: a warm start
  (`AT WS`), then a cold one (`AT Z`) if that fails, so you never walk back to
  the OBD port to unplug it. Both are announced in the adapter log. It stands
  down while the dongle is handed off, and it does not fire when the adapter's
  own voltage reading says the engine is off and the ECUs are asleep.

## The PIDs (why Torque "doesn't share it")

| Gauge | Mode+PID | Header | Equation | Notes |
|---|---|---|---|---|
| Trans pan | `221627` | `701` | `(A*459/255)+(B*1.6/255)-40` (°F) | Owner-verified on 2016+ Tacoma V6 A/T |
| Converter-out | `221628` | `701` | same as above (°F) | Runs hotter than pan under load |
| Coolant | `0105` | default | `A-40` (°C) → °F | Standard SAE |
| Engine oil | `015C` | default | `A-40` (°C) → °F | Standard SAE; **often NO DATA on the 2GR-FKS** — app shows "NO DATA", not junk |
| RPM / speed | `010C` / `010D` | default | `(A*256+B)/4`, `A` km/h → mph | Standard SAE |

These trans PIDs are community-verified (TacomaWorld Torque thread), not a
Toyota publication. Celsius variant: `((A*459/255)+(B*1.6/255)-72)*5/9`.
Leave Torque diagnostic start/stop blank; scale ×1. This app has them
preloaded — no manual PID entry.

**Observed on this truck (2026-11-16).** 2019 TRD Off-Road, 3.5L V6 A/T, with a
working BLE adapter: pan **103 °F**, converter-out **115 °F** against coolant
160 °F at 25 mph / 1682 rpm. Both gauges populate from `221627` / `221628` at
header `701`.

That closes the question this project spent days on: the same build returned
nothing but `NO DATA` / `CAN ERROR` from an ODBBLE clone reporting
`ELM327 V2.1`, while basic mode 01 data streamed normally from it. The DID was
never wrong and the truck always published it — the clone's CAN layer could not
carry a manufacturer request and lock up quietly rather than answering with a
refusal (`7F`).

**Two checks that turn "plausible" into "verified".**

- *Cold soak.* Park overnight, ignition on before starting, read the pan before
the engine runs. It should sit within a couple of degrees of ambient air
temperature. That validates scale and offset far better than any warm reading.
- *Load.* A long climb should push converter-out clearly above pan, then let
them converge at steady cruise. If they behave the other way round, the two
DIDs are mapped to the wrong gauges.

**Tow thresholds (defaults, editable in `TacomaPids.kt`)**
pan WARN 220 / CRIT 240 °F · converter WARN 230 / CRIT 250 °F ·
coolant WARN 223 / CRIT 235 °F. Rule of thumb: pan past 220 back off,
past 240 stop and cool with the engine running.

## Build & run (you have everything on this machine)

Your SDK is at `C:/dev` (`local.properties` already points there) and
Android Studio is at `C:/dev/android-studio`.

1. Open this folder in **Android Studio** → let it sync
   (Gradle 9.3.1, AGP 8.13.1, Kotlin 2.2.21, compile/target 35, min 26),
   or build from the command line with `./gradlew assembleDebug`
   (APK lands in `app/build/outputs/apk/debug/`).
   Fresh clones need a `local.properties` with `sdk.dir` pointing at your
   SDK (e.g. `sdk.dir=C\:/dev`); Android Studio normally writes this for you.
2. Classic dongles: pair in **system Bluetooth** first (PIN `0000` or `1234`,
   names like `OBDII` / `ELM327` / `VGate`), ignition **ON** (engine running best).
   BLE dongles (`odbble` and friends): **no pairing** — open the app, tap
   Connect, hit **Scan for BLE adapters**, tap yours when it appears.
3. `Run ▶` the `app` config on your phone. Tap **Connect ELM327**,
   pick the paired dongle. First sweep takes ~5 s (header flips to `701`).
4. No adapter handy? Tap **Demo** — simulated tow climb to learn the UI.
5. **Tow mode** keeps temps and alarms on with the screen off (foreground service);
   **Log tow trip** writes `files/tow-logs/tacoma-tow-<stamp>.csv`.

If the app was NOT built on this machine yet there is no APK checked in —
open in Studio and Run; that produces the APK.

## Android Auto — honest limits (please read)

A continuously-updating custom gauge dashboard is **not a supported
Android Auto experience**. The Car App Library offers Navigation / POI /
IoT / Weather + host-rendered List/Pane/Grid templates — there is no
vehicle-health / OBD / custom-canvas category, and sideloading does not
unlock one. `unknown sources` explicitly does not apply to car-app-library
apps; distribution is via Play review or internal test tracks.

What this project does about that:
- `TacomaCarAppService` declares the closest supported category (**IOT**,
  framed as "connected-dongle readout") and renders one `PaneTemplate`
  with the 4 temps, refreshed at **5 s** via `invalidate()`.
- Expect **Play review to reject** a public listing. For *your* truck, use
  **internal app sharing / an internal test track** and the **Desktop Head
  Unit (DHU)** for development.
- Mount the phone for real gauges; treat the car screen as a slow
  at-a-glance readout.

**DHU test:** install the debug APK on the phone, start the DHU
(`C:/dev` SDK → `extras` if present, else Studio's DHU launcher),
`adb forward tcp:5277 tcp:5277`, open the app on the phone once
(so the OBD loop runs), then find *Tacoma Tow Monitor* in the DHU.
If you want a pixel mirror of the phone instead, that's a separate
screen-mirroring-app path (Fermata / Screen2Auto style), outside Play
policy and outside this codebase.

## Troubleshooting

- **Clone won't connect:** kill Torque (it holds the connection), ignition ON.
  Classic: unpair/re-pair in system Bluetooth. BLE (`odbble`): don't pair — scan
  from inside the app; if the scan finds nothing, the dongle may still be
  connected to Torque or your radio, so force-close those first.
- **The phone asks to pair with the dongle:** it should not. A BLE pick never
  opens an SPP socket, and the classic transport skips an unpaired device, so
  pairing only ever happens in Android's Bluetooth settings (PIN `0000`/`1234`).
  That prompt came from the app trying classic on a BLE dongle.
- **Connected but every gauge says No data:** the adapter answers AT, the truck
  does not. On a dual-mode dongle one radio can carry ELM text and no CAN
  traffic; the app now tries the other radio before settling, and accepts a
  silent link only when the adapter reads engine-off volts (< 12.9 V). If it
  still sits blank with the engine running, share the adapter log.
- **Trans shows NO DATA but coolant works:** header/ECU mismatch. Confirm the
  dongle answers `AT SH 701` + `221627` in a terminal app; some 4-cyl / manual
  trucks never supported these PIDs.
- **Oil always NO DATA:** normal on the 2GR-FKS. The app keeps the row so you
  know it checked.
- **AA icon missing in the car:** car-app-library apps only appear via proper
  distribution (internal track) — not `unknown sources`. Verify in the DHU first.
- **Poll slow:** normal — ~120 ms between PIDs + 600 ms sweep pause to keep
  the ELM bus stable with header flips.

## Files

```
app/.../obd/TacomaPids.kt      PID table + thresholds
app/.../obd/Elm327Client.kt    SPP socket, ATZ init, header flips, parsers
app/.../obd/ObdRepository.kt   poll loop, WARN/CRIT, CSV logging, demo mode
app/.../MainActivity.kt        phone dashboard (Compose) + BT picker
app/.../car/                   AA service + 5 s Pane screen
```

Tow safe. Watch the pan on long grades — that A/T will thank you.

## Self-updates

On launch the app asks GitHub for the latest release of this repository and, if
the tag is newer than the installed `versionName`, shows an **UPDATE AVAILABLE**
card with a Download button. The APK is fetched into the app cache, its SHA-256
is checked against the hash published in the release notes, and only a match is
handed to the system installer via a `FileProvider` scoped to `cache/updates/`
only; the first install from the app needs "install unknown apps" allowed for
this app. A release with no hash in its notes, or bytes that do not match the
hash, is deleted and never offered for install.

This reads the public GitHub API, so **the repository has to be public** for the
check to work. On a private repository the API answers `404` and the app says so
in a single muted line instead of failing silently. There is no embedded token,
by design: a token shipped in an APK is not a secret.

## Where the data comes from (and what this is not)

This project is independent. It is not affiliated with, endorsed by, sponsored
by, or derived from Toyota or any commercial diagnostic product, and it ships no
third-party table, database or extracted asset.

**What it does.** It talks to the owner's own vehicle over the standard
diagnostic connector using published standards: SAE J1979 for modes 01 and 09,
and the ISO 15765 / ISO 14229 service families for the Toyota local-identifier
reads (`21 <LID>`) used by the customize explorer. No protection measure is
circumvented and no system the owner does not own is accessed. The current
build is read-only: it sends no write to the truck (`WritePolicy` still
refuses dangerous commands typed into the raw console; the retired write
experiment is recorded in docs/superpowers/specs/write-path.md).

**Where mappings come from.** A block is read, the owner changes one setting
using whatever they already have (the factory radio's own customization menu, a
dealer tool, or a phone app they paid for), the block is read again, and the two
are diffed. The artefact is the owner's measurement of their own property: a
plain-text note of what was changed, an ECU address, a byte index, and the values either side. Every entry is
stamped with model year, trim and calibration so another owner can judge whether
it applies to their truck.

**What is deliberately absent.** No vendor software was decompiled, no vendor
database was extracted, and no proprietary table is reproduced here — not in the
source, not in the build, not in the release notes. There is no built-in list
of settings anywhere in the app: the name of a change is whatever the owner
types, in their own wording.

**For contributors.** Only first-hand observations of a vehicle you have access
to, with the year, trim and calibration recorded. Do not paste a vendor's table,
screenshots of a vendor's UI, or anything extracted from vendor software; those
pull requests are declined, not cleaned up. There is no built-in list of
settings to pick from: the name of a change is whatever the owner types, so a
checklist can never pass off a guess as a fact.

Product and company names remain the property of their owners and are used, when
at all, only to describe compatibility.

## Releases — do this for every user-visible change

The in-app updater only ever sees GitHub releases, so a version that is built but
not released simply never reaches the phone. That makes releasing part of
finishing the work, not an optional extra. Every user-visible change ships:

1. Bump `versionCode` (always +1) and `versionName` in `app/build.gradle.kts`.
   The `versionCode` must increase every time or Android refuses the install.
2. Build: `./gradlew assembleDebug`. Never release a build that did not compile.
3. Copy the APK out as `TacomaTowMonitor-vX.Y.Z-debug.apk`.
4. `git add` the sources and `app/build.gradle.kts` — **not** the APK. The APK is
   a release asset, never a file in the tree.
5. Commit with a message that says why it changed, then `git push origin main`.
6. `gh release create vX.Y.Z --title "vX.Y.Z — <one line>" --target main` with
   notes and the APK attached; include the APK's SHA-256 in the notes.
7. Verify the exact endpoint the app calls:
   `curl -s https://api.github.com/repos/iairj84/TacomaTowMonitor/releases/latest`
   must return the new tag **and** an `.apk` asset. A release without an asset is
   an update the user cannot install.

## Sharing the adapter log

The adapter log card has **Copy** and **Share** buttons. Both produce the same
text: app version and build, phone model and Android version, adapter name and
connection state, protocol, adapter voltage, then the log lines verbatim. A bare
`NO DATA` line is useless without that header, so the context travels with it.

## Contributing

**A bug report is only as good as its log.** Tap **Share** on the adapter log card
and paste the result. Raw replies (`NO DATA`, `7F 22 31`, `SEARCHING...`) are
the difference between a diagnosis and a guess — see "The PIDs" above for why.

**For a protocol bug, send a transcript instead.** Diagnostics → **Record
transcript** captures every command with the window it was sent with and the
exact reply, then shares it as text. Paste it into an issue and it becomes a
test: `app/src/test/resources/transcripts/` holds these files, and
`ReplayTransport` feeds one back through the real client, strictly, in order.
A bug that arrived as 'show me the reply' then stays fixed — every shape this
project got wrong (a 9-byte block read as 4, a packed `7F` taken as ours, `78`
ending a read) is exactly what such a file preserves.

One trimming step first. A live recording is the whole session — it starts
wherever Record was tapped, and it keeps every polling cycle after the reads
that matter. Because replay is one ordered stream, cut it down to the window
the test will drive: from the empty-line sync and `ATI` if the test calls
`connect()`, or from the first `AT SH`/`ATCEA` of the target under test. A file
that begins mid-poll will fail on its first entry rather than mislead anyone.

**Welcome:**

- **PID definitions for other years and engines.** Include the model year, cab
  and engine, the request, the raw reply, and the temperature it should have
  shown on a known-cold or known-hot transmission. A PID that "works" without a
  reference reading is not verified.
- **Adapter quirks.** If a dongle needed a different init order, a different
  write characteristic, or a protocol it refused, that is genuinely useful —
  this project exists because the descriptor tables and the marketing copy lie.
- **Parser fixes** where a documented formula and a real reply disagree. Include
the transcript that shows the shape; a reply reconstructed from memory is how
the same bug gets fixed twice.

**What the Customize explorer does now.** Three steps, top to bottom. Step 1
scans the C-BEST blocks (full scan once, re-scan after that). Step 2 learns
noise once: three scans with nothing changing, so bits that move on their own
are ignored from then on. Step 3 maps a setting: type what you are changing,
tap Scan for change to save the baseline (1/3), change that ONE setting on the factory radio (engine RUNNING, parked), Scan again (2/3), put it BACK, Scan a third time (3/3) — only the block that follows you there and back counts. That scan becomes the baseline for the next one, so you chain change, scan, repeat. The app names the confirmed mover in an answer card — tap Save, or Not this to try the next confirmed block. One-time movers stay hidden behind Details. Changes are saved as records
stamped with the truck in Settings (year/trim/calibration — set it there, not
on this screen) and exported as JSON. The name comes from your truck and your
note — the app learns the bytes from the diff, never from a list.

**Customize explorer: read-only, buried.** `CustomizeActivity` reads C-BEST blocks and nothing else — the v0.9.31 write experiment (one guarded send) was retired in v0.9.32: the send path is deleted, and settings changes are quicker in a dedicated customize app. The explorer stays for reference only, reached from Diagnostics → Advanced, not from the dashboard.

**Ground rules:**

- Say what you tested on. "Compiles" and "worked on my 2019 TRD Off-Road with a
  Kiwi 3" are different claims; only claim the second when it happened.
- No new dependency for something the platform already does — this app is
  `HttpURLConnection` and `org.json` on purpose, so a sideloaded build has no
  supply chain to trust.
- Keep feature PRs small and single-purpose. Refactors bundled into features
  will be asked to split.

**Build:** `./gradlew assembleDebug`. Debug builds carry a `.debug` application
id suffix, so they install alongside a release build without clobbering it.

## License

MIT — see [LICENSE](LICENSE). Do what you like with it, keep the notice.

Bundled libraries are AndroidX/Compose and the Car App Library, all Apache-2.0.

This project is not affiliated with Toyota, Elm Electronics or any adapter
vendor. The current build only reads standard and manufacturer diagnostic data — it sends no write
to the truck. Use at your own risk: no warranty, and no promise that a
PID decodes correctly on your truck.
