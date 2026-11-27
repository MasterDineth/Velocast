# PROGRESS

Update this file whenever you stop. The owner will say "Continue" and you resume from here.

## Current step
S05B — Shell-user spike results, capability matrix & platform shim fixes (complete on `step/S05b-spike-results`, closing `SEC-04` evidence and `CQ-03` probe/shims).

## Done
- S01 — Restructure, toolchain and SDK 36 (merged in PR #2).
- S02 — Protocol v2 module (merged in PR #4).
- S03A — `:server-core` refactor & hardening (merged in PR #5).
- S03B — `:server-core` media pipeline hardening (complete on `step/S03b-media`):
  - Configured `ScreenEncoder` (`EncoderSettings.kt`, `H264NalHelper.kt`): `KEY_BITRATE_MODE` (`CBR` default with `--bitrate-mode VBR` benchmark switch), `KEY_MAX_B_FRAMES = 0`, `KEY_PROFILE = AVCProfileConstrainedBaseline` when `MediaCodecInfo` reports support, `KEY_PREPEND_HEADER_TO_SYNC_FRAMES = 1`, `KEY_LOW_LATENCY = 1`, `KEY_PRIORITY = 0`; chosen settings reported in `STATUS` (`BITRATE`, `FPS`).
  - Added on-demand IDR requests (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) on `KEYFRAME_REQ`, `RESUME`, size/rotation change, and any dropped frame, plus monotonic `captureUs` in every `VIDEO` frame header.
  - Added bounded 3-frame latest-wins `FrameQueue` (`FrameQueue.kt`, dropping oldest non-keyframes first and requesting an IDR), dedicated writer thread in `SessionFrameSink` prioritizing `PONG`, `STATUS`, `ERROR`, `DEVICE_INFO`, and `BYE` over video, `TCP_NODELAY`, and configurable `SO_SNDBUF` defaulting to 64 KB (`--send-buffer-bytes`).
  - Added `RotationStateMachine` + `RotationWatcherShim` (`IWindowManager.watchRotation` + `500 ms` `VirtualDisplayBridge` poll), rebuilding capture and encoder on size/rotation changes, sending `VIDEO_SIZE` then `CONFIG` then an IDR, and atomically updating `InputInjector` dimensions.
  - Added session-start capture & teardown restore of rotation lock and display power (`DeviceStateController`, `DisplayPowerShim`), plus out-of-process `setsid` `com.mastersoft.velocast.server.CleanUp` guardian (`CleanUp.kt`, `CleanUpProcessGuardian.kt`) for `kill -9` recovery.
  - Added capability `STATUS` reporting (`rotation_watcher`, `display_power`, `notification_shade`, `wake`) + `StatusKind.DEGRADED` entries and non-fatal `ErrorCode.INJECTION_DENIED` reporting on refused input injection.
  - Added unit and instrumentation tests (`FrameQueueTest`, `SendPathStallAndPriorityTest`, `RotationStateMachineTest`, `EncoderSettingsAndNalTest`, `StateRestoreAndCleanUpTest`, `CapabilitiesAndInjectionDeniedTest`, `ScreenEncoderInstrumentedTest`).
- S04A — `:client-core` session engine & `MirrorClient` transport (complete on `step/S04a-session`):
  - Added `SessionEngine` (`SessionEngine.kt`, `ActiveSessionMonitor.kt`, `SessionHandshakeRunner.kt`) with immutable `SessionState` via `StateFlow` covering `Idle`, `Discovering`, `Connecting`, `StartingServer`, `Handshaking`, `Streaming`, `Paused`, `Reconnecting`, and `Failed`.
  - Injected `ServerLauncher`, `StreamTransport`, `SessionClock`, `SurfaceController`, and `SessionFrameConsumer` interfaces for Android-free unit testing.
  - Implemented 2 s bidirectional heartbeats, 5 s socket read timeout, 6 s silence watchdog (`LINK_LOST`), and exponential-backoff reconnect (1, 2, 4, 8, 15 s cap for up to 10 minutes configurable) that re-handshakes first and relaunches the server only when unreachable.
  - Added sealed `SessionError` with `ErrorCode`, `causeKey`, and optional `detail`, mapping server `ERROR` frames and protocol violations.
  - Implemented `pause()` (`PAUSE` + surface release) and `resume()` (`RESUME` + `KEYFRAME_REQ`, or rediscovery if link died while paused).
  - Implemented reusable `MirrorClient` (`MirrorClient.kt`) as a `StreamTransport` with dedicated reader/writer coroutines, handshake-to-session phase gating, and structured cancellation.
  - Added unit tests (`SessionErrorTest`, `SessionEngineTransitionsTest`, `SessionEngineLivenessAndReconnectTest`, `SessionEnginePauseResumeAndCancellationTest`, `MirrorClientTest`).
- S04B — `:client-core` media pipeline, touch, metrics & `:app-viewer` v2 wiring (complete on `step/S04b-decoder`, closing `FUN-02`, `FUN-04`, `FUN-05`, `PERF-02`, `PERF-03`, `PERF-07`, `PERF-08`):
  - Added `FrameBufferPool` and `ControlPacketPool` (`FrameBufferPool.kt`, `ControlPacketPool.kt`) wired into `MirrorClient`, `StreamTransport`, and `ActiveSessionMonitor` for zero-allocation hot-path `VIDEO` reads and `ControlPacket` writes.
  - Added capacity-6 `BufferOverflow.DROP_OLDEST` `VideoFrameQueue` (`VideoFrameQueue.kt`) that sets `waitForKeyframe`, emits `KEYFRAME_REQ`, and skips non-key frames until the next `FrameFlags.KEYFRAME`.
  - Replaced synchronous `MediaCodec` polling with asynchronous `MediaCodec.Callback` decoding (`VideoDecoder.kt`, `H264SpsParser.kt`), bounded input queue (capacity `4`), dedicated `"velocast-decoder-render"` render thread, `KEY_MAX_WIDTH`/`KEY_MAX_HEIGHT` adaptive playback, `CONFIG`-size recreation, and `CodecException` recovery (`isTransient` flush, `isRecoverable` reset, else recreate + `KEYFRAME_REQ` / loud `DecoderState.Failed` escalation).
  - Added `ViewportMapper` and `TouchPipeline` (`ViewportMapper.kt`, `TouchPipeline.kt`) with reliable ordered delivery for `DOWN`/`UP`/`CANCEL`, per-pointer `MOVE` coalescing, pause/disconnect pointer cancellation (`SessionLifecycleHooks`), and centered letterbox coordinate mapping driven by `VIDEO_SIZE`.
  - Added `SessionMetrics` (`SessionMetrics.kt`) computing per-frame `receiveQueueUs`, `decodeUs`, `renderUs`, `captureToReceiveUs`, `endToEndUs`, rolling p50/p95 stats, and min-RTT `PING`/`PONG` clock offset via `StateFlow` plus `DiagnosticsRingBuffer`, and wired `ClientMediaSession` into `:app-viewer` (`MirrorActivity.kt`, `TouchOverlayController.kt`, `MirrorPreferences.kt`), removing legacy v1 client/decoder/protocol classes.
  - Added unit & recorded-H.264 tests (`FrameBufferPoolAndZeroAllocTest`, `VideoFrameQueueTest`, `VideoDecoderTest`, `TouchPipelineTest`, `SessionMetricsTest`, `ViewerTouchWiringTest`) plus `VideoDecoderInstrumentedTest` verified on two physical Android devices after an induced 1-second consumer stall:
    - Host `6f8d1516` (`M2101K6G`, Android 16): `preMedianUs=10862`, `postMedianUs=10505`, `recoveryMs=31`, `maxQueueDepth=6`, `dropped=50`, `skipped=0`, `keyframeReqs=52`, `rendered=104`.
    - Client `fba5cfe8` (`25019PNF3C`, Android 17): `preMedianUs=2980`, `postMedianUs=3374`, `recoveryMs=5`, `maxQueueDepth=6`, `dropped=47`, `skipped=0`, `keyframeReqs=47`, `rendered=107`.
- S05A — Shell-user feasibility spike tooling & `CapabilityProbe` (complete on `step/S05a-spike`):
  - Added `PlatformContext`, `ShellIdentity`, and `FakeContext` (`FakeContext.kt`) in `com.mastersoft.velocast.server.platform` (default package `com.android.shell`, UID-aware `callerUserId`/`isShellUid`/`isRootUid`/`AttributionSource`) without UI libraries or app `Context`.
  - Added `CapabilityProbe`, `ProbeResult` (`Supported`, `Unsupported`, `Denied`), `CapabilityProbeBackend`, `AndroidCapabilityProbeBackend`, and `CapabilityProbeMain` exercising `virtual_display`, `input_injection`, `notification_shade`, `rotation`, `display_power`, `wake`, and `clipboard`, emitting one JSON line per capability.
  - Added `:server-core:assembleDexJar` Gradle task (`server-core/build.gradle.kts`) and spike scripts `tools/spike/run-shell-server.sh`, `tools/spike/run-shell-server.ps1`, and `tools/spike/README.md`.
  - Added unit tests (`CapabilityProbeTest`, `FakeContextIdentityTest`) and ran `run-shell-server.ps1` against `M2101K6G` (Android 16, API 36) and `25019PNF3C` (Android 17, API 37), saving results in `tools/spike/results/`.
- S05B — Shell-user capability matrix, `platform/` shim fixes & `STATUS` capability reporting (complete on `step/S05b-spike-results`):
  - Fixed Android 14+ (`API 34+`) `DisplayPowerShim` `UnsatisfiedLinkError` by loading `com.android.server.display.DisplayControl` from `/system/framework/services.jar` via `ClassLoaderFactory.createClassLoader(..., isNamespaceShared = true)` and binding `libandroid_servers.so` via `Runtime.loadLibrary0` cached once per `app_process` lifetime, with eager token verification and API 26-28 / 29-33 `SurfaceControl` fallbacks.
  - Added `PlatformApiPolicy` and `ClipboardShim` (`ClipboardBackend`, `ReflectionClipboardBackend`, `ClipboardBinderInvoker`) supporting AOSP API 26-37 and 6-arg OEM `IClipboard` signatures with `FakeContext` (`com.android.shell`), plus `CLIPBOARD` session handling and optional `KEYCODE_PASTE` injection in `ServerSession`.
  - Updated `VirtualDisplayBridge` (`FakeContext` + `VirtualDisplayConfig` / `DisplayManager(Context)` per-API-level fallbacks), `BinderInjectorSink` (`InputManagerGlobal` API 34+ vs `InputManager` API 26-33, startup `KEYCODE_UNKNOWN` injection probe, removal of raw `service call statusbar` transaction IDs, `com.android.shell` caller package on `IWindowManager.freezeRotation`/`thawRotation`), `AndroidDeviceStateController`, and `CleanUp`.
  - Expanded `ServerCapabilities` (`input_injection`, `rotation_watcher`, `display_power`, `notification_shade`, `wake`, `clipboard`) and wired viewer-side `STATUS` parsing (`StatusKind.DISPLAY_POWER` summary + `StatusKind.DEGRADED`) into `SessionState.Streaming.serverCapabilities` and `degradedCapabilities` in `:client-core`.
  - Verified **7 / 7** `supported` capabilities in `tools/spike/results/M2101K6G-android36.json` and `tools/spike/results/25019PNF3C-android37.json` and completed full protocol-v2 streaming sessions as `uid 2000` (`LaunchMode.SHELL`, `capabilities=0x1a`) on both physical phones.
  - Wrote `docs/spike-shell-user.md` and updated `docs/architecture.md`, `docs/findings.md`, `docs/README.md`, and `tools/spike/README.md`.

## Next
- Proceed to S06 — Session security: pairing secret, key derivation and AEAD (`step/S06-security`).

## Open questions
- None.

## Owner checks pending (needs a real phone)
- Confirm on device (`S05`): run `tools/spike/run-shell-server.sh` (or `.ps1`) on physical Android 11 (API 30), Android 13 (API 33), and Android 14 (API 34, e.g. Samsung One UI) phones, plus on Xiaomi HyperOS with **USB debugging (Security settings)** toggled off to observe `Denied` / `INJECTION_DENIED`, and commit the resulting JSON files under `tools/spike/results/`.
- On the rooted old phone, start the server through `:app-host` (`DaemonController` -> `com.mastersoft.velocast.server.Main` with `/data/adb/velocast`), connect, rotate three times, `kill -9` the server, and confirm rotation lock and display state are restored.
