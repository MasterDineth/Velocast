# PROGRESS

Update this file whenever you stop. The owner will say "Continue" and you resume from here.

## Current step
S02 — Protocol v2 module (implementation complete; ready for PR / owner review of `docs/protocol.md`).

## Done
- S01 — Restructure, toolchain and SDK 36 (merged in PR #2).
- Implemented VeloCast wire protocol v2 in `:protocol` (`com.mastersoft.velocast.protocol`) as a pure Kotlin/JVM library with zero Android or external runtime dependencies:
  - Big-endian framing (`FrameHeader` 24-byte S->C header with `type u8`, `flags u8` (`KEYFRAME`, `CONFIG`, `RESET`, `ERROR`), `seq u16`, `length u32` <= 4 MiB, `ptsUs i64`, `captureUs i64`; `ControlPacket` 16-byte C->S header with `type u8`, `pointer u8`, `seq u16`, `param i32`, `x f32`, `y f32`).
  - All 20 message types (`HELLO`, `SERVER_HELLO`, `AUTH_PROOF`, `BYE`, `PING`, `PONG`, `VIDEO`, `CONFIG`, `VIDEO_SIZE`, `STATUS`, `ERROR`, `CLIPBOARD`, `TOUCH`, `KEY`, `TEXT`, `CONFIG_STREAM`, `KEYFRAME_REQ`, `PAUSE`, `RESUME`, `ACTION`) and typed, self-validating payload models.
  - Handshake state machines (`ClientHandshake`, `ServerHandshake`), `Transcript` builder with client/server domain separation, `Features` bitmap, `Authenticator` interface, and `HmacTestAuthenticator` test double in `testFixtures`.
  - `ErrorCode` enum covering all 11 user-facing codes from `docs/product-requirements.md` plus protocol violation codes (`100..112`) carried by `ProtocolException`.
  - Strict validators (`Validators`, `KeyCodes` allow-list, allocation-free `Utf8`, `PointerStateTracker`, `SeqTracker`) and phase-enforced, zero-allocation hot-path stream I/O (`FrameReader`, `FrameWriter`, `ControlReader`, `ControlWriter`) with incremental `GrowingBuffer` protection against oversized length announcements.
  - 64 JUnit tests across 10 suites: independent Python-generated `golden-vectors.txt` for every message type/direction, round-trip tests (up to 4 MiB frames), strictness/hostile-input tests, handshake tests, 1,000,000-input property-based fuzz test (`FuzzTest`), thread allocation bounded-memory tests (`BoundedMemoryTest`), and `DocExamplesTest` parsing and verifying `docs/protocol.md` tables and worked examples.
- Authored `docs/protocol.md` with complete byte tables, Mermaid handshake sequence/state diagrams, error catalogue, and worked hex examples; indexed it in `docs/README.md` and left `TODO(S03)` / `TODO(S04)` markers on the legacy v1 `Protocol.kt` copies.

## Next
- S03 — Server core: extraction, session guard and media pipeline (on branch `step/S03-server-core`).

## Open questions
- `SERVER_HELLO` includes `DeviceInfo` (manufacturer, model, SDK level, display geometry) in response to any syntactically valid `HELLO` before `AUTH_PROOF` verification. Rescue scenario R10 (`docs/product-requirements.md`) states that a hostile LAN peer connecting without credentials should receive "no data". Confirm before S03/S06 whether sending `SERVER_HELLO` prior to `AUTH_PROOF` is acceptable, or whether `DeviceInfo` should be redacted/moved post-auth (or gated behind an ADB/TLS tunnel).

## Owner checks pending (needs a real phone)
- Skim `docs/protocol.md` (the wire contract shared by `:server-core`, `:client-core`, and all launchers).
- Install `app-host-debug.apk` on the rooted old phone and `app-viewer-debug.apk` on the new phone for the S01 smoke test if not already completed.
