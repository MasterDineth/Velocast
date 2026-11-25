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

Rules: :protocol has no Android dependency; launchers depend on :protocol (and cores as listed) only; apps depend on cores, launchers and :designsystem; :server-core has no UI libraries and no app Context.

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

## Protocol v2 summary

Video header (24 bytes, big-endian): type u8, flags u8 (KEYFRAME, CONFIG, RESET, ERROR), seq u16, length u32, ptsUs i64, captureUs i64. Control (16 bytes): type u8, pointer u8, seq u16, param i32, x f32, y f32. Handshake: HELLO (magic VLC2, version, features, client nonce), SERVER_HELLO (version, capabilities, server nonce), AUTH_PROOF (HMAC over transcript). Then AEAD-protected messages. Full byte tables go in docs/protocol.md (step S02).
