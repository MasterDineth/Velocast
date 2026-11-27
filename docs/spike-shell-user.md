# Shell-User Feasibility Spike (`S05`)

Status: Complete (`S05A` + `S05B`).
Closes: `SEC-04` (evidence that `:server-core` runs unprivileged as `uid 2000`), `CQ-03` (per-API-level `platform/` shims, capability probe, and `STATUS` capability reporting).

## 1. Executive Summary

`:server-core` runs end-to-end as the unprivileged ADB `shell` user (`uid 2000`, SELinux context `u:r:shell:s0`, package identity `com.android.shell` via `FakeContext`) without root (`uid 0`), an application `Context`, or UI libraries:

- **Physical device verification**: Both the reference rescue phone (**Xiaomi Redmi Note 10 Pro `M2101K6G`, Android 16 / API 36, custom AOSP**) and a flagship OEM phone (**Xiaomi 15 Ultra `25019PNF3C`, Android 17 / API 37, Xiaomi HyperOS `V816`**) support **7 / 7** probed capabilities (`virtual_display`, `input_injection`, `notification_shade`, `rotation`, `display_power`, `wake`, `clipboard`) as `uid 2000` after the `S05B` platform shim fixes, and both complete a full protocol-v2 streaming session (`LaunchMode.SHELL`, `SERVER_HELLO.capabilities = 0x1a`, `STATUS` capability summary, H.264 keyframes, display-power off/on while mirroring, pause/resume, and state-restoring `BYE`).
- **Remaining ROM / platform limits**:
  - Xiaomi MIUI / HyperOS blocks shell `injectInputEvent` until the user enables Developer Options → **"USB debugging (Security settings)"** (`persist.security.adbinput=1`); ColorOS / OxygenOS / Realme UI may similarly require **"Disable permission monitoring"**. VeloCast detects this at session start (`STATUS` `input_injection=0,wake=0` + `StatusKind.DEGRADED`) and on touch/key attempts (non-fatal `ErrorCode.INJECTION_DENIED`), keeping video mirroring alive.
  - Android 11 (API 30), Android 13 (API 33), and Android 14 (API 34 / Samsung One UI) columns are derived from AOSP API contracts and covered by `PlatformApiPolicy` unit tests, and are marked **Confirm on device** per `AGENTS.md` Rule 8 until physical runs are recorded.

---

## 2. Capability Matrix (`uid 2000`, `com.android.shell`)

Raw JSON probe outputs from connected devices are committed under [`tools/spike/results/`](../tools/spike/results/).

