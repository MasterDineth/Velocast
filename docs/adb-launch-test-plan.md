# ADB Server Launch, Tunnel Fallback, Prepare Automation, and DeviceStore — Manual Device Test Plan (`S07B`)

Status: Ready for owner verification on physical devices (`AGENTS.md` Rule 8 — **Confirm on device**).
Modules under test: `:launcher-adb` (`AdbLauncher`, `AdbTunnelSocketConnector`, `PrepareRunner`, `AssetServerJarSource`) and `:client-core` (`MirrorClient`, `SessionEngine`, `SessionMetrics`, `EncryptedFileDeviceStore`).

## Prerequisites

1. **Viewer phone**: Android 8.0+ (`API 26+`) with `:app-viewer` debug APK installed (`./gradlew :app-viewer:installDebug`), paired to the old phone per `docs/adb-pairing-test-plan.md` (`S07A`).
2. **Old phone (unrooted)**: Android 11+ (`API 30+`, e.g. Android 11 and Android 16 reference phones per `S07` acceptance criteria) on the same Wi-Fi network, with **Wireless debugging** enabled. No root required; `:app-host` is not required for `M-S07B-01` or `M-S07B-02`, and is installed on the old phone (`com.mastersoft.velocast.host`) for `M-S07B-03`.
3. **Server dex jar**: Built via `./gradlew :server-core:assembleDexJar` (`server-core/build/outputs/dex/velocast-server.jar`).

---

## Test Cases

### M-S07B-01 — End-to-end unrooted Wi-Fi session via `AdbLauncher` (`DIRECT_TCP`)
- **Action**:
  1. Remove any stale jar on the old phone: `adb shell rm -f /data/local/tmp/velocast-server.jar`.
  2. Connect to the old phone using `AdbLauncher` + `SessionEngine` + `MirrorClient`.
  3. Capture `adb logcat` on the Viewer and `/data/local/tmp` state on the old phone during launch, streaming, and disconnect.
- **Expected**:
  - `AdbLauncher` computes the local jar SHA-256, pushes to `/data/local/tmp/velocast-server.jar`, and verifies matching `sha256sum` after push.
  - Second launch on the same device skips `push` because the remote SHA-256 already matches.
  - `ps -ef | grep app_process` on the old phone shows `--port 0 --bind <wlan-ip> --secret-from-stdin` with **no** secret in `/proc/<pid>/cmdline` or logcat.
  - Stream reaches `SessionState.Streaming`, `SessionMetrics.state.value.dataChannelMode == DataChannelMode.DIRECT_TCP`, and `DiagnosticsRingBuffer` records `DATA_CHANNEL` (`mode=DIRECT_TCP reason=wifi_direct_tcp`).
  - Calling `SessionEngine.disconnect()` closes the ADB shell stream and terminates the remote `app_process` server cleanly (with rotation lock & display state restored).

### M-S07B-02 — Wi-Fi client isolation fallback to `ADB_TUNNEL`
- **Setup**: Place the Viewer and old phone on a Wi-Fi network with **AP/client isolation** enabled (or simulate direct TCP refusal via `DataChannelPreference.FORCE_ADB_TUNNEL` / firewall drop on the old phone's `wlan0` port).
- **Action**: Start a session via `AdbLauncher` (`DataChannelPreference.AUTO`) + `SessionEngine` + `MirrorClient`.
- **Expected**:
  - Initial direct TCP connect fails transiently; `SessionEngine` triggers `AdbLauncher.fallbackAfterDirectConnectFailure`.
  - `AdbLauncher` closes the first shell stream, relaunches with `--bind loopback`, and returns `DataChannelMode.ADB_TUNNEL` (`reason = "wifi_direct_tcp_fallback"`).
  - `MirrorClient` opens an ADB `tcp:<port>` stream via `AdbTunnelSocketConnector`, completes the Protocol v2 AEAD handshake, and streams video normally.
  - `SessionMetrics.state.value.dataChannelMode == DataChannelMode.ADB_TUNNEL` and `DiagnosticsRingBuffer` shows `DATA_CHANNEL` with `wifi_direct_tcp_fallback`.

### M-S07B-03 — `PrepareRunner` commands on real devices *(Confirm on device)*
- **Setup**: Install `:app-host` (`com.mastersoft.velocast.host`) on the old phone.
- **Action**: Run `PrepareRunner(adbClient).runAll()` twice in a row against each target ROM (AOSP Android 11/16, Samsung One UI, Xiaomi MIUI/HyperOS).
- **Expected & Device-Confirmation Checklist**:
  1. `settings put global stay_on_while_plugged_in 7` -> `Done` on first run, `AlreadyDone` on second; confirm device stays awake on USB/AC/Wireless power.
  2. `settings put global adb_allowed_connection_time 0` -> `Done` then `AlreadyDone`; confirm Developer Options *Disable adb authorization timeout* toggle is ON and authorisation survives >7 days (`R8`).
  3. `pm grant com.mastersoft.velocast.host android.permission.WRITE_SECURE_SETTINGS` -> `Done` then `AlreadyDone` on AOSP; confirm whether Xiaomi HyperOS/MIUI (with *USB debugging (Security settings)* OFF) or ColorOS returns `NeedsUser(...)` cleanly with actionable guidance and transitions to `Done` once enabled.
  4. `cmd deviceidle whitelist +com.mastersoft.velocast.host` -> `Done` then `AlreadyDone`; confirm Host shows *Unrestricted* battery usage in Android Settings.
  5. `cmd appops set com.mastersoft.velocast.host RUN_ANY_IN_BACKGROUND allow` -> `Done` then `AlreadyDone` on Android 9+; confirm behaviour on API 26–28 (`NeedsUser` if `RUN_ANY_IN_BACKGROUND` is unavailable).

### M-S07B-04 — Encrypted `DeviceStore` persistence across Viewer restarts
- **Action**:
  1. Save a `Device` (all fields + `putDeviceSecret`) via `EncryptedFileDeviceStore(context)`.
  2. Force-stop and relaunch the Viewer app.
- **Expected**:
  - `devices.vcd` is created under `context.noBackupFilesDir/velocast_devices` (`0600`) with encrypted payload and no plaintext device names/IPs/secrets.
  - After restart, `EncryptedFileDeviceStore(context)` reloads the `Device` and `getDeviceSecret(device.id)` decrypts the 256-bit secret via `AndroidKeyStore`.
