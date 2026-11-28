# Audit findings

Source: VeloCast Technical Audit and Production-Readiness Report (22 November 2026). Severity: Critical, High, Medium, Low, Info. Confidence: V = verified in code, D = confirm on device.

## SEC-01 - Unauthenticated root-privileged control and video socket (Critical, effort M, V)
Where: `VeloCastDaemonMain.kt:62-64, 68-98`

The daemon runs as uid 0, binds 0.0.0.0:27183 and accepts any TCP peer. There is no handshake, token, pairing or allow-list: the first bytes received are parsed as control packets. Anyone on the same Wi-Fi network can watch the screen and inject touches and keys (including POWER), open the notification shade and rotate the display. That is enough to observe a PIN being entered, read messages, approve dialogs or send messages as the owner.

Recommendation: Mandatory versioned handshake with a pairing-derived credential before any frame is sent or any input is accepted; bind to the Wi-Fi interface address only; show an on-device approval prompt for new clients; log and rate-limit failed attempts.

## SEC-02 - No transport encryption or integrity protection (High, effort L, V)
Where: `Protocol.kt (both modules); MirrorClient.kt:68-73`

Video, touch coordinates and key codes travel in clear text. A passive observer can reconstruct the screen and recover typed PINs from touch positions; an active on-path attacker can inject control packets.

Recommendation: Wrap the session in an AEAD channel (TLS 1.3 with a pinned self-signed certificate, or a Noise-style handshake keyed from the pairing secret), or tunnel the stream through the authenticated ADB TLS connection (see Section 10).

## SEC-03 - Any peer can pre-empt the active session (denial of service / takeover) (High, effort S, V)
Where: `VeloCastDaemonMain.kt:77-81`

A new connection immediately closes the existing session before the newcomer has proved anything. A scanner or a hostile device can keep the legitimate user permanently disconnected.

Recommendation: Authenticate first, then pre-empt; require explicit confirmation on the host for a takeover; add a per-IP back-off.

## SEC-04 - Entire network-facing stack runs as root (High, effort L, V)
Where: `DaemonController.kt:114-126; VeloCastDaemonMain.kt`

Socket parsing, MediaCodec, reflection into system services and input injection all execute with uid 0. Any parsing or logic flaw becomes a root-level flaw. Screen capture and input injection do not require root (they work as the ADB shell user, which is how scrcpy operates).

Recommendation: Make the shell-user (uid 2000) server the default; keep root only as an optional launcher for convenience features such as boot auto-start.

## SEC-05 - Unvalidated key codes and pointer IDs from the network (Medium, effort S, D)
Where: `VeloCastDaemonMain.kt:159-161; InputInjector.kt:200-212, 318-338`

Any integer is accepted as a KeyEvent code, so a peer can send POWER, CALL, ENDCALL or other keys the UI never offers. Pointer IDs are used as-is when building MotionEvents; the framework only supports small IDs (0-31), so an out-of-range value will most likely throw inside the control thread and end the session.

Recommendation: Allow-list the key codes the client UI can send; validate pointer ID range and action/state consistency; close the session on protocol violations.

## SEC-06 - Root logic trusts files in a shell-writable directory (Medium, effort S, V)
Where: `DaemonController.kt:22-23, 71-83, 147-153`

The PID file and the log live in /data/local/tmp, which the adb shell user can write. A root script reads a PID from that file and runs kill -9 on it; logs contain peer IP addresses.

Recommendation: Keep state under a root-only directory (for example /data/adb/velocast, mode 0700/0600) or use a lock file with flock; never kill a PID that was not verified against /proc/<pid>/exe and argv.

## SEC-07 - Process detection by substring match, then kill -9 (Medium, effort S, D)
Where: `DaemonController.kt:68-106, 155-166`

