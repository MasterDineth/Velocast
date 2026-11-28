# VeloCast docs

- [architecture.md](architecture.md) - modules, launchers, secrets, state machine, reboot recovery
- [protocol.md](protocol.md) - wire protocol v2 framing, message payloads, handshake, validators, error codes, golden vectors
- [product-requirements.md](product-requirements.md) - decisions, rescue scenarios, platform limits, requirements, error catalogue
- [design-spec.md](design-spec.md) - principles, tokens, components, screens, copy, accessibility
- [findings.md](findings.md) - the 51 audit findings and resolution traceability
- [steps.md](steps.md) - implementation steps with acceptance criteria
- [security.md](security.md) - threat model, `SESSION_SECRET` / `PAIRING_SECRET` lifecycle, HKDF-SHA256 key schedule, AES-256-GCM record framing, Android Keystore storage, and log redaction (`S06`)
- [spike-shell-user.md](spike-shell-user.md) - shell-user (`uid 2000`) capability matrix, failure analysis and platform shim decisions (`S05`)
- [dependencies.md](dependencies.md) - dependency, version and licence record (agents keep it current)
- ../prompts/ - one prompt file per step and prompts/templates.md
