# Steps

Run in numeric order. S12 may run in parallel with S02-S06.

| Step | Title | Depends on | Effort (days) | Closes |
| --- | --- | --- | --- | --- |
| S00 | Agent workspace and repository hygiene | none | 1 | BLD-01, SEC-09, CQ-07, DOC-02, DOC-01 (README) |
| S01 | Restructure, toolchain and SDK 36 | S00 | 1-2 | BLD-02 (target SDK), BLD-04, BLD-05, CQ-10 (split) |
| S02 | Protocol v2 module | S01 | 2-3 | CQ-02, SEC-05 (validators), FUN-02 (message), PERF-08 (timestamp field) |
| S03 | Server core: extraction, session guard and media pipeline | S02 | 4-6 | SEC-01 (server), SEC-03, SEC-05, SEC-06, FUN-01, FUN-02 (server), FUN-05 (server), FUN-06, PERF-01, PERF-04, PERF-09, CQ-04, CQ-06, CQ-07 |
| S04 | Client core: session engine, decoder and touch | S02 | 3-5 | FUN-02, FUN-03, FUN-04, FUN-05, FUN-08, FUN-09, PERF-02, PERF-03, PERF-07, PERF-08, CQ-04 |
| S05 | Shell-user feasibility spike | S03 | 2-3 | SEC-04 (evidence), CQ-03 (probe) |
| S06 | Session security: pairing secret, key derivation and AEAD | S02, S03 | 3-4 | SEC-01 (complete), SEC-02 |
| S07 | ADB launcher and device store | S05, S06 | 5-8 | SEC-04, FUN-13, CQ-03 |
| S08 | Host app: Prepare and keep-alive | S05, S06 | 4-6 | FUN-10, PERF-06 |
| S09 | Shizuku launcher (optional, hands-free after reboot) | S07, S08 | 3-4 | SEC-04 (alt path) |
| S10 | Root launcher (optional) | S08 | 1-3 | FUN-10, SEC-06, SEC-07, PERF-06 |
| S11 | Rescue session features | S04, S07, S08 | 4-6 | FUN-07, FUN-14 (partial) |
| S12 | Design system module | S01 | 2-3 | CQ-08, CQ-05 (foundation) |
| S13 | Viewer app UI | S11, S12 | 5-8 | CQ-05, CQ-08, FUN-08, FUN-09, FUN-11, FUN-12 |
| S14 | Host app UI | S08, S09, S10, S12 | 3-4 | CQ-05, CQ-08 |
| S15 | Performance and adaptive bitrate | S13 | 4-6 | PERF-05, PERF-10, PERF-11, PERF-08 (validation) |
| S16 | Tests, security checks and device lab | S14 | 4-6 | CQ-01 |
| S17 | Release engineering and documentation | S15, S16 | 3-5 | BLD-02, BLD-03, DOC-01, DOC-02, SEC-08 |
| S18 | Optional extensions: audio, file recovery, extra codecs | S17 | 5-10 | FUN-14 |

## S00 Agent workspace and repository hygiene

Depends on: none. Effort: 1 days. Closes: BLD-01, SEC-09, CQ-07, DOC-02, DOC-01 (README).

Goal: Give the agent a safe, reproducible workspace and a clean repository to work in.

Tasks:
- Owner: tag the current state (git tag pre-hardening) and back it up.
- Owner: purge build output and IDE state from history with git filter-repo (commands in the audit report, Appendix B.1), then force-push. Do this before the agent starts.
- Owner: add the agent pack (AGENTS.md, docs/, prompts/) to the repository root.
- Agent: .gitignore, remove tracked build output, delete magisk/mirror.sh and legacy names, remove the hard-coded LAN IP default.
- Agent: rewrite README so every technical statement matches the code; add LICENSE (Apache-2.0 unless you choose otherwise), SECURITY.md, CONTRIBUTING.md, PR template.
- Agent: add GitHub Actions CI that builds both apps and runs lint on every push.

Acceptance criteria:
- git ls-files lists no build/, .gradle/, .idea/ or local.properties entries.
- ./gradlew assembleDebug lint passes on a fresh clone and in CI.
- Every number in README (intervals, timeouts) matches the code constants.
- No reference to the old default IP, mirror.sh or MirrorServerMain remains.

Owner checks (needs a real phone):
- Choose the licence (Apache-2.0 is recommended because it is compatible with reusing scrcpy code).
- Confirm the repository is under 5 MB after the purge.

