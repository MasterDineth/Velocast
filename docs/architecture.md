# Architecture

## Modules and dependency rules

| Module | Type | Responsibility | Depends on |
| --- | --- | --- | --- |
| :protocol | Pure Kotlin/JVM | Wire formats, handshake, validators, crypto interfaces, error codes, golden vectors and fuzz tests. | nothing |
| :server-core | Android library, built as a dex jar | Capture, encoder, input, display and rotation handling, session guard, capability probe, cleanup helper. No UI libraries, no app Context. | :protocol |
| :client-core | Android library | Session engine and state machine, stream transport, decoder, touch pipeline, metrics, connection coordinator. | :protocol |
| :launcher-adb | Android library | ADB client abstraction, pairing, discovery, USB OTG transport, push and start, Prepare automation. | :protocol, :client-core |
| :launcher-shizuku | Android library | Shizuku detection and UserService that runs the server as shell. | :protocol, :server-core |
| :launcher-root | Android library | Supervised root start, Magisk boot script generation. | :protocol, :server-core |
| :designsystem | Android library (Compose) | Theme, tokens, components, strings, previews and screenshot tests. | nothing |
| :app-viewer | Application (com.mastersoft.velocast) | Viewer UI: devices, add, connect, mirror, settings, diagnostics, rescue guide. | :client-core, :launcher-adb, :designsystem |
| :app-host | Application (com.mastersoft.velocast.host) | Old-phone app: Prepare checklist, keep-alive, boot recovery, status, Shizuku and root launch. | :server-core, :launcher-shizuku, :launcher-root, :designsystem |

Rules:
- `:protocol` <- `:server-core`, `:client-core`, `:launcher-adb`, `:launcher-shizuku`, `:launcher-root`. `:protocol` is pure Kotlin/JVM and has zero Android plugins or Android dependencies (`com.android.*`, `androidx.*`, `com.google.android.*`).
- Launchers (`:launcher-adb`, `:launcher-shizuku`, `:launcher-root`) depend on `:protocol` only (until their implementation steps add their listed core dependency) and must **never** depend on `:app-viewer`, `:app-host`, `:designsystem`, or another launcher.
- `:designsystem` depends on nothing (no project dependencies).
- Apps depend only on their respective core, launchers, and `:designsystem` (`:app-viewer` -> `:client-core`, `:launcher-adb`, `:designsystem`; `:app-host` -> `:server-core`, `:launcher-shizuku`, `:launcher-root`, `:designsystem`).
- `:server-core` has no UI libraries (`androidx.appcompat`, `com.google.android.material`, `androidx.compose.*`, `androidx.activity`, `androidx.fragment`) and no app `Context`.
- Automated enforcement: `./gradlew verifyModuleDependencies` (run automatically by `./gradlew check`) fails the build if any module violates these rules.

## Launchers (order of preference)

| Launcher | Runs on | Needs | Strength | Limit |
| --- | --- | --- | --- | --- |
| ADB over Wi-Fi | Viewer | Old phone: debugging and Wireless debugging on, viewer paired | Nothing installed on the old phone; first choice | Off after reboot; needs Wi-Fi; 7-day authorisation rule unless disabled |
| ADB over USB OTG | Viewer | Old phone: USB debugging on, viewer authorised; OTG cable | Survives reboot; lowest latency; no Wi-Fi | Needs a cable and adapter |
| Shizuku | Host app on the old phone | Shizuku installed and started (non-root: wireless debugging) | Hands-free restart after boot with a start-on-boot fork | Extra app; may wait for Wi-Fi and unlock |
| Root (Magisk) | Host app on the old phone | Rooted phone | Hands-free boot start with supervision | Smallest audience; highest risk; optional |

## Secrets

