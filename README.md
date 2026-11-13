# Tacoma Tow Monitor — 2019 Tacoma + ELM327 + Android Auto (read-only)

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
5. **Keep alive** starts the foreground service (screen-off polling);
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