## S01 Restructure, toolchain and SDK 36

Depends on: S00. Effort: 1-2 days. Closes: BLD-02 (target SDK), BLD-04, BLD-05, CQ-10 (split).

Goal: Move to a modular build with a version catalog, quality gates and API 36, with no behaviour change.

Tasks:
- Introduce gradle/libs.versions.toml and move every version into it.
- Create the module skeleton: :protocol, :server-core, :client-core, :launcher-adb, :launcher-shizuku, :launcher-root, :designsystem, :app-viewer, :app-host.
- Move existing code into the new modules without changing behaviour.
- Raise compileSdk and targetSdk to 36 for both apps; fix compile and runtime behaviour changes.
- Add ktlint (or Spotless) and detekt with baselines; make ./gradlew check run lint, format, static analysis and unit tests.
- Document module responsibilities and allowed dependency directions in docs/architecture.md and enforce them with a build check.

Acceptance criteria:
- All modules build; :protocol has no Android dependency (enforced by a check).
- ./gradlew check is green locally and in CI.
- Both apps install and behave exactly as before (manual smoke test listed in the PR).
- targetSdk is 36 in both apps; no deprecated Gradle features that fail in the next major Gradle release.

Owner checks (needs a real phone):
- Install both apps on the old rooted phone and the new phone and run a normal session as a regression baseline.

## S02 Protocol v2 module

Depends on: S01. Effort: 2-3 days. Closes: CQ-02, SEC-05 (validators), FUN-02 (message), PERF-08 (timestamp field).

Goal: One shared, versioned, tested wire protocol with typed messages and strict validation.

Tasks:
- Define message types, framing and flags (video header with type, flags, sequence, length, ptsUs, captureUs; control packets with type, pointer, sequence and payload).
- Define HELLO, SERVER_HELLO and AUTH messages and version negotiation with a capability bitmap.
- Define typed STATUS and ERROR messages with the error codes from the error catalogue.
- Implement allocation-conscious readers and writers with maximum sizes and validators (pointer id 0 to 31, key allow-list, finite floats, bounded config).
- Write golden-vector tests, round-trip tests and fuzz tests; publish docs/protocol.md with byte tables.

Acceptance criteria:
- Every message type round-trips; golden vectors committed.
- Fuzzing with at least one million random inputs produces only ProtocolException, never another exception, hang or unbounded allocation.
- docs/protocol.md matches the code (tests parse the worked examples in the document).
- Module is pure JVM with no Android imports.

Owner checks (needs a real phone):
- Skim docs/protocol.md; this is the contract both apps and every launcher depend on.

## S03 Server core: extraction, session guard and media pipeline

Depends on: S02. Effort: 4-6 days. Closes: SEC-01 (server), SEC-03, SEC-05, SEC-06, FUN-01, FUN-02 (server), FUN-05 (server), FUN-06, PERF-01, PERF-04, PERF-09, CQ-04, CQ-06, CQ-07.

Goal: A launcher-independent server with an authenticated session guard, validated input, a resilient media pipeline and clean state restoration.

Tasks:
- Entry point Main with --port, --bind, --state-dir, --secret-from-stdin and --log-level; prints READY on stdout.
- SessionGuard: handshake before anything else, back-off on failures, only authenticated peers replace a session.
- Validated input and a rewritten touch state machine; no allocation per event.
- Encoder settings, on-demand keyframes, bounded latest-wins send queue, separate priority path for pong and status.
- Rotation and size-change handling with VIDEO_SIZE; save and restore rotation lock and display state; out-of-process cleanup helper.
- Logger with levels; typed STATUS and ERROR messages; capability report.

Acceptance criteria:
- No network input is processed before authentication (unit tests prove it).
- Send queue never exceeds three frames under a simulated one-second stall; keyframe follows any drop.
- After rotation, VIDEO_SIZE arrives before the first frame at the new size and touch maps correctly.
- State restore works after normal exit and after kill -9 of the server.
- No /data/local/tmp paths, println calls or silent catch Throwable remain.

Owner checks (needs a real phone):
- On the rooted old phone, start the new server through the existing root path, connect, rotate three times, kill the server and confirm rotation lock and display state are restored.

## S04 Client core: session engine, decoder and touch

Depends on: S02. Effort: 3-5 days. Closes: FUN-02, FUN-03, FUN-04, FUN-05, FUN-08, FUN-09, PERF-02, PERF-03, PERF-07, PERF-08, CQ-04.

