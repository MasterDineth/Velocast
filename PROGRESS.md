# PROGRESS

Update this file whenever you stop. The owner will say "Continue" and you resume from here.

## Current step
S01 — Restructure, toolchain and SDK 36 (implementation complete; ready for PR / device smoke test).

## Done
- Upgraded Gradle wrapper to `9.9.2` and pinned Gradle daemon JVM and Kotlin/Java toolchains to JDK 17 (`gradle/gradle-daemon-jvm.properties`).
- Added `gradle/libs.versions.toml` and moved all plugin and library versions into it; documented verified versions and POM licences in `docs/dependencies.md`.
- Created the 9-module build structure: `:protocol`, `:server-core`, `:client-core`, `:launcher-adb`, `:launcher-shizuku`, `:launcher-root`, `:designsystem` (Compose enabled with `VeloCastTheme` stub), `:app-viewer` (`com.mastersoft.velocast`), and `:app-host` (`com.mastersoft.velocast.host`).
- Moved daemon classes to `:server-core` (`// TODO(S03)`), `HostMainActivity`/`DaemonController` + Host manifest/resources to `:app-host`, and client classes + manifest/resources to `:app-viewer`; removed old `:server` and `:client` modules.
- Raised `compileSdk` and `targetSdk` to `36` for `:app-viewer` and `:app-host` (keeping `minSdk` 26 and 30 respectively) and added `WindowInsetsCompat` edge-to-edge inset padding in `HostMainActivity`, `ConnectActivity`, and `SettingsActivity`.
- Added Spotless (`8.12.0` + `ktlint` `1.8.0`), Detekt (`dev.detekt:2.0.0-alpha.6` with baselines for `:server-core`, `:app-viewer`, `:app-host`), unit test smoke coverage in `:protocol`, `:server-core`, and `:app-viewer`, and automated `verifyModuleDependencies` wired into `./gradlew check`.
- Updated `docs/architecture.md`, `README.md`, and `.github/workflows/ci.yml` (`./gradlew check assembleDebug`).
- Verified `./gradlew check assembleDebug --warning-mode all` passes across all 9 modules.

## Next
- S02 — Protocol v2 module (on branch `step/S02-protocol`).

## Open questions
- None.

## Owner checks pending (needs a real phone)
- Install `app-host-debug.apk` on the rooted old phone and `app-viewer-debug.apk` on the new phone and run a normal mirroring session (including checking status/navigation bar insets on Android 15/16, root start/stop, and Magisk auto-start script).
- Open/merge the pull request for `step/S01-restructure` -> `main` on GitHub if not already created.
