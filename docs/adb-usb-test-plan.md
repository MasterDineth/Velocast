# ADB USB OTG Transport, Authorisation, Auto-Fallback, and Reboot Recovery — Manual Device Test Plan (`S07C`)

Status: Ready for owner verification on two physical phones (`AGENTS.md` Rule 8 — **Confirm on device**).
Modules under test: `:launcher-adb` (`AdbUsbTransport`, `AndroidUsbHostBackend`, `AdbUsbController`, `AdbUsbState`, `InHouseAdbClient`, `AdbLauncher`) and `:client-core` (`SessionEngine`, `MirrorClient`, `SessionMetrics`).

## Prerequisites

1. **Viewer phone**: Android 8.0+ (`API 26+`) supporting USB Host / OTG mode, with `:app-viewer` debug APK installed (`./gradlew :app-viewer:installDebug`).
2. **Old phone (unrooted)**: Android 8.0–16+ (`API 26–36+`, e.g. Android 11 and Android 16 reference phones per `S07` acceptance criteria) with **Developer options -> USB debugging** accessible; no root and no VeloCast Host app required.
3. **USB OTG cable / adapter**:
   - Prefer a **USB-C OTG host adapter** plugged into the **Viewer phone** + a standard USB-A-to-USB-C data cable into the **Old phone** so USB-PD Dual-Role Port (`DRP`) role negotiation deterministically assigns **Host (`DFP`)** to the Viewer and **Peripheral (`UFP`)** to the old phone.
   - If using a direct USB-C-to-USB-C cable, confirm in Android USB preferences that *Controlled by: This device* is active on the Viewer (*Confirm on device*).
4. **Server dex jar**: Built via `./gradlew :server-core:assembleDexJar` (`server-core/build/outputs/dex/velocast-server.jar`).

---

## Test Cases

### M-S07C-01 — Cable attach/detach broadcasts, `CableDetected` -> `Authorising` -> `Ready`, and key persistence
- **Setup**: On the old phone, open **Developer options -> Revoke USB debugging authorizations** and switch **USB debugging** **OFF** initially.
- **Action**:
  1. Start the Viewer (`AdbClientFactory.create(context)`) and observe `adbClient.usbState`.
  2. Plug the OTG cable between Viewer (host) and old phone (peripheral) while USB debugging is still OFF.
  3. Toggle **USB debugging** **ON** on the old phone.
  4. Call `adbClient.usb.connect()`.
  5. Approve Android's local USB access prompt on the Viewer, then check *"Always allow from this computer"* and tap **Allow** on the old phone's *"Allow USB debugging?"* dialog.
  6. Unplug the OTG cable, wait 3 seconds, plug it back in, and call `adbClient.usb.connect()` again.
- **Expected**:
  - Step 2 (USB debugging OFF): `usbState` reports `AdbUsbState.Disconnected(nonAdbUsbAttached = true)` if the phone exposes MTP/charging descriptors without ADB (`0xFF/0x42/0x01`), or `Disconnected(nonAdbUsbAttached = false)` in pure charge-only mode.
  - Step 3 (USB debugging ON): `ACTION_USB_DEVICE_ATTACHED` transitions `usbState` to `AdbUsbState.CableDetected(permissionGranted = false)`.
  - Step 4–5: `usbState` transitions to `AdbUsbState.Authorising(stage = VIEWER_USB_PERMISSION)`, then `AdbUsbState.Authorising(stage = DEVICE_ADB_APPROVAL)`, and upon tapping **Allow** reaches `AdbUsbState.Ready` (`connectedDevice.transportKind == AdbTransportKind.USB`, `shell("id").stdout` contains `uid=2000(shell)`).
  - Step 6: Unplugging emits `ACTION_USB_DEVICE_DETACHED` -> `AdbUsbState.Disconnected`; replugging emits `AdbUsbState.CableDetected` -> `AdbUsbState.Ready` with **zero** confirmation dialog on the old phone.

### M-S07C-02 — Authorisation dialog timeout / `AUTH_EXPIRED` when the old phone cannot be tapped
- **Setup**: Tap **Revoke USB debugging authorizations** on the old phone while **USB debugging** remains **ON** (simulating expired 7-day authorisation `L1` on a phone with dead touch).
- **Action**:
  1. Plug in the OTG cable and call `adbClient.usb.connect()` (or `SessionEngine.connect()` via `AdbLauncher`), **without** touching the *"Allow USB debugging?"* prompt on the old phone.
  2. After the timeout fires, attach/switch to an OTG mouse (or tap **Allow** on the old phone) and retry `adbClient.usb.connect()` without unplugging the cable.
