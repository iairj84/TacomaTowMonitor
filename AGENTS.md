# AGENTS.md — Tacoma Tow Monitor

## Project
Phone dashboard + tow-trip logger for a 2019 Tacoma V6 A/T over ELM327
(Bluetooth Classic SPP and BLE). Big trans-pan/converter/coolant/oil gauges,
WARN/CRIT alarms, CSV logging to `tow-logs/`, demo mode, bus watchdog
(AT WS → AT Z reset), plus a slow-refresh Android Auto IOT Pane (5 s).

## Tech stack
Gradle 9.3.1, AGP 8.13.1, Kotlin 2.2.21, compile/targetSdk 35, minSdk 26.
Compose (BOM 2024.06.00) + Material3, car-app 1.7.0, lifecycle/activity-compose.
No new deps without need — networking is `HttpURLConnection` + `org.json`.
Unit tests: JUnit 4.13.2, incl. transcript replay (`ReplayTransport`).

## Install / run / build / test
- SDK via `local.properties`: `sdk.dir=C\:/dev` (Studio writes this on fresh clones).
- Sync/open in Android Studio, or CLI: `./gradlew assembleDebug`.
- APK: `app/build/outputs/apk/debug/`. Debug has `.debug` id suffix.
- Run `app` config on phone; ignition ON; Classic = pair in system BT first,
  BLE (`odbble`) = scan inside app, no system pairing. No adapter: Demo mode.
- Tests: `./gradlew testDebugUnitTest` (add transcript to
  `app/src/test/resources/transcripts/` for protocol bugs).
- Release flow (README): bump versionCode+1/versionName, build, copy APK as
  `TacomaTowMonitor-vX.Y.Z-debug.apk`, commit sources only, push, `gh release`
  with APK + SHA-256, verify `releases/latest` has the asset.

## Documentation rules
- Update README.md when features or setup change.
- Add a dated line to CHANGELOG.md for every change (create if missing).
- Record design decisions in docs/NOTES.md.
- Commit messages explain why, not just what.

## Skill workflow
- New app/feature → brainstorming → writing-plans → executing-plans.
- Bug/failing test → systematic-debugging first.
- UI work → frontend-design, checked with webapp-testing.
- Before done → verification-before-completion, then requesting-code-review.
- Before putting a web tool online → security-review.
- Commits → git-commit. Docs work → documentation-writer.

## Working rules
- Small focused changes; single-purpose PRs; split bundled refactors.
- Run tests or build before saying done; state what ran on what truck/adapter.
- Ask before deleting files or changing dependencies.
- Never commit secrets, keystores, `local.properties`, or APKs.
- Read-only vehicle: no write path without WRITE-SAFETY.md + WritePolicy + review.
