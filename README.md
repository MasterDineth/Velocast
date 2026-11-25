# VeloCast (`com.mastersoft.velocast`) & VeloCast Host (`com.mastersoft.velocast.host`)

Android-to-Android screen mirroring and remote control suite written in Kotlin for a **rooted Android host** (`minSdk` 30) and a **non-rooted Android viewer** (`minSdk` 26) over TCP (default port `27183`).

## Status

> **Pre-release — not safe on shared or untrusted networks.**
>
> VeloCast is under active hardening. The `:server-core` entry point (`com.mastersoft.velocast.server.Main`) now enforces a protocol v2 HMAC-SHA256 handshake (`SessionGuard`) before accepting session traffic, while transport encryption (AEAD) arrives in S06 and the `:app-viewer` client is updated to protocol v2 in S04. Only run on a trusted private network until S06 is complete. See [SECURITY.md](SECURITY.md) and [docs/findings.md](docs/findings.md).

## Modules

- **`:protocol`** — Pure Kotlin/JVM module for shared wire protocol v2 formats, handshake, and strict validators.
- **`:server-core`** — Android library (`minSdk` 30, `compileSdk` 36) containing the headless `app_process` server (`com.mastersoft.velocast.server.Main`), `SessionGuard` / `ServerSession` state machine, hardware H.264 encoder (`ScreenEncoder`), strict multi-touch/Binder input injector (`InputInjector`), `VirtualDisplayBridge`, and ring-buffered `Logger`:
  - `--port`, `--bind IP|loopback`, `--state-dir`, `--secret-from-stdin`, and `--log-level` CLI options; prints `READY port=<p> nonce=<n>` on stdout.
  - 3 s handshake deadline, per-IP exponential back-off, authenticated session replacement, and strict `:protocol` input validation.
  - 5 s I-frame interval (`KEY_I_FRAME_INTERVAL = 5`), 250 ms idle frame repeat (`KEY_REPEAT_PREVIOUS_FRAME_AFTER = 250_000L`), realtime priority (`KEY_PRIORITY = 0`), and optional keys on supported API levels (`KEY_MAX_FPS_TO_ENCODER` API 29+, `KEY_LOW_LATENCY = 1` API 30+).
  - 20 s server-side socket read watchdog (`DEFAULT_SESSION_IDLE_TIMEOUT_MS = 20_000L`) to clean up dropped sessions while keeping the acceptor open.
  - Binder IPC via reflection (`BinderInjectorSink`) for expanding/collapsing the notification shade (`IStatusBarService`, with `cmd` fallback) and toggling rotation (`IWindowManager`, with `settings` fallback).
- **`:client-core`**, **`:launcher-adb`**, **`:launcher-shizuku`**, **`:launcher-root`**, **`:designsystem`** — Modular libraries scaffolded for S04–S12.
- **`:app-host` (`com.mastersoft.velocast.host`, VeloCast Host)** — Installable Android app (`minSdk` 30, `targetSdk` 36, `compileSdk` 36):
  - Requests Magisk root access (`su -c`) from the app, displays the host Wi-Fi IP and port, toggles **Start / Stop Server**, tails server logs (`/data/adb/velocast/server.log`), and manages **Auto-Start on Boot (Magisk)** (`/data/adb/service.d/velocast.sh`, resolving the APK path via `pm path`).
- **`:app-viewer` (`com.mastersoft.velocast`, VeloCast)** — Non-rooted Android viewer app (`minSdk` 26, `targetSdk` 36, `compileSdk` 36):
  - Coroutines TCP client (`MirrorClient`) with `TCP_NODELAY`, a bounded 24-slot `Channel` (`incomingNalQueue`), and `MediaCodec` hardware decoding (`VideoDecoder`, setting `KEY_LOW_LATENCY = 1` on API 30+).
  - Real-time stats HUD, draggable Floating Quick Toolbox (FAB) with 7 actions (`POWER`, `VOL_UP`, `VOL_DOWN`, `SHADE`, `ROTATE`, `BACK`, `SCREENSHOT`), and Mirror Settings.

## Quick Setup

1. Run checks and build both debug APKs:
   ```bash
   ./gradlew check assembleDebug
   ```
2. Install **VeloCast Host** on your rooted host device:
   ```bash
   adb install app-host/build/outputs/apk/debug/app-host-debug.apk
   ```
   Open **VeloCast Host**, grant Magisk root when prompted, optionally toggle **Auto-Start on Boot**, and tap **Start Server**.
3. Install **VeloCast** on your non-rooted client device:
   ```bash
   adb install app-viewer/build/outputs/apk/debug/app-viewer-debug.apk
   ```
   Enter the Host IP shown in VeloCast Host and tap **Connect**.

## Documentation

See [docs/README.md](docs/README.md) for the architecture, product requirements, design spec, audit findings, and step-by-step roadmap.