isRunning() and stopDaemon() scan every /proc/*/cmdline for substrings such as the main class name and mirror.sh, and kill matches. The wrapper process that carries this very script in its argv can also match. isRunning() additionally reports RUNNING whenever any process listens on port 27183.

Recommendation: Match argv[0..n] exactly (app_process + main class) or, better, let the daemon expose its own status through a local socket; do not infer state from a TCP port.

## SEC-08 - MirrorActivity is exported and accepts host and port from extras (Low, effort S, V)
Where: `client AndroidManifest.xml:492-496`

Any installed app can start the viewer and make it connect to an arbitrary endpoint.

Recommendation: Set android:exported="false"; use explicit intents inside the app.

## SEC-09 - Personal and environment data committed to the repository (Low, effort S, V)
Where: `local.properties; .idea/; client Protocol.kt:10; activity_connect.xml:81`

local.properties holds a Windows user-profile path, .idea/ (including caches/deviceStreaming.xml, 2,446 lines) is tracked, and a private LAN IP (192.168.31.109) is the compiled-in default host.

Recommendation: Add .gitignore, remove the files, purge history (git filter-repo), and replace the default host with an empty field plus discovery.

## FUN-01 - Display rotation is not handled end to end (High, effort M, D)
Where: `VeloCastDaemonMain.kt:144-146; ScreenEncoder.kt:93-95; InputInjector.kt:239-268`

Display size is queried once per session (injector) or once per encoder start (encoder). After the Rotate action the encoded size and the touch-mapping size are stale until the user reconnects. scrcpy resets the whole encoding session on rotation or folding for exactly this reason.

Recommendation: Watch rotation (IWindowManager.watchRotation or a display listener), restart capture at the new size, push a VIDEO_SIZE message to the client and update the injector atomically.

## FUN-02 - A dropped frame corrupts the picture for up to 5 seconds (High, effort M, V)
Where: `VideoDecoder.kt:328-343; ScreenEncoder.kt:228; Protocol.kt`

When no decoder input buffer is free within 15-25 ms the NAL unit is discarded and logged. With a 5-second I-frame interval and no way to ask for a keyframe, every dropped P-frame leaves visible artefacts until the next IDR.

Recommendation: Add a REQUEST_KEYFRAME control message (server: MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME); on any drop or decoder error, discard until the next IDR and request one.

## FUN-03 - Client cannot detect a dead connection (High, effort S, V)
Where: `MirrorClient.kt:68-73, 162-199`

The client socket has no read timeout and the reader blocks in readLong(). If the host vanishes (Wi-Fi roaming, sleep, crash) the viewer shows a frozen frame while still reporting connected; only TCP keep-alive eventually notices. The server has a 20 s watchdog but the client has none.

Recommendation: Set soTimeout (for example 3-5 s, frames or pongs arrive at least every 2 s) and treat expiry as a disconnect; surface a reconnecting state.

## FUN-04 - Mid-stream resolution change is not guaranteed to work (Medium, effort M, D)
Where: `VideoDecoder.kt:272-295, 316-352; ScreenEncoder.kt:126-131`

On a scale or FPS change the encoder is rebuilt and a new SPS/PPS is sent while the same decoder instance keeps running. The decoder is not configured with KEY_MAX_WIDTH/KEY_MAX_HEIGHT for adaptive playback, so behaviour depends on the vendor decoder.

Recommendation: Configure max dimensions, or recreate the decoder when a CODEC_CONFIG frame with a different size arrives; test on at least three SoC families.

## FUN-05 - Touch state machine can leave stuck or phantom pointers (Medium, effort M, V)
Where: `MirrorClient.kt:45, 201-203; InputInjector.kt:149-158, 175-188`

Control packets are queued with trySend into a 256-slot channel and silently dropped when full, which can lose an ACTION_UP. On the server, an UP for an unknown pointer inserts a new pointer before emitting POINTER_UP, and MOVE for an unknown pointer starts a new gesture.

Recommendation: Reliable delivery for state-changing events, coalescing for MOVE; strict server-side state machine; cancel all pointers when the client backgrounds or disconnects (already done on disconnect).

## FUN-06 - Rotation lock is changed and never restored (Medium, effort S, V)
Where: `InputInjector.kt:258-283`

freezeRotation (and the settings fallback, which also disables accelerometer rotation) persists after the session ends, leaving the old phone locked in an orientation the user did not choose.

Recommendation: Record the original state at session start and restore it on teardown (and on daemon crash through a cleanup helper, as scrcpy does).

## FUN-07 - 'Screen Off' is a POWER key toggle, not a display power control (Medium, effort M, D)
Where: `MirrorActivity.kt:406-415; MirrorPreferences.kt (ToolboxAction.POWER)`

The toolbox sends KEYCODE_POWER. The host screen turns off, but the state is not tracked, there is no wake on connect and no restore on exit. Comparable tools (scrcpy) offer turning the target display off while mirroring as a deliberate, separate feature.

Recommendation: Use SurfaceControl/DisplayControl display power mode while keeping capture active (scrcpy --turn-screen-off), expose the state to the client, and restore it on teardown.

## FUN-08 - Stream keeps running while the viewer is in the background (Medium, effort M, V)
Where: `MirrorActivity.kt:596-689`

There is no onStop/onStart handling. When the Surface is destroyed the decoder simply stops rendering but the host keeps encoding and the Wi-Fi keeps carrying 8-20 Mbps.

Recommendation: On onStop send PAUSE (server stops the encoder and virtual display), on onStart resume and request a keyframe; consider a foreground service if background mirroring is intended.

## FUN-09 - No automatic reconnect (Medium, effort M, V)
Where: `MirrorActivity.kt:640-649; MirrorClient.kt:56-160`

Every failure ends in a toast followed by finish(). MirrorClient is single-use (its scope is cancelled on disconnect), so a brief Wi-Fi hiccup always costs a manual reconnect.

Recommendation: Connection state machine (Connecting, Streaming, Reconnecting, Failed) with exponential back-off and a visible overlay.

## FUN-10 - Daemon is not supervised; stale boot script contradicts docs (Medium, effort S, V)
Where: `DaemonController.kt:191-216; magisk/mirror.sh:53-71`

The auto-start script generated by the Host app launches the daemon once. If the daemon dies (the uncaught-exception handler only logs) nothing restarts it. The repository still ships magisk/mirror.sh, an older script with an infinite restart loop and a different APK path, although the technical summary says that loop was removed.

Recommendation: Supervise with a bounded back-off loop in service.d or a watchdog in the Host app; delete the legacy script and legacy names.

## FUN-11 - Screenshot is view-sized and unsupported on API 26-28 (Low, effort S, V)
Where: `MirrorActivity.kt:418-472`

PixelCopy captures the SurfaceView at its on-screen size, not at the stream resolution, so it is not lossless as described. minSdk is 26 but writing to the gallery on API 26-28 needs WRITE_EXTERNAL_STORAGE, which is not declared.

Recommendation: Capture from the decoder output (ImageReader) or label it 'view capture'; guard or declare the storage permission for API < 29.

## FUN-12 - Settings screen shows fabricated telemetry (Low, effort S, V)
Where: `SettingsActivity.kt:269-271, 304-306`

When no session has run, the live-preview card shows 12 ms, 60 FPS and 8.0 Mbps as if they were measurements.

Recommendation: Label the card 'Preview' or show dashes until real data exists.

## FUN-13 - ACCESS_LOCAL_NETWORK is requested before it applies (Low, effort S, V)
Where: `ConnectActivity.kt:227-237; client AndroidManifest.xml:467`

The viewer requests the permission at runtime on SDK 34+, but Android's local-network guidance says apps targeting below 37 receive an implicit grant and should not request it. The request is meaningless today and will become mandatory when the app targets 37.

Recommendation: Remove the runtime request until targetSdk 37; then implement the request flow or use the system device pickers.

## FUN-14 - Feature gaps against scrcpy and Duplila (Info, effort L, V)
Where: `-`

Not present: audio, clipboard sync, text input and hardware keyboard, file transfer, USB transport, display-off while mirroring, multi-device, and connection discovery.

Recommendation: Prioritise audio, clipboard and text input after the stability work (Section 12 roadmap).

## PERF-01 - Blocking socket writes build latency instead of dropping frames (High, effort M, V)
Where: `ScreenEncoder.kt:162-165; VeloCastDaemonMain.kt:141, 193`

The encoder thread writes each frame synchronously under a lock shared with pong replies. A 256 KB send buffer holds about 260 ms of video at 8 Mbps (about 105 ms at 20 Mbps), and Linux typically doubles the requested size, so write() may not block until even more is queued; the encoder keeps producing meanwhile. On a congested link the viewer falls behind and never catches up, and RTT pongs queue behind video.

Recommendation: Bounded frame queue with a latest-wins policy (drop non-reference frames, request IDR), smaller SO_SNDBUF, separate priority path for pongs, congestion feedback to the bitrate controller.

## PERF-02 - Client queue applies backpressure and has no catch-up policy (High, effort S, V)
Where: `MirrorClient.kt:46, 197`

incomingNalQueue has 24 slots and uses a suspending send, so a slow decoder blocks the TCP reader and the stall propagates to the host. The class comment promises zero backpressure; the code does the opposite.

Recommendation: Use DROP_OLDEST with a 'wait for keyframe' flag so the viewer skips to the newest picture; keep queue depth at 2-4 frames.

## PERF-03 - Per-frame and per-packet allocations (Medium, effort M, V)
Where: `MirrorClient.kt:184, 197; client Protocol.kt:453-460; server Protocol.kt:339-348; InputInjector.kt:322-323`

Each NAL becomes a fresh ByteArray plus a NalPacket; each control packet allocates a ByteBuffer and array; the server allocates a ControlPacket and two arrays per touch event. At 60-120 Hz this feeds the garbage collector on older SoCs.

Recommendation: Pool buffers (ring of preallocated arrays), reuse packet objects, batch MOVE events.

## PERF-04 - Encoder configuration is incomplete relative to the documentation (Medium, effort S, D)
Where: `ScreenEncoder.kt:214-242`

KEY_BITRATE_MODE, KEY_MAX_B_FRAMES and profile/level are never set, although the technical summary lists VBR and zero B-frames. Behaviour is therefore device-default. Low-latency streaming normally wants an explicit rate-control mode and no B-frames.

Recommendation: Set rate control explicitly (benchmark CBR against VBR for latency and heat), cap B-frames, pick a constrained profile and prepend headers to sync frames; verify with MediaCodecInfo capabilities.

## PERF-05 - FPS and scale changes tear down the whole pipeline (Medium, effort M, V)
Where: `ScreenEncoder.kt:56-69, 126-131`

Only a bitrate change is applied live. Any FPS or scale change stops capture, releases the codec and waits 200 ms before rebuilding, producing a visible stall.

Recommendation: Apply scale and FPS changes at the next IDR through output-size negotiation, or accept the restart but hide it with a frozen last frame.

## PERF-06 - Host UI spawns two root shells every two seconds (Medium, effort M, V)
Where: `HostMainActivity.kt:154-170; DaemonController.kt:68-106, 232-235`

While the Host screen is open, each poll runs su twice: a full /proc scan plus TCP-table grep, and a log tail. On the old, thermally constrained phone this adds process spawns and wake-ups and makes Magisk log every call.

Recommendation: Use one persistent root shell (for example libsu) or a status socket exposed by the daemon; poll slowly and only when the screen is visible.

## PERF-07 - Synchronous MediaCodec with long lock holds (Medium, effort M, V)
Where: `VideoDecoder.kt:316-352`

feedNalUnit holds codecLock while it waits up to 15 ms (25 ms for large or config frames) for an input buffer, blocking surface changes and the output drain in the same lock.

Recommendation: Switch to asynchronous callback mode (setCallback) with a small input queue and a dedicated render thread.

## PERF-08 - No true end-to-end latency measurement (Medium, effort M, V)
Where: `MirrorClient.kt:119-133, 174-181`

The HUD ping measures the control channel only. Capture, encode, queueing, decode and display time are invisible, so tuning is guesswork.

Recommendation: Add a monotonic capture timestamp to the frame header and estimate clock offset from pings; log per-stage timings; validate with a high-speed camera test.

## PERF-09 - Long GOP plus idle repeat trade recovery speed for heat (Low, effort S, V)
Where: `ScreenEncoder.kt:228-231`

A 5 s I-frame interval and 250 ms repeat save power on static screens but lengthen artefact recovery (FUN-02).

Recommendation: Keep the long GOP only together with on-demand IDR; optionally enable periodic intra-refresh.

## PERF-10 - Bitrate and resolution are static user choices (Low, effort M, V)
Where: `ConnectActivity.kt:145-158`

There is no adaptation to link quality; the user must guess between 4 and 20 Mbps.

Recommendation: Adaptive bitrate driven by send-queue depth and RTT, with the user value as a ceiling.

## PERF-11 - H.264 only (Low, effort M, V)
Where: `ScreenEncoder.kt:198`

Many devices have hardware HEVC encoders and decoders that deliver the same quality at lower bitrates, which also lowers radio and thermal load.

Recommendation: Negotiate H.265 when both ends report hardware support; keep H.264 as fallback.

## CQ-01 - No automated tests, CI, lint or static analysis (High, effort M, V)
Where: `repository-wide`

There are no test source sets and no workflow files. Protocol parsing, the touch state machine and the codec fallbacks are untested, and every change relies on manual device checks.

Recommendation: Add unit tests for protocol and state machines, instrumentation smoke tests, ktlint/detekt/Android Lint, and a CI pipeline that builds both modules on every push.

## CQ-02 - Wire protocol duplicated in both modules, unversioned (Medium, effort M, V)
Where: `server Protocol.kt (102 lines); client Protocol.kt (45 lines)`

The two copies can drift silently and there is no magic number or version field, so a mismatched client and server fail in confusing ways.

Recommendation: Extract a pure-JVM :protocol module shared by both apps, add a version handshake and round-trip/fuzz tests.

## CQ-03 - Heavy reliance on hidden APIs with silent fallbacks (Medium, effort L, V)
Where: `VirtualDisplayBridge.kt; InputInjector.kt (34 reflection call sites)`

Failures downgrade silently (for example rotation falls back to spawning the settings binary). The project has no compatibility matrix, so a new Android release can break capture or input without any signal. scrcpy needed a dedicated fix after the Android 16 upgrade.

Recommendation: Isolate platform shims per API level, report capabilities to the client, add emulator-matrix tests, and track upstream scrcpy fixes.

## CQ-04 - Concurrency hygiene (Medium, effort M, V)
Where: `MirrorActivity.kt:67-72, 612-624; ScreenEncoder.kt:83-86; VeloCastDaemonMain.kt:124-131`

Several fields are written from IO or codec threads and read from the UI without synchronisation; ScreenEncoder.streamTo() sets running to true after stop() may already have been called; session close() runs on the accept thread with a 1.5 s join.

Recommendation: Single-owner state holders (StateFlow / confined dispatcher), explicit session state machine, structured concurrency on the server.

## CQ-05 - Oversized activities and global mutable state (Medium, effort L, V)
Where: `MirrorActivity.kt (698 lines); SettingsActivity.kt (462) + activity_settings.xml (1,500); MirrorPreferences.kt:122`

UI construction, input handling, networking wiring, screenshot I/O and thread creation share one class; TelemetryState is a global singleton. This makes behaviour hard to test and to change.

Recommendation: Introduce ViewModels with immutable UI state, move session logic to a service/repository layer, split settings into components.

## CQ-06 - Error handling swallows information (Medium, effort M, V)
Where: `22 occurrences of catch (_: Throwable); 44 println/System.err calls (server)`

Exceptions are ignored or printed to a file without levels or structure, and the protocol has no way to tell the client why something failed.

Recommendation: Introduce a small logger with levels, typed errors, and a STATUS/ERROR message from server to client.

## CQ-07 - Dead and legacy code paths (Low, effort S, V)
Where: `VirtualDisplayBridge.kt:393-439; DaemonController.kt:161; magisk/mirror.sh`

The SurfaceControl fallback targets signatures that modern Android no longer exposes, and legacy names (MirrorServerMain, mirror-server.apk) linger in scripts.

Recommendation: Remove or quarantine behind a version check; delete legacy files.

## CQ-08 - Hard-coded UI strings (Low, effort M, V)
Where: `108 android:text literals in layouts`

No localisation, and no consistent content descriptions for accessibility.

Recommendation: Move to strings.xml, add contentDescription for icon buttons, test with TalkBack.

## CQ-09 - Manual SharedPreferences boilerplate (Low, effort M, V)
Where: `MirrorPreferences.kt (270 lines)`

Hand-written getters and setters for ~40 keys.

Recommendation: Typed settings object on Jetpack DataStore.

## CQ-10 - Headless daemon is packaged inside a Material/AppCompat app (Low, effort M, V)
Where: `server/build.gradle.kts; server-debug.apk 12.9 MB`

The root process only needs the framework and the hidden-API bypass library, yet it is launched from the full UI APK.

Recommendation: Split a lean :server-core module (dex jar) from the Host UI.

## BLD-01 - Build outputs and IDE state are committed (High, effort S, V)
Where: `git index: 1,522 tracked files, 1,392 under build/`

No .gitignore exists. Compiled intermediates, debug APKs, Gradle caches (.gradle/), the IDE folder and local.properties are all tracked; .git is 32 MB and the working tree carries about 90 MB of build output.

Recommendation: Add .gitignore, git rm -r --cached, rewrite history with git filter-repo and force-push (acceptable for a single-developer repo).

## BLD-02 - Not publishable on Google Play with targetSdk 34 (High, effort M, V)
Where: `client/build.gradle.kts, server/build.gradle.kts`

Since 31 August 2026 Play requires new apps and updates to target API 36, and existing apps that fall below the minimum target stop being offered to new users on devices running a newer Android version than the app targets. A viewer targeting 34 would therefore not be offered to new users on the Android 15/16 phones it is meant for.

Recommendation: Raise targetSdk to 36 (then plan 37) and review behaviour changes: edge-to-edge, foreground service types, local network, large-screen orientation rules.

## BLD-03 - No release build configuration (Medium, effort M, V)
Where: `build.gradle.kts files`

Minification is off, there is no signing configuration, no versioning automation and no reproducible-build setup; only debug APKs are documented.

Recommendation: Enable R8 with keep rules for the reflection-based server, add signing through CI secrets, and generate version codes from tags.

## BLD-04 - Dependency and Gradle hygiene (Low, effort S, V)
Where: `build.gradle.kts; build/reports/problems`

Versions are hard-coded per module without a version catalog; the Gradle problems report records a deprecation (multi-string dependency notation) that will fail in Gradle 10; no automated dependency updates.

Recommendation: Introduce libs.versions.toml and Renovate/Dependabot; keep AGP, Gradle and Kotlin on a tested combination.

## BLD-05 - Toolchain inconsistency (Info, effort S, V)
Where: `gradle/gradle-daemon-jvm.properties; module build files`

The Gradle daemon is pinned to JDK 25 while Kotlin and Java target 17, and compileSdk is 36 while targetSdk is 34.

Recommendation: Document the intended toolchain and pin it in CI.

## DOC-01 - Documentation and code disagree (Medium, effort S, V)
Where: `README.md; Technical Summary; source`

See Section 9.2: VBR, zero B-frames, GOP length, idle repeat, watchdog timeout, boot-script behaviour and the 'zero backpressure' claim differ between the README, the technical summary and the code.

Recommendation: Generate the parameter list from code constants, keep one authoritative architecture document, and review docs in pull requests.

## DOC-02 - Missing project documents (Low, effort S, V)
Where: `repository root`

There is no LICENSE, protocol specification, troubleshooting guide or contribution guide.

Recommendation: Add LICENSE (needed before sharing), docs/protocol.md, docs/troubleshooting.md.

---

## Findings traceability (`S00`–`S07A`)

| Finding | Status | Evidence / Where closed |
| --- | --- | --- |
| `SEC-01` | Closed (`S02`, `S03A`, `S06`) | Mandatory `HELLO`/`SERVER_HELLO`/`AUTH_PROOF` handshake (`HMAC-SHA256` with `HKDF-SHA256` derived key over `SHA-256(transcript)`), constant-time comparison, mandatory `SECURE_CHANNEL`, and per-IP back-off before any session frame or input (`HkdfAuthenticator`, `SessionGuard`, `SecurityCryptoTest`, `SessionGuardTest`, `ServerSessionSecurityTest`). |
| `SEC-02` | Closed (`S06`) | All post-handshake `Phase.SESSION` traffic wrapped in `AES-256-GCM` records with 96-bit directional counter nonces, 16-byte AAD headers, strict counter monotonicity, automatic HKDF rekeying before `2^32` frames, `AndroidKeyStore`-encrypted secret files, and `SecretRedactor` log scrubbing (`AesGcmSessionCipher`, `EncryptedFileKeyStore`, `AeadFramingAndKeyStoreTest`, `ServerSessionSecurityTest`, `ClientSecurityWiringTest`, [security.md](security.md)). |
| `SEC-03` | Closed (`S03A`) | `SessionGuard` authenticates new peers before pre-empting an active session and applies per-IP exponential back-off (`SessionGuardTest`). |
| `SEC-04` | Evidence proven (`S05A`, `S05B`); ADB client/pairing in `S07A`, launcher cutover in `S07B`/`S09` | `:server-core` verified end-to-end as unprivileged `uid 2000` (`LaunchMode.SHELL`, `FakeContext` `com.android.shell`) across all 7 capabilities ([spike-shell-user.md](spike-shell-user.md)); `AdbClient` wireless pairing, `STLS` TLS 1.3 connect, `shell`, `push`, and `openStream` added in `:launcher-adb` (`AdbClientIntegrationTest`). |
| `SEC-05` | Closed (`S02`, `S03A`) | Strict `KeyCodes` allow-list, pointer ID `0..31`, finite `[0, 1]` coordinates, and `PointerStateTracker` enforced in `:protocol` and `InputInjector` (`ValidatorsTest`, `ServerSessionValidationTest`). |
| `SEC-06` | Partial (`S03A`; root launcher in `S10`) | Removed hardcoded `/data/local/tmp` state/log paths from `:server-core`; `--state-dir` is explicit (`ServerArgsAndMainTest`). |
| `SEC-09` | Closed (`S00`) | `.gitignore` added, tracked IDE/build state removed, default LAN IP removed. |
| `FUN-01` | Closed (`S03B`) | `RotationWatcherShim` + `RotationStateMachine` rebuild capture/encoder on rotation, emit `VIDEO_SIZE` (`RESET`) + `CONFIG` + IDR, and update `InputInjector` dimensions (`RotationStateMachineTest`). |
| `FUN-02` | Closed (`S02`, `S03B`, `S04B`) | `KEYFRAME_REQ` wired end-to-end; `VideoFrameQueue` drops oldest and waits for IDR (`VideoFrameQueueTest`, `VideoDecoderInstrumentedTest`). |
| `FUN-03` | Closed (`S04A`) | 2 s `PING`/`PONG` heartbeats, 5 s socket `soTimeout`, and 6 s silence watchdog (`SessionEngineLivenessAndReconnectTest`). |
| `FUN-04` | Closed (`S04B`) | `VideoDecoder` sets `KEY_MAX_WIDTH`/`KEY_MAX_HEIGHT` and recreates cleanly on `CONFIG` geometry changes (`VideoDecoderTest`). |
| `FUN-05` | Closed (`S03A`, `S04B`) | Reliable `DOWN`/`UP`/`CANCEL` delivery with `MOVE` coalescing in `TouchPipeline` and strict server `PointerStateTracker` + teardown cancel (`TouchPipelineTest`, `TouchStateMachineTest`). |
| `FUN-06` | Closed (`S03B`, hardened in `S05B`) | `AndroidDeviceStateController` + out-of-process `setsid` `CleanUp` snapshot and restore rotation lock and display power using `FakeContext` caller identity (`StateRestoreAndCleanUpTest`). |
| `FUN-07` | Server & capability layer closed (`S03B`, `S05B`; viewer UI in `S11`) | `DisplayPowerShim` controls panel power while capture continues (including Android 14+ `DisplayControl` via `ClassLoaderFactory` + `libandroid_servers.so`), verified as `uid 2000` on Android 16 & 17. |
| `FUN-08` | Engine closed (`S04A`, `S04B`; Compose UI in `S13`) | `PAUSE`/`RESUME` stops encoding on background and resumes with an IDR frame (`SessionEnginePauseResumeAndCancellationTest`). |
| `FUN-09` | Engine closed (`S04A`; Compose UI in `S13`) | `SessionEngine` reconnects with exponential back-off for up to 10 minutes (`SessionEngineLivenessAndReconnectTest`). |
| `FUN-13` | Closed (`S07A`) | Removed premature `SDK_INT >= 34` `ACCESS_LOCAL_NETWORK` runtime prompt and manifest entry while `targetSdk < 37`; centralized API 37+ policy in `:launcher-adb` `LocalNetworkPermissionGate` (`AdbQrPayloadAndPermissionTest`). |
| `PERF-01` | Closed (`S03B`) | Bounded 3-frame latest-wins `FrameQueue`, priority control lane for `PONG`/`STATUS`/`ERROR`, `TCP_NODELAY`, and 64 KB `SO_SNDBUF` (`FrameQueueTest`, `SendPathStallAndPriorityTest`). |
| `PERF-02` | Closed (`S04B`) | Capacity-6 `DROP_OLDEST` `VideoFrameQueue` with `< 50 ms` measured recovery after 1 s stall (`VideoDecoderInstrumentedTest`). |
| `PERF-03` | Closed (`S03A`, `S04B`) | Zero-allocation touch arrays in `InputInjector` and `FrameBufferPool` / `ControlPacketPool` in `:client-core` (`TouchStateMachineTest`, `FrameBufferPoolAndZeroAllocTest`). |
| `PERF-04` | Closed (`S03B`) | Explicit `CBR`/`VBR`, `KEY_MAX_B_FRAMES = 0`, constrained baseline profile, and sync-frame headers (`EncoderSettingsAndNalTest`). |
| `PERF-07` | Closed (`S04B`) | Asynchronous `MediaCodec.Callback` decoder with dedicated render thread (`VideoDecoderTest`). |
| `PERF-08` | Plumbing closed (`S02`, `S03B`, `S04B`; camera benchmark in `S15`) | Monotonic `captureUs` + min-RTT `PING`/`PONG` clock offset + per-stage `SessionMetrics` (`SessionMetricsTest`). |
| `PERF-09` | Closed (`S03B`) | Long GOP paired with on-demand `PARAMETER_KEY_REQUEST_SYNC_FRAME` (`EncoderSettingsAndNalTest`). |
| `CQ-02` | Closed (`S02`) | Shared pure-JVM `:protocol` module with golden vectors and 1M+ fuzz inputs (`GoldenVectorTest`, `FuzzTest`). |
| `CQ-03` | Closed for `:server-core` (`S05A`, `S05B`); emulator/lab matrix in `S07`/`S16` | Hidden APIs isolated in `server-core/.../platform/` (`PlatformApiPolicy`, `FakeContext`, `DisplayPowerShim`, `ClipboardShim`, `VirtualDisplayBridge`, `BinderInjectorSink`, `RotationWatcherShim`), verified by `CapabilityProbe` and reported to the viewer via `STATUS` ([spike-shell-user.md](spike-shell-user.md)). |
| `CQ-04` | Closed in `:server-core` & `:client-core` (`S03A`, `S04A`, `S04B`) | Single-owner `ServerSession` and coroutine-confined `SessionEngine` `StateFlow`. |
| `CQ-06` | Closed (`S02`, `S03A`, `S03B`, `S05B`) | Structured `Logger`, typed `ERROR`/`STATUS` frames (`INJECTION_DENIED`, `SERVER_START_FAILED`, `StatusKind.DEGRADED`), and viewer `SessionState.Streaming` capability tracking. |
| `CQ-07` | Closed (`S00`, `S03A`, `S05B`) | Deleted `magisk/mirror.sh`, legacy `SurfaceControl` display capture fallback, and raw `service call statusbar` transaction numbers. |
| `CQ-10` | Closed (`S01`, `S05A`) | Lean `:server-core` module assembled as standalone `velocast-server.jar` (`:server-core:assembleDexJar`) with zero UI dependencies (`verifyModuleDependencies`). |
| `BLD-01` | Closed (`S00`) | Build outputs and IDE files untracked and guarded by `.gitignore`. |
| `BLD-02` | Partial (`S01`; release Play checks in `S17`) | `compileSdk` and `targetSdk` raised to `36` across modules. |
| `BLD-04` | Closed (`S01`) | Centralized `gradle/libs.versions.toml` and non-deprecated Gradle DSL. |
| `BLD-05` | Closed (`S01`) | Toolchain pinned and documented in CI and Gradle config. |
| `DOC-01` | Partial (`S00`, `S02`, `S05B`; final audit in `S17`) | `README.md`, `docs/protocol.md`, `docs/architecture.md`, and `docs/spike-shell-user.md` match code constants and behaviour. |
| `DOC-02` | Partial (`S00`, `S02`, `S05B`; troubleshooting guide in `S17`) | Added `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, `docs/protocol.md`, and `docs/spike-shell-user.md`. |