| Capability | Android 11 (API 30, AOSP) *(Confirm on device)* | Android 13 (API 33, AOSP) *(Confirm on device)* | Android 14 (API 34, AOSP / One UI) *(Confirm on device)* | Android 16 (API 36, Xiaomi `M2101K6G`, Custom AOSP `BP4A.251205.006`) **(Verified)** | Android 17 (API 37, Xiaomi `25019PNF3C`, HyperOS `V816` `CP2A.260605.016`) **(Verified)** |
| --- | --- | --- | --- | --- | --- |
| `virtual_display` | Expected **Supported** (`DisplayManager.createVirtualDisplay` 5-arg `@SystemApi` + `FakeContext` fallback; `CAPTURE_VIDEO_OUTPUT` granted to shell) | Expected **Supported** (`DisplayManager.createVirtualDisplay` 5-arg `@SystemApi` + `VirtualDisplayConfig` fallback) | Expected **Supported** (`DisplayManager.createVirtualDisplay` 5-arg `@SystemApi`) | **Supported** (`DisplayManager.createVirtualDisplay (720x1600)`) | **Supported** (`DisplayManager.createVirtualDisplay (720x1600)`) |
| `input_injection` | Expected **Supported** (`InputManager.getInstance().injectInputEvent`) | Expected **Supported** (`InputManager.getInstance().injectInputEvent`) | Expected **Supported** on AOSP / One UI (`InputManagerGlobal`); **Denied** on MIUI/HyperOS if security toggle off | **Supported** (`InputManagerGlobal.injectInputEvent`) | **Supported** with `persist.security.adbinput=1` (**Denied** `INJECT_EVENTS` when Xiaomi *USB debugging (Security settings)* is off) |
| `notification_shade` | Expected **Supported** (`IStatusBarService` + `cmd statusbar` fallback) | Expected **Supported** (`IStatusBarService` + `cmd statusbar` fallback) | Expected **Supported** (`IStatusBarService` + `cmd statusbar` fallback) | **Supported** (`IStatusBarService.expandNotificationsPanel/collapsePanels`) | **Supported** (`IStatusBarService.expandNotificationsPanel/collapsePanels`) |
| `rotation` | Expected **Supported** (`IWindowManager.freezeRotation(int)` / `thawRotation()`) | Expected **Supported** (`IWindowManager.freezeRotation(int)` / `thawRotation()`) | Expected **Supported** (`IWindowManager.freezeRotation(int, String)` with `com.android.shell`) | **Supported** (`IWindowManager.freezeRotation(int, String)` `0 -> 1 -> 0`) | **Supported** (`IWindowManager.freezeRotation(int, String)` `0 -> 1 -> 0`) |
| `rotation_watcher` *(session)* | Expected **Supported** (`IWindowManager.watchRotation(IRotationWatcher, int)` + 500 ms poll) | Expected **Supported** (`IWindowManager.watchRotation(IRotationWatcher, int)` + 500 ms poll) | Expected **Supported** (`IWindowManager.watchRotation(IRotationWatcher, int)` + 500 ms poll) | **Supported** (`rotation_watcher=1` in session `STATUS`) | **Supported** (`rotation_watcher=1` in session `STATUS`) |
| `display_power` | Expected **Supported** (`SurfaceControl.getInternalDisplayToken` / `getPhysicalDisplayIds` + `setDisplayPowerMode`) | Expected **Supported** (`SurfaceControl.getInternalDisplayToken` / `getPhysicalDisplayIds` + `setDisplayPowerMode`) | Expected **Supported** after `S05B` fix (`DisplayControl` via `ClassLoaderFactory` + `libandroid_servers.so`) | **Supported** after `S05B` fix (was `UnsatisfiedLinkError` in `S05A`; now `SurfaceControl.setDisplayPowerMode` via `DisplayControl`) | **Supported** after `S05B` fix (was `UnsatisfiedLinkError` in `S05A`; now `SurfaceControl.setDisplayPowerMode` via `DisplayControl`) |
| `wake` | Expected **Supported** (`KEYCODE_WAKEUP` via `InputManager`) | Expected **Supported** (`KEYCODE_WAKEUP` via `InputManager`) | Expected **Supported** on AOSP / One UI; gated by OEM input toggle on MIUI/HyperOS | **Supported** (`KEYCODE_WAKEUP` via `InputManagerGlobal`) | **Supported** with `persist.security.adbinput=1` (gated by Xiaomi security toggle if off) |
| `clipboard` | Expected **Supported** (`IClipboard` 4-arg `(ClipData, pkg, tag, userId)` with `FakeContext`) | Expected **Supported** (`IClipboard` 4-arg / 5-arg with `FakeContext`) | Expected **Supported** (`IClipboard` 5-arg `(..., deviceId)` / 6-arg OEM variant with `FakeContext`) | **Supported** (`IClipboard.setPrimaryClip(argCount=5)`) | **Supported** (`IClipboard.setPrimaryClip(argCount=5)`) |

### Verified Shell Permissions (`dumpsys package com.android.shell`)

On both `M2101K6G` (API 36) and `25019PNF3C` (API 37), `com.android.shell` holds `granted=true` for:
- `android.permission.CAPTURE_VIDEO_OUTPUT` and `android.permission.ADD_TRUSTED_DISPLAY` (virtual display mirroring)
- `android.permission.INJECT_EVENTS` and `android.permission.INTERNAL_SYSTEM_WINDOW` (touch, key, and wake injection)
- `android.permission.ACCESS_SURFACE_FLINGER` and `android.permission.DEVICE_POWER` (panel power control while mirroring)
- `android.permission.EXPAND_STATUS_BAR`, `android.permission.STATUS_BAR`, and `android.permission.STATUS_BAR_SERVICE` (shade expand/collapse)
- `android.permission.WRITE_SETTINGS` and `android.permission.WRITE_SECURE_SETTINGS` (rotation restore & Prepare automation)
- `android.permission.READ_CLIPBOARD_IN_BACKGROUND` (headless clipboard read/write when `callingPackage == "com.android.shell"`)

---

## 3. Failure Analysis and Engineering Decisions

Every failure observed during `S05A` or identified across API 26–37 and OEM ROMs is Dispositioned below into **Fix in code**, **Downgrade to typed `ERROR` / `STATUS` capability flag**, or **Document as ROM / platform limitation**.

### 3.1 `display_power`: `UnsatisfiedLinkError` on Android 14+ (`DisplayControl.nativeGetPhysicalDisplayIds`)

