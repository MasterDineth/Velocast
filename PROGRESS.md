# PROGRESS

Update this file whenever you stop. The owner will say "Continue" and you resume from here.

## Current step
S00 — Agent workspace and repository hygiene (complete; ready for S01).

## Done
- Verified `.gitignore` and confirmed no `build/`, `.gradle/`, `.idea/`, `local.properties`, `*.apk`, or `*.aab` files are tracked.
- Deleted `magisk/mirror.sh` and removed legacy names (`MirrorServerMain`, `mirror.sh`, `mirror-server.apk`) from `DaemonController.kt`.
- Replaced hard-coded `192.168.31.109` default host with `""` in client `Protocol.kt`, `activity_connect.xml`, and `activity_settings.xml`.
- Rewrote `README.md` to match the actual code constants (`5 s` I-frame interval, `250 ms` idle repeat, `20 s` server watchdog) and added a pre-release security Status section.
- Added `LICENSE` (Apache-2.0), `SECURITY.md`, `CONTRIBUTING.md`, `.github/pull_request_template.md`, and `.github/workflows/ci.yml`.
- Fixed `NewApi` lint error in `client/src/main/res/values/themes.xml` (`tools:targetApi="27"` on `android:windowLayoutInDisplayCutoutMode`).
- Verified `./gradlew assembleDebug lint` passes locally.

## Next
- S01 — Restructure, toolchain and SDK 36 (on branch `step/S01-restructure`).

## Open questions
- None.

## Owner checks pending (needs a real phone)
- Confirm the repository size is under 5 MB after the history purge.
- Open the pull request for `step/S00-hygiene` -> `main` on GitHub (no `gh` CLI installed in this environment).
