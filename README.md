# VeloCast (`com.mastersoft.velocast`) & VeloCast Daemon (`com.mastersoft.velocast.daemon`)

Low-latency, thermal-optimized Android-to-Android screen mirroring and remote control suite written in Kotlin for a **rooted headless Android 16 host** (e.g., Redmi Note 10 Pro running PixelOS 16) and a **non-rooted Android client** over 5GHz Wi-Fi TCP.

## Modules

- **`:server` (`com.mastersoft.velocast.daemon`, VeloCast Daemon)** — Headless APK executed via `app_process` as root:
  - Caps 120Hz AMOLED input cadence with `KEY_MAX_FPS_TO_ENCODER`, uses VBR bitrate mode, 10s I-frame interval, zero B-frames (`KEY_MAX_B_FRAMES = 0`), `KEY_LOW_LATENCY = 1`, and 1s idle frame repeat to keep older SoCs cool.
  - Uses zero-allocation coalesced TCP frame writes (`Protocol.writeCoalescedFrame`) and a 10-second socket heartbeat watchdog (`soTimeout = 10_000`) so dropped Wi-Fi sessions immediately release `MediaCodec` and `VirtualDisplay`.
  - Executes Notification Shade (`IStatusBarService.expandNotificationsPanel`) and Screen Rotation (`IWindowManager.freezeRotation`) directly via Android Binder IPC with bounded-timeout shell fallbacks.
- **`:client` (`com.mastersoft.velocast`, VeloCast)** — Standard non-rooted Android APK with adaptive launcher icons:
  - Decoupled 3-stage pipeline (TCP reader coroutine → bounded NAL channel → dedicated `MediaCodec` input feeder + high-priority output drain thread) eliminating Wi-Fi TCP backpressure.
  - Adjustable real-time stats HUD (FPS, live Mbps/MB/s throughput, Host IP, real RTT ping, alignment, opacity, idle auto-fade).
  - Decoupled, draggable Floating Quick Toolbox (FAB) with auto-flipping glass dock, centered item labels, and 7 toggleable remote actions (Screen Off/Sleep, Volume Up/Down, Notification Shade, Rotate Screen, System Back, PNG Screenshot Capture).
  - Mirror Settings screen with live HUD/Anchor previews and Host Resolution Scale (`100% Native`, `75% Cool`, `67% 720p`) for thermal control.

## Build & Deployment

```bash
./gradlew :server:assembleDebug :client:assembleDebug
```

### Deploy VeloCast Daemon to Rooted Host
```bash
adb push server/build/outputs/apk/debug/server-debug.apk /data/local/tmp/velocast-daemon.apk
adb push magisk/mirror.sh /data/local/tmp/mirror.sh
adb shell su -c "cp /data/local/tmp/mirror.sh /data/adb/service.d/mirror.sh && chmod 755 /data/adb/service.d/mirror.sh"
adb shell su -c "/data/adb/service.d/mirror.sh"
adb shell su -c "tail -f /data/local/tmp/velocast-daemon.log"
```

### Install VeloCast Client
```bash
adb install client/build/outputs/apk/debug/client-debug.apk
```