- **Observed in `S05A`** on `M2101K6G` (API 36) and `25019PNF3C` (API 37):
  ```text
  UnsatisfiedLinkError: No implementation found for long[] com.android.server.display.DisplayControl.nativeGetPhysicalDisplayIds()
  ```
  Followed during intermediate testing by:
  ```text
  UnsatisfiedLinkError: Shared library "/system/lib64/libandroid_servers.so" already opened by ClassLoader ...
  ```
- **Root cause**:
  1. In Android 14 (API 34), Google removed `getPhysicalDisplayIds()` and `getPhysicalDisplayToken(long)` from `android.view.SurfaceControl` and moved them to `com.android.server.display.DisplayControl` inside `/system/framework/services.jar`.
  2. Inside `system_server`, `libandroid_servers.so` is preloaded globally, so `DisplayControl` has no `System.loadLibrary("android_servers")` static initializer. Loading `services.jar` via a standard `PathClassLoader` creates an isolated ART linker namespace (`isNamespaceShared = false`) that cannot link private system libraries, leaving `DisplayControl`'s JNI methods unresolved.
  3. Furthermore, Android ART forbids opening `/system/lib64/libandroid_servers.so` in more than one `ClassLoader` instance within the same process, and `S03B`'s `DisplayPowerShim` deferred token lookup into a lazy lambda, causing `isSupported` to report `true` even when native linkage was broken.
- **Decision**: **Fix in code + capability flag fallback**.
  - `DisplayPowerShim` (`DisplayControlLoader`) now uses `com.android.internal.os.ClassLoaderFactory.createClassLoader("/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true /* isNamespaceShared */, null)`, binds `libandroid_servers.so` via `Runtime.getRuntime().loadLibrary0(displayControlClass, "android_servers")`, caches the loaded `Class<?>` once per `app_process` lifetime, and eagerly verifies a non-null `IBinder` display token at initialization.
  - Per-API-level token lookup order (`PlatformApiPolicy.displayPowerStrategies`):
    - **API 34+**: `DisplayControl` (`services.jar` + `libandroid_servers.so`) → `SurfaceControl` physical IDs → `SurfaceControl.getInternalDisplayToken()`.
    - **API 30–33**: `SurfaceControl.getInternalDisplayToken()` → `SurfaceControl.getPhysicalDisplayToken(long)`.
    - **API 29**: `SurfaceControl.getInternalDisplayToken()` → `SurfaceControl.getBuiltInDisplay(0)`.
    - **API 26–28**: `SurfaceControl.getBuiltInDisplay(0)`.
  - If a future ROM or SELinux policy blocks `DisplayControl` or `ACCESS_SURFACE_FLINGER`, `displayPowerSupported` becomes `false`, `SERVER_HELLO.capabilities` omits `FEATURE_DISPLAY_POWER` (`0x08`), and the server emits `Status(StatusKind.DEGRADED, 3, "display_power")`.

### 3.2 `input_injection` & `wake`: OEM Security Toggles and `InputManagerGlobal` API 34 Split

- **Root cause**:
  1. **API-level split**: `android.hardware.input.InputManagerGlobal.getInstance()` was introduced in Android 14 (API 34); Android 8–13 (API 26–33) exposes `android.hardware.input.InputManager.getInstance()`. Unconditionally reflecting `InputManagerGlobal` first logged spurious warnings on API ≤ 33.
  2. **ROM limitation (`L8`)**: On Xiaomi MIUI / HyperOS, `InputManagerService` gates `uid 2000` input injection behind system property `persist.security.adbinput=1` (**Settings → Additional settings → Developer options → USB debugging (Security settings)**, which is **OFF by default** and requires a signed-in Xiaomi account and active SIM card to enable). On OPPO / Realme / OnePlus (ColorOS / OxygenOS), **Disable permission monitoring** in Developer Options may similarly be required. When disabled, `injectInputEvent` returns `false` or throws `SecurityException` even though `com.android.shell` holds `INJECT_EVENTS`.
- **Decision**: **Fix API-level lookup in code + Document as ROM limitation + Downgrade to `STATUS` capability flag and non-fatal typed `ERROR(INJECTION_DENIED)`**.
  - `PlatformApiPolicy.inputManagerClasses(sdkInt)` selects `InputManagerGlobal` on API 34+ and `InputManager` on API 26–33.
  - At session setup, `BinderInjectorSink` runs a harmless synchronous probe (`KeyEvent.KEYCODE_UNKNOWN`, `ACTION_UP`, `INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1`). When blocked, the server immediately reports `input_injection=0,wake=0` in the initial `STATUS` summary plus `Status(StatusKind.DEGRADED, 0, "input_injection")` and `Status(StatusKind.DEGRADED, 0, "wake")`.
  - Subsequent `TOUCH`, `KEY`, or `WAKE` requests still attempt injection so toggling the OEM setting takes effect without reconnecting; while refused, `ServerSession` emits non-fatal `ErrorMessage(ErrorCode.INJECTION_DENIED, fatal = false, detail)` (rate-limited once per touch gesture on `MOVE`) and keeps the video stream running so an owner with a dead display can still view their phone.