- **Expected**:
  - Step 1: `usbState` transitions `CableDetected -> Authorising(DEVICE_ADB_APPROVAL)` and then fails after `usbAuthPromptTimeoutMs` (`15 s` default) with `AdbFailure.AuthExpired` / `SessionError.AuthExpired` (`ErrorCode.AUTH_EXPIRED`), while `usbState` returns to `AdbUsbState.CableDetected(lastFailure = AuthExpired(...))` because the cable is still physically attached.
  - Step 2: Retrying and approving on the old phone transitions `CableDetected -> Authorising -> Ready`.

### M-S07C-03 — Automatic Wi-Fi-to-USB selection and end-to-end `ADB_TUNNEL` streaming (`R5`)
- **Setup**:
  - Old phone previously authorised over ADB; switch **Wireless debugging** **OFF** on the old phone (and optionally enable **Airplane mode** on both phones so Wi-Fi is completely unavailable).
  - Connect the authorised USB OTG cable between Viewer and old phone.
- **Action**:
  1. Start a session using default `AdbLauncher` (`transportKind = AdbTransportKind.WIFI`, `autoSelectUsbWhenWifiUnavailable = true`) + `SessionEngine` + `MirrorClient`.
  2. Stream video for 60 seconds, inject taps, swipes, Back/Home, and rotate the old phone.
  3. Call `SessionEngine.disconnect()`.
- **Expected**:
  - `AdbLauncher` detects that Wi-Fi debugging is unavailable, automatically selects the attached USB OTG cable (`DiagnosticsRingBuffer` records `Wi-Fi ADB unavailable ... choosing attached USB OTG cable`), verifies/pushes `/data/local/tmp/velocast-server.jar` over USB `sync:` (`16 KB` chunked bulk transfers + ZLP framing), and starts `app_process` with `--port 0 --bind loopback --secret-from-stdin`.
  - No session secret appears in `/proc/<pid>/cmdline` or `logcat`.
  - `SessionState.Streaming` is reached with `endpoint.dataChannelMode == DataChannelMode.ADB_TUNNEL`, `endpoint.dataChannelReason == "usb_transport"`, and `SessionMetrics.state.value.dataChannelMode == DataChannelMode.ADB_TUNNEL`.
  - Video renders smoothly with touch/key injection working end-to-end; disconnecting closes the USB shell stream and terminates the remote server cleanly.

### M-S07C-04 — Reboot of the old phone while connected over USB OTG *(Confirm on device)*
- **Setup**: Active USB OTG streaming session running from `M-S07C-03` (run `PrepareRunner` first so `stay_on_while_plugged_in = 7` and `adb_allowed_connection_time = 0` are active).
- **Action**:
  1. **Unlocked / no-lock baseline**: With screen lock set to *Swipe* or *None* on the old phone, reboot the old phone while the session is actively streaming (`adb shell svc power reboot` or hold Power -> Restart), **keeping the OTG cable plugged in throughout the reboot**.
  2. Observe `SessionEngine.state`, `adbClient.usbState`, and Viewer `logcat` from reboot start until 30 seconds after Android finishes booting.
  3. **Secure lock screen check (`L10`, `R4`)**: Repeat Step 1–2 with a **PIN lock** configured on the old phone, recording behaviour both **before** and **after** first unlock (and checking Android 12+ Developer options *Allow ADB debugging in charge only mode* if present on the OEM ROM).
- **Expected & Device-Confirmation Checklist**:
  - When the old phone powers down its USB controller:
    - Viewer receives `ACTION_USB_DEVICE_DETACHED` without ANR or blocked `usbfs` threads.
    - `usbState` enters `AdbUsbState.Disconnected(awaitingRebootReconnect = true)`.
    - `SessionEngine.state` transitions from `Streaming` to `Reconnecting` and **stays** in `Reconnecting` (never failing early with `DEBUGGING_OFF` during the 15–35 s boot window or transient charging-only USB enumeration).
  - When the old phone finishes booting and `adbd` re-enumerates on a new USB bus address (`/dev/bus/usb/001/00X`):
    - `ACTION_USB_DEVICE_ATTACHED` transitions `usbState` to `AdbUsbState.CableDetected(reconnectedAfterDetach = true)` and triggers `SessionEngine.retryNow()`.
    - **Confirm on device (Viewer USB permission)**: record whether the Viewer's Android build requires tapping Android's local `UsbManager` permission dialog once for the new `deviceId`, or grants immediately; confirm that once granted, the old phone requires **zero** on-device input because `/data/misc/adb/adb_keys` persisted across reboot (`L3`).
    - `AdbLauncher` starts a fresh `:server-core` process bound to `loopback`, opens a new `tcp:<port>` tunnel over USB, completes the Protocol v2 AEAD handshake, and restores live `SessionState.Streaming` automatically.
    - **Confirm on device (Secure lock `L10`)**: record per OEM ROM (Pixel AOSP, Samsung One UI, Xiaomi HyperOS) whether USB `adbd` enumerates and allows `app_process` capture before first PIN unlock (showing the lock screen on the Viewer so the user can enter their PIN remotely!), or defers until a specific OEM developer toggle is enabled.
