# AGENTS.md - VeloCast

## Mission
VeloCast mirrors and controls an old Android phone (often with a broken screen or touch) from a new phone. It is rescue-first: after a one-time Prepare step the old phone must need zero input. ADB (Wi-Fi, then USB OTG) is the primary way to start the server, Shizuku is second, root is optional.

## Read first
- docs/README.md (index), docs/architecture.md, docs/product-requirements.md, docs/design-spec.md
- docs/findings.md (audit findings you are closing) and docs/steps.md (the step you are on)
- PROGRESS.md (where the last session stopped)

## Architecture in one screen
- :protocol: Wire formats, handshake, validators, crypto interfaces, error codes, golden vectors and fuzz tests. Depends on: nothing.
- :server-core: Capture, encoder, input, display and rotation handling, session guard, capability probe, cleanup helper. No UI libraries, no app Context. Depends on: :protocol.
- :client-core: Session engine and state machine, stream transport, decoder, touch pipeline, metrics, connection coordinator. Depends on: :protocol.
- :launcher-adb: ADB client abstraction, pairing, discovery, USB OTG transport, push and start, Prepare automation. Depends on: :protocol, :client-core.
- :launcher-shizuku: Shizuku detection and UserService that runs the server as shell. Depends on: :protocol, :server-core.
- :launcher-root: Supervised root start, Magisk boot script generation. Depends on: :protocol, :server-core.
- :designsystem: Theme, tokens, components, strings, previews and screenshot tests. Depends on: nothing.
- :app-viewer: Viewer UI: devices, add, connect, mirror, settings, diagnostics, rescue guide. Depends on: :client-core, :launcher-adb, :designsystem.
- :app-host: Old-phone app: Prepare checklist, keep-alive, boot recovery, status, Shizuku and root launch. Depends on: :server-core, :launcher-shizuku, :launcher-root, :designsystem.

## Rules
1. Work on one step at a time on branch step/Sxx-name. Small, reviewable commits with clear messages.
2. ./gradlew check must pass before you finish. Never disable a test or lint rule to make a build pass.
3. No behaviour change without a test that proves it. Prefer unit tests; add instrumentation tests where the platform is involved.
4. Security: never log secrets, PINs or full screen content; process no network input before authentication; compare secrets in constant time; use vetted crypto libraries, never invent primitives.
5. Platform: :server-core runs as the shell user (uid 2000) or root. It must not use UI libraries or an app Context. Hidden APIs are accessed only through shims in a platform/ package with per-API-level handling and capability probes.
6. Dependencies: record name, version and licence in docs/dependencies.md before adding. Prefer maintained libraries. Do not use deprecated APIs for new code.
7. UI: Jetpack Compose with Material 3; colours, type and spacing come from :designsystem tokens only; all strings in resources; accessibility requirements in docs/design-spec.md are mandatory.
8. When Android behaviour is uncertain, write a probe or test, document the finding and mark it Confirm on device. Do not guess and do not claim a device test you could not run.
9. If the task is too large for one session, stop at a coherent commit boundary and update PROGRESS.md with what is done, what is next and any open questions, so the owner can say Continue.
10. Finish every step with the summary format below.

## Commands
- ./gradlew check            lint, format, static analysis, unit tests (must pass)
- ./gradlew assembleDebug    build both apps
- ./gradlew :protocol:test   fast protocol tests
- tools/spike/run-shell-server.sh   run the server as the shell user on a connected phone

## Definition of done
- Acceptance criteria of the step are met with evidence.
- ./gradlew check passes; no test or lint rule weakened.
- Docs and strings updated; no secrets in logs.
- Anything that needs a real phone is listed for the owner and not claimed as tested.

## Summary format (end every session with this)
1. What changed (files and modules).
2. Which acceptance criteria are met and the evidence (test names, outputs).
3. What could not be verified and what the owner must test on a device.
4. Open questions and risks.
5. Suggested next step.

## Glossary
- Viewer: the app on the new phone (com.mastersoft.velocast).
- Host: the app on the old phone (com.mastersoft.velocast.host).
- Server: server-core running as shell or root on the old phone.
- Prepare: one-time setup done while the old phone still works.
- Rescue: using the viewer after the old phone's screen or touch is broken.
