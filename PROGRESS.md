# PROGRESS

Update this file whenever you stop. The owner will say "Continue" and you resume from here.

## Current step
S04A — `:client-core` session engine, liveness, backoff reconnect, typed errors, pause/resume, and `MirrorClient` transport (complete on `step/S04a-session`).

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

## Next
- S04B — `:client-core` asynchronous `VideoDecoder`, `DROP_OLDEST` frame queue, buffer pools, touch pipeline, and stage latency metrics.

## Open questions
- None.

## Owner checks pending (needs a real phone)
- On the rooted old phone, start the server through `:app-host` (`DaemonController` -> `com.mastersoft.velocast.server.Main` with `/data/adb/velocast`), connect, rotate three times, `kill -9` the server, and confirm rotation lock and display state are restored.
- Run `./gradlew :server-core:connectedDebugAndroidTest` on a physical device or emulator if not already verified on CI/local device.