Goal: A viewer engine that never freezes silently: it detects loss, sheds old frames, recovers, reconnects and reports its state.

Tasks:
- SessionEngine with the state machine (Idle, Discovering, Connecting, StartingServer, Handshaking, Streaming, Paused, Reconnecting, Failed) exposed as StateFlow.
- Heartbeats, read timeout, link-lost detection and exponential-back-off reconnect for at least ten minutes.
- Typed SessionError with the error catalogue codes.
- Decoder in asynchronous mode with adaptive playback, drop-oldest queue, wait-for-keyframe and recovery from CodecException.
- Reliable delivery for touch DOWN, UP and CANCEL, MOVE coalescing, buffer pools, per-frame stage timings.

Acceptance criteria:
- State machine tests cover every transition and the back-off timing with a fake clock.
- A one-second induced stall never grows latency; a dropped frame recovers within 500 ms.
- The decoder survives truncated and corrupted streams and CodecException without freezing.
- No stuck touches after pause, disconnect or queue overflow.

Owner checks (needs a real phone):
- With two phones, switch Wi-Fi off for 5 s and on again: the viewer should reconnect by itself. Background and foreground the viewer: the stream resumes with a fresh keyframe.

## S05 Shell-user feasibility spike

Depends on: S03. Effort: 2-3 days. Closes: SEC-04 (evidence), CQ-03 (probe).

Goal: Prove which parts of the server run as the ADB shell user on real devices before building launchers around it.

Tasks:
- Agent: a CapabilityProbe that tries each privileged action (capture, input, shade, rotation, display power, wake, clipboard) and reports a typed result.
- Agent: scripts to build, push and run the server as the shell user from a computer, and to collect the probe output.
- Agent: a FakeContext for APIs that need a package identity (package com.android.shell).
- Owner: run the scripts on at least four phones (different Android versions and ROMs) and collect results.
- Agent: fix incompatibilities and write docs/spike-shell-user.md with a capability matrix.

Acceptance criteria:
- Capability matrix covers at least Android 11, 13, 14, 16 and one Xiaomi or Samsung ROM.
- Every failure has a documented cause and either a fix or a typed error.
- The server runs unprivileged-by-root on the reference old phone.

Owner checks (needs a real phone):
- Run tools/spike/run-shell-server.sh on the devices listed in the matrix and paste the output into the PR or the follow-up prompt.

## S06 Session security: pairing secret, key derivation and AEAD

Depends on: S02, S03. Effort: 3-4 days. Closes: SEC-01 (complete), SEC-02.

Goal: Authenticated, encrypted sessions with secrets stored safely on both phones.

Tasks:
- Define two auth modes: SESSION_SECRET (random per session, delivered over ADB stdin) and PAIRING_SECRET (32-byte secret created during Prepare, stored by viewer and Host app).
- Key derivation from the secret and both nonces with HKDF; per-direction keys; AEAD (AES-256-GCM) framing with counter nonces and replay protection.
- Bind the handshake transcript (versions, capabilities, nonces) into the proof.
- Keystore-backed storage on both apps; no secrets in logs; constant-time comparisons.
- Test vectors and negative tests (wrong secret, replay, truncation, reordering).

Acceptance criteria:
- An unauthenticated or wrongly keyed client receives nothing and cannot affect an active session.
- Replayed, reordered or modified frames fail authentication.
- RFC test vectors pass for HKDF; AEAD uses unique nonces per direction.
- No secret appears in logs, crash reports or diagnostics output.

Owner checks (needs a real phone):
- Review the threat model paragraph and the key-lifecycle diagram the agent adds to docs/security.md.

## S07 ADB launcher and device store

Depends on: S05, S06. Effort: 5-8 days. Closes: SEC-04, FUN-13, CQ-03.

Goal: The viewer pairs with an old phone, connects, prepares it, pushes and starts the server and streams, with no root and nothing installed on the old phone.

Tasks:
- Decide the ADB library (libadb-android, dadb with wireless pairing, or a small in-house client) with a short written comparison including licences.
- Pairing by QR code (viewer shows, old phone scans) and by 6-digit code and port; mDNS discovery for pairing and connect services.
- Connect with TLS, push the server jar with checksum verification, start it, read READY and open the data channel (direct TCP or ADB stream tunnel).
- Prepare automation: idempotent shell commands for stay-awake, authorisation timeout, battery exemption and permission grants, each verified.
- USB OTG transport using the Android USB host API.
- Encrypted device store (id, name, model, last address, key alias, secret reference, method, last seen).

