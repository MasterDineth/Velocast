# VeloCast (`com.mastersoft.velocast`) & VeloCast Host (`com.mastersoft.velocast.daemon`)

Android-to-Android screen mirroring and remote control suite written in Kotlin for a **rooted Android host** (`minSdk` 30) and a **non-rooted Android client** (`minSdk` 26) over TCP (default port `27183`).

## Status

> **Pre-release — not safe on shared or untrusted networks.**
>
> VeloCast is under active hardening. The current server (`VeloCastDaemonMain`) binds `0.0.0.0:27183` and accepts unauthenticated, unencrypted TCP connections. Only run it on a trusted private network until session authentication and transport encryption are implemented. See [SECURITY.md](SECURITY.md) and [docs/findings.md](docs/findings.md).

## Modules

- **`:server` (`com.mastersoft.velocast.daemon`, VeloCast Host)** — Installable Android app (`minSdk` 30, `targetSdk` 34, `compileSdk` 36) with Host UI and a root `app_process` daemon (`VeloCastDaemonMain`):
  - Requests Magisk root access (`su -c`) from the app, displays the host Wi-Fi IP and port, toggles **Start / Stop Server**, tails daemon logs (`/data/local/tmp/velocast-daemon.log`), and manages **Auto-Start on Boot (Magisk)** (`/data/adb/service.d/velocast.sh`, resolving the APK path via `pm path`).
  - Hardware H.264 (`video/avc`) encoder (`ScreenEncoder`):
    - 5 s I-frame interval (`KEY_I_FRAME_INTERVAL = 5`)
    - 250 ms idle frame repeat (`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 250_000L`)
    - Realtime priority (`KEY_PRIORITY = 0`)
    - Optional keys on supported API levels (with fresh fallback encoder if rejected): `KEY_MAX_FPS_TO_ENCODER` (API 29+) and `KEY_LOW_LATENCY = 1` (API 30+)
    - Rate-control mode (`KEY_BITRATE_MODE`) and B-frame count (`KEY_MAX_B_FRAMES`) are not explicitly set and use device defaults.
  - 20 s server-side socket read watchdog (`CLIENT_IDLE_TIMEOUT_MS = 20_000`) to clean up dropped sessions while keeping the `ServerSocket` open.
  - Binder IPC via reflection (`InputInjector`) for expanding the notification shade (`IStatusBarService`, with `cmd` fallback) and toggling rotation (`IWindowManager`, with `settings` fallback).
- **`:client` (`com.mastersoft.velocast`, VeloCast)** — Non-rooted Android viewer app (`minSdk` 26, `targetSdk` 34, `compileSdk` 36):
  - Coroutines TCP client (`MirrorClient`) with `TCP_NODELAY`, a bounded 24-slot `Channel` (`incomingNalQueue`), and `MediaCodec` hardware decoding (`VideoDecoder`, setting `KEY_LOW_LATENCY = 1` on API 30+).
  - Real-time stats HUD, draggable Floating Quick Toolbox (FAB) with 7 actions (`POWER`, `VOL_UP`, `VOL_DOWN`, `SHADE`, `ROTATE`, `BACK`, `SCREENSHOT`), and Mirror Settings.

## Quick Setup

1. Build both debug APKs:
   ```bash
   ./gradlew assembleDebug
   ```
2. Install **VeloCast Host** on your rooted host device:
   ```bash
   adb install server/build/outputs/apk/debug/server-debug.apk
   ```
   Open **VeloCast Host**, grant Magisk root when prompted, optionally toggle **Auto-Start on Boot**, and tap **Start Server**.
3. Install **VeloCast** on your non-rooted client device:
   ```bash
   adb install client/build/outputs/apk/debug/client-debug.apk
   ```
   Enter the Host IP shown in VeloCast Host and tap **Connect**.

## Documentation

See [docs/README.md](docs/README.md) for the architecture, product requirements, design spec, audit findings, and step-by-step roadmap.
