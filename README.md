# VeloCast (`com.mastersoft.velocast`) & VeloCast Host (`com.mastersoft.velocast.host`)

Android-to-Android screen mirroring and remote control suite written in Kotlin for a **rooted Android host** (`minSdk` 30) and a **non-rooted Android viewer** (`minSdk` 26) over TCP (default port `27183`).

## Status

> **Pre-release — not safe on shared or untrusted networks.**
>
> VeloCast is under active hardening. The `:server-core` entry point (`com.mastersoft.velocast.server.Main`) now enforces a protocol v2 HMAC-SHA256 handshake (`SessionGuard`) before accepting session traffic, while transport encryption (AEAD) arrives in S06 and the `:app-viewer` client is updated to protocol v2 in S04. Only run on a trusted private network until S06 is complete. See [SECURITY.md](SECURITY.md) and [docs/findings.md](docs/findings.md).

## Modules

- **`:protocol`** — Pure Kotlin/JVM module for shared wire protocol v2 formats, handshake, and strict validators.
- **`:server-core`** — Android library (`minSdk` 30, `compileSdk` 36) containing the headless `app_process` server (`com.mastersoft.velocast.server.Main`), `SessionGuard` / `ServerSession` state machine, hardware H.264 encoder (`ScreenEncoder`), bounded latest-wins `FrameQueue` & priority writer (`SessionFrameSink`), rotation/size state machine (`RotationStateMachine`), strict multi-touch/Binder input injector (`InputInjector`), `VirtualDisplayBridge`, state restoration (`CleanUp` `setsid` guardian), and ring-buffered `Logger`:
  - `--port`, `--bind IP|loopback`, `--state-dir`, `--secret-from-stdin`, `--log-level`, `--bitrate-mode CBR|VBR` (default `CBR`), and `--send-buffer-bytes` (default `65536` / 64 KB) CLI options; prints `READY port=<p> nonce=<n>` on stdout.
  - 3 s handshake deadline, per-IP exponential back-off, authenticated session replacement, and strict `:protocol` input validation.
  - Encoder defaults: `KEY_BITRATE_MODE = CBR` (with `VBR` benchmark switch), `KEY_MAX_B_FRAMES = 0`, `AVCProfileConstrainedBaseline` when supported by `MediaCodecInfo`, `KEY_PREPEND_HEADER_TO_SYNC_FRAMES = 1`, `KEY_I_FRAME_INTERVAL = 5`, `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 250_000L`, `KEY_PRIORITY = 0`, and `KEY_LOW_LATENCY = 1` (API 30+); settings and capability status (`rotation_watcher`, `display_power`, `notification_shade`, `wake`) reported in `STATUS`, monotonic `captureUs` in every `VIDEO` frame header, and on-demand IDR on `KEYFRAME_REQ`, `RESUME`, size change, and dropped frames.
  - Bounded 3-frame latest-wins `FrameQueue` (`DEFAULT_CAPACITY = 3`, dropping oldest non-keyframes first and requesting an IDR), dedicated writer thread prioritizing `PONG`/`STATUS`/`ERROR` over video, `TCP_NODELAY`, and 64 KB default `SO_SNDBUF`.
  - Rotation & size tracking (`IWindowManager.watchRotation` + `500 ms` `VirtualDisplayBridge` polling) rebuilding capture/encoder with `VIDEO_SIZE` -> `CONFIG` -> `IDR` ordering and atomic `InputInjector` updates; session-start capture & teardown restore of rotation lock and display power plus out-of-process `setsid` `com.mastersoft.velocast.server.CleanUp` guardian; non-fatal `INJECTION_DENIED` reporting.
  - 20 s server-side socket read watchdog (`DEFAULT_SESSION_IDLE_TIMEOUT_MS = 20_000L`) to clean up dropped sessions while keeping the acceptor open.
  - Binder IPC via reflection (`BinderInjectorSink`) for expanding/collapsing the notification shade (`IStatusBarService`, with `cmd` fallback) and toggling rotation (`IWindowManager`, with `settings` fallback).
- **`:client-core`** — Android library (`minSdk` 26, `compileSdk` 36) containing `SessionEngine`, reusable protocol v2 `MirrorClient` transport, `FrameBufferPool` / `ControlPacketPool` rings, capacity-6 `DROP_OLDEST` `VideoFrameQueue` with `waitForKeyframe` + `KEYFRAME_REQ`, asynchronous `MediaCodec` `VideoDecoder` (`KEY_MAX_WIDTH`/`KEY_MAX_HEIGHT`, `CONFIG` recreation, `CodecException` recovery, dedicated render thread), `TouchPipeline` / `ViewportMapper`, and `SessionMetrics` (`StateFlow` + `DiagnosticsRingBuffer`).
- **`:launcher-adb`**, **`:launcher-shizuku`**, **`:launcher-root`**, **`:designsystem`** — Modular libraries scaffolded for S07–S12.
- **`:app-host` (`com.mastersoft.velocast.host`, VeloCast Host)** — Installable Android app (`minSdk` 30, `targetSdk` 36, `compileSdk` 36):
  - Requests Magisk root access (`su -c`) from the app, displays the host Wi-Fi IP and port, toggles **Start / Stop Server**, tails server logs (`/data/adb/velocast/server.log`), and manages **Auto-Start on Boot (Magisk)** (`/data/adb/service.d/velocast.sh`, resolving the APK path via `pm path`).
- **`:app-viewer` (`com.mastersoft.velocast`, VeloCast)** — Non-rooted Android viewer app (`minSdk` 26, `targetSdk` 36, `compileSdk` 36):
  - Wired to `:client-core` (`ClientMediaSession`, protocol v2 async `VideoDecoder`, capacity-6 `DROP_OLDEST` queue, `TouchPipeline`).
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