Acceptance criteria:
- Fresh viewer pairs and mirrors an unrooted old phone on Android 11 and 16 with no app installed on it.
- Prepare commands report per-item success or a typed reason.
- The viewer re-uses the stored authorisation after restart without prompting.
- USB OTG session works on at least one phone pair.

Owner checks (needs a real phone):
- Pair with a real unrooted phone by QR and by code. Switch the old phone's Wi-Fi off and on and check rediscovery. Plug in an OTG cable and connect.

## S08 Host app: Prepare and keep-alive

Depends on: S05, S06. Effort: 4-6 days. Closes: FUN-10, PERF-06.

Goal: The old-phone app verifies and fixes every prerequisite, keeps the phone reachable, and restores wireless debugging after a reboot where the platform allows.

Tasks:
- ChecklistEngine with typed items (check, fix, guide) for developer options, USB debugging, Wireless debugging, authorisation timeout, stay-awake, battery optimisation, notifications, WRITE_SECURE_SETTINGS, boot receiver, optional launchers, Wi-Fi and charging state.
- KeepAliveService (foreground) with Wi-Fi low-latency lock and wake handling only while charging or streaming.
- BootReceiver that handles locked-boot and boot-completed events and re-enables wireless debugging once Wi-Fi is connected, with bounded retries and a visible status.
- PairingProvider that accepts the pairing secret only from the shell or root uid while a pairing window is open.
- Status model for the UI and a notification that appears when a checklist item regresses.

Acceptance criteria:
- Every checklist item has a unit-tested check; items fixable with WRITE_SECURE_SETTINGS are fixed without user input once granted.
- After a reboot with Wi-Fi available, wireless debugging returns without touching the phone (device test), or the status says exactly why not.
- The pairing provider rejects callers other than uid 2000 or 0 (test).
- Keep-alive costs are measured: one hour on charge without throttling or battery drain beyond charging.

Owner checks (needs a real phone):
- On a real old phone: run Prepare, reboot it, wait for Wi-Fi, and check the viewer can connect without touching the old phone. Repeat with a secure lock screen enabled and record what happens before first unlock.

## S09 Shizuku launcher (optional, hands-free after reboot)

Depends on: S07, S08. Effort: 3-4 days. Closes: SEC-04 (alt path).

Goal: Let the Host app start the same server through Shizuku so the old phone can come back after a reboot without the viewer.

Tasks:
- Add the Shizuku API and provider; detect installed, running and permission states.
- Bind a UserService that runs ServerRunner (from :server-core) as the shell user; confirm that no app Context is needed.
- Advertise the server over mDNS (_velocast._tcp) from the Host app so the viewer discovers it without ADB.
- Guide the user through installing and starting Shizuku; document the community forks that add start-on-boot and their Wi-Fi requirement.
- Fallback behaviour when Shizuku is missing or stopped: the status says what to do; ADB route is unaffected.

Acceptance criteria:
- With Shizuku running, the Host app starts the server after boot and the viewer connects with no ADB step.
- Missing, stopped or permission-denied Shizuku is detected and explained; no crash.
- Licences and versions of the Shizuku libraries recorded in docs/dependencies.md.

Owner checks (needs a real phone):
- Install Shizuku on the old phone, start it by wireless debugging, reboot, and check the Host app status and the viewer.

## S10 Root launcher (optional)

Depends on: S08. Effort: 1-3 days. Closes: FUN-10, SEC-06, SEC-07, PERF-06.

Goal: Keep root as an optional, safer and supervised way to start the server and recover after boot.

Tasks:
- RootLauncher with supervised start (back-off restarts), exact process identification and state kept in a root-only directory.
- Magisk service.d boot script generated by the Host app, one-shot per boot with bounded retries.
- No polling: status through the same local socket or Binder used by other launchers.
- Clear labelling in the UI as optional and advanced.

Acceptance criteria:
- Server starts after boot on a rooted phone without opening the app; crash restarts with back-off.
- State lives in /data/adb/velocast (mode 0700) and nothing in /data/local/tmp.
- Process matching uses exact argv; no substring kill.

Owner checks (needs a real phone):
- Test on the rooted old phone: reboot, wait for Wi-Fi, connect from the viewer. Kill the server and confirm it restarts.