### 3.3 `rotation`: `IWindowManager.freezeRotation` / `thawRotation` Caller Package & Signature Split

- **Root cause**:
  - Android 8–13 (API 26–33) exposes `freezeRotation(int)` and `thawRotation()`.
  - Android 14+ (API 34+) changed both signatures to `freezeRotation(int, String caller)` and `thawRotation(String caller)`.
  - Production `BinderInjectorSink` and `AndroidDeviceStateController` previously passed hardcoded `"velocast"` / `"velocast-restore"` strings rather than `FakeContext.callerPackage` (`"com.android.shell"`), risking AppOps or package-ownership rejection on strict OEM `WindowManagerService` hooks.
- **Decision**: **Fix in code**.
  - `WindowManagerRotationHelper` + `PlatformApiPolicy.rotationFreezeStrategies(sdkInt)` dispatches `WITH_CALLER_PACKAGE` first on API 34+ and `WITHOUT_CALLER_PACKAGE` first on API 26–33 (with cross-fallback), always passing `platformContext.callerPackage` (`"com.android.shell"`) in `BinderInjectorSink`, `AndroidDeviceStateController`, and `CleanUp`.
  - If `IWindowManager.watchRotation` is unavailable, `ServerSession` reports `rotation_watcher=0` + `Status(StatusKind.DEGRADED, 4, "rotation_watcher")` while `ScreenEncoder`'s 500 ms `DisplayInfo` geometry poll (`RotationStateMachine`) keeps `VIDEO_SIZE` and touch mapping accurate.

### 3.4 `clipboard`: `IClipboard` Signature Drift and Background Access Checks

- **Root cause**:
  - `IClipboard` method signatures changed across four Android generations (`(ClipData, pkg)` on API 26–28; `(+userId)` on API 29; `(+attributionTag)` on API 30–33; `(+deviceId)` on API 34–37; plus `(+boolean userOperate)` on certain HyperOS/One UI builds).
  - Android 10+ blocks background `getPrimaryClip` unless the caller presents `callingPackage = "com.android.shell"` (which holds `READ_CLIPBOARD_IN_BACKGROUND`) with matching `userId = uid / 100_000`.
  - Prior to `S05B`, `:server-core` had probe-only clipboard code and discarded `MessageType.CLIPBOARD` in `ServerSession`.
- **Decision**: **Fix in code + `STATUS` capability flag**.
  - Extracted `ClipboardShim.kt` (`ClipboardBackend` / `ReflectionClipboardBackend` / `ClipboardBinderInvoker`) ordered by `PlatformApiPolicy.clipboardSignatures(sdkInt)` and backed by `FakeContext`.
  - `AndroidSessionEnvironment` advertises `FEATURE_CLIPBOARD` (`0x02`) when supported; `ServerSession` applies `CLIPBOARD` writes (and injects `KEYCODE_PASTE` when `paste = true`), or emits `Status(StatusKind.DEGRADED, 1, "clipboard")` if unsupported or denied.

### 3.5 `virtual_display`: API 26–29 / Stripped-ROM Fallback and `FLAG_SECURE` Windows

- **Root cause**:
  - Static `@SystemApi` `DisplayManager.createVirtualDisplay(String, int, int, int, Surface)` was introduced in Android 11 (API 30). Devices on API 26–29 (`minSdk = 26`) or custom builds missing the static helper require `DisplayManager(FakeContext)` or `DisplayManagerGlobal.createVirtualDisplay` with `VirtualDisplayConfig` and `FakeContext` (`com.android.shell`).
  - Windows with `WindowManager.LayoutParams.FLAG_SECURE` are blanked in compositor mirror output for non-root callers (`L9`).
- **Decision**: **Fix per-API-level fallbacks in code + Fatal typed `ERROR(SERVER_START_FAILED)` on total capture failure + Document `FLAG_SECURE` (`L9`)**.
  - `VirtualDisplayBridge` uses `PlatformApiPolicy.virtualDisplayStrategies(sdkInt)`: static 5/6-arg `DisplayManager.createVirtualDisplay` → `DisplayManagerGlobal` + `VirtualDisplayConfig` + `FakeContext` → `DisplayManager(FakeContext)` (`VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`).
  - If all capture strategies fail, `ServerSession` sends fatal `ErrorMessage(ErrorCode.SERVER_START_FAILED, fatal = true, detail)` and closes cleanly.