| Secret | Created by | Stored | Used for |
| --- | --- | --- | --- |
| ADB key pair | Viewer (at first pairing) | Keystore-protected store on the viewer; trusted key on the old phone | Authorises the viewer to the old phone's debugging service |
| Session secret (256-bit) | Viewer, per session | Memory only; sent to the server on stdin | Handshake proof and key derivation for ADB-launched servers |
| Pairing secret (256-bit) | Viewer, during Prepare | Viewer and Host app, Keystore-protected | Handshake and keys for servers the Host app starts (Shizuku, root) |
| Unlock PIN (optional) | User, opt-in | Viewer only, Keystore key, optional biometric gate | Unlock helper; never logged or backed up |

## Trust model

1. The viewer pairs with the old phone's Wireless debugging (QR code or 6-digit code); Android stores the viewer's ADB key.
2. Prepare over ADB: stay-awake and no-expiry settings, WRITE_SECURE_SETTINGS and battery exemption for the Host app, a 256-bit pairing secret.
3. The pairing secret is handed to the Host app's provider, which accepts it only from uid 2000 or 0 while a pairing window is open.
4. Every session: ADB-launched servers get a fresh session secret on stdin; Host-started servers use the pairing secret. Same handshake and key derivation.

## Session state machine (viewer)

States: Idle, Discovering, Connecting (ADB), StartingServer, Handshaking, Streaming, Paused, Reconnecting, Failed.
Transitions: Idle -> Discovering (connect); Discovering -> Connecting (found); Connecting -> StartingServer (ADB up); StartingServer -> Handshaking (READY); Handshaking -> Streaming (auth OK); Streaming -> Paused (background); Paused -> Discovering (resume); Streaming -> Reconnecting (no data for 6 s); Reconnecting -> Handshaking (back online); Reconnecting -> Failed (retries exhausted); Failed -> Idle (user retry).

## After a reboot

| Configuration | What happens |
| --- | --- |
| ADB over Wi-Fi only | Wireless debugging is off after reboot. The viewer reports WIRELESS_OFF_AFTER_REBOOT and offers the USB route. |
| Host app with WRITE_SECURE_SETTINGS | After boot, once Wi-Fi is up, the Host app turns wireless debugging back on; the viewer then connects normally. May wait for first unlock on secure-lock phones (confirm on devices). |
| Shizuku (start on boot) | The Host app waits for the Shizuku binder, starts the server and advertises it over mDNS; the viewer connects without ADB. |
| Root | The Magisk script starts the supervised server after boot; the viewer connects without ADB. |
| USB OTG | USB debugging persists, so a cable connection works after reboot if the viewer is still authorised. |

## Shell-user platform shims and capability reporting (`S05`)

`:server-core` runs headless under `app_process` primarily as the ADB `shell` user (`uid 2000`, `LaunchMode.SHELL`) and optionally as `root` (`uid 0`, `LaunchMode.ROOT`), with zero UI libraries and no Android application `Context`. All hidden-API reflection is isolated inside `com.mastersoft.velocast.server.platform` behind JVM-testable interfaces (`PlatformApiPolicy`):

