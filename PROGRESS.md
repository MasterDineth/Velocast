# PROGRESS

Update this file whenever you stop. The owner will say "Continue" and you resume from here.

## Current step
S03A — `:server-core` extraction, `SessionGuard`, state machine, input validation, touch state machine, and `Logger` (complete on `step/S03a-server-core`).

## Done
- S01 — Restructure, toolchain and SDK 36 (merged in PR #2).
- S02 — Protocol v2 module (merged in PR #4).
- S03A — `:server-core` refactor & hardening:
  - Added entry point `com.mastersoft.velocast.server.Main` (`--port`, `--bind IP|loopback`, `--state-dir`, `--secret-from-stdin`, `--log-level`) and `ServerRunner`, printing `"READY port=<p> nonce=<n>"` and flushing stdout.
  - Replaced the unauthenticated accept loop with `SessionGuard` (3 s total handshake deadline via `HandshakeTimer`, per-IP exponential back-off via `PeerBackoffTracker`, authenticated-only replacement, one active session at a time) and `HmacSha256Authenticator` (`Authenticator`).
  - Added `ServerSession` state machine (`Handshaking`, `Active`, `Closing`) with single resource ownership (`PeerSocket`, `InputInjector`, `MediaPipeline`, `SessionFrameSink`) and structured shutdown without unsynchronized flags or accept-thread joins.
  - Wired `:protocol` validators (`ControlReader`, `SeqTracker`, `PointerStateTracker`, `KeyCodes`, `TextInput`, `ClipboardMessage`, `Validators`); violations send fatal `ERROR` and close the session.
  - Rewrote `InputInjector` touch state machine with strict pointer validation, `ACTION_CANCEL` on teardown and `PAUSE`, and pre-allocated primitive arrays (zero per-event allocation); isolated Android Binder IPC into `BinderInjectorSink`.
  - Replaced `/data/local/tmp` assumptions, `println`, `System.err`, and silent `catch (_: Throwable)` with `Logger` (levels, bounded ring buffer, optional `--state-dir/server.log`) and explicit cause logging.
  - Removed `SurfaceControl` fallback and legacy `com.mastersoft.velocast.daemon` / `VeloCastDaemonMain` / v1 `Protocol`; `VirtualDisplayBridge` throws `UnsupportedPlatformException` when display mirroring is unavailable.
  - Updated `:app-host` (`DaemonController`, `HostMainActivity`) to launch `com.mastersoft.velocast.server.Main` with `/data/adb/velocast` (`--state-dir`) and `secret.hex` on stdin.
  - Added JUnit suites (`LoggerTest`, `ServerArgsAndMainTest`, `SessionGuardTest`, `ServerSessionValidationTest`, `TouchStateMachineTest`, `VirtualDisplayBridgeTest`).

## Next
- S03B — Media pipeline hardening (`FrameQueue`, CBR/codec configuration, on-demand keyframes, rotation watcher, state restore + cleanup process, capture timestamps, capability report).

## Open questions
- None.

## Owner checks pending (needs a real phone)
- On a rooted old phone, verify starting the server through `:app-host` (`DaemonController` -> `com.mastersoft.velocast.server.Main` with `/data/adb/velocast` state dir) succeeds and accepts a v2 handshake.