## S11 Rescue session features

Depends on: S04, S07, S08. Effort: 4-6 days. Closes: FUN-07, FUN-14 (partial).

Goal: Make the session work with minimum input: wake, unlock, display control, keep-awake, typing, clipboard and auto-connect.

Tasks:
- Wake on connect with a key event; keep the display awake while streaming; true display power off and on with restore.
- Optional Unlock helper: detect the keyguard, wake, swipe, enter a stored PIN once per connect, never retry automatically.
- TEXT input (key events for ASCII, clipboard paste for everything else) and optional clipboard sync.
- Auto-connect to the last device on launch, app shortcuts and a Quick Settings tile; handle several devices.
- Banners for Screen off and Locked with one-tap fixes.

Acceptance criteria:
- A sleeping, locked old phone is woken and (if the helper is on) unlocked by one tap with no more than one PIN attempt.
- Display-off works while capture continues and is restored on exit.
- Typing and paste work for ASCII and non-ASCII text.
- The PIN is never in logs, backups or screenshots and is protected by the Keystore (optionally biometric-gated).

Owner checks (needs a real phone):
- Lock the old phone with a PIN, let it sleep, and connect: check wake, unlock helper, typing and display off. Try a wrong PIN once and confirm there is no automatic retry.

## S12 Design system module

Depends on: S01. Effort: 2-3 days. Closes: CQ-08, CQ-05 (foundation).

Goal: A shared Compose design system so both apps look and behave like one product.

Tasks:
- Material 3 theme with the design tokens (light, dark, optional dynamic colour), typography, shapes and spacing.
- Components from the spec: StatusChip, DeviceCard, StepIndicator, ChecklistRow, ProgressSteps, ErrorPanel, ToggleRow, ChoiceRow, EmptyState, DiagnosticsLog, PinPad, Hud, ToolboxSheet.
- String resources with translator comments; pseudo-locale test; icon set.
- Previews and screenshot tests for light, dark, large font and small width.
- Accessibility semantics built into every component.

Acceptance criteria:
- Every component has previews and a screenshot test in four configurations.
- TalkBack labels and 48 dp targets verified by tests and a manual pass.
- No colour is hard-coded outside the theme file.

Owner checks (needs a real phone):
- Review the component gallery screen on a phone and a tablet.

## S13 Viewer app UI

Depends on: S11, S12. Effort: 5-8 days. Closes: CQ-05, CQ-08, FUN-08, FUN-09, FUN-11, FUN-12.

Goal: A redesigned viewer: devices, one-tap connect, guided add, rescue guide, full-screen mirror, settings and diagnostics.

Tasks:
- Navigation graph and ViewModels with immutable UI state mapped from SessionState and DeviceStore.
- Onboarding, Devices, Add old phone (QR, code, USB), Prepare check, Device details, Connecting.
- Mirror viewer: SurfaceView host, letterbox, HUD, toolbox and keyboard sheets, immersive mode, banners.
- Rescue guide with the decision flow and OTG-mouse walk-through; Settings and Diagnostics.
- Adaptive layouts for large screens and foldables; accessibility pass.

Acceptance criteria:
- Every screen in the viewer spec exists with its states (empty, loading, error, success).
- One tap from Home to Connect for a paired phone; auto-connect works.
- All error panels map to the error catalogue with a single fix button.
- TalkBack walkthrough and 200 percent font pass; screenshot tests green.
- No business logic in composables; ViewModels are unit tested.

Owner checks (needs a real phone):
- Walk the full flow with a fresh install: onboarding, pair, prepare, connect, rotate, background and return, error cases. Run Accessibility Scanner.

## S14 Host app UI

Depends on: S08, S09, S10, S12. Effort: 3-4 days. Closes: CQ-05, CQ-08.

Goal: A calm, guided Host app: first run, Prepare checklist, pairing helper, status and advanced settings.

Tasks:
- First run, Prepare checklist hub with Fix next item, pairing helper with deep links, Status home, Advanced (launch method, paired viewers, logs, reset).
- Notification states and the persistent keep-alive notification.
- Plain-language copy; ADB, Shizuku and root only under Advanced.
- Accessibility and large-text support.

Acceptance criteria:
- A new user completes Prepare in under five minutes on a real phone.
- Each checklist item shows its reason and exactly one fix action.
- Revoke and Reset are confirmed with plain warnings.
- Screenshot and ViewModel tests are green; TalkBack pass done.

