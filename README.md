# VeloCast (`com.mastersoft.velocast`) & VeloCast Host (`com.mastersoft.velocast.daemon`)

Low-latency, thermal-optimized Android-to-Android screen mirroring and remote control suite written in Kotlin for a **rooted Android 16 host** and a **non-rooted Android client** over 5GHz Wi-Fi TCP.

## Modules

- **`:server` (`com.mastersoft.velocast.daemon`, VeloCast Host)** — Standard installable Android app with dark Host UI + root `app_process` daemon:
  - Install normally on your rooted host device (no `adb push` required).
  - Request Magisk root access from inside the app, view/copy your host Wi-Fi IP & port, toggle **Start / Stop Server**, view live daemon logs, and toggle **Auto-Start on Boot (Magisk)** (`/data/adb/service.d/velocast.sh`, which dynamically resolves the installed APK path via `pm path`).
  - Thermal-optimized hardware H.264 encoder: `KEY_MAX_FPS_TO_ENCODER` (prevents 120Hz AMOLED oversampling), VBR, 10s GOP, 1s idle repeat, `KEY_MAX_B_FRAMES = 0`, `KEY_LOW_LATENCY = 1`, coalesced TCP writes, 10s zombie-client watchdog, and direct Binder IPC for Notification Shade (`IStatusBarService`) and Screen Rotation (`IWindowManager`).
- **`:client` (`com.mastersoft.velocast`, VeloCast)** — Standard non-rooted Android APK:
  - Decoupled 3-stage pipeline (TCP reader → bounded NAL channel → dedicated `MediaCodec` input feeder + high-priority output drain thread).
  - Adjustable real-time stats HUD, decoupled draggable Floating Quick Toolbox (FAB) with 7 toggleable actions, and Mirror Settings with live previews and Host Resolution Scale (`100% / 75% / 67% 720p`).

## Quick Setup

1. Build both APKs:
   ```bash
   ./gradlew :server:assembleDebug :client:assembleDebug
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