### 3.6 `notification_shade`: Fragile Raw Binder Transaction Codes

- **Root cause**:
  - `BinderInjectorSink` previously retained a last-resort `service call statusbar 1` / `2` fallback whose numeric AIDL transaction IDs are not stable across Android releases (`CQ-03`).
- **Decision**: **Fix in code + `STATUS` capability flag**.
  - Removed raw `service call` transaction numbers; `BinderInjectorSink` uses `IStatusBarService.expandNotificationsPanel()` / `collapsePanels()` over Binder with `/system/bin/cmd statusbar expand-notifications` / `collapse` as the version-stable shell fallback, and reports `notification_shade=0` + `Status(StatusKind.DEGRADED, 0, "notification_shade")` when neither is available.

---

## 4. Viewer `STATUS` Capability Reporting Contract

Immediately after `AUTH_PROOF` succeeds and `DEVICE_INFO` (`LaunchMode.SHELL` or `LaunchMode.ROOT`) is queued, `ServerSession` probes active capabilities and sends:

1. **Summary `STATUS` (`StatusKind.DISPLAY_POWER`)**:
   - `value`: `1` (panel on) or `0` (panel off).
   - `detail` (≤ 96 UTF-8 bytes):
     ```text
     input_injection=1,rotation_watcher=1,display_power=1,notification_shade=1,wake=1,clipboard=1
     ```
2. **Per-capability `StatusKind.DEGRADED` frames** for any capability that is `0` at startup or degrades mid-session:
   - `Status(DEGRADED, 0, "input_injection")`
   - `Status(DEGRADED, 4, "rotation_watcher")`
   - `Status(DEGRADED, 3, "display_power")`
   - `Status(DEGRADED, 0, "notification_shade")`
   - `Status(DEGRADED, 0, "wake")`
   - `Status(DEGRADED, 1, "clipboard")`
3. **Viewer state integration (`:client-core`)**:
   - `ActiveSessionMonitor` parses both the `DISPLAY_POWER` capability summary and subsequent `DEGRADED` frames into `SessionState.Streaming.serverCapabilities: Map<String, Boolean>` and `SessionState.Streaming.degradedCapabilities: Set<String>`.

---

## 5. End-to-End Shell-User Session Verification

Full protocol-v2 sessions were executed against `com.mastersoft.velocast.server.Main` launched via `app_process` as `uid 2000` (`--port 0 --bind loopback --secret-from-stdin`) on both physical phones:

| Device | Serial | Launch UID / Mode | `SERVER_HELLO.capabilities` | `STATUS` Summary | Verified Session Operations |
| --- | --- | --- | --- | --- | --- |
| Xiaomi Redmi Note 10 Pro (`M2101K6G`, Android 16, API 36) | `6f8d1516` | `uid=2000` (`LaunchMode.SHELL`) | `0x1a` (`CLIPBOARD \| DISPLAY_POWER \| ROTATION_EVENTS`) | `input_injection=1,rotation_watcher=1,display_power=1,notification_shade=1,wake=1,clipboard=1` | Handshake (`AUTH_PROOF`), `DEVICE_INFO`, `VIDEO_SIZE` (`1072x2400` stream / `1080x2400@0` display), H.264 `CONFIG` + IDR keyframes, `PING`/`PONG`, `DISPLAY_POWER_OFF` (`0`) → `DISPLAY_POWER_ON` (`1`), `PAUSE` (`1`) → `RESUME` (`0`) + fresh IDR, clean `BYE` |
| Xiaomi 15 Ultra (`25019PNF3C`, Android 17, API 37, HyperOS `V816`) | `fba5cfe8` | `uid=2000` (`LaunchMode.SHELL`) | `0x1a` (`CLIPBOARD \| DISPLAY_POWER \| ROTATION_EVENTS`) | `input_injection=1,rotation_watcher=1,display_power=1,notification_shade=1,wake=1,clipboard=1` | Handshake (`AUTH_PROOF`), `DEVICE_INFO`, `VIDEO_SIZE` (`1072x2400` stream / `1080x2400@0` display), H.264 `CONFIG` + IDR keyframes, `PING`/`PONG`, `DISPLAY_POWER_OFF` (`0`) → `DISPLAY_POWER_ON` (`1`), `PAUSE` (`1`) → `RESUME` (`0`) + fresh IDR, clean `BYE` |