Owner checks (needs a real phone):
- Time a first-time Prepare with someone who has not seen the app. Note every hesitation and feed it back.

## S15 Performance and adaptive bitrate

Depends on: S13. Effort: 4-6 days. Closes: PERF-05, PERF-10, PERF-11, PERF-08 (validation).

Goal: Meet the latency, recovery and thermal targets with evidence, and adapt bitrate to the link.

Tasks:
- Benchmark harness: scripted matrix of rate-control modes, bitrates, resolutions and frame rates with latency and thermal logging.
- Adaptive bitrate driven by queue depth and RTT with the user's setting as ceiling.
- Apply scale and FPS changes at a keyframe without tearing down the pipeline where possible.
- Optional H.265 negotiation with capability checks.
- Latency validation with the external camera method; write docs/perf-report.md.

Acceptance criteria:
- Median glass-to-glass latency under 80 ms and p95 under 150 ms at 1080p60 on clean 5 GHz on the reference pair (or documented reasons and the achieved numbers).
- Artefact recovery under 500 ms after an induced loss; reconnect after a one-second outage under three seconds.
- Two-hour soak without memory growth or throttling on the reference old phone.

Owner checks (needs a real phone):
- Do the external camera latency measurement (240 fps camera, millisecond counter) for at least 30 samples per condition.

## S16 Tests, security checks and device lab

Depends on: S14. Effort: 4-6 days. Closes: CQ-01.

Goal: A test suite and a repeatable manual rescue test plan that guard every earlier fix.

Tasks:
- Integration harness: server and client in one process over a loopback proxy that adds loss, delay and bandwidth caps.
- Instrumentation smoke tests for both apps; Gradle managed devices in CI where possible.
- Security tests: unauthenticated connect, malformed packets, replay, port scan script.
- Soak and chaos scripts (Wi-Fi flaps, rotation x50, 500 connect/disconnect cycles).
- docs/device-lab.md and docs/rescue-test-plan.md with the scenario matrix and a results template.
- Coverage report, dependency and licence scan in CI.

Acceptance criteria:
- CI runs unit, integration and smoke tests on every pull request.
- Each acceptance criterion from S03 to S11 is covered by an automated test or a numbered manual test.
- Security tests prove the unauthenticated path is closed.

Owner checks (needs a real phone):
- Run the manual rescue test plan on at least three phone models and record results in docs/rescue-test-results.md.

## S17 Release engineering and documentation

Depends on: S15, S16. Effort: 3-5 days. Closes: BLD-02, BLD-03, DOC-01, DOC-02, SEC-08.

Goal: Signed, minified, reproducible releases with accurate documentation and a privacy-respecting listing.

Tasks:
- R8 with keep rules for reflection-based server code and Shizuku; check the dex jar.
- Release signing through CI secrets; versions from git tags; reproducible-build check.
- Release workflow producing both APKs, the server jar, checksums and an SBOM.
- User guide (setup, rescue, troubleshooting, FAQ), privacy policy, in-app licences screen, SECURITY.md final.
- Store metadata for F-Droid and GitHub Releases; evaluate Play policy separately.
- Test on Android 17 preview with targetSdk 36 and note the work needed for 37.

Acceptance criteria:
- Tagged build produces signed APKs and checksums in CI; install and smoke test pass on a clean device.
- Minified build passes the full smoke tests including the server jar.
- Docs match behaviour; no stale claims; privacy policy states exactly what data stays on device.

Owner checks (needs a real phone):
- Create the signing key, store it in CI secrets and keep an offline backup. Decide the distribution channels.

## S18 Optional extensions: audio, file recovery, extra codecs

Depends on: S17. Effort: 5-10 days. Closes: FUN-14.

Goal: Add features after the product is stable: audio, file and photo recovery, and screenshots or screen recording of the old phone.

Tasks:
- Audio forwarding (Android 11+) with Opus and latency control.
- File recovery: browse and pull files and photos from the old phone over the ADB sync service.
- Screen recording and full-resolution screenshots of the stream.
- Each feature behind a flag with its own tests and documentation.

Acceptance criteria:
- Features are disabled by default, documented and covered by tests.
- No regression in the performance and security tests.

Owner checks (needs a real phone):
- Decide which of these is worth building first; for broken-phone owners file recovery is usually the most valuable.