| Shim / Component | Responsibility & per-API-level handling | Failure / Degradation behaviour |
| --- | --- | --- |
| `FakeContext` / `PlatformContext` | Supplies caller package `com.android.shell`, UID-derived `userId = uid / 100_000`, `attributionTag`, and API 31+ `AttributionSource` without an app `Context`. | Pure `ShellIdentity` math is unit-tested on the JVM (`FakeContextIdentityTest`). |
| `VirtualDisplayBridge` | Queries `DisplayManagerGlobal.getDisplayInfo` and creates the auto-mirror virtual display: API 30+ static `@SystemApi` `DisplayManager.createVirtualDisplay(String, int, int, int, Surface)` -> `DisplayManagerGlobal` + `VirtualDisplayConfig` + `FakeContext` -> API 26+ `DisplayManager(FakeContext)`. | Fatal typed `ERROR(SERVER_START_FAILED)` if no mirror virtual display can be created. Protected (`FLAG_SECURE`) windows render black (`L9`). |
| `DisplayPowerShim` | Switches physical panel power (`POWER_MODE_OFF` / `POWER_MODE_NORMAL`) while capture continues: API 34+ loads `com.android.server.display.DisplayControl` from `/system/framework/services.jar` via `ClassLoaderFactory.createClassLoader(..., isNamespaceShared=true)` and binds `libandroid_servers.so` once per process; API 29-33 uses `SurfaceControl` internal/physical tokens; API 26-28 uses `SurfaceControl.getBuiltInDisplay(0)`. | Eagerly verifies token resolution; if unsupported or denied, omits `FEATURE_DISPLAY_POWER` (`0x08`) from `SERVER_HELLO` and emits `Status(DEGRADED, 3, "display_power")`. |
| `BinderInjectorSink` | Injects touch/key/wake events (`InputManagerGlobal` on API 34+, `InputManager` on API 26-33), expands/collapses shade (`IStatusBarService` + `/system/bin/cmd statusbar` fallback), and rotates (`IWindowManager.freezeRotation` with `com.android.shell` caller on API 34+, 1-arg on API 26-33, `/system/bin/settings` fallback). | Probes harmless `KEYCODE_UNKNOWN` at session start; if OEM security toggles block shell input (e.g. Xiaomi `persist.security.adbinput=0`, `L8`), reports `input_injection=0,wake=0` + `Status(DEGRADED, 0, "input_injection")` and emits non-fatal `ERROR(INJECTION_DENIED)` on input attempts while keeping video active. |
| `RotationWatcherShim` + `DeviceStateController` + `CleanUp` | Watches display rotation via `IWindowManager.watchRotation` (plus 500 ms `RotationStateMachine` geometry poll) and snapshots/restores rotation lock and display power on normal teardown or `SIGKILL` via `setsid` `CleanUp`. | Emits `Status(DEGRADED, 4, "rotation_watcher")` if Binder watcher registration fails; polling still emits `VIDEO_SIZE` on rotation. |
| `ClipboardShim` | Reads and writes clipboard via `IClipboard` with `FakeContext` (`com.android.shell`, `READ_CLIPBOARD_IN_BACKGROUND`), selecting API 34+ 5-arg/6-arg, API 30-33 4-arg, API 29 3-arg, or API 26-28 2-arg signatures. | Advertises `FEATURE_CLIPBOARD` (`0x02`) when available; emits `Status(DEGRADED, 1, "clipboard")` if unsupported or denied. |

After authentication, the server sends `DEVICE_INFO`, an initial `STATUS(DISPLAY_POWER, powerOn, "input_injection=...,rotation_watcher=...,display_power=...,notification_shade=...,wake=...,clipboard=...")`, and individual `STATUS(DEGRADED, bitIndex, name)` frames, which `:client-core` (`ActiveSessionMonitor`) exposes on `SessionState.Streaming.serverCapabilities` and `degradedCapabilities`. Full matrix and device logs live in [spike-shell-user.md](spike-shell-user.md).

## Protocol v2 summary

Video header (24 bytes, big-endian): type u8, flags u8 (KEYFRAME, CONFIG, RESET, ERROR), seq u16, length u32, ptsUs i64, captureUs i64. Control (16 bytes): type u8, pointer u8, seq u16, param i32, x f32, y f32. Handshake: HELLO (magic VLC2, version, features, client nonce), SERVER_HELLO (version, capabilities, server nonce), AUTH_PROOF (HMAC-SHA256 with HKDF-derived key over SHA-256 transcript hash). Then AES-256-GCM record framing (`SECURE_CHANNEL`, 96-bit directional counter nonces, 16-byte AAD record header, automatic HKDF rekey before `2^32` frames). Full byte tables live in [protocol.md](protocol.md) and threat model / key lifecycle in [security.md](security.md).
